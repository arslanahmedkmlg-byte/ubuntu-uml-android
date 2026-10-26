#!/usr/bin/env bash
# Validate a candidate image and its persistent UML network configuration.
# These checks do not prove UML boot or end-to-end network connectivity.
set -Eeuo pipefail
IMAGE="${1:?Usage: check-rootfs-image.sh path/to/ubuntu.ext4}"
SIZE_MIB="${DISK_SIZE_MIB:-2048}"

if [[ "$(id -u)" != 0 ]]; then
  echo "Run this image validation as root to mount the ext4 image read-only." >&2
  exit 2
fi
if ! [[ "$SIZE_MIB" =~ ^[0-9]+$ ]] || (( SIZE_MIB < 1024 )); then
  echo "DISK_SIZE_MIB must be an integer >= 1024." >&2
  exit 2
fi
[[ -s "$IMAGE" ]] || { echo "Image is missing or empty: $IMAGE" >&2; exit 1; }
IMAGE="$(realpath "$IMAGE")"
file "$IMAGE" | grep -qi 'ext4 filesystem data'
EXPECTED_BYTES=$((SIZE_MIB * 1024 * 1024))
ACTUAL_BYTES="$(stat -c %s "$IMAGE")"
[[ "$ACTUAL_BYTES" -eq "$EXPECTED_BYTES" ]] || {
  echo "Expected ${SIZE_MIB} MiB, got ${ACTUAL_BYTES} bytes." >&2
  exit 1
}
e2fsck -fn "$IMAGE"

CHECKSUM="$(basename "$IMAGE").sha256"
[[ -s "$(dirname "$IMAGE")/$CHECKSUM" ]] || {
  echo "Missing checksum file: $(dirname "$IMAGE")/$CHECKSUM" >&2
  exit 1
}
(cd "$(dirname "$IMAGE")" && sha256sum -c "$CHECKSUM")

MOUNT_DIR="$(mktemp -d)"
cleanup() {
  if mountpoint -q "$MOUNT_DIR"; then
    umount "$MOUNT_DIR"
  fi
  rmdir "$MOUNT_DIR"
}
trap cleanup EXIT
mount -o loop,ro,noload "$IMAGE" "$MOUNT_DIR"

require_file() {
  [[ -e "$MOUNT_DIR$1" ]] || {
    echo "Required rootfs path is missing: $1" >&2
    exit 1
  }
}
require_enabled() {
  find "$MOUNT_DIR/etc/systemd/system" "$MOUNT_DIR/usr/lib/systemd/system" \
    -type l -path "*/wants/$1" -print -quit | grep -q . || {
    echo "Required systemd service is not enabled: $1" >&2
    exit 1
  }
}

file "$MOUNT_DIR/usr/bin/dpkg" | grep -Eqi 'ARM aarch64'
require_file /sbin/init
require_file /bin/sh
require_file /usr/bin/apt-get
require_file /usr/bin/curl
require_file /usr/bin/wget
require_file /usr/bin/dig
require_file /usr/lib/systemd/systemd-networkd
require_file /usr/lib/systemd/systemd-resolved
require_file /usr/lib/systemd/system/dbus.service
require_file /usr/bin/dbus-daemon
require_file /etc/hosts
require_file /etc/hostname
require_file /etc/nsswitch.conf
require_file /etc/fstab

