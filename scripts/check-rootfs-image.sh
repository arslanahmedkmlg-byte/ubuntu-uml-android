#!/usr/bin/env bash
# Validate a candidate image and its persistent UML configuration.
# Static checks do not prove UML boot or end-to-end networking.
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
require_executable() {
  [[ -x "$MOUNT_DIR$1" ]] || {
    echo "Required rootfs executable is missing or not executable: $1" >&2
    exit 1
  }
}
require_package() {
  local package="$1" package_status
  if ! package_status="$(dpkg-query --admindir="$MOUNT_DIR/var/lib/dpkg" \
    -W -f='${Status}' "$package" 2>/dev/null)"; then
    echo "Required package is not in the installed package database: $package" >&2
    exit 1
  fi
  [[ "$package_status" == 'install ok installed' ]] || {
    echo "Required package is not fully installed: $package ($package_status)" >&2
    exit 1
  }
}
require_enabled() {
  local enabled_link
  enabled_link="$(find "$MOUNT_DIR/etc/systemd/system" "$MOUNT_DIR/usr/lib/systemd/system" \
    -type l -path "*.wants/$1" -print -quit)"
  [[ -n "$enabled_link" ]] || {
    echo "Required systemd service is not enabled: $1" >&2
    exit 1
  }
}

file "$MOUNT_DIR/usr/bin/dpkg" | grep -Eqi 'ARM aarch64'
PACKAGES=(
  systemd systemd-sysv systemd-resolved systemd-timesyncd udev dbus
  dbus-daemon sudo bash ca-certificates curl wget iproute2 iputils-ping
  netbase bind9-dnsutils vim-tiny less locales tzdata
  util-linux login passwd procps kmod e2fsprogs git openssh-client python3-minimal
)
for package in "${PACKAGES[@]}"; do
  require_package "$package"
done

REQUIRED_FILES=(
  /usr/lib/systemd/system/dbus.service /usr/lib/systemd/system/dbus.socket
  /usr/lib/systemd/system/systemd-udevd.service
  /usr/lib/systemd/system/systemd-udevd-kernel.socket
  /usr/lib/systemd/system/systemd-udevd-control.socket
  /usr/lib/systemd/system/systemd-udev-trigger.service
  /usr/lib/systemd/system/console-getty.service
  /usr/share/terminfo/x/xterm-256color
  /etc/hosts /etc/hostname /etc/nsswitch.conf /etc/fstab
)
for path in "${REQUIRED_FILES[@]}"; do
  require_file "$path"
done
EXECUTABLES=(
  /sbin/init /bin/sh /usr/bin/apt-get /usr/bin/dpkg-query /usr/bin/curl
  /usr/bin/wget /usr/bin/dig /usr/bin/ip /usr/bin/ping /usr/bin/ssh
  /usr/bin/git /usr/bin/udevadm /usr/bin/resolvectl /usr/bin/networkctl
  /usr/bin/systemctl /usr/bin/dbus-daemon /usr/bin/ps /usr/bin/locale
  /usr/bin/timedatectl /usr/sbin/modprobe /usr/sbin/agetty /usr/bin/login
  /usr/bin/passwd /usr/bin/python3 /usr/local/sbin/uml-pty-server /usr/bin/mount /usr/sbin/mkfs.ext4 /usr/sbin/fsck.ext4
  /usr/lib/systemd/systemd-udevd /usr/lib/systemd/systemd-networkd
  /usr/lib/systemd/systemd-resolved /usr/lib/systemd/systemd-timesyncd
)
for path in "${EXECUTABLES[@]}"; do
  require_executable "$path"
done
require_file /lib/ld-linux-aarch64.so.1
require_file /lib/aarch64-linux-gnu/libc.so.6
require_file /usr/lib/aarch64-linux-gnu/libsystemd.so.0
[[ -d "$MOUNT_DIR/usr/lib/udev/rules.d" ]]
[[ -n "$(find "$MOUNT_DIR/usr/lib/udev/rules.d" -maxdepth 1 -type f -print -quit)" ]]

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
grep -Fxq 'DHCP=ipv4' "$VEC"
grep -Fxq 'IPv6AcceptRA=yes' "$VEC"
grep -Fxq 'UseDNS=yes' "$VEC"
grep -Fxq 'UseRoutes=yes' "$VEC"
! grep -Eq '^[[:space:]]*Address=' "$VEC"
! grep -Fq '[DHCPv6]' "$VEC"
require_enabled systemd-networkd.service
require_enabled systemd-resolved.service
require_enabled systemd-timesyncd.service
WAIT_ONLINE_LINK="$(find "$MOUNT_DIR/etc/systemd/system" "$MOUNT_DIR/usr/lib/systemd/system" \
  -type l -path '*.wants/systemd-networkd-wait-online.service' -print -quit)"
if [[ -n "$WAIT_ONLINE_LINK" ]]; then
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

