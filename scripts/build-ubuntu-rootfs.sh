#!/usr/bin/env bash
# Build a minimal Ubuntu ARM64 rootfs and a 2 GiB ext4 disk image.
# Intended for a native ARM64 Ubuntu GitHub Actions runner, not Android/Termux.
# This produces a candidate image only; UML boot compatibility must be tested separately.
set -Eeuo pipefail

RELEASE="${UBUNTU_RELEASE:-noble}"
ARCH="arm64"
SIZE_MIB="${DISK_SIZE_MIB:-2048}"
OUT="${OUT_DIR:-$PWD/out}"
WORK="${WORK_DIR:-$PWD/.work}"
ROOT="$WORK/rootfs"
IMG="$OUT/ubuntu-${RELEASE}-${ARCH}.ext4"

cleanup_mounts() {
  local status=$?
  trap - EXIT
  for target in "$WORK/mnt" "$ROOT/sys" "$ROOT/proc"; do
    if [[ -d "$target" ]] && mountpoint -q "$target"; then
      if ! umount "$target"; then
        echo "Failed to unmount $target during cleanup." >&2
        status=1
      fi
    fi
  done
  exit "$status"
}
trap 'status=$?; printf "ERROR: line %s: command exited with status %s: %s\n" "$LINENO" "$status" "$BASH_COMMAND" >&2' ERR
trap cleanup_mounts EXIT

if [[ "$(id -u)" != 0 ]]; then
  echo "Run this script as root on an Ubuntu build runner." >&2
  exit 2
fi
if ! [[ "$SIZE_MIB" =~ ^[0-9]+$ ]] || (( SIZE_MIB < 1024 )); then
  echo "DISK_SIZE_MIB must be an integer >= 1024." >&2
  exit 2
fi
if [[ "$(dpkg --print-architecture)" != "$ARCH" ]]; then
  echo "This build must run natively on $ARCH; QEMU cross-architecture builds are unsupported." >&2
  exit 2
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends debootstrap e2fsprogs ca-certificates util-linux
mkdir -p "$OUT" "$WORK"
for target in "$WORK/mnt" "$ROOT/sys" "$ROOT/proc" "$ROOT"; do
  if mountpoint -q "$target"; then umount "$target"; fi
done
rm -rf "$ROOT" "$IMG"
mkdir -p "$ROOT"

debootstrap --arch="$ARCH" --variant=minbase "$RELEASE" "$ROOT" http://ports.ubuntu.com/ubuntu-ports

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
for spec in 'null:c:1:3:666' 'zero:c:1:5:666' 'tty:c:5:0:666' 'tty0:c:4:0:600' 'console:c:5:1:600' 'random:c:1:8:666' 'urandom:c:1:9:666'; do
  IFS=: read -r node kind major minor mode <<< "$spec"
  if [[ ! -e "$ROOT/dev/$node" ]]; then mknod "$ROOT/dev/$node" "$kind" "$major" "$minor"; fi
  chmod "$mode" "$ROOT/dev/$node"
done
# Ensure chroot package operations can resolve mirrors even when the base
# image uses systemd-resolved's stub symlink, which is not running in chroot.
rm -f "$ROOT/etc/resolv.conf"
cp --dereference /etc/resolv.conf "$ROOT/etc/resolv.conf"

# Package maintainer scripts and systemd-tmpfiles expect these kernel
# filesystems to be mounted inside the chroot.
mkdir -p "$ROOT/proc" "$ROOT/sys"
mount -t proc -o nosuid,nodev,noexec proc "$ROOT/proc"
mount -t sysfs -o ro,nosuid,nodev,noexec sysfs "$ROOT/sys"
mountpoint -q "$ROOT/proc"
mountpoint -q "$ROOT/sys"

