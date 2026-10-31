#!/usr/bin/env bash
set -uo pipefail

LIMIT="${UML_ACCEPTANCE_TIMEOUT:-30}"
if [[ ! "$LIMIT" =~ ^[1-9][0-9]*$ ]]; then
  echo "UML_ACCEPTANCE_TIMEOUT must be a positive integer." >&2
  exit 2
fi

blocked() {
  echo "BLOCKED: $*" >&2
  return 77
}

need_root() {
  [[ "$(id -u)" == 0 ]] || blocked "requires guest root"
}

need_commands() {
  local command
  for command in "$@"; do
    if ! command -v "$command" >/dev/null 2>&1; then
      blocked "required diagnostic tool is missing: $command"
      return
    fi
  done
}

test_01() {
  blocked "isolated nftables/iptables conntrack-forwarding-NAT fixture is not configured"
}

test_02() {
  need_commands ping || return
  ping -6 -c 1 -W 2 ::1 >/dev/null
}

test_03() {
  need_commands runuser unshare id || return
  local uid
  uid="$(runuser -u nobody -- unshare --user --map-root-user id -u)" || return 1
  [[ "$uid" == 0 ]]
}

test_04() {
  need_root || return
  need_commands unshare || return
  unshare --pid --fork --mount-proc sh -c 'test "$$" -eq 1'
}

test_05() {
  blocked "bridge forwarding fixture is not configured"
}

test_06() {
  need_root || return
  need_commands ip ping || return
  local suffix="u$(( $$ % 10000 ))"
  local left="ul${suffix}a" right="ul${suffix}b"
  cleanup_veth() {
    ip link del "$left" >/dev/null 2>&1 || true
  }
  trap cleanup_veth RETURN
  ip link add "$left" type veth peer name "$right" || return 1
  ip addr add 192.0.2.1/30 dev "$left" || return 1
  ip addr add 192.0.2.2/30 dev "$right" || return 1
  ip link set "$left" up || return 1
  ip link set "$right" up || return 1
  ping -I "$left" -c 1 -W 2 192.0.2.2 >/dev/null
}

test_07() {
  blocked "safe cgroup-v2 memory workload fixture is not configured"
}

test_08() {
  blocked "bounded pids.max fork-enforcement fixture is not configured"
}

test_09() {
  blocked "bounded cpu.max throttling fixture is not configured"
}

test_10() {
  need_root || return
  need_commands mount mountpoint || return
  local tmp
  tmp="$(mktemp -d /var/tmp/uml-overlay.XXXXXX)" || return 1
  cleanup_overlay() {
    mountpoint -q "$tmp/merged" && umount "$tmp/merged"
    rm -rf "$tmp"
  }
  trap cleanup_overlay RETURN
  mkdir "$tmp/lower" "$tmp/upper" "$tmp/work" "$tmp/merged"
  printf 'lower\n' > "$tmp/lower/probe"
  mount -t overlay overlay -o "lowerdir=$tmp/lower,upperdir=$tmp/upper,workdir=$tmp/work" \
    "$tmp/merged" || return 1
  printf 'upper\n' > "$tmp/merged/probe" || return 1
  [[ "$(cat "$tmp/merged/probe")" == upper ]] || return 1
  [[ "$(cat "$tmp/lower/probe")" == lower ]] || return 1
  [[ -f "$tmp/upper/probe" ]]
}

test_11() {
  blocked "a local FUSE daemon fixture is required to prove mount and unmount"
}

test_12() {
  need_root || return
  need_commands mksquashfs mount mountpoint || return
  local tmp compressor
  tmp="$(mktemp -d /var/tmp/uml-squashfs.XXXXXX)" || return 1
  cleanup_squashfs() {
    mountpoint -q "$tmp/mnt" && umount "$tmp/mnt"
    rm -rf "$tmp"
  }
  trap cleanup_squashfs RETURN
  mkdir "$tmp/input" "$tmp/mnt"
  printf 'squashfs acceptance\n' > "$tmp/input/probe"
  for compressor in gzip xz lz4 zstd; do
    mksquashfs "$tmp/input" "$tmp/$compressor.sqfs" -noappend -processors 1 \
      -quiet -comp "$compressor" || return 1
    mount -t squashfs -o loop,ro "$tmp/$compressor.sqfs" "$tmp/mnt" || return 1
    [[ "$(cat "$tmp/mnt/probe")" == "squashfs acceptance" ]] || return 1
    umount "$tmp/mnt" || return 1
  done
}

