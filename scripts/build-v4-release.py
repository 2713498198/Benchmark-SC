#!/usr/bin/env python3
"""Rebuild the complete public and private CryptoBench-SC V4.2 release."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run(*args: str) -> None:
    subprocess.run([sys.executable, *args], cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-zip", required=True, type=Path)
    parser.add_argument("--judge-zip", required=True, type=Path)
    parser.add_argument("--version", default="4.2.0")
    args = parser.parse_args()
    run("scripts/import-v4-release.py",
        "--public-zip", str(args.public_zip),
        "--judge-zip", str(args.judge_zip),
        "--version", args.version)
    run("tests/verify-v4-2-import.py")
    print("V4 public and private release artifacts rebuilt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