# Keep the guest small but usable. Package availability is tested at image build time.
chroot "$ROOT" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get update
# Keep the full guest utility baseline while avoiding recommends that are not
# needed for UML, networking, package management, or local console use.
chroot "$ROOT" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get \
  -o Dpkg::Use-Pty=0 install -y --no-install-recommends \
  systemd-sysv systemd-resolved systemd-timesyncd udev dbus sudo bash \
  ca-certificates \
  curl wget iproute2 iputils-ping netbase dnsutils vim-tiny less locales tzdata \
  util-linux login passwd procps kmod e2fsprogs git openssh-client tmux
chroot "$ROOT" apt-get clean
umount "$ROOT/sys"
umount "$ROOT/proc"

# UML's vector transport creates vec0. passt's forced local mode supplies its
# DHCP address, gateway, and DNS; networkd retries DHCP as the device appears.
mkdir -p "$ROOT/etc/systemd/network"
cat > "$ROOT/etc/systemd/network/10-loopback.network" <<'NETWORK'
[Match]
Name=lo

[Network]
Address=127.0.0.1/8
Address=::1/128
LinkLocalAddressing=no
ConfigureWithoutCarrier=yes
NETWORK
cat > "$ROOT/etc/systemd/network/20-uml-vec.network" <<'NETWORK'
[Match]
Name=vec0

[Link]
RequiredForOnline=no

[Network]
DHCP=ipv4
IPv6AcceptRA=yes

[DHCPv4]
UseDNS=yes
UseRoutes=yes
NETWORK
systemctl --root="$ROOT" enable \
  systemd-networkd.service systemd-resolved.service dbus.service \
  systemd-timesyncd.service
systemctl --root="$ROOT" disable systemd-networkd-wait-online.service

# UML exposes its inherited PTY as the guest console. Keep automatic access
# restricted to that local console; no SSH server is installed.
mkdir -p "$ROOT/etc/systemd/system/console-getty.service.d"
cat > "$ROOT/etc/systemd/system/console-getty.service.d/10-uml-autologin.conf" <<'GETTY'
[Service]
ExecStart=
TTYPath=/dev/tty0
ExecStart=-/sbin/agetty --autologin root --noclear --keep-baud 115200,38400,9600 tty0 vt102
GETTY
mkdir -p "$ROOT/etc/systemd/system/getty.target.wants"
ln -sfn /usr/lib/systemd/system/console-getty.service \
  "$ROOT/etc/systemd/system/getty.target.wants/console-getty.service"

# An interactive login can host multiple independent guest PTYs with tmux.
# Only start it on a real TTY, and leave an ordinary login shell available
# if tmux cannot start. Do not involve the recovery init=/bin/bash path.
cat >> "$ROOT/root/.profile" <<'TMUX_PROFILE'
if [ -t 0 ] && [ -z "${TMUX:-}" ] && command -v tmux >/dev/null 2>&1; then
  tmux new-session -A -s ubuntu
fi
TMUX_PROFILE
cat > "$ROOT/root/.tmux.conf" <<'TMUX_CONF'
set -g history-limit 25000
set -g mouse off
set -g status on
set -g renumber-windows on
set -g default-terminal "screen-256color"
set -g escape-time 10
set -g allow-passthrough on
TMUX_CONF

# OSC 133 marks shell prompt, input and output boundaries. This enables
# accurate tap-to-position and "copy last output" in the Haven renderer.
# tmux needs its DCS passthrough envelope for terminal-directed OSC codes.
cat >> "$ROOT/root/.bashrc" <<'SHELL_INTEGRATION'
if [[ $- == *i* && -z ${_UML_OSC133_READY:-} ]]; then
  _UML_OSC133_READY=1
  _uml_osc133() {
    if [[ -n ${TMUX:-} ]]; then
      printf '\033Ptmux;\033\033]133;%s\007\033\\' "$1"
    else
      printf '\033]133;%s\007' "$1"
    fi
  }
  _uml_previous_prompt_command=${PROMPT_COMMAND:-}
  _uml_finish_command() {
    local exit_code=$?
    _uml_osc133 "D;${exit_code}"
    if [[ -n $_uml_previous_prompt_command ]]; then
      eval "$_uml_previous_prompt_command"
    fi
  }
  PROMPT_COMMAND=_uml_finish_command
  PS0='\[$(_uml_osc133 C)\]'
  PS1='\[$(_uml_osc133 A)\]'"${PS1:-\u@\h:\w\$ }"'\[$(_uml_osc133 B)\]'
