# Ubuntu UML terminal integration

This stage integrates:

- Haven's terminal renderer.
- ConnectBot terminal engine.
- Native raw PTY.
- UML kernel launcher.
- Passt networking.
- Ubuntu rootfs importer.
- Start and Stop controls.
- Terminal font sizing.
- Extra-key toolbar.

The Android app imports an Ubuntu ARM64 ext4 filesystem
through the Android document picker.

Supported import formats:

- .ext4
- .img
- .gz
- ZIP archives containing a supported filesystem

The UML components are downloaded from GlassOnTin/uml-transport
and verified against pinned SHA256 checksums.

The Ubuntu 24.04 rootfs has not yet been boot-tested
against this implementation.

Haven and GlassOnTin components remain subject to their
respective open-source licenses.

Current limitations:

- VM lifecycle belongs to the terminal Activity.
- VM stops when the Activity is destroyed.
- RAM is configurable before boot.
- Disk expansion is not implemented yet.
- Advanced font and appearance settings are pending.
- Full Haven modifier-key toolbar integration is pending.
