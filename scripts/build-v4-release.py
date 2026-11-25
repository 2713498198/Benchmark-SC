#!/usr/bin/env python3
"""Rebuild the complete public and private CryptoBench-SC V4 release."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run(*args: str) -> None:
    subprocess.run([sys.executable, *args], cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path,
                        help="upstream release root; defaults to CRYPTOBENCH_V4_RELEASE_ROOT")
    args = parser.parse_args()
    source_value = args.source or (
        Path(os.environ["CRYPTOBENCH_V4_RELEASE_ROOT"])
        if os.environ.get("CRYPTOBENCH_V4_RELEASE_ROOT") else None
    )
    if source_value is None:
        raise SystemExit("source is required; pass it or set CRYPTOBENCH_V4_RELEASE_ROOT")
    source = source_value.resolve()
    run("scripts/build_v4_evaluator_catalog.py", str(source))
    run("scripts/import_cryptobench.py", str(source), "--version", "4.1.0", "--execution-ready")
    run("scripts/build-v4-private-manifest.py", str(source))
    run("tests/verify-v4-1-import.py")
    run("tests/verify-v4-evaluator-catalog.py")
    print("V4 public and private release artifacts rebuilt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