fi
SHELL_INTEGRATION

mkdir -p "$ROOT/etc/systemd/resolved.conf.d"
cat > "$ROOT/etc/systemd/resolved.conf.d/10-ubuntu-uml.conf" <<'RESOLVED'
[Resolve]
DNSStubListener=yes
FallbackDNS=1.1.1.1 2606:4700:4700::1111
RESOLVED

# passt advertises its configured DNS server (1.1.1.1 by default) over DHCP.
# resolved consumes that per-link server and serves local clients via this stub.
rm -f "$ROOT/etc/resolv.conf"
ln -s ../run/systemd/resolve/stub-resolv.conf "$ROOT/etc/resolv.conf"
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/var/cache/apt/archives/"*.deb
rm -rf "$ROOT/debootstrap"

test -s "$ROOT/etc/systemd/network/10-loopback.network"
test -s "$ROOT/etc/systemd/network/20-uml-vec.network"
grep -Fxq 'Name=vec0' "$ROOT/etc/systemd/network/20-uml-vec.network"
grep -Fxq 'DHCP=ipv4' "$ROOT/etc/systemd/network/20-uml-vec.network"
service_enabled() {
  local enabled_link
  enabled_link="$(find "$ROOT/etc/systemd/system" "$ROOT/usr/lib/systemd/system" \
    -type l -path "*.wants/$1" -print -quit)"
  [[ -n "$enabled_link" ]]
}
service_enabled systemd-networkd.service
service_enabled systemd-resolved.service
service_enabled systemd-timesyncd.service
test -s "$ROOT/usr/lib/systemd/system/dbus.service"
test -x "$ROOT/usr/bin/dbus-daemon"
test -x "$ROOT/usr/lib/systemd/systemd-udevd"
test -x "$ROOT/usr/bin/udevadm"
test -x "$ROOT/usr/bin/ssh"
test -x "$ROOT/usr/bin/git"
test -x "$ROOT/usr/sbin/modprobe"
grep -Fq -- '--autologin root' \
  "$ROOT/etc/systemd/system/console-getty.service.d/10-uml-autologin.conf"
test -L "$ROOT/etc/systemd/system/getty.target.wants/console-getty.service"
wait_online_link="$(find "$ROOT/etc/systemd/system" "$ROOT/usr/lib/systemd/system" \
  -type l -path '*.wants/systemd-networkd-wait-online.service' -print -quit)"
if [[ -n "$wait_online_link" ]]; then
  echo "networkd-wait-online must stay disabled for the late-arriving UML interface." >&2
  exit 1
fi
test "$(readlink "$ROOT/etc/resolv.conf")" = ../run/systemd/resolve/stub-resolv.conf
test -s "$ROOT/etc/systemd/resolved.conf.d/10-ubuntu-uml.conf"
test ! -e "$ROOT/usr/sbin/sshd"

# Create a filesystem image; no loop mount is needed for population.
truncate -s "${SIZE_MIB}M" "$IMG"
mkfs.ext4 -F -L UBUNTU_UML "$IMG"
mkdir -p "$WORK/mnt"
mount -o loop "$IMG" "$WORK/mnt"
cp -a "$ROOT/." "$WORK/mnt/"
sync
umount "$WORK/mnt"

# Basic structural checks, not a UML boot test.
file "$IMG"
 dumpe2fs -h "$IMG" 2>/dev/null | grep -E 'Filesystem volume name|Block count|Block size'
test -x "$ROOT/sbin/init"
test -x "$ROOT/bin/sh"
test -x "$ROOT/usr/bin/apt-get"
(cd "$OUT" && sha256sum "$(basename "$IMG")" | tee "$(basename "$IMG").sha256")
echo "Candidate image: $IMG"
echo "NOTE: boot under ARM64 UML and test networking/APT/persistence before release."
