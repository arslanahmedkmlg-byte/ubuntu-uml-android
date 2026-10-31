#!/usr/bin/env python3
"""Verify APK's stripped UML kernel preserves the built ELF load segments."""

import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


PT_LOAD = 1
PT_INTERP = 3
APK_KERNEL = "lib/arm64-v8a/libvmlinux.so"
APK_ARTIFACTS = {
    "libvmlinux.so": APK_KERNEL,
    "libuml-stub.so": "lib/arm64-v8a/libuml-stub.so",
    "libuml-passt.so": "lib/arm64-v8a/libuml-passt.so",
}


def parse_elf(data: bytes, label: str):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise ValueError(f"{label} is not a little-endian ELF64 file")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type not in (2, 3) or machine != 183:
        raise ValueError(f"{label} is not an executable AArch64 ELF")

    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phoff + phentsize * phnum > len(data):
        raise ValueError(f"{label} has an invalid program-header table")

    segments = []
    has_interpreter = False
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, p_memsz, p_align = (
            struct.unpack_from("<IIQQQQQQ", data, offset)
        )
        if p_type == PT_INTERP:
            has_interpreter = True
        if p_type == PT_LOAD:
            end = p_offset + p_filesz
            if end > len(data):
                raise ValueError(f"{label} has a load segment outside the file")
            segments.append(
                (
                    p_flags,
                    p_vaddr,
                    p_filesz,
                    p_memsz,
                    p_align,
                    data[p_offset:end],
                )
            )
    if has_interpreter:
        raise ValueError(f"{label} has a dynamic interpreter")
    if not segments:
        raise ValueError(f"{label} has no loadable segments")
    return segments


def load_segments_match(expected, actual):
    if len(expected) != len(actual):
        return False

    for expected_segment, actual_segment in zip(expected, actual):
        if expected_segment[:5] != actual_segment[:5]:
            return False

        expected_data = bytearray(expected_segment[5])
        actual_data = bytearray(actual_segment[5])
        if expected_segment[1] == 0 and expected_data[:4] == b"\x7fELF":
            # APK stripping can relocate the section table in the ELF header.
            for start, end in ((40, 48), (60, 64)):
                expected_data[start:end] = b"\0" * (end - start)
                actual_data[start:end] = b"\0" * (end - start)
        if expected_data != actual_data:
            return False

    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--kernel", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--extract-ikconfig", type=Path, required=True)
    parser.add_argument("--seccomp-checker", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    expected_config_hash = manifest["kernel"]["embedded_config_sha256"]
    built_kernel = args.kernel.read_bytes()
    built_segments = parse_elf(built_kernel, str(args.kernel))

    try:
        with zipfile.ZipFile(args.apk) as apk:
            packaged = {
                name: apk.read(path)
                for name, path in APK_ARTIFACTS.items()
            }
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        print(f"APK kernel verification failed: {error}", file=sys.stderr)
        return 1

    for name, apk_path in APK_ARTIFACTS.items():
        if name == "libvmlinux.so":
            expected_data = built_kernel
            expected_segments = built_segments
        else:
            expected_data = Path(args.kernel).with_name(name).read_bytes()
            expected_segments = parse_elf(expected_data, name)
        actual_segments = parse_elf(packaged[name], f"{args.apk}:{apk_path}")
        if not load_segments_match(expected_segments, actual_segments):
            print(f"APK {name} PT_LOAD segments differ from the build artifact", file=sys.stderr)
            return 1

    with tempfile.TemporaryDirectory(prefix="uml-apk-check-") as directory:
        packaged_path = Path(directory) / "libvmlinux.so"
        kernel_data = packaged["libvmlinux.so"]
        packaged_path.write_bytes(kernel_data)
        subprocess.run(
            [sys.executable, str(args.seccomp_checker), str(packaged_path)],
            check=True,
        )
        extracted = subprocess.run(
            ["bash", str(args.extract_ikconfig), str(packaged_path)],
            check=True,
            stdout=subprocess.PIPE,
        ).stdout

    actual_config_hash = hashlib.sha256(extracted).hexdigest()
    if actual_config_hash != expected_config_hash:
        print(
            "APK kernel embedded config hash mismatch: "
            f"expected {expected_config_hash}, got {actual_config_hash}",
            file=sys.stderr,
        )
        return 1

    print(
        "APK UML kernel verified: AArch64 static ELF, seccomp gate clean, "
        "loadable segments unchanged and embedded config matches manifest."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