test_13() {
  need_root || return
  need_commands setfacl getfacl runuser || return
  local tmp
  tmp="$(mktemp /var/tmp/uml-acl.XXXXXX)" || return 1
  trap 'rm -f "$tmp"' RETURN
  chmod 600 "$tmp"
  printf 'acl acceptance\n' > "$tmp"
  setfacl -m u:nobody:r "$tmp" || return 1
  getfacl -cp "$tmp" | grep -Fxq 'user:nobody:r--' || return 1
  runuser -u nobody -- cat "$tmp" 2>/dev/null | grep -Fxq 'acl acceptance'
}

test_14() {
  need_root || return
  need_commands setcap getcap || return
  local tmp
  tmp="$(mktemp /var/tmp/uml-xattr.XXXXXX)" || return 1
  trap 'rm -f "$tmp"' RETURN
  cp /bin/true "$tmp" || return 1
  setcap cap_net_bind_service=ep "$tmp" || return 1
  getcap "$tmp" | grep -Fq 'cap_net_bind_service=ep'
}

test_15() {
  blocked "AppArmor enforcement needs a loaded guest LSM and a disposable denial profile"
}

test_16() {
  blocked "audit event collection needs an isolated audit-rule fixture"
}

test_17() {
  need_commands keyctl || return
  local serial value description="uml-acceptance-$$"
  serial="$(keyctl add user "$description" probe @s)" || return 1
  cleanup_key() {
    keyctl unlink "$serial" @s >/dev/null 2>&1 || true
  }
  trap cleanup_key RETURN
  value="$(keyctl pipe "$serial")" || return 1
  [[ "$value" == probe ]]
}

test_18() {
  need_commands python3 || return
  python3 - <<'PY'
import hashlib
import socket

try:
    parent = socket.socket(socket.AF_ALG, socket.SOCK_SEQPACKET, 0)
except (AttributeError, OSError) as exc:
    raise SystemExit(f"AF_ALG unavailable: {exc}")
parent.bind(("hash", "sha256"))
operation, _ = parent.accept()
message = b"UML kernel crypto acceptance"
operation.sendall(message)
if operation.recv(32) != hashlib.sha256(message).digest():
    raise SystemExit("kernel AF_ALG SHA-256 result mismatch")
PY
}

test_19() {
  blocked "disposable loop-backed dm-crypt fixture is not configured"
}

test_20() {
  blocked "a prebuilt minimal BPF program fixture is required"
}

test_21() {
  blocked "fanotify event helper is not installed in the diagnostic profile"
}

test_22() {
  blocked "bounded tc rate/counter traffic fixture is not configured"
}

test_23() {
  need_root || return
  need_commands ip ping || return
  local suffix="u$(( $$ % 10000 ))"
  local left="uv${suffix}a" right="uv${suffix}b"
  local left_vlan="ux${suffix}" right_vlan="uy${suffix}"
  cleanup_vlan() {
    ip link del "$left" >/dev/null 2>&1 || true
  }
  trap cleanup_vlan RETURN
  ip link add "$left" type veth peer name "$right" || return 1
  ip link add link "$left" name "$left_vlan" type vlan id 23 || return 1
  ip link add link "$right" name "$right_vlan" type vlan id 23 || return 1
  ip addr add 198.18.23.1/30 dev "$left_vlan" || return 1
  ip addr add 198.18.23.2/30 dev "$right_vlan" || return 1
  ip link set "$left" up || return 1
  ip link set "$right" up || return 1
  ip link set "$left_vlan" up || return 1
  ip link set "$right_vlan" up || return 1
  ping -I "$left_vlan" -c 1 -W 2 198.18.23.2 >/dev/null
}

test_24() {
  need_commands python3 || return
  python3 - <<'PY'
import os
import subprocess
import sys

try:
    cpus = sorted(os.sched_getaffinity(0))
except (AttributeError, OSError) as exc:
    raise SystemExit(f"CPU affinity unavailable: {exc}")
if len(cpus) < 2:
    raise SystemExit(77)
worker = (
    "import os,sys; "
    "os.sched_setaffinity(0,{int(sys.argv[1])}); "
    "sum(range(500000))"
)
processes = [
    subprocess.Popen([sys.executable, "-c", worker, str(cpu)])
    for cpu in cpus[:2]
]
if any(process.wait(timeout=3) != 0 for process in processes):
    raise SystemExit("pinned guest CPU worker failed")
PY
}

