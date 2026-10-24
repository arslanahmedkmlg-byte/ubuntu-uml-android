# Ubuntu UML for Android — initial engineering workspace

Goal: a dedicated Android ARM64 app that boots an Ubuntu ARM64 guest using User-Mode Linux (UML), not QEMU/KVM, and does not require Android root.

This repository seed is **not yet a working APK**. It defines the first reproducible milestone: build a persistent Ubuntu ARM64 ext4 guest image and validate its filesystem-level prerequisites. The next gate is to boot this image with Haven's existing ARM64 UML transport on an actual ARM64 Android device, then integrate the working path into a minimal standalone app.

## Non-negotiable requirements

- Android `arm64-v8a`, no root, no QEMU, no KVM.
- UML kernel + Haven-compatible launcher and patched `passt` transport.
- Ubuntu ARM64 guest, persistent ext4 disk; 2 GiB default disk size, configurable later.
- RAM configurable in the app; do not assume a fixed value is optimal.
- Network/DNS, interactive console, start/stop/restart and persistent state.
- No guest rootfs download choice in the user-facing UI: guest provisioning is part of first-run setup or the APK distribution strategy.

## First validation gate

1. Build an Ubuntu ARM64 base rootfs using the script under `scripts/`.
2. Confirm the image is ext4 and contains `/sbin/init`, `/bin/sh`, APT, CA certificates and resolver setup.
3. Boot it under the pinned ARM64 UML transport on an ARM64 Android device.
4. From the guest, verify `ip route`, DNS resolution, `apt update`, package installation, shutdown/reboot, and persistence.

A successful `debootstrap` is **not** proof that Ubuntu boots under this UML kernel. Do not label the guest compatible until the device boot test passes.

## Licensing

Haven is AGPL-3.0. Before copying or distributing Haven application code, preserve notices and comply with its license. The UML kernel and transport components have their own license/notice requirements; keep their source pins and checksums documented.
