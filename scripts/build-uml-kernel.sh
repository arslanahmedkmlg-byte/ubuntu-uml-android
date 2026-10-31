#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KERNEL_COMMIT="8897487c52233cd00cf2850008ca068892f1ae91"
PATCH_COMMIT="58ecef8464c7c312a2d5ec1477be862b408cef5c"
HARNESS_COMMIT="21ef1403aa41ac31f1f02e83718bd45ca560252c"
NDK_VERSION="27.0.12077973"
ANDROID_API="${ANDROID_API:-30}"
WORK_DIR="${WORK_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ubuntu-uml-kernel}"
SOURCE_DIR="$WORK_DIR/linux-um-arm64"
OUTPUT_DIR="${ARTIFACT_DIR:-$WORK_DIR/artifacts-$(date -u +%Y%m%dT%H%M%SZ)}"
BUILD_DIR="${KERNEL_BUILD_DIR:-$WORK_DIR/build}"
BASELINE_DIR="$WORK_DIR/baseline"
PATCH_MARKER="$WORK_DIR/patch-series.sha256"
NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/$NDK_VERSION}}"
TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
HOSTCC="${HOSTCC:-$(command -v clang || true)}"
HOSTCXX="${HOSTCXX:-$(command -v clang++ || true)}"

trap 'status=$?; printf "build-uml-kernel: failed at line %s (status %s): %s\n" "$LINENO" "$status" "$BASH_COMMAND" >&2; exit "$status"' ERR

fail() {
	echo "build-uml-kernel: $*" >&2
	exit 1
}

verify_aarch64_static() {
	local path="$1"
	local machine
	machine="$(readelf -h "$path" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
	[[ "$machine" == "AArch64" ]] || fail "$path is not an AArch64 ELF (machine: ${machine:-unknown})"
	if readelf -l "$path" 2>/dev/null | grep -q INTERP; then
		fail "$path has a dynamic interpreter; Android execution requires static artifacts"
	fi
}

[[ "$ANDROID_API" =~ ^[0-9]+$ ]] || fail "ANDROID_API must be an integer"
[[ -x "$TOOL/clang" ]] || fail "Android NDK $NDK_VERSION is required; set NDK to that exact revision"
[[ -n "$HOSTCC" && -x "$HOSTCC" ]] || fail "host clang is required for Kbuild tools"
[[ -n "$HOSTCXX" && -x "$HOSTCXX" ]] || fail "host clang++ is required for Kbuild tools"
grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$NDK_VERSION$" "$NDK/source.properties" ||
	fail "NDK at $NDK is not revision $NDK_VERSION"
command -v git >/dev/null || fail "git is required"
command -v readelf >/dev/null || fail "readelf is required"
command -v python3 >/dev/null || fail "python3 is required"
command -v patch >/dev/null || fail "patch is required"
command -v bc >/dev/null || fail "bc is required; install the kernel build dependencies"
command -v flex >/dev/null || fail "flex is required; install the kernel build dependencies"
command -v bison >/dev/null || fail "bison is required; install the kernel build dependencies"