APT_SOURCE_FILES=()
[[ ! -s "$MOUNT_DIR/etc/apt/sources.list" ]] || \
  APT_SOURCE_FILES+=("$MOUNT_DIR/etc/apt/sources.list")
if [[ -d "$MOUNT_DIR/etc/apt/sources.list.d" ]]; then
  while IFS= read -r source_file; do
    APT_SOURCE_FILES+=("$source_file")
  done < <(find "$MOUNT_DIR/etc/apt/sources.list.d" -maxdepth 1 -type f \
    \( -name '*.list' -o -name '*.sources' \) -print)
fi
[[ "${#APT_SOURCE_FILES[@]}" -gt 0 ]] || {
  echo "No APT source configuration found in the image." >&2
  exit 1
}
source_configured=false
for source_file in "${APT_SOURCE_FILES[@]}"; do
  if grep -Eq '^[[:space:]]*deb[[:space:]]+http://ports\.ubuntu\.com/ubuntu-ports[[:space:]]+noble([[:space:]]|$)|^[[:space:]]*(URIs:.*http://ports\.ubuntu\.com/ubuntu-ports|Suites:.*noble)' "$source_file"; then
    source_configured=true
  fi
done
[[ "$source_configured" == true ]] || {
  echo "No Ubuntu Noble ARM64 ports repository configured for APT." >&2
  exit 1
}

CONSOLE_OVERRIDE="$MOUNT_DIR/etc/systemd/system/console-getty.service.d/10-uml-autologin.conf"
require_file /dev/console
require_file /dev/tty0
[[ -c "$MOUNT_DIR/dev/tty0" ]]
[[ "$(stat -c '%t:%T' "$MOUNT_DIR/dev/tty0")" == '4:0' ]]
[[ -c "$MOUNT_DIR/dev/console" ]]
[[ "$(stat -c '%t:%T' "$MOUNT_DIR/dev/console")" == '5:1' ]]
require_file /etc/systemd/system/getty.target.wants/console-getty.service
[[ -L "$MOUNT_DIR/etc/systemd/system/getty.target.wants/console-getty.service" ]]
[[ "$(readlink "$MOUNT_DIR/etc/systemd/system/getty.target.wants/console-getty.service")" == \
  /usr/lib/systemd/system/console-getty.service ]]
require_file "${CONSOLE_OVERRIDE#"$MOUNT_DIR"}"
grep -Fq -- '--autologin root' "$CONSOLE_OVERRIDE"
grep -Fxq 'TTYPath=/dev/tty0' "$CONSOLE_OVERRIDE"
require_file /usr/local/sbin/uml-pty-server
require_file /etc/systemd/system/uml-pty-server.service
require_enabled uml-pty-server.service
grep -Fq '_uml_osc133' "$MOUNT_DIR/root/.bashrc"
grep -Fq ' tty0 xterm-256color' "$CONSOLE_OVERRIDE"
grep -Fxq 'ExecStart=' "$CONSOLE_OVERRIDE"
awk -F: '$1 == "root" && $2 ~ /^[!*]/ { locked=1 } END { exit !locked }' \
  "$MOUNT_DIR/etc/shadow"
if dpkg-query --admindir="$MOUNT_DIR/var/lib/dpkg" -W \
  -f='${Status}' tmux 2>/dev/null | grep -qx 'install ok installed'; then
  echo "tmux must not be installed in the native PTY rootfs." >&2
  exit 1
fi
if dpkg-query --admindir="$MOUNT_DIR/var/lib/dpkg" -W \
  -f='${Status}' screen 2>/dev/null | grep -qx 'install ok installed'; then
  echo "screen must not be installed in the native PTY rootfs." >&2
  exit 1
fi
if dpkg-query --admindir="$MOUNT_DIR/var/lib/dpkg" -W \
  -f='${Status}' openssh-server 2>/dev/null | grep -qx 'install ok installed'; then
  echo "openssh-server must not be installed by default." >&2
  exit 1
fi
[[ ! -e "$MOUNT_DIR/usr/sbin/sshd" ]]

if [[ -e "$MOUNT_DIR/usr/bin/qemu-aarch64-static" || -d "$MOUNT_DIR/debootstrap" ]]; then
  echo "Unexpected QEMU or debootstrap build artifact in rootfs." >&2
  exit 1
fi
if find "$MOUNT_DIR/usr/bin" "$MOUNT_DIR/usr/sbin" -maxdepth 1 -type f \
  -name 'qemu-*' -print -quit | grep -q .; then
  echo "Unexpected QEMU binary in rootfs." >&2
  exit 1
fi
if [[ -e "$MOUNT_DIR/usr/lib/systemd/system/NetworkManager.service" ]]; then
  echo "NetworkManager is installed and could conflict with systemd-networkd." >&2
  exit 1
fi

echo "Static rootfs checks passed: ARM64, ${SIZE_MIB} MiB ext4, installed package inventory, systemd/udev/console, vec0 DHCPv4/RA/DNS, APT, and checksum."
echo "This does not prove UML boot, console login, DHCP, DNS, APT connectivity, or persistence."
