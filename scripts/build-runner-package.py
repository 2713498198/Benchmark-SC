#!/usr/bin/env python3
"""Build the portable Windows x64 CryptoBench Runner archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "runner"
NODE_VERSION = "24.22.0"
NODE_ARCHIVE = f"node-v{NODE_VERSION}-win-x64.zip"
NODE_URL = f"https://nodejs.org/dist/v{NODE_VERSION}/{NODE_ARCHIVE}"
NODE_SHA256 = "f9683a19877e12a9b167305c8db71040d812876359de21f0d2e97c3f321f80c3"
ZIP_ROOT = "CryptoBench-Runner"
MANIFEST_SCHEMA = "cryptobench-runner-package-v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def runner_version() -> str:
    value = json.loads((RUNNER / "package.json").read_text(encoding="utf-8"))
    version = value.get("version")
    if not isinstance(version, str) or not version.strip():
        raise SystemExit("runner/package.json does not contain a version")
    return version.strip()


def source_commit(explicit: str | None) -> str:
    if explicit:
        return explicit.strip()
    configured = os.environ.get("CRYPTOBENCH_SOURCE_COMMIT", "").strip()
    if configured:
        return configured
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def download_node(destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    request = urllib.request.Request(
        NODE_URL,
        headers={"User-Agent": "CryptoBench-Runner-Builder/1.0"},
    )
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
            temporary.replace(destination)
            return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            temporary.unlink(missing_ok=True)
            if attempt < 4:
                time.sleep(attempt * 2)
    raise SystemExit(f"Unable to download {NODE_URL}: {last_error}")


def node_from_archive(archive: Path, stage: Path) -> None:
    if sha256(archive).lower() != NODE_SHA256:
        raise SystemExit(f"Node archive checksum mismatch: {archive}")
    prefix = f"node-v{NODE_VERSION}-win-x64/"
    with zipfile.ZipFile(archive) as bundle:
        names = set(bundle.namelist())
        executable = prefix + "node.exe"
        license_file = prefix + "LICENSE"
        if executable not in names or license_file not in names:
            raise SystemExit("Node archive is missing node.exe or LICENSE")
        (stage / "node.exe").write_bytes(bundle.read(executable))
        (stage / "NODE-LICENSE.txt").write_bytes(bundle.read(license_file))


def node_from_local(executable: Path, license_file: Path, stage: Path) -> None:
    if not executable.is_file():
        raise SystemExit(f"Node executable not found: {executable}")
    if not license_file.is_file():
        raise SystemExit(f"Node license not found: {license_file}")
    shutil.copy2(executable, stage / "node.exe")
    shutil.copy2(license_file, stage / "NODE-LICENSE.txt")


def copy_runner_files(stage: Path) -> None:
    for name in ("src", "examples"):
        shutil.copytree(
            RUNNER / name,
            stage / name,
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".DS_Store"),
        )
    for name in ("package.json", "cryptobench-runner.cmd"):
        shutil.copy2(RUNNER / name, stage / name)
    shutil.copy2(RUNNER / "PORTABLE-README.md", stage / "README.md")


def write_zip(stage: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    files = sorted(
        path for path in stage.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"
    )
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            relative = path.relative_to(stage).as_posix()
            info = zipfile.ZipInfo(f"{ZIP_ROOT}/{relative}", date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compresslevel=9)
    temporary.replace(output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "target" / "runner-package")
    parser.add_argument("--node-archive", type=Path,
                        help="cached official Windows Node archive; downloaded when omitted")
    parser.add_argument("--node-exe", type=Path,
                        help="test-only override for the packaged node executable")
    parser.add_argument("--node-license", type=Path,
                        help="license file paired with --node-exe")
    parser.add_argument("--source-commit", help="source revision recorded in package metadata")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    version = runner_version()
    filename = f"CryptoBench-Runner-windows-x64-{version}.zip"
    output_zip = output_dir / filename
    metadata = {
        "schema": MANIFEST_SCHEMA,
        "runnerVersion": version,
        "platform": "windows-x64",
        "nodeVersion": NODE_VERSION,
        "sourceCommit": source_commit(args.source_commit),
    }

    with tempfile.TemporaryDirectory(prefix="cryptobench-runner-package-") as temporary:
        staging_root = Path(temporary) / ZIP_ROOT
        staging_root.mkdir(parents=True)
        copy_runner_files(staging_root)
        if args.node_exe:
            if not args.node_license:
                raise SystemExit("--node-license is required with --node-exe")
            node_from_local(args.node_exe, args.node_license, staging_root)
        else:
            archive = (
                args.node_archive.resolve()
                if args.node_archive
                else ROOT / "target" / "runner-cache" / NODE_ARCHIVE
            )
            if not archive.is_file():
                download_node(archive)
            node_from_archive(archive, staging_root)
        (staging_root / "runner-package.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        write_zip(staging_root, output_zip)

    manifest = {
        **metadata,
        "filename": filename,
        "bytes": output_zip.stat().st_size,
        "sha256": sha256(output_zip),
        "manifestPath": "/api/runners/package",
        "downloadPath": "/api/runners/download",
    }
    manifest_path = output_dir / "runner-package-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "archive": str(output_zip),
        "manifest": str(manifest_path),
        "bytes": manifest["bytes"],
        "sha256": manifest["sha256"],
        "runnerVersion": version,
        "nodeVersion": NODE_VERSION,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
