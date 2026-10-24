#!/usr/bin/env bash
# Structural sanity checks for a candidate image; does not prove UML compatibility.
set -Eeuo pipefail
IMAGE="${1:?Usage: check-rootfs-image.sh path/to/ubuntu.ext4}"
test -s "$IMAGE"
file "$IMAGE" | grep -qi 'ext4 filesystem data'
SIZE=$(stat -c %s "$IMAGE")
(( SIZE >= 1073741824 )) || { echo "Image is smaller than 1 GiB" >&2; exit 1; }
if [[ -f "$IMAGE.sha256" ]]; then (cd "$(dirname "$IMAGE")" && sha256sum -c "$(basename "$IMAGE").sha256"); fi
echo "Structural check passed; this is not a boot/network/persistence test."
