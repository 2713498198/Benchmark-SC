"""Validate the complete CryptoBench-SC V4 release contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "src/main/resources/static/data/cryptobench-sc-v4/4.1.0"
PRIVATE = ROOT / "src/main/resources/evaluation/cryptobench-sc-v4/4.1.0"


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fail(message: str) -> None:
    raise SystemExit("V4 release gate failed: " + message)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path,
                        help="upstream release directory; defaults to CRYPTOBENCH_V4_RELEASE_ROOT")
    parser.add_argument("--public-dir", type=Path, default=PUBLIC)
    parser.add_argument("--private-dir", type=Path, default=PRIVATE)
    parser.add_argument("--validator-report", type=Path,
                        help="output from scripts/validate-v4-validators.py")
    parser.add_argument("--require-validators", action="store_true",
                        help="require a passing 65/65 validator contract report")
    args = parser.parse_args()
    source_value = args.source or (
        Path(os.environ["CRYPTOBENCH_V4_RELEASE_ROOT"])
        if os.environ.get("CRYPTOBENCH_V4_RELEASE_ROOT") else None
    )
    if source_value is None:
        fail("source is required; pass it or set CRYPTOBENCH_V4_RELEASE_ROOT")
    source = source_value.resolve()
    public = args.public_dir.resolve()
    private = args.private_dir.resolve()
    catalog_path = public / "catalog.json"
    release_path = public / "release.json"
    zip_path = public / "public-tasks.zip"
    private_catalog_path = private / "tasks.json"
    private_manifest_path = private / "release-manifest.json"
    catalog = read_json(catalog_path)
    public_release = read_json(release_path)
    private_catalog = read_json(private_catalog_path)
    private_manifest = read_json(private_manifest_path)
    task_count = len(catalog["tasks"])
    if task_count != 170:
        fail(f"expected 170 public tasks, found {task_count}")
    public_ids = {task["task_id"] for task in catalog["tasks"]}
    private_tasks = private_catalog["tasks"]
    private_ids = {task["id"] for task in private_tasks}
    if public_ids != private_ids or len(public_ids) != 170:
        fail("public and private task coverage differs")
    if sorted(task["question_number"] for task in catalog["tasks"]) != list(range(1, 171)):
        fail("question numbers are not contiguous")
    if len({task["question"] for task in catalog["tasks"]}) != 170:
        fail("duplicate public questions")
    if any(not task["question"].strip() for task in catalog["tasks"]):
        fail("blank public question")
    attachment_count = 0
    workspace_root = ROOT / "src/main/resources/static"
    for task in catalog["tasks"]:
        for file in task["files"]:
            path = workspace_root / file["url"]
            if not path.is_file():
                fail(f"missing public file: {file['url']}")
            content = path.read_bytes()
            if len(content) != file["bytes"] or sha256(path) != file["sha256"]:
                fail(f"public file hash mismatch: {file['url']}")
            if "/attachments/" in file["url"].replace("\\", "/"):
                attachment_count += 1
                if not content:
                    fail(f"empty public attachment: {file['url']}")
    if attachment_count != 75:
        fail(f"expected 75 attachments, found {attachment_count}")
    if sha256(catalog_path) != public_release["publicCatalogSha256"]:
        fail("public release catalog hash mismatch")
    for key, path in (
        ("publicReleaseSha256", release_path),
        ("publicCatalogSha256", catalog_path),
        ("publicZipSha256", zip_path),
        ("privateCatalogSha256", private_catalog_path),
    ):
        if private_manifest[key] != sha256(path):
            fail(f"private manifest {key} mismatch")
    if private_manifest["taskCount"] != 170 or private_manifest["validatorCount"] != 65:
        fail("private manifest counts are incomplete")
    if private_catalog.get("schema") != "cryptobench-v4-evaluator-catalog-v2":
        fail("private evaluator catalog schema is not v2")
    if private_catalog.get("evaluatorVersion") != public_release["evaluatorVersion"]:
        fail("public/private evaluator versions differ")
    if catalog["dataset"].get("sourceManifestSha256") != sha256(source / "manifest.json"):
        fail("source manifest hash differs from imported catalog")
    rules = Counter(task["verification"]["type"] for task in private_tasks)
    expected_rules = {
        "numeric": 33, "string": 31, "boolean": 1, "fields": 39,
        "piecewise": 1, "workspace-validator": 65,
    }
    if dict(rules) != expected_rules:
        fail(f"unexpected rule distribution: {dict(rules)}")
    deterministic = [task for task in private_tasks
                     if task["verification"]["type"] != "workspace-validator"]
    if len(deterministic) != 105 or any(not task["expectedAnswer"] for task in deterministic):
        fail("deterministic task answer coverage is incomplete")
    workspace = [task for task in private_tasks
                 if task["verification"]["type"] == "workspace-validator"]
    if any(not task.get("validator", {}).get("ref") for task in workspace):
        fail("workspace validator reference is missing")
    for task in workspace:
        validator = source / task["validator"]["ref"]
        if not validator.is_file() or sha256(validator) != task["validator"]["sha256"]:
            fail(f"validator hash mismatch: {task['id']}")
    if any(task["submissionMode"] != "workspace" for task in workspace):
        fail("workspace task submission mode mismatch")
    if any(task["submissionMode"] != "final_answer" for task in deterministic):
        fail("deterministic task submission mode mismatch")
    validator_contracts = None
    if args.validator_report:
        validator_contracts = read_json(args.validator_report.resolve())
        if validator_contracts.get("status") != "passed":
            fail("validator contract report is not passing")
        if validator_contracts.get("validatorCount") != 65 or validator_contracts.get("passedCount") != 65:
            fail("validator contract coverage is not 65/65")
    if args.require_validators and validator_contracts is None:
        fail("--require-validators needs --validator-report")
    result = {
        "schema": "cryptobench-v4-release-validation-v1",
        "suiteId": catalog["dataset"]["suiteId"],
        "datasetId": catalog["dataset"]["id"],
        "version": catalog["dataset"]["version"],
        "releaseRevision": private_catalog["releaseRevision"],
        "evaluatorVersion": private_catalog["evaluatorVersion"],
        "taskCount": task_count,
        "attachmentCount": attachment_count,
        "publicFileCount": public_release["publicFileCount"],
        "ruleCounts": dict(sorted(rules.items())),
        "deterministicCount": len(deterministic),
        "workspaceValidatorCount": len(workspace),
        "validatorContracts": (
            {"required": args.require_validators, "status": validator_contracts["status"],
             "passedCount": validator_contracts["passedCount"]}
            if validator_contracts else None
        ),
        "technicalReleaseReady": bool(
            not args.require_validators or (
                validator_contracts
                and validator_contracts.get("status") == "passed"
                and validator_contracts.get("passedCount") == 65
            )
        ),
        "rightsReview": public_release["rightsReview"],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