NETWORK_FILES="$(
  find "$MOUNT_DIR/etc/systemd/network" -maxdepth 1 -type f -name '*.network' \
    -printf '%f\n' | sort
)"
[[ "$NETWORK_FILES" == $'10-loopback.network\n20-uml-vec.network' ]] || {
  echo "Unexpected or conflicting networkd configurations:" >&2
  printf '%s\n' "$NETWORK_FILES" >&2
  exit 1
}
LOOPBACK="$MOUNT_DIR/etc/systemd/network/10-loopback.network"
grep -Fxq 'Name=lo' "$LOOPBACK"
grep -Fxq 'Address=127.0.0.1/8' "$LOOPBACK"
grep -Fxq 'Address=::1/128' "$LOOPBACK"
VEC="$MOUNT_DIR/etc/systemd/network/20-uml-vec.network"
grep -Fxq 'Name=vec0' "$VEC"
grep -Fxq 'RequiredForOnline=no' "$VEC"
grep -Fxq 'DHCP=yes' "$VEC"
grep -Fxq 'IPv6AcceptRA=yes' "$VEC"
grep -Fxq 'UseDNS=yes' "$VEC"
grep -Fxq 'UseRoutes=yes' "$VEC"
! grep -Eq '^[[:space:]]*Address=' "$VEC"
require_enabled systemd-networkd.service
require_enabled systemd-resolved.service
require_enabled dbus.service
if find "$MOUNT_DIR/etc/systemd/system" "$MOUNT_DIR/usr/lib/systemd/system" \
  -type l -path '*/wants/systemd-networkd-wait-online.service' -print -quit | grep -q .; then
  echo "networkd-wait-online must be disabled so a late UML interface cannot block startup." >&2
  exit 1
fi

[[ -L "$MOUNT_DIR/etc/resolv.conf" ]]
[[ "$(readlink "$MOUNT_DIR/etc/resolv.conf")" == ../run/systemd/resolve/stub-resolv.conf ]]
require_file /etc/systemd/resolved.conf.d/10-ubuntu-uml.conf
grep -Fxq 'DNSStubListener=yes' "$MOUNT_DIR/etc/systemd/resolved.conf.d/10-ubuntu-uml.conf"
grep -Fq 'FallbackDNS=1.1.1.1 2606:4700:4700::1111' \
  "$MOUNT_DIR/etc/systemd/resolved.conf.d/10-ubuntu-uml.conf"
grep -Eq '^[[:space:]]*hosts:.*files.*(dns|resolve)' "$MOUNT_DIR/etc/nsswitch.conf"
grep -Eq '^127\.0\.0\.1[[:space:]]+localhost([[:space:]]|$)' "$MOUNT_DIR/etc/hosts"
grep -Eq '^::1[[:space:]]+localhost([[:space:]]|$)' "$MOUNT_DIR/etc/hosts"
grep -Eq '^[[:space:]]*proc[[:space:]]+/proc[[:space:]]+proc([[:space:]]|$)' "$MOUNT_DIR/etc/fstab"
grep -Eq '^[[:space:]]*sysfs[[:space:]]+/sys[[:space:]]+sysfs([[:space:]]|$)' "$MOUNT_DIR/etc/fstab"
grep -Eq '^[[:space:]]*devpts[[:space:]]+/dev/pts[[:space:]]+devpts([[:space:]]|$)' "$MOUNT_DIR/etc/fstab"
grep -Eq '^[[:space:]]*tmpfs[[:space:]]+/run[[:space:]]+tmpfs([[:space:]]|$)' "$MOUNT_DIR/etc/fstab"

if [[ ! -s "$MOUNT_DIR/etc/apt/sources.list" ]] &&
   { [[ ! -d "$MOUNT_DIR/etc/apt/sources.list.d" ]] ||
     ! find "$MOUNT_DIR/etc/apt/sources.list.d" -maxdepth 1 -type f \
       \( -name '*.list' -o -name '*.sources' \) -print -quit | grep -q .; }; then
  echo "No APT source configuration found in the image." >&2
  exit 1
fi
if [[ -e "$MOUNT_DIR/usr/lib/systemd/system/NetworkManager.service" ]]; then
  echo "NetworkManager is installed and could conflict with systemd-networkd." >&2
  exit 1
fi
if [[ -e "$MOUNT_DIR/usr/bin/qemu-aarch64-static" ]]; then
  echo "Unexpected QEMU binary in rootfs." >&2
  exit 1
fi

echo "Static rootfs checks passed: ARM64, ${SIZE_MIB} MiB ext4, vec0 DHCP/routes/DNS, resolver, systemd, and APT."
echo "This does not prove UML boot or end-to-end network connectivity."
