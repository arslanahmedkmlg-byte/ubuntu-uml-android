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