mkdir -p "$WORK_DIR" "$BASELINE_DIR"
if [[ -d "$OUTPUT_DIR" ]] &&
	[[ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
	fail "artifact output is not empty: $OUTPUT_DIR"
fi
mkdir -p "$OUTPUT_DIR"

UML_OUTPUT="$BASELINE_DIR" bash "$ROOT/scripts/fetch-uml.sh"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
	if [[ -d "$SOURCE_DIR" ]] &&
		[[ -n "$(find "$SOURCE_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
		fail "source directory exists without a git checkout: $SOURCE_DIR"
	fi
	mkdir -p "$SOURCE_DIR"
	git -C "$SOURCE_DIR" init -q
	git -C "$SOURCE_DIR" remote add origin https://github.com/zalexdev/linux-um-arm64.git
	git -C "$SOURCE_DIR" fetch --depth=1 origin "$KERNEL_COMMIT"
	git -C "$SOURCE_DIR" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$SOURCE_DIR" rev-parse HEAD)" == "$KERNEL_COMMIT" ]] ||
	fail "kernel source checkout is not the pinned commit $KERNEL_COMMIT"

PATCH_SERIES=(
	"$ROOT/kernel/patches/stub-execve-fallback.patch"
	"$ROOT/kernel/patches/android-app-compat.patch"
	"$ROOT/kernel/patches/vector-napi-budget.patch"
)
PATCH_HASHES=(
	"1bcd1a7c935c59dd6e2f8c023451b3dd3efbf5a0308b574fd78d34f0f6b83e34"
	"f97687ae4e40aee5f6e4ab136619d4af2c0f9f012a817850016a8f9fec6f0631"
	"5e276e1b9766fd08276158be69fd8f2abf8854272e49d2ca4ddc99c6e20985e7"
)
for index in "${!PATCH_SERIES[@]}"; do
	printf '%s  %s\n' "${PATCH_HASHES[$index]}" "${PATCH_SERIES[$index]}" |
		sha256sum -c -
done
printf '%s  %s\n' \
	"b07bdc75b016a62cbfe31ca8bf4a860c8a3a4a874d6b9eb6515a2141b04a01dc" \
	"$ROOT/kernel/harness/check-app-seccomp.sh" |
	sha256sum -c -
printf '%s  %s\n' \
	"1fa469d86e9a0c767a9edafcb2d65dc445ac7dfa2bd5f0fd09ffefd76fc94860" \
	"$ROOT/kernel/harness/patch-glibc-seccomp.py" |
	sha256sum -c -
PATCH_DIGEST="$(
	sha256sum "${PATCH_SERIES[@]}" |
		sha256sum |
		awk '{print $1}'
)"
if [[ ! -f "$PATCH_MARKER" ]]; then
	[[ -z "$(git -C "$SOURCE_DIR" status --porcelain)" ]] ||
		fail "unmarked source tree has local changes; use a new WORK_DIR"
	for patch_file in "${PATCH_SERIES[@]}"; do
		patch -d "$SOURCE_DIR" -p1 --forward --batch < "$patch_file"
	done
	printf '%s\n' "$PATCH_DIGEST" > "$PATCH_MARKER"
elif [[ "$(cat "$PATCH_MARKER")" != "$PATCH_DIGEST" ]]; then
	fail "patches changed after this source tree was prepared; use a new WORK_DIR"
fi

BASELINE_KERNEL="$BASELINE_DIR/arm64-v8a/libvmlinux.so"
BASELINE_CONFIG="$WORK_DIR/uml-guest-4.config"
"$SOURCE_DIR/scripts/extract-ikconfig" "$BASELINE_KERNEL" > "$BASELINE_CONFIG"
printf '%s  %s\n' \
	"84fae4254570e154b7c42764440b7910e82a74f7d63849d3d77ce42a0b1e1174" \
	"$BASELINE_CONFIG" | sha256sum -c -

mkdir -p "$BUILD_DIR"
cp "$BASELINE_CONFIG" "$BUILD_DIR/.config"
"$SOURCE_DIR/scripts/kconfig/merge_config.sh" -m -O "$BUILD_DIR" \
	"$BUILD_DIR/.config" "$ROOT/kernel/config/ubuntu-uml-capabilities.config"

SHIM="$BUILD_DIR/.bionic-shim"
mkdir -p "$SHIM"
printf '#!/bin/sh\nexec "%s/clang" "$@" --target=aarch64-linux-android%s\n' \
	"$TOOL" "$ANDROID_API" > "$SHIM/clang"
cp "$SHIM/clang" "$SHIM/clang++"
chmod 0755 "$SHIM/clang" "$SHIM/clang++"
for tool in ld.lld llvm-ar llvm-nm llvm-objcopy llvm-objdump llvm-readelf llvm-strip; do
	ln -sf "$TOOL/$tool" "$SHIM/$tool"
done
export PATH="$SHIM:$TOOL:$PATH"
export KBUILD_BUILD_TIMESTAMP="${KBUILD_BUILD_TIMESTAMP:-Thu Jan  1 00:00:00 UTC 1970}"
export KBUILD_BUILD_USER=um
export KBUILD_BUILD_HOST=arm64
export KBUILD_BUILD_VERSION=1

MAKE_ARGS=(
	-C "$SOURCE_DIR"
	O="$BUILD_DIR"
	ARCH=um
	SUBARCH=arm64
	LLVM=1
	HOSTCC="$HOSTCC"
	HOSTCXX="$HOSTCXX"
)
make "${MAKE_ARGS[@]}" olddefconfig
python3 "$ROOT/scripts/validate-uml-config.py" --config "$BUILD_DIR/.config"

available_mib="$(awk '/MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)"
if [[ -r /sys/fs/cgroup/memory.max ]]; then
	cgroup_limit="$(cat /sys/fs/cgroup/memory.max)"
	if [[ "$cgroup_limit" =~ ^[0-9]+$ ]]; then
		cgroup_mib=$((cgroup_limit / 1024 / 1024))
		(( cgroup_mib < available_mib )) && available_mib="$cgroup_mib"
	fi
fi
cpu_count="$(nproc)"
jobs_by_memory=$((available_mib / 3072))
(( jobs_by_memory < 1 )) && jobs_by_memory=1
JOBS="${JOBS:-$jobs_by_memory}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || fail "JOBS must be a positive integer"
(( JOBS > cpu_count )) && JOBS="$cpu_count"
echo "build-uml-kernel: NDK=$NDK_VERSION jobs=$JOBS memory_mib=$available_mib CPUs=$cpu_count"
CLANG_VERSION="$("$TOOL/clang" --version | head -1)"
echo "$CLANG_VERSION"
export KERNEL_CLANG_VERSION="$CLANG_VERSION"

make "${MAKE_ARGS[@]}" -j"$JOBS" 2>&1 | tee "$OUTPUT_DIR/kernel-build.log"

KERNEL="$BUILD_DIR/linux"
STUB="$BUILD_DIR/arch/um/kernel/skas/stub_exe"
[[ -s "$KERNEL" ]] || fail "kernel link output is missing"
[[ -s "$STUB" ]] || fail "UML SKAS stub output is missing"
verify_aarch64_static "$KERNEL"
verify_aarch64_static "$STUB"

if python3 "$ROOT/kernel/harness/check-app-seccomp.sh" "$KERNEL" \
	>"$OUTPUT_DIR/seccomp-prepatch.log" 2>&1; then
	echo "build-uml-kernel: seccomp scan is clean; no post-link transform needed"
else
	result=$?
	[[ "$result" -eq 1 ]] || fail "seccomp pre-patch scan failed with status $result"
	python3 "$ROOT/kernel/harness/patch-glibc-seccomp.py" "$KERNEL" \
		>"$OUTPUT_DIR/seccomp-patch.log"
	python3 "$ROOT/kernel/harness/check-app-seccomp.sh" "$KERNEL"
fi
verify_aarch64_static "$KERNEL"

KERNEL_RELEASE="$(make "${MAKE_ARGS[@]}" -s kernelrelease)"
EXPECTED_RELEASE="7.2.0-rc4-eaglenet-uml-cap34"
[[ "$KERNEL_RELEASE" == "$EXPECTED_RELEASE" ]] ||
	fail "unexpected kernel release $KERNEL_RELEASE (expected $EXPECTED_RELEASE)"

MODULE_DIR="$WORK_DIR/module-probe"
mkdir -p "$MODULE_DIR"
cp "$ROOT/kernel/tests/uml_module_probe.c" "$MODULE_DIR/"
printf 'obj-m += uml_module_probe.o\n' > "$MODULE_DIR/Makefile"
make "${MAKE_ARGS[@]}" M="$MODULE_DIR" modules
MODULE="$MODULE_DIR/uml_module_probe.ko"
[[ -s "$MODULE" ]] || fail "external UML module probe was not built"
strings "$MODULE" | grep -Fq "vermagic=$KERNEL_RELEASE " ||
	fail "module probe vermagic does not match $KERNEL_RELEASE"

mkdir -p "$OUTPUT_DIR/rollback" "$OUTPUT_DIR/tools"
cp "$KERNEL" "$OUTPUT_DIR/libvmlinux.so"
install -m 0755 "$STUB" "$OUTPUT_DIR/libuml-stub.so"
cp "$BASELINE_DIR/arm64-v8a/libuml-passt.so" "$OUTPUT_DIR/libuml-passt.so"
cp "$BASELINE_CONFIG" "$OUTPUT_DIR/baseline.config"
cp "$BUILD_DIR/.config" "$OUTPUT_DIR/kernel.config"
cp "$MODULE" "$OUTPUT_DIR/uml_module_probe.ko"
cp "$SOURCE_DIR/scripts/extract-ikconfig" "$OUTPUT_DIR/tools/extract-ikconfig"
chmod 0755 "$OUTPUT_DIR/tools/extract-ikconfig"
cp "$ROOT/kernel/config/baseline-artifacts.json" "$OUTPUT_DIR/rollback/"
UML_OUTPUT="$OUTPUT_DIR/rollback" bash "$ROOT/scripts/fetch-uml.sh"
python3 "$ROOT/scripts/validate-uml-config.py" --config "$BUILD_DIR/.config" \
	--built --report "$OUTPUT_DIR/capabilities-config-report.json"
"$SOURCE_DIR/scripts/extract-ikconfig" "$OUTPUT_DIR/libvmlinux.so" \
	> "$OUTPUT_DIR/embedded.config"
cmp "$OUTPUT_DIR/kernel.config" "$OUTPUT_DIR/embedded.config"

SOURCE_PACKAGE="$WORK_DIR/source-package"
mkdir -p "$SOURCE_PACKAGE/patches" "$SOURCE_PACKAGE/harness" "$SOURCE_PACKAGE/config"
git -C "$SOURCE_DIR" archive --format=tar --prefix="linux-um-arm64-$KERNEL_COMMIT/" "$KERNEL_COMMIT" |
	gzip -n > "$SOURCE_PACKAGE/linux-um-arm64-source.tar.gz"
cp "${PATCH_SERIES[@]}" "$SOURCE_PACKAGE/patches/"
cp "$ROOT/kernel/harness/check-app-seccomp.sh" "$ROOT/kernel/harness/patch-glibc-seccomp.py" \
	"$SOURCE_PACKAGE/harness/"
cp "$ROOT/kernel/config/ubuntu-uml-capabilities.config" \
	"$ROOT/kernel/config/capabilities.json" \
	"$ROOT/kernel/config/baseline-artifacts.json" \
	"$ROOT/kernel/config/uml-guest-4.baseline.config" \
	"$SOURCE_PACKAGE/config/"
mkdir -p "$SOURCE_PACKAGE/tests"
cp "$ROOT/kernel/tests/uml_module_probe.c" "$SOURCE_PACKAGE/tests/"
cp "$ROOT/scripts/build-uml-kernel.sh" "$ROOT/scripts/validate-uml-config.py" \
	"$SOURCE_PACKAGE/"
cp "$ROOT/docs/source-pins.md" "$SOURCE_PACKAGE/"
cp "$OUTPUT_DIR/kernel.config" "$OUTPUT_DIR/embedded.config" "$SOURCE_PACKAGE/config/"
tar -czf "$OUTPUT_DIR/uml-kernel-source-bundle.tar.gz" -C "$SOURCE_PACKAGE" .

python3 - "$OUTPUT_DIR" "$KERNEL_RELEASE" "$NDK_VERSION" "$KERNEL_COMMIT" \
	"$PATCH_COMMIT" "$HARNESS_COMMIT" "$ANDROID_API" "$ROOT/kernel/patches" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

output = Path(sys.argv[1])
kernel_release, ndk_version, source_commit, patch_commit, harness_commit, api, patch_dir = sys.argv[2:]
artifact_names = ("libvmlinux.so", "libuml-stub.so", "libuml-passt.so")


def digest(path):
    data = path.read_bytes()
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


baseline = json.loads((output / "rollback/baseline-artifacts.json").read_text())
patches = {}
for path in sorted(Path(patch_dir).glob("*.patch")):
    patches[path.name] = digest(path)
manifest = {
    "release": "eaglenet-uml-cap34",
    "base_url": "",
    "kernel_source_commit": source_commit,
    "patch_source_commit": patch_commit,
    "post_link_harness_commit": harness_commit,
    "toolchain": {
        "ndk_revision": ndk_version,
        "android_api": int(api),
        "clang_version": os.environ.get("KERNEL_CLANG_VERSION", "see kernel-build.log"),
    },
    "kernel": {
        "release": kernel_release,
        "feature_set": "cap34",
        "config_sha256": digest(output / "kernel.config")["sha256"],
        "embedded_config_sha256": digest(output / "embedded.config")["sha256"],
        "module_release": kernel_release,
    },
    "patches": patches,
    "artifacts": {name: digest(output / name) for name in artifact_names},
    "rollback": {
        name: baseline["artifacts"][name]["sha256"]
        for name in artifact_names
    },
    "source_bundle_sha256": digest(output / "uml-kernel-source-bundle.tar.gz")["sha256"],
    "module_probe": {
        "file": "uml_module_probe.ko",
        "status": "BUILT_NOT_RUNTIME_TESTED"
    }
}
(output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(output / "rollback/baseline-artifacts.json").write_text(json.dumps(baseline, indent=2) + "\n")
PY

sha256sum "$OUTPUT_DIR/manifest.json" "$OUTPUT_DIR/libvmlinux.so" \
	"$OUTPUT_DIR/libuml-stub.so" "$OUTPUT_DIR/libuml-passt.so" \
	"$OUTPUT_DIR/kernel.config" "$OUTPUT_DIR/uml-kernel-source-bundle.tar.gz" \
	| tee "$OUTPUT_DIR/SHA256SUMS"
echo "build-uml-kernel: artifacts written to $OUTPUT_DIR"