test_25() {
  need_root || return
  need_commands insmod rmmod modinfo || return
  local module="${UML_MODULE_PROBE:-}"
  [[ -n "$module" && -s "$module" ]] ||
    { blocked "set UML_MODULE_PROBE to the matching uml_module_probe.ko"; return; }
  local release
  release="$(uname -r)"
  modinfo -F vermagic "$module" | grep -Fq "$release " ||
    { echo "module vermagic does not match $release" >&2; return 1; }
  insmod "$module" || return 1
  rmmod uml_module_probe
}

test_26() {
  blocked "local WireGuard peer handshake fixture is not configured"
}

test_27() {
  blocked "set up a disposable NFS export and endpoint before testing the client"
}

test_28() {
  blocked "set up a disposable SMB3 share and local credential file before testing the client"
}

test_29() {
  blocked "Btrfs loop-image formatting and snapshot fixture is not configured"
}

test_30() {
  blocked "XFS loop-image formatting fixture is not configured"
}

test_31() {
  blocked "zram device reset fixture is not configured"
}

test_32() {
  blocked "zswap requires a temporary swap-backed bounded workload fixture"
}

test_33() {
  local path
  for path in /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io; do
    [[ -r "$path" ]] || { blocked "PSI interface is unavailable: $path"; return; }
    grep -Eq '^some avg10=[0-9]+([.][0-9]+)? avg60=[0-9]+([.][0-9]+)? avg300=[0-9]+([.][0-9]+)? total=[0-9]+$' \
      "$path" || return 1
  done
}

test_34() {
  blocked "taskstats requires a generic-netlink query helper not installed in the diagnostic profile"
}

run_test() {
  local id="$1" name="$2" function="$3" log status rc
  log="$(mktemp)" || return 1
  if timeout --foreground "${LIMIT}s" bash -c \
    "$(declare -f blocked need_root need_commands "$function"); $function" \
    >"$log" 2>&1; then
    status=PASS
  else
    rc=$?
    if [[ "$rc" == 77 ]]; then
      status=BLOCKED
    else
      status=FAIL
    fi
  fi
  printf '%s\t%s\t%s' "$id" "$status" "$name"
  if [[ -s "$log" ]]; then
    printf '\t'
    tr '\n' ' ' < "$log"
  elif [[ "$status" == FAIL ]]; then
    printf '\tcommand exited with status %s' "$rc"
  fi
  printf '\n'
  rm -f "$log"
  case "$status" in
    FAIL) (( FAILURES += 1 )) ;;
    BLOCKED) (( BLOCKED += 1 )) ;;
  esac
}

FAILURES=0
BLOCKED=0
run_test 01 "Netfilter, nftables/iptables, conntrack and NAT" test_01
run_test 02 "IPv6" test_02
run_test 03 "User namespaces" test_03
run_test 04 "PID namespaces" test_04
run_test 05 "Bridges" test_05
run_test 06 "VETH" test_06
run_test 07 "Cgroup memory limits" test_07
run_test 08 "Cgroup process-count limits" test_08
run_test 09 "Cgroup CPU quotas" test_09
run_test 10 "OverlayFS" test_10
run_test 11 "FUSE" test_11
run_test 12 "SquashFS compressors" test_12
run_test 13 "ext4 POSIX ACLs" test_13
run_test 14 "ext4 security xattrs" test_14
run_test 15 "LSM and AppArmor" test_15
run_test 16 "Kernel auditing" test_16
run_test 17 "Kernel keyrings" test_17
run_test 18 "Kernel crypto API" test_18
run_test 19 "Device mapper and dm-crypt/LUKS" test_19
run_test 20 "eBPF program loading" test_20
run_test 21 "Fanotify" test_21
run_test 22 "Traffic scheduling and shaping" test_22
run_test 23 "VLAN interfaces" test_23
run_test 24 "Multiple guest CPUs" test_24
run_test 25 "Loadable modules" test_25
run_test 26 "WireGuard" test_26
run_test 27 "NFS client" test_27
run_test 28 "SMB/CIFS client" test_28
run_test 29 "Btrfs" test_29
run_test 30 "XFS" test_30
run_test 31 "zram" test_31
run_test 32 "zswap" test_32
run_test 33 "Pressure Stall Information" test_33
run_test 34 "Task statistics" test_34

printf 'Summary: %d PASS, %d FAIL, %d BLOCKED\n' \
  "$((34 - FAILURES - BLOCKED))" "$FAILURES" "$BLOCKED"
if (( FAILURES > 0 )); then
  exit 1
elif (( BLOCKED > 0 )); then
  exit 2
fi
