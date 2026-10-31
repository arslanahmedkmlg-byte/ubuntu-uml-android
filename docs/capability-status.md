# UML kernel capability status

The baseline-derived config resolved all 34 capability requirements through
the pinned source's `olddefconfig` and `scripts/validate-uml-config.py`.
That is configuration evidence only. The candidate kernel build is in
progress; no capability is reported as runtime-working.

| ID | Capability | Current status | Runtime evidence |
|---:|---|---|---|
| 1 | Netfilter, nftables/iptables, conntrack and NAT | CONFIG_RESOLVED_NOT_BUILT | Pending isolated namespace forwarding/NAT checks |
| 2 | IPv6 | CONFIG_RESOLVED_NOT_BUILT | Pending guest loopback, namespace, RA and external passt checks |
| 3 | User namespaces | CONFIG_RESOLVED_NOT_BUILT | Pending non-root UID-map check |
| 4 | PID namespaces | CONFIG_RESOLVED_NOT_BUILT | Pending nested PID/procfs check |
| 5 | Bridges | CONFIG_RESOLVED_NOT_BUILT | Pending bridged namespace traffic check |
| 6 | VETH | CONFIG_RESOLVED_NOT_BUILT | Pending veth traffic and cleanup check |
| 7 | Cgroup memory limits | CONFIG_RESOLVED_NOT_BUILT | Pending bounded cgroup-v2 enforcement check |
| 8 | Cgroup process-count limits | CONFIG_RESOLVED_NOT_BUILT | Pending bounded `pids.max` enforcement check |
| 9 | Cgroup CPU quotas | CONFIG_RESOLVED_NOT_BUILT | Pending `cpu.max` throttling check |
| 10 | OverlayFS | CONFIG_RESOLVED_NOT_BUILT | Pending copy-up and unmount check |
| 11 | FUSE | CONFIG_RESOLVED_NOT_BUILT | Pending `/dev/fuse` and local mount check |
| 12 | SquashFS | CONFIG_RESOLVED_NOT_BUILT | Pending gzip, xz, lz4 and zstd image checks |
| 13 | ext4 POSIX ACLs | CONFIG_RESOLVED_NOT_BUILT | Pending ACL enforcement and persistence check |
| 14 | ext4 security xattrs | CONFIG_RESOLVED_NOT_BUILT | Pending file-capability xattr check |
| 15 | LSM framework and guest AppArmor | CONFIG_RESOLVED_NOT_BUILT | Pending active LSM/profile-denial check |
| 16 | Kernel auditing | CONFIG_RESOLVED_NOT_BUILT | Pending audit-rule event check |
| 17 | Kernel keyrings | CONFIG_RESOLVED_NOT_BUILT | Pending temporary key add/read/remove check |
| 18 | Kernel crypto API | CONFIG_RESOLVED_NOT_BUILT | Pending AF_ALG/subsystem checks |
| 19 | Device mapper and dm-crypt/LUKS | CONFIG_RESOLVED_NOT_BUILT | Pending disposable encrypted loop-image check |
| 20 | eBPF program loading | CONFIG_RESOLVED_NOT_BUILT | Pending minimal program load/run check |
| 21 | Fanotify | CONFIG_RESOLVED_NOT_BUILT | Pending event and permission-event checks |
| 22 | Traffic scheduling and shaping | CONFIG_RESOLVED_NOT_BUILT | Pending bounded qdisc traffic/counter check |
| 23 | VLAN interfaces | CONFIG_RESOLVED_NOT_BUILT | Pending isolated tagged traffic check |
| 24 | Multiple guest CPUs | CONFIG_RESOLVED_NOT_BUILT | Pending multi-CPU Android-device boot/load check |
| 25 | Loadable modules | CONFIG_RESOLVED_NOT_BUILT | Pending matching module load/unload check |
| 26 | WireGuard | CONFIG_RESOLVED_NOT_BUILT | Pending local peer handshake and traffic check |
| 27 | NFS client | CONFIG_RESOLVED_NOT_BUILT | Pending disposable reachable export check |
| 28 | SMB/CIFS client | CONFIG_RESOLVED_NOT_BUILT | Pending disposable SMB3 share check |
| 29 | Btrfs | CONFIG_RESOLVED_NOT_BUILT | Pending disposable format/write/snapshot check |
| 30 | XFS | CONFIG_RESOLVED_NOT_BUILT | Pending disposable format/read/write check |
| 31 | zram | CONFIG_RESOLVED_NOT_BUILT | Pending bounded I/O and reset check |
| 32 | zswap | CONFIG_RESOLVED_NOT_BUILT | Pending swap-backed compressed-page check |
| 33 | Pressure Stall Information | CONFIG_RESOLVED_NOT_BUILT | Pending PSI interface and pressure check |
| 34 | Task statistics | CONFIG_RESOLVED_NOT_BUILT | Pending generic-netlink accounting check |

Update statuses only after evidence exists. A successful build without device
runtime evidence changes a row to `BUILT_NOT_RUNTIME_TESTED`, not `PASS`.
Use `scripts/guest/test-kernel-capabilities.sh` in a disposable guest for
available non-destructive checks; it reports unconfigured fixtures and
unavailable endpoints as `BLOCKED`.
