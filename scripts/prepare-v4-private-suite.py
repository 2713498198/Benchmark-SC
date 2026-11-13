"""Stage the server-private part of a CryptoBench-SC V4 release for scoring.

Copies only what the V4ValidatorRunner needs onto the server, and never the
public question/attachment files. The staged directory is the value of
CRYPTOBENCH_V4_SUITE_ROOT.

Usage:
    python scripts/prepare-v4-private-suite.py <release-root> <target-dir>
"""

import argparse
import shutil
from pathlib import Path


PRIVATE_UNDER_TASKS = ("hidden", "oracle")
PRIVATE_SCRIPTS = ("run_task_validator.py", "validators")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="release root with manifest.json and tasks/")
    parser.add_argument("target", type=Path, help="server-private suite directory")
    args = parser.parse_args()
    source = args.source.resolve()
    target = args.target.resolve()
    if target == source or target.is_relative_to(source):
        raise SystemExit("target must be outside the source release")

    tasks_root = source / "tasks"
    scripts_root = source / "scripts"
    for task_dir in sorted(tasks_root.iterdir()):
        if not task_dir.is_dir():
            continue
        for sub in PRIVATE_UNDER_TASKS:
            src = task_dir / sub
            if src.is_dir():
                shutil.copytree(src, target / "tasks" / task_dir.name / sub,
                                dirs_exist_ok=True)
    for name in PRIVATE_SCRIPTS:
        src = scripts_root / name
        if src.exists():
            dst = target / "scripts" / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, dst, dirs_exist_ok=True)
            else:
                shutil.copy2(src, dst)
    print(f"Staged private V4 suite into {target}")


if __name__ == "__main__":
    main()
