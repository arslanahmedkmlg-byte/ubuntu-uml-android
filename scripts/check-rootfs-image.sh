#!/usr/bin/env bash
# Inspect the FINISHED ext4 image. Does not prove that Android UML boots.
set -Eeuo pipefail
IMAGE="${1:?Usage: check-rootfs-image.sh path/to/ubuntu.ext4}"
test -s "$IMAGE"
file "$IMAGE" | grep -qi "ext4 filesystem data"
(( $(stat -c %s "$IMAGE") >= 1073741824 )) || { echo "Image smaller than 1 GiB" >&2; exit 1; }
if [[ -f "$IMAGE.sha256" ]]; then (cd "$(dirname "$IMAGE")" && sha256sum -c "$(basename "$IMAGE").sha256"); fi
[[ "$(id -u)" -eq 0 ]] || { echo "Image inspection requires root" >&2; exit 2; }
MNT=$(mktemp -d)
cleanup() { umount "$MNT" 2>/dev/null || true; rmdir "$MNT" 2>/dev/null || true; }
trap cleanup EXIT
mount -t ext4 -o loop,ro,noload "$IMAGE" "$MNT"
required=(/sbin/init /bin/sh /usr/bin/apt-get /usr/bin/bash
 /usr/lib/systemd/systemd-udevd /usr/lib/systemd/systemd-networkd
 /usr/lib/systemd/systemd-resolved /usr/bin/resolvectl
 /usr/bin/networkctl /usr/bin/ip /usr/bin/udevadm /sbin/agetty
 /usr/bin/curl /usr/bin/git /usr/bin/ssh)
for p in "${required[@]}"; do
  test -e "$MNT$p" || { echo "MISSING IN EXT4: $p" >&2; exit 1; }
done
packages=(systemd-sysv udev systemd-resolved systemd-timesyncd dbus login passwd
 util-linux iproute2 iputils-ping ca-certificates curl wget dnsutils procps git openssh-client)
for pkg in "${packages[@]}"; do
  dpkg-query --admindir="$MNT/var/lib/dpkg" -W -f='${Status}' "$pkg" 2>/dev/null | grep -qx "install ok installed" || {
    echo "PACKAGE MISSING/UNCONFIGURED: $pkg" >&2; exit 1;
  }
done
grep -qx "Name=vec0" "$MNT/etc/systemd/network/20-uml-vec0.network"
grep -qx "DHCP=ipv4" "$MNT/etc/systemd/network/20-uml-vec0.network"
grep -q -- "--autologin root" "$MNT/etc/systemd/system/console-getty.service.d/10-uml-autologin.conf"
test -L "$MNT/etc/systemd/system/getty.target.wants/console-getty.service"
test -L "$MNT/etc/resolv.conf"
test -L "$MNT/etc/systemd/system/multi-user.target.wants/systemd-networkd.service"
test -L "$MNT/etc/systemd/system/multi-user.target.wants/systemd-resolved.service" || test -L "$MNT/etc/systemd/system/sysinit.target.wants/systemd-resolved.service"
echo "PASS: critical files, packages, console login, vec0 DHCP, udev, DNS baseline."
echo "NOT TESTED: actual UML boot, DHCP handshake, apt network, persistence, Android terminal."
