#!/bin/bash
# Fetch the UML guest transport binaries (PROTOTYPE.md in the uml-transport
# workspace) into jniLibs.
#
# The UML guest transport runs a real Linux kernel (user-mode linux) as the
# pty child, wired to passt for networking. Four binaries are involved:
#   libvmlinux.so   the UML kernel, built against bionic, static (79 MB)
#   libuml-stub.so  UML's SKAS stub (memfd exec is refused on Android, so
#                   the kernel loads it from disk via stub_exe=)
#   libuml-passt.so passt, musl static, with the PASST_RAW_L2 /
#                   PASST_NO_SANDBOX patch set (integration/passt/)
#   libuml-net.so   the socketpair launcher — built in-repo from
#                   src/main/cpp/uml_net.c (CMake), NOT fetched here
#
# Android app storage is noexec and exec is only allowed from
# nativeLibraryDir, so they ship as jniLibs. arm64-v8a only: there is no
# bionic build of this kernel for the other ABIs. Builds for other ABIs
# (and F-Droid, via -PskipUml) simply lack the guest transport; the
# NativeFeatures.uml probe hides the UI.
#
# The rollback artifact pins live together in kernel/config/baseline-artifacts.json.
# CI may supply a generated manifest and mirror containing a new kernel/stub
# pair plus the unchanged passt binary.
# Skip with SKIP_UML=1. The Gradle project property is not wired to this
# standalone fetch script.
#
# UML_RELEASE_MIRROR overrides the base URL (file:// works) for
# offline/airgapped builds.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

OUT="${UML_OUTPUT:-$SCRIPT_DIR/src/main/jniLibs}"
MANIFEST="${UML_ARTIFACT_MANIFEST:-$REPO_ROOT/kernel/config/baseline-artifacts.json}"
if [ ! -s "$MANIFEST" ]; then
    echo "fetch-uml: artifact manifest is missing: $MANIFEST" >&2
    exit 1
fi

BASE="${UML_RELEASE_MIRROR:-$(python3 - "$MANIFEST" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as manifest_file:
    print(json.load(manifest_file)["base_url"])
PY
)}"

ASSETS=(libvmlinux.so libuml-stub.so libuml-passt.so)
python3 - "$MANIFEST" "${ASSETS[@]}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as manifest_file:
    manifest = json.load(manifest_file)
assets = manifest.get("artifacts", {})
for name in sys.argv[2:]:
    entry = assets.get(name)
    if not isinstance(entry, dict) or len(entry.get("sha256", "")) != 64:
        raise SystemExit(f"fetch-uml: manifest has no SHA-256 pin for {name}")
PY

if [ "${SKIP_UML:-0}" = "1" ]; then
    echo "fetch-uml: SKIP_UML=1 — APK will lack the UML guest transport"
    exit 0
fi

TMP=$(mktemp -d)
CANDIDATE=""
cleanup() {
    if [ -n "$CANDIDATE" ]; then
        rm -f -- "$CANDIDATE"
    fi
    rm -rf -- "$TMP"
}
trap cleanup EXIT

verify_artifact() {
    local path="$1"
    local machine

    machine="$(readelf -h "$path" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
    if [ "$machine" != "AArch64" ]; then
        echo "fetch-uml: $path is not an AArch64 ELF (machine: ${machine:-unknown})" >&2
        return 1
    fi
    if readelf -l "$path" 2>/dev/null | grep -q 'INTERP'; then
        echo "fetch-uml: $path has a dynamic interpreter; Android execution requires static artifacts" >&2
        return 1
    fi
}

if ! command -v readelf >/dev/null 2>&1; then
    echo "fetch-uml: readelf is required to verify artifact architecture and linkage" >&2
    exit 1
fi

mkdir -p "$OUT/arm64-v8a"

for name in "${ASSETS[@]}"; do
    sha="$(python3 - "$MANIFEST" "$name" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as manifest_file:
    print(json.load(manifest_file)["artifacts"][sys.argv[2]]["sha256"])
PY
)"
    dest="$OUT/arm64-v8a/$name"
    if [ -s "$dest" ]; then
        if printf '%s  %s\n' "$sha" "$dest" | sha256sum -c - >/dev/null 2>&1 &&
            verify_artifact "$dest"; then
            echo "fetch-uml: $dest verified — skipping"
            continue
        fi
        echo "fetch-uml: cached $dest failed verification; fetching a replacement" >&2
    fi
    url="$BASE/$name"
    echo "fetch-uml: $url"
    # --retry-all-errors: plain --retry ignores TLS handshake failures,
    # which is how a release build died fetching a pinned tarball.
    curl -fsSL --retry 3 --retry-all-errors -o "$TMP/$name" "$url" || {
        echo "fetch-uml: FAILED to fetch $url" >&2
        echo "  To build without the UML guest: SKIP_UML=1 or -PskipUml." >&2
        echo "  To build offline from local files: UML_RELEASE_MIRROR=file:///path." >&2
        exit 1
    }
    echo "$sha  $TMP/$name" | sha256sum -c - >/dev/null || {
        echo "fetch-uml: sha256 MISMATCH for $name" >&2
        exit 1
    }
    verify_artifact "$TMP/$name"
    CANDIDATE="$(mktemp "$dest.tmp.XXXXXX")"
    install -m 0755 "$TMP/$name" "$CANDIDATE"
    mv -f -- "$CANDIDATE" "$dest"
    CANDIDATE=""
    echo "fetch-uml: installed $dest ($(stat -c %s "$dest") bytes)"
done