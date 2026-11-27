#!/usr/bin/env python3
"""Build a Windows offline evaluation bundle with tasks, Runner, and Docker assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ZIP_ROOT = "CryptoBench-Offline"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_zip(stage: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    files = sorted(path for path in stage.rglob("*") if path.is_file())
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            info = zipfile.ZipInfo(
                f"{ZIP_ROOT}/{path.relative_to(stage).as_posix()}",
                date_time=(2026, 1, 1, 0, 0, 0),
            )
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compresslevel=9)
    temporary.replace(output)


def build_runner_archive(temporary: Path, source_commit: str | None) -> Path:
    output_dir = temporary / "runner-package"
    command = [
        "python", str(ROOT / "scripts" / "build-runner-package.py"),
        "--output-dir", str(output_dir),
    ]
    if source_commit:
        command.extend(["--source-commit", source_commit])
    subprocess.run(command, cwd=ROOT, check=True)
    return next(output_dir.glob("CryptoBench-Runner-windows-x64-*.zip"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tasks",
        type=Path,
        default=ROOT / "src/main/resources/evaluation/cryptobench-sc-v4/4.2.0/tasks.json",
    )
    parser.add_argument(
        "--public-root",
        type=Path,
        default=ROOT / "src/main/resources/static/data/cryptobench-sc-v4/4.2.0",
    )
    parser.add_argument("--suite", type=Path, help="private judge suite copied into the judge image context")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "target" / "offline-package")
    parser.add_argument("--source-commit")
    args = parser.parse_args()

    tasks = args.tasks.resolve()
    public_root = args.public_root.resolve()
    if not tasks.is_file():
        raise SystemExit(f"Tasks file not found: {tasks}")
    if not public_root.is_dir():
        raise SystemExit(f"Public root not found: {public_root}")
    catalog = json.loads(tasks.read_text(encoding="utf-8"))
    version = str(catalog.get("version") or "unknown")
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="cryptobench-offline-package-") as tmp:
        temporary = Path(tmp)
        stage = temporary / "stage"
        stage.mkdir(parents=True)

        runner_archive = build_runner_archive(temporary, args.source_commit)
        with zipfile.ZipFile(runner_archive) as bundle:
            for member in bundle.infolist():
                relative = member.filename.split("/", 1)[-1]
                target = stage / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.read(member))

        shutil.copy2(tasks, stage / "tasks.json")
        shutil.copytree(public_root, stage / "public", dirs_exist_ok=True)
        (stage / "agent.json").write_text(
            (ROOT / "runner/examples/offline-agent.json").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        shutil.copy2(ROOT / "runner/examples/offline-agents.json", stage / "offline-agents.json")
        shutil.copy2(ROOT / "runner/examples/offline-codex.Dockerfile", stage / "offline-codex.Dockerfile")
        shutil.copy2(ROOT / "docker/offline-agent-base.Dockerfile", stage / "offline-agent-base.Dockerfile")
        shutil.copy2(ROOT / "docs/offline-docker-evaluation.md", stage / "README.md")
        judge_dir = stage / "offline-judge"
        judge_dir.mkdir(parents=True)
        shutil.copy2(ROOT / "docker/offline-judge.Dockerfile", judge_dir / "Dockerfile")
        if args.suite:
            suite = args.suite.resolve()
            if not suite.is_dir():
                raise SystemExit(f"Judge suite not found: {suite}")
            shutil.copytree(suite, judge_dir / "suite", dirs_exist_ok=True)
        else:
            (judge_dir / "README.txt").write_text(
                "Private judge suite was not included. Add suite/ before building the judge image.\n",
                encoding="utf-8",
            )

        metadata = {
            "schema": "cryptobench-offline-package-v1",
            "datasetId": catalog.get("datasetId"),
            "version": version,
            "evaluatorVersion": catalog.get("evaluatorVersion"),
            "judgeSuiteIncluded": bool(args.suite),
            "networkDefault": "none",
        }
        (stage / "offline-package.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        output_zip = output_dir / f"CryptoBench-Offline-windows-x64-{version}.zip"
        write_zip(stage, output_zip)

    manifest = {
        "filename": output_zip.name,
        "bytes": output_zip.stat().st_size,
        "sha256": sha256(output_zip),
        "datasetId": catalog.get("datasetId"),
        "version": version,
        "judgeSuiteIncluded": bool(args.suite),
    }
    manifest_path = output_dir / "offline-package-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archive": str(output_zip), "manifest": str(manifest_path), **manifest}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
