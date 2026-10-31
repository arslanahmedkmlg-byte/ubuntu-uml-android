# Upstream UML source pins and integration notes

## Reuse rather than rewrite

- Haven source: https://github.com/GlassHaven/Haven
- UML transport and release assets: https://github.com/GlassOnTin/uml-transport
- ARM64 UML kernel source: https://github.com/zalexdev/linux-um-arm64

Haven's `core/local/fetch-uml.sh` pins release `uml-guest-4` and checks SHA-256 for:

| Artifact | SHA-256 |
|---|---|
| `libvmlinux.so` | `71cc9dc73c683a8c00fb533d01e7a1be27974876dcab2068419ea4b272fb387c` |
| `libuml-stub.so` | `83f51f7c45133daa135b595562b09c5e2829f1d0f1e00b7fce2a7695370781fc` |
| `libuml-passt.so` | `17703eb787afcfc57475921f186bec6eae479db00cf60e1763c1a8459c055b36` |

`libuml-net.so` is built from Haven's `core/local/src/main/cpp/uml_net.c`. The launcher uses a Unix `SOCK_SEQPACKET` socketpair and a patched `passt` raw-L2 mode. Keep the matching transport versions together; don't mix arbitrary kernel/stub/passt artifacts.

Haven's existing rootfs fetch script is not included in this seed because the standalone app should provision its own Ubuntu image rather than offer a distro/rootfs picker. Preserve the upstream licenses and source notices when reusing code or binaries.

## Verified kernel baseline and rebuild chain

The rollback set is pinned in [`kernel/config/baseline-artifacts.json`](../kernel/config/baseline-artifacts.json). The `uml-guest-4` kernel hash is `71cc9dc73c683a8c00fb533d01e7a1be27974876dcab2068419ea4b272fb387c`. Its ELF is a static ARM64 executable with no `PT_INTERP`; the embedded config was extracted with the pinned kernel's `scripts/extract-ikconfig` and hashes to `84fae4254570e154b7c42764440b7910e82a74f7d63849d3d77ce42a0b1e1174`. The embedded metadata records Linux/um `7.2.0-rc4` and Android Clang `18.0.1`. `file` reports a GNU/Linux ELF platform string, which does not identify a libc for a static binary; the source README's bionic description is consistent with the Android toolchain metadata but cannot be independently inferred from an interpreter (there is none).

The exact kernel source is `zalexdev/linux-um-arm64` commit `8897487c52233cd00cf2850008ca068892f1ae91`, not the moving `um-arm64` branch and not the stale `7edec4df1` comment in the fetch script. The transport repository's `uml-guest-4` README still labels its table as `uml-guest-3`; the release notes identify the additional post-link compatibility step. The first three kernel patches are vendored under `kernel/patches/`, in this order, from transport commit `58ecef8464c7c312a2d5ec1477be862b408cef5c`:

| Patch | SHA-256 |
|---|---|
| `stub-execve-fallback.patch` | `1bcd1a7c935c59dd6e2f8c023451b3dd3efbf5a0308b574fd78d34f0f6b83e34` |
| `android-app-compat.patch` | `f97687ae4e40aee5f6e4ab136619d4af2c0f9f012a817850016a8f9fec6f0631` |
| `vector-napi-budget.patch` | `5e276e1b9766fd08276158be69fd8f2abf8854272e49d2ca4ddc99c6e20985e7` |
| `module-export-symbols.patch` | `3100157aaf58edb6cbc064eb3cbc8a22c58584202a2079e64ad017c206813597` |

`module-export-symbols.patch` is a local UML build adjustment: modpost accepts the `kernel_` aliases created by the ARM64 UML/bionic link step while retaining the actual renamed export names.

The Android post-link transform and its regression checker are pinned to transport commit `21ef1403aa41ac31f1f02e83718bd45ca560252c`, and vendored in `kernel/harness/`. The build scans syscall instruction sites and applies the upstream-specific replacements for `set_robust_list` and `rseq`; it does not perform blind byte-offset substitutions. The `uml-guest-4` release binary is already patched, so the baseline hash is retained only for rollback and is never represented as the rebuilt candidate.

`kernel/config/uml-guest-4.baseline.config` is the exact extracted baseline. `kernel/config/ubuntu-uml-capabilities.config` is merged into that config and passed through the pinned source's `olddefconfig`; `kernel/config/capabilities.json` and `scripts/validate-uml-config.py` gate all 34 requested capabilities. In Linux 7.2-rc4, nft counter and device-mapper linear support have no independent Kconfig symbols and are compiled with their parent implementations; memory swap accounting is part of `MEMCG`; and ext4 xattr handlers are built with ext4/security support. These are recorded as source-specific mappings rather than invented config flags.

SMP is supported by this ARM64 UML source port: `arch/arm64/um/Kconfig` selects `UML_SUBARCH_SUPPORTS_SMP`. With `SMP=y`, Kconfig naturally clears `BROKEN_ON_SMP` because that marker depends on `!SMP`; no upstream gate is edited. The app explicitly boots with `ncpus=1` to preserve the existing single-CPU behavior until device runtime checks prove SMP and a bounded setting can be exposed. Feature-bearing filesystem, network, cgroup, crypto and other drivers are configured built-in; `MODULES=y`, `MODULE_UNLOAD=y` and `MODVERSIONS=y` are separately requested for module acceptance. Module signing is not enforced because no private key is shipped, and `MODULES_TREE_LOOKUP` is not enabled because its source dependencies (`PERF_EVENTS`, `TRACING` or `CFI`) are not otherwise required.

Run `scripts/build-uml-kernel.sh` with Android NDK `27.0.12077973`. It downloads the baseline set, verifies its checksums and embedded config, fetches the exact kernel commit, applies the vendored series, resolves and validates Kconfig, builds the ARM64 UML kernel and matching stub, applies/gates the Android syscall transform, builds a matching benign module probe, and emits hashed candidate/rollback artifacts plus a source bundle. Candidate hashes are generated per build in `manifest.json`; no mutable release tag or expiring workflow URL is a permanent pin. A successful local candidate build is recorded, but the APK/CI integration and all 34 runtime operations still require verification before any capability is reported as working.

## Guest acceptance and rootfs candidates

The rootfs workflow is separate from the APK/kernel workflow. It can build an independent ext4 candidate with `KERNEL_DIAGNOSTICS=1` (or the workflow's **Include optional guest kernel diagnostic tools** input); it never replaces or migrates an installed writable rootfs. Boot and test a disposable candidate before considering it for use.

The image installs `/usr/local/sbin/uml-kernel-acceptance`. Run it as guest root to emit one tab-separated `PASS`, `FAIL`, or `BLOCKED` result per capability ID, followed by a summary. Missing diagnostic tools, unavailable endpoints, and tests requiring a not-yet-configured isolated fixture are reported as `BLOCKED`, not as success. Set `UML_MODULE_PROBE` to the matching build's `uml_module_probe.ko` to run the module load/unload check. Exit status is `0` when every test passes, `1` when any test fails, and `2` when checks are blocked.
