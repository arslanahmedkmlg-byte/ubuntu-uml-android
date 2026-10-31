#!/usr/bin/env python3
"""Check resolved UML Kconfig values against the 34 capability requirements."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "kernel/config/capabilities.json"
FRAGMENT = ROOT / "kernel/config/ubuntu-uml-capabilities.config"


def read_config(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        match = re.fullmatch(r"CONFIG_([A-Z0-9_]+)=(.*)", line)
        if match:
            values[match.group(1)] = match.group(2).strip('"')
            continue
        match = re.fullmatch(r"# CONFIG_([A-Z0-9_]+) is not set", line)
        if match:
            values[match.group(1)] = "n"
    return values


def read_fragment(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        match = re.fullmatch(r"CONFIG_([A-Z0-9_]+)=(.*)", line)
        if match:
            values[match.group(1)] = match.group(2).strip('"')
            continue
        match = re.fullmatch(r"# CONFIG_([A-Z0-9_]+) is not set", line)
        if match:
            values[match.group(1)] = "n"
    return values


def matches(actual: str, expected: str) -> bool:
    if expected.startswith("contains:"):
        return expected.removeprefix("contains:") in actual
    if expected.startswith("min:"):
        try:
            return int(actual) >= int(expected.removeprefix("min:"))
        except ValueError:
            return False
    return actual == expected


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--fragment", type=Path, default=FRAGMENT)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--built", action="store_true")
    args = parser.parse_args()

    config = args.config.resolve()
    manifest = json.loads(args.manifest.read_text())
    capabilities = manifest["capabilities"]
    expected_ids = {str(number) for number in range(1, 35)}
    if set(capabilities) != expected_ids:
        print("requirements manifest must contain exactly capability IDs 1-34", file=sys.stderr)
        return 2
    if not config.is_file():
        print(f"resolved config does not exist: {config}", file=sys.stderr)
        return 2

    values = read_config(config)
    fragment = read_fragment(args.fragment)
    report = {
        "config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
        "kernel_source_commit": manifest["kernel_source_commit"],
        "capabilities": {},
    }
    failures = 0

    for symbol, expected in fragment.items():
        actual = values.get(symbol, "n")
        if not matches(actual, expected):
            failures += 1
            print(
                f"FAIL [fragment] CONFIG_{symbol}: expected {expected}, got {actual}"
            )

    modules = sorted(symbol for symbol, value in values.items() if value == "m")
    if modules:
        failures += len(modules)
        print("FAIL: modules are configured but no matching module deployment is implemented:")
        for symbol in modules:
            print(f"  CONFIG_{symbol}=m")

    for capability_id in sorted(capabilities, key=int):
        requirement = capabilities[capability_id]
        missing = []
        resolved = {}
        for symbol, expected in requirement["symbols"].items():
            actual = values.get(symbol, "n")
            resolved[symbol] = actual
            if not matches(actual, expected):
                missing.append(f"CONFIG_{symbol}: expected {expected}, got {actual}")
        status = "BUILT_NOT_RUNTIME_TESTED" if args.built and not missing else (
            "CONFIG_RESOLVED_NOT_BUILT" if not missing else "BLOCKED"
        )
        report["capabilities"][capability_id] = {
            "name": requirement["name"],
            "configuration_status": status,
            "runtime_status": "NOT_RUN",
            "resolved_symbols": resolved,
            "unconditional": requirement.get("unconditional", []),
            "runtime_test": requirement["runtime_test"],
            "failures": missing,
        }
        if missing:
            failures += 1
            print(f"FAIL [{capability_id}] {requirement['name']}")
            for detail in missing:
                print(f"  {detail}")
        else:
            print(f"PASS [{capability_id}] {requirement['name']}: {status}")

    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Config requirements: {34 - failures}/34 resolved; runtime tests are not implied.")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
