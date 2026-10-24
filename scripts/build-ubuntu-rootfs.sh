#!/usr/bin/env bash
# Build a minimal Ubuntu ARM64 rootfs and a 2 GiB ext4 disk image.
# Intended for a disposable Ubuntu GitHub Actions runner, not Android/Termux.
# This produces a candidate image only; UML boot compatibility must be tested separately.
set -Eeuo pipefail

RELEASE="${UBUNTU_RELEASE:-noble}"
ARCH="arm64"
SIZE_MIB="${DISK_SIZE_MIB:-2048}"
OUT="${OUT_DIR:-$PWD/out}"
WORK="${WORK_DIR:-$PWD/.work}"
ROOT="$WORK/rootfs"
IMG="$OUT/ubuntu-${RELEASE}-${ARCH}.ext4"

if [[ "$(id -u)" != 0 ]]; then
  echo "Run this script as root on an Ubuntu build runner." >&2
  exit 2
fi
if ! [[ "$SIZE_MIB" =~ ^[0-9]+$ ]] || (( SIZE_MIB < 1024 )); then
  echo "DISK_SIZE_MIB must be an integer >= 1024." >&2
  exit 2
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends debootstrap qemu-user-static binfmt-support e2fsprogs ca-certificates util-linux
mkdir -p "$OUT" "$WORK"
if mountpoint -q "$ROOT"; then umount -lf "$ROOT"; fi
rm -rf "$ROOT" "$IMG"
mkdir -p "$ROOT"

# Cross-architecture debootstrap uses qemu-user-static on x86_64 runners.
if [[ "$(dpkg --print-architecture)" == "arm64" ]]; then
  debootstrap --arch="$ARCH" --variant=minbase "$RELEASE" "$ROOT" http://ports.ubuntu.com/ubuntu-ports
else
  debootstrap --foreign --arch="$ARCH" --variant=minbase "$RELEASE" "$ROOT" http://ports.ubuntu.com/ubuntu-ports
  install -D -m 0755 /usr/bin/qemu-aarch64-static "$ROOT/usr/bin/qemu-aarch64-static"
  chroot "$ROOT" /debootstrap/debootstrap --second-stage
fi

# Essential guest defaults; systemd and networking behavior still need UML testing.
cat > "$ROOT/etc/hostname" <<'HOSTNAME'
ubuntu-uml
HOSTNAME
cat > "$ROOT/etc/hosts" <<'HOSTS'
127.0.0.1 localhost
127.0.1.1 ubuntu-uml
::1 localhost ip6-localhost ip6-loopback
HOSTS
cat > "$ROOT/etc/fstab" <<'FSTAB'
proc /proc proc defaults 0 0
sysfs /sys sysfs defaults 0 0
devpts /dev/pts devpts gid=5,mode=620 0 0
tmpfs /run tmpfs nosuid,nodev,mode=755 0 0
tmpfs /tmp tmpfs nosuid,nodev,mode=1777 0 0
FSTAB
mkdir -p "$ROOT/dev/pts" "$ROOT/dev/shm" "$ROOT/run" "$ROOT/tmp"
chmod 1777 "$ROOT/dev/shm" "$ROOT/tmp"
# UML device nodes are not guaranteed to be supplied by devtmpfs.
for spec in 'null:c:1:3:666' 'zero:c:1:5:666' 'tty:c:5:0:666' 'console:c:5:1:600' 'random:c:1:8:666' 'urandom:c:1:9:666'; do
  IFS=: read -r node kind major minor mode <<< "$spec"
  if [[ ! -e "$ROOT/dev/$node" ]]; then mknod "$ROOT/dev/$node" "$kind" "$major" "$minor"; fi
  chmod "$mode" "$ROOT/dev/$node"
done
# Ensure chroot package operations can resolve mirrors even when the base
# image uses systemd-resolved's stub symlink, which is not running in chroot.
rm -f "$ROOT/etc/resolv.conf"
cp --dereference /etc/resolv.conf "$ROOT/etc/resolv.conf"

# Keep the guest small but usable. Package availability is tested at image build time.
chroot "$ROOT" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get update
chroot "$ROOT" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  systemd-sysv dbus sudo bash ca-certificates curl wget iproute2 iputils-ping \
   netbase dnsutils vim-tiny less locales tzdata apt-transport-https
chroot "$ROOT" apt-get clean
# Restore the normal Ubuntu resolver symlink; runtime DNS is supplied by passt.
rm -f "$ROOT/etc/resolv.conf"
ln -s ../run/systemd/resolve/stub-resolv.conf "$ROOT/etc/resolv.conf"
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/var/cache/apt/archives/"*.deb
rm -f "$ROOT/usr/bin/qemu-aarch64-static"

# Create a filesystem image; no loop mount is needed for population.
truncate -s "${SIZE_MIB}M" "$IMG"
mkfs.ext4 -F -L UBUNTU_UML "$IMG"
mkdir -p "$WORK/mnt"
mount -o loop "$IMG" "$WORK/mnt"
trap 'umount "$WORK/mnt" 2>/dev/null || true' EXIT
cp -a "$ROOT/." "$WORK/mnt/"
sync
umount "$WORK/mnt"
trap - EXIT

# Basic structural checks, not a UML boot test.
file "$IMG"
 dumpe2fs -h "$IMG" 2>/dev/null | grep -E 'Filesystem volume name|Block count|Block size'
test -x "$ROOT/sbin/init"
test -x "$ROOT/bin/sh"
test -x "$ROOT/usr/bin/apt-get"
sha256sum "$IMG" | tee "$IMG.sha256"
echo "Candidate image: $IMG"
echo "NOTE: boot under ARM64 UML and test networking/APT/persistence before release."
