# UML kernel capability status

The baseline-derived config resolved all 34 capability requirements through
the pinned source's `olddefconfig` and `scripts/validate-uml-config.py`.
The pinned kernel and matching module probe built locally; no capability is
reported as runtime-working until device/guest acceptance checks pass.

| ID | Capability | Current status | Runtime evidence |
|---:|---|---|---|
| 1 | Netfilter, nftables/iptables, conntrack and NAT | BUILT_NOT_RUNTIME_TESTED | Pending isolated namespace forwarding/NAT checks |
| 2 | IPv6 | BUILT_NOT_RUNTIME_TESTED | Pending guest loopback, namespace, RA and external passt checks |
| 3 | User namespaces | BUILT_NOT_RUNTIME_TESTED | Pending non-root UID-map check |
| 4 | PID namespaces | BUILT_NOT_RUNTIME_TESTED | Pending nested PID/procfs check |
| 5 | Bridges | BUILT_NOT_RUNTIME_TESTED | Pending bridged namespace traffic check |
| 6 | VETH | BUILT_NOT_RUNTIME_TESTED | Pending veth traffic and cleanup check |
| 7 | Cgroup memory limits | BUILT_NOT_RUNTIME_TESTED | Pending bounded cgroup-v2 enforcement check |
| 8 | Cgroup process-count limits | BUILT_NOT_RUNTIME_TESTED | Pending bounded `pids.max` enforcement check |
| 9 | Cgroup CPU quotas | BUILT_NOT_RUNTIME_TESTED | Pending `cpu.max` throttling check |
| 10 | OverlayFS | BUILT_NOT_RUNTIME_TESTED | Pending copy-up and unmount check |
| 11 | FUSE | BUILT_NOT_RUNTIME_TESTED | Pending `/dev/fuse` and local mount check |
| 12 | SquashFS | BUILT_NOT_RUNTIME_TESTED | Pending gzip, xz, lz4 and zstd image checks |
| 13 | ext4 POSIX ACLs | BUILT_NOT_RUNTIME_TESTED | Pending ACL enforcement and persistence check |
| 14 | ext4 security xattrs | BUILT_NOT_RUNTIME_TESTED | Pending file-capability xattr check |
| 15 | LSM framework and guest AppArmor | BUILT_NOT_RUNTIME_TESTED | Pending active LSM/profile-denial check |
| 16 | Kernel auditing | BUILT_NOT_RUNTIME_TESTED | Pending audit-rule event check |
| 17 | Kernel keyrings | BUILT_NOT_RUNTIME_TESTED | Pending temporary key add/read/remove check |
| 18 | Kernel crypto API | BUILT_NOT_RUNTIME_TESTED | Pending AF_ALG/subsystem checks |
| 19 | Device mapper and dm-crypt/LUKS | BUILT_NOT_RUNTIME_TESTED | Pending disposable encrypted loop-image check |
| 20 | eBPF program loading | BUILT_NOT_RUNTIME_TESTED | Pending minimal program load/run check |
| 21 | Fanotify | BUILT_NOT_RUNTIME_TESTED | Pending event and permission-event checks |
| 22 | Traffic scheduling and shaping | BUILT_NOT_RUNTIME_TESTED | Pending bounded qdisc traffic/counter check |
| 23 | VLAN interfaces | BUILT_NOT_RUNTIME_TESTED | Pending isolated tagged traffic check |
| 24 | Multiple guest CPUs | BUILT_NOT_RUNTIME_TESTED | Pending multi-CPU Android-device boot/load check |
| 25 | Loadable modules | BUILT_NOT_RUNTIME_TESTED | Pending matching module load/unload check |
| 26 | WireGuard | BUILT_NOT_RUNTIME_TESTED | Pending local peer handshake and traffic check |
| 27 | NFS client | BUILT_NOT_RUNTIME_TESTED | Pending disposable reachable export check |
| 28 | SMB/CIFS client | BUILT_NOT_RUNTIME_TESTED | Pending disposable SMB3 share check |
| 29 | Btrfs | BUILT_NOT_RUNTIME_TESTED | Pending disposable format/write/snapshot check |
| 30 | XFS | BUILT_NOT_RUNTIME_TESTED | Pending disposable format/read/write check |
| 31 | zram | BUILT_NOT_RUNTIME_TESTED | Pending bounded I/O and reset check |
| 32 | zswap | BUILT_NOT_RUNTIME_TESTED | Pending swap-backed compressed-page check |
| 33 | Pressure Stall Information | BUILT_NOT_RUNTIME_TESTED | Pending PSI interface and pressure check |
| 34 | Task statistics | BUILT_NOT_RUNTIME_TESTED | Pending generic-netlink accounting check |

Update statuses only after evidence exists. A successful build without device
runtime evidence changes a row to `BUILT_NOT_RUNTIME_TESTED`, not `PASS`.
Use `scripts/guest/test-kernel-capabilities.sh` in a disposable guest for
available non-destructive checks; it reports unconfigured fixtures and
unavailable endpoints as `BLOCKED`.
