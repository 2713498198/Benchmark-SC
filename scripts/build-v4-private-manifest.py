#!/usr/bin/env python3
"""Build the public-safe private release manifest for CryptoBench-SC V4."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PUBLIC = ROOT / "src/main/resources/static/data/cryptobench-sc-v4/4.1.0"
DEFAULT_PRIVATE = ROOT / "src/main/resources/evaluation/cryptobench-sc-v4/4.1.0"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="upstream V4 release root")
    parser.add_argument("--public-dir", type=Path, default=DEFAULT_PUBLIC)
    parser.add_argument("--private-dir", type=Path, default=DEFAULT_PRIVATE)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    public = args.public_dir.resolve()
    private = args.private_dir.resolve()
    public_catalog_path = public / "catalog.json"
    public_release_path = public / "release.json"
    public_zip_path = public / "public-tasks.zip"
    private_catalog_path = private / "tasks.json"
    catalog = read_json(public_catalog_path)
    public_release = read_json(public_release_path)
    private_catalog = read_json(private_catalog_path)
    catalog_paths = {
        path: sha256(path)
        for path in (public_catalog_path, public_release_path, public_zip_path, private_catalog_path)
    }
    if public_release["publicCatalogSha256"] != catalog_paths[public_catalog_path]:
        raise SystemExit("Public release catalog hash does not match catalog.json")
    public_ids = {task["task_id"] for task in catalog["tasks"]}
    private_ids = {task["id"] for task in private_catalog["tasks"]}
    if public_ids != private_ids or len(public_ids) != 170:
        raise SystemExit("Public and private V4 task coverage differ")
    rule_counts = Counter(task["verification"]["type"] for task in private_catalog["tasks"])
    tasks = []
    for task in private_catalog["tasks"]:
        validator = task.get("validator")
        tasks.append({
            "id": task["id"],
            "questionNumber": task["questionNumber"],
            "sourceTaskId": task["sourceTaskId"],
            "submissionMode": task["submissionMode"],
            "ruleType": task["verification"]["type"],
            "oracleSha256": task["provenance"]["oracleSha256"],
            "expectedAnswerSha256": hashlib.sha256(task["expectedAnswer"].encode("utf-8")).hexdigest(),
            **({
                "validator": {
                    "ref": validator["ref"],
                    "sha256": validator["sha256"],
                    "tests": validator["tests"],
                },
            } if validator else {}),
        })
    manifest = {
        "schema": "cryptobench-v4-private-release-v1",
        "datasetId": catalog["dataset"]["id"],
        "version": catalog["dataset"]["version"],
        "releaseRevision": private_catalog["releaseRevision"],
        "evaluatorVersion": private_catalog["evaluatorVersion"],
        "publicationStatus": catalog["dataset"]["publicationStatus"],
        "sourceManifestSha256": sha256(source / "manifest.json"),
        "publicCatalogSha256": catalog_paths[public_catalog_path],
        "publicReleaseSha256": catalog_paths[public_release_path],
        "publicZipSha256": catalog_paths[public_zip_path],
        "privateCatalogSha256": catalog_paths[private_catalog_path],
        "taskCount": len(tasks),
        "ruleCounts": dict(sorted(rule_counts.items())),
        "validatorCount": sum(1 for task in tasks if "validator" in task),
        "attachmentCount": public_release["attachmentCount"],
        "publicFileCount": public_release["publicFileCount"],
        "rightsReview": public_release["rightsReview"],
        "tasks": tasks,
    }
    output = (args.out or (private / "release-manifest.json")).resolve()
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "taskCount": manifest["taskCount"],
        "validatorCount": manifest["validatorCount"],
        "ruleCounts": manifest["ruleCounts"],
        "publicCatalogSha256": manifest["publicCatalogSha256"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
