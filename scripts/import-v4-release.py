#!/usr/bin/env python3
"""Import the 20261125-auto1 public and judge bundles as a new V4 release."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "src" / "main" / "resources" / "static"
EVALUATION = ROOT / "src" / "main" / "resources" / "evaluation"
DATASET_ID = "cryptobench-sc-v4"
DEFAULT_BASE = "4.1.0"
DEFAULT_VERSION = "4.2.0"
DEFAULT_REVISION = "2026-11-25"
UPSTREAM_RELEASE = "20261125-auto1"
EVALUATOR_VERSION = "cryptobench-sc-v4-evaluator-1.1.0"
PUBLIC_CATALOG_SCHEMA = "cryptobench-public-catalog-v2"
PRIVATE_CATALOG_SCHEMA = "cryptobench-v4-evaluator-catalog-v3"
PRIVATE_MANIFEST_SCHEMA = "cryptobench-v4-private-release-v2"
AUTOMATIC_TASKS = (
    "023_CBSC-V2-NL-D11",
    "057_CBSC-V2-PS-2408-DL-TRANSFORM-022",
    "110_CBSC-V4-PD-AES128-KEYPROJ2-010",
    "121_CBSC-V4-PD-WARP-MP20-003",
    "129_CBSC-V4-PD-BAKSHEESH-OPT12-025",
    "130_CBSC-V4-PL-BAKSHEESH-KR20-026",
    "149_CBSC-V4-PB-LEA128-RKID4-045",
    "170_CBSC-V4-PD-SCREAM-AFFINE-WEAKKEY-066",
)
ANSWER_ONLY_TASKS = (
    "121_CBSC-V4-PD-WARP-MP20-003",
    "129_CBSC-V4-PD-BAKSHEESH-OPT12-025",
    "130_CBSC-V4-PL-BAKSHEESH-KR20-026",
    "149_CBSC-V4-PB-LEA128-RKID4-045",
)
ALLOWED_PUBLIC_SUFFIXES = {".md", ".json", ".txt", ".pdf", ".csv"}
ATTACHMENT_REFERENCE = re.compile(r"attachments/([A-Za-z0-9_.-]+)")


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def file_digest(path: Path) -> str:
    return digest(path.read_bytes())


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value: object) -> bytes:
    payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return payload


def safe_relative(name: str) -> str:
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or path.as_posix() != name
            or "\\" in name or ":" in name or ".." in path.parts):
        raise ValueError(f"unsafe archive path: {name!r}")
    return name


def zip_files(path: Path) -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    with ZipFile(path) as archive:
        broken = archive.testzip()
        if broken:
            raise ValueError(f"corrupt archive member in {path}: {broken}")
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = safe_relative(info.filename)
            if name in result:
                raise ValueError(f"duplicate archive path: {name}")
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                raise ValueError(f"archive contains a symbolic link: {name}")
            result[name] = archive.read(info)
    return result


def validate_public_catalog(catalog: dict, files: dict[str, bytes]) -> dict[str, dict]:
    if catalog.get("release") != UPSTREAM_RELEASE:
        raise ValueError(f"unexpected public release: {catalog.get('release')!r}")
    if catalog.get("audience") != "participant" or catalog.get("task_count") != 170:
        raise ValueError("public catalog is not a complete participant release")
    tasks = catalog.get("tasks")
    if not isinstance(tasks, list) or len(tasks) != 170:
        raise ValueError("public catalog must contain 170 tasks")
    by_id: dict[str, dict] = {}
    numbers = set()
    for task in tasks:
        task_id = str(task.get("task_id", ""))
        number = task.get("number")
        if not re.fullmatch(r"\d{3}_[A-Za-z0-9_-]+", task_id) or task_id in by_id:
            raise ValueError(f"invalid or duplicate task ID: {task_id!r}")
        if not isinstance(number, int) or number in numbers:
            raise ValueError(f"invalid question number for {task_id}")
        numbers.add(number)
        question_path = str(task.get("question", ""))
        if question_path != f"tasks/{task_id}/public/question.md" or question_path not in files:
            raise ValueError(f"missing question for {task_id}")
        if digest(files[question_path]) != task.get("question_sha256"):
            raise ValueError(f"question hash mismatch for {task_id}")
        attachment_names = task.get("attachments", [])
        if not isinstance(attachment_names, list) or len(set(attachment_names)) != len(attachment_names):
            raise ValueError(f"invalid attachment list for {task_id}")
        for name in attachment_names:
            path = f"tasks/{task_id}/public/attachments/{name}"
            if path not in files or PurePosixPath(name).suffix.lower() not in ALLOWED_PUBLIC_SUFFIXES:
                raise ValueError(f"missing or unsupported attachment: {path}")
        question = files[question_path].decode("utf-8-sig")
        references = set(ATTACHMENT_REFERENCE.findall(question))
        if not references.issubset(set(attachment_names)):
            raise ValueError(f"unresolved attachment reference in {task_id}: {sorted(references - set(attachment_names))}")
        by_id[task_id] = task
    if numbers != set(range(1, 171)):
        raise ValueError("public question numbers must be exactly 1 through 170")
    return by_id


def validate_judge(public_zip: Path, judge_files: dict[str, bytes]) -> tuple[dict, dict]:
    manifest = json.loads(judge_files["manifest.json"].decode("utf-8-sig"))
    routes = json.loads(judge_files["metadata/validation_routes.json"].decode("utf-8-sig"))
    if manifest.get("release") != UPSTREAM_RELEASE or manifest.get("audience") != "maintainer":
        raise ValueError("judge manifest release or audience is unexpected")
    if manifest.get("task_count") != 170 or len(manifest.get("tasks", [])) != 170:
        raise ValueError("judge manifest must contain 170 tasks")
    if manifest.get("public_zip_sha256") != file_digest(public_zip):
        raise ValueError("judge manifest does not identify the supplied public ZIP")
    if len(routes) != 170:
        raise ValueError("judge validation routes must cover 170 tasks")
    modes = Counter(route.get("mode") for route in routes.values())
    if modes != {"generic": 103, "task_specific": 67}:
        raise ValueError(f"unexpected judge route distribution: {dict(modes)}")
    for task in manifest["tasks"]:
        task_id = task["task_id"]
        route = routes.get(task_id)
        if route is None:
            raise ValueError(f"missing validation route for {task_id}")
        oracle_path = f"tasks/{task_id}/hidden/oracle.json"
        question_path = f"tasks/{task_id}/public/question.md"
        if oracle_path not in judge_files or question_path not in judge_files:
            raise ValueError(f"judge package is missing oracle or question for {task_id}")
        oracle = json.loads(judge_files[oracle_path].decode("utf-8-sig"))
        if oracle.get("task_id") != task_id:
            raise ValueError(f"oracle task ID mismatch for {task_id}")
        if digest(judge_files[question_path]) != task.get("question_sha256"):
            raise ValueError(f"judge question hash mismatch for {task_id}")
        implementation = route.get("implementation")
        if implementation not in judge_files:
            raise ValueError(f"missing route implementation for {task_id}: {implementation}")
        for required in route.get("required_files", []):
            if required not in judge_files:
                raise ValueError(f"missing route dependency for {task_id}: {required}")
        wrapper = f"tasks/{task_id}/oracle/validator.py"
        if (route.get("mode") == "task_specific") != (wrapper in judge_files):
            raise ValueError(f"task-specific validator mismatch for {task_id}")
    policy = json.loads(judge_files["metadata/release_scoring_policy.json"].decode("utf-8-sig"))
    if tuple(policy.get("automatic_completion_tasks", [])) != AUTOMATIC_TASKS:
        raise ValueError("judge automatic task policy differs from the supported release")
    if tuple(policy.get("answer_only_for_original_proof", [])) != ANSWER_ONLY_TASKS:
        raise ValueError("judge answer-only policy differs from the supported release")
    return manifest, routes


def stage_judge(judge_files: dict[str, bytes], target: Path) -> None:
    target = target.resolve()
    if target.exists() and not target.is_dir():
        raise SystemExit(f"staging target is not a directory: {target}")
    for name, payload in judge_files.items():
        path = target / PurePosixPath(safe_relative(name))
        if path.exists() and path.read_bytes() != payload:
            raise SystemExit(f"refusing to overwrite a different staged file: {path}")
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_bytes(payload)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-zip", required=True, type=Path)
    parser.add_argument("--judge-zip", required=True, type=Path)
    parser.add_argument("--base-version", default=DEFAULT_BASE)
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--release-revision", default=DEFAULT_REVISION)
    parser.add_argument("--stage-dir", type=Path, default=ROOT / "private" / "v4-suite")
    args = parser.parse_args()

    public_zip = args.public_zip.resolve()
    judge_zip = args.judge_zip.resolve()
    if not public_zip.is_file() or not judge_zip.is_file():
        raise SystemExit("both public and judge ZIP files are required")
    version = args.version.strip()
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise SystemExit("version must use semantic versioning")

    base_public = STATIC / "data" / DATASET_ID / args.base_version
    base_private = EVALUATION / DATASET_ID / args.base_version
    base_catalog = read_json(base_public / "catalog.json")
    base_tasks = {task["task_id"]: task for task in base_catalog["tasks"]}
    base_private_catalog = read_json(base_private / "tasks.json")
    base_private_tasks = {task["id"]: task for task in base_private_catalog["tasks"]}
    base_attribution = read_json(base_public / "attribution.json")

    public_files = zip_files(public_zip)
    judge_files = zip_files(judge_zip)
    source_public = json.loads(public_files["catalog.json"].decode("utf-8-sig"))
    source_tasks = validate_public_catalog(source_public, public_files)
    judge_manifest, routes = validate_judge(public_zip, judge_files)
    if set(source_tasks) != set(base_tasks) or set(source_tasks) != set(base_private_tasks):
        raise ValueError("bundle and base release task coverage differs")

    public_dir = STATIC / "data" / DATASET_ID / version
    public_prefix = f"data/{DATASET_ID}/{version}"
    entries = []
    public_output: dict[str, bytes] = {}
    attachment_count = 0
    changed_questions = []
    preserved_attachments = []

    for number in range(1, 171):
        source_task = next(task for task in source_tasks.values() if task["number"] == number)
        task_id = source_task["task_id"]
        base_task = base_tasks[task_id]
        question_path = f"tasks/{task_id}/public/question.md"
        if question_path not in public_files:
            raise ValueError(f"missing public question: {task_id}")
        question = public_files[question_path].decode("utf-8-sig")
        if question != base_task["question"]:
            changed_questions.append(task_id)

        task_files: list[tuple[str, bytes]] = [(question_path, public_files[question_path])]
        for name in source_task.get("attachments", []):
            path = f"tasks/{task_id}/public/attachments/{name}"
            task_files.append((path, public_files[path]))
            attachment_count += 1

        # Preserve reviewed background-paper attachments retained by the site
        # even though the upstream participant bundle now links them externally.
        for file in base_task.get("files", []):
            relative = f"tasks/{task_id}/public/{file['name']}"
            if relative in public_files or relative == question_path:
                continue
            source = base_public / relative
            if not source.is_file() or "/attachments/" not in f"/{relative}":
                continue
            task_files.append((relative, source.read_bytes()))
            preserved_attachments.append(relative)
            attachment_count += 1

        file_records = []
        for relative, payload in sorted(task_files, key=lambda item: ("question.md" not in item[0], item[0])):
            name = relative.split(f"tasks/{task_id}/public/", 1)[1]
            file_records.append({
                "name": name,
                "url": f"{public_prefix}/{relative}",
                "bytes": len(payload),
                "sha256": digest(payload),
            })
            public_output[relative] = payload

        entry = dict(base_task)
        entry.update({
            "task_id": task_id,
            "title": source_task.get("title") or base_task["title"],
            "cipher": source_task.get("cipher") or base_task["cipher"],
            "question": question,
            "files": file_records,
        })
        entries.append(entry)

    dataset = dict(base_catalog["dataset"])
    dataset.update({
        "version": version,
        "releaseDate": args.release_revision,
        "releaseRevision": args.release_revision,
        "upstreamRelease": UPSTREAM_RELEASE,
        "evaluatorVersion": EVALUATOR_VERSION,
        "publicSourceZipSha256": file_digest(public_zip),
        "judgeSourceZipSha256": file_digest(judge_zip),
        "executionReady": True,
        "publicationStatus": "technical_ready",
        "evaluationCoverage": {"judgeGeneric": 103, "judgeTaskSpecific": 67},
        "scoring": {
            "taskResult": "release_check",
            "overallMetric": "passed_tasks/total_tasks",
            "partialScoresRetainedAsEvidence": True,
            "completionMetricEligibleTasks": 166,
            "answerOnlyTasks": list(ANSWER_ONLY_TASKS),
            "automaticTasks": list(AUTOMATIC_TASKS),
        },
        "catalogUrl": f"{public_prefix}/catalog.json",
        "downloadUrl": f"{public_prefix}/public-tasks.zip",
        "releaseManifestUrl": f"{public_prefix}/release.json",
        "attributionUrl": f"{public_prefix}/attribution.json",
    })
    dataset["rightsReview"] = dict(dataset["rightsReview"])
    dataset["rightsReview"]["manifest"] = f"{public_prefix}/attribution.json"
    dataset.pop("sourceCommit", None)
    catalog = {"schema": PUBLIC_CATALOG_SCHEMA, "dataset": dataset, "tasks": entries}
    catalog_payload = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    public_release = {
        "schema": "cryptobench-v4-public-release-v2",
        "datasetId": DATASET_ID,
        "version": version,
        "releaseRevision": args.release_revision,
        "upstreamRelease": UPSTREAM_RELEASE,
        "publicationStatus": "technical_ready",
        "suiteId": dataset["suiteId"],
        "sourceReleaseDate": args.release_revision,
        "publicSourceZipSha256": file_digest(public_zip),
        "judgeSourceZipSha256": file_digest(judge_zip),
        "evaluatorVersion": EVALUATOR_VERSION,
        "taskCount": len(entries),
        "publicFileCount": len(public_output),
        "attachmentCount": attachment_count,
        "changedTaskCount": len(changed_questions),
        "changedTasks": changed_questions,
        "publicCatalogSha256": digest(catalog_payload),
        "evaluationCoverage": dataset["evaluationCoverage"],
        "scoring": dataset["scoring"],
        "rightsReview": dataset["rightsReview"],
    }
    release_payload = (json.dumps(public_release, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    attribution = dict(base_attribution)
    attribution.update({
        "version": version,
        "upstreamRelease": UPSTREAM_RELEASE,
        "sourceSuiteId": dataset["suiteId"],
        "sourceReleaseDate": args.release_revision,
    })
    attribution_payload = (json.dumps(attribution, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    for relative, payload in public_output.items():
        destination = public_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
    (public_dir / "catalog.json").write_bytes(catalog_payload)
    (public_dir / "release.json").write_bytes(release_payload)
    (public_dir / "attribution.json").write_bytes(attribution_payload)
    with ZipFile(public_dir / "public-tasks.zip", "w", compression=ZIP_DEFLATED) as archive:
        year, month, day = (int(part) for part in args.release_revision.split("-", 2))
        for name, payload in {
            "catalog.json": catalog_payload,
            "release.json": release_payload,
            "attribution.json": attribution_payload,
            **public_output,
        }.items():
            info = ZipInfo(name, date_time=(year, month, day, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, payload)

    metadata_name = f"cryptobench-v4-{version.replace('.', '-')}.js"
    export_name = f"cryptobenchV4_{version.replace('.', '_')}"
    (STATIC / "js" / metadata_name).write_text(
        "// Generated by scripts/import-v4-release.py. Public metadata only.\n"
        f"export const {export_name} = {json.dumps(dataset, ensure_ascii=False, indent=2)};\n",
        encoding="utf-8",
    )

    private_tasks = []
    for number in range(1, 171):
        public_task = next(task for task in entries if task["question_number"] == number)
        task_id = public_task["task_id"]
        base_task = base_private_tasks[task_id]
        route = routes[task_id]
        implementation = route["implementation"]
        oracle_path = f"tasks/{task_id}/hidden/oracle.json"
        validator_path = f"tasks/{task_id}/oracle/validator.py"
        attachments = [
            {"path": file["url"].split(f"{public_prefix}/", 1)[1],
             "bytes": file["bytes"], "sha256": file["sha256"]}
            for file in public_task["files"] if "/attachments/" in file["url"]
        ]
        private_tasks.append({
            "id": task_id,
            "sourceTaskId": base_task.get("sourceTaskId", ""),
            "questionNumber": number,
            "question": public_task["question"],
            "expectedAnswer": "",
            "publicFiles": [
                file["url"].split(f"{public_prefix}/", 1)[1] for file in public_task["files"]
            ],
            "input": {
                "answerFormat": {
                    "kind": "workspace",
                    "answerFile": "final_answer.md",
                    "artifactField": "artifact.files",
                },
            },
            "category": base_task.get("category", ""),
            "submissionMode": "workspace",
            "verification": {
                "type": "workspace-validator",
                "judgeRoute": route["mode"],
                "implementation": implementation,
                "requiredFiles": route.get("required_files", []),
            },
            "provenance": {
                "oraclePath": oracle_path,
                "oracleSha256": digest(judge_files[oracle_path]),
                "questionSha256": digest(public_files[f"tasks/{task_id}/public/question.md"]),
                "attachments": attachments,
            },
            "validator": {
                "ref": implementation,
                "sha256": digest(judge_files[implementation]),
                "tests": [],
                "routeMode": route["mode"],
                **({"wrapper": validator_path} if validator_path in judge_files else {}),
            },
        })
    private_policy = json.loads(judge_files["metadata/release_scoring_policy.json"].decode("utf-8-sig"))
    private_catalog = {
        "schema": PRIVATE_CATALOG_SCHEMA,
        "datasetId": DATASET_ID,
        "version": version,
        "evaluatorVersion": EVALUATOR_VERSION,
        "releaseRevision": args.release_revision,
        "upstreamRelease": UPSTREAM_RELEASE,
        "scoringModel": {
            "taskResult": "judge_release_check",
            "overallMetric": "passed_tasks/total_tasks",
            "partialScoresRetainedAsEvidence": True,
            "completionMetricEligibleTasks": 166,
            "answerOnlyTasks": list(ANSWER_ONLY_TASKS),
            "automaticTasks": list(AUTOMATIC_TASKS),
        },
        "judgePolicy": private_policy,
        "tasks": private_tasks,
    }
    private_catalog_payload = write_json(base_private.parent / version / "tasks.json", private_catalog)
    public_zip_hash = file_digest(public_dir / "public-tasks.zip")
    private_manifest = {
        "schema": PRIVATE_MANIFEST_SCHEMA,
        "datasetId": DATASET_ID,
        "version": version,
        "releaseRevision": args.release_revision,
        "upstreamRelease": UPSTREAM_RELEASE,
        "evaluatorVersion": EVALUATOR_VERSION,
        "publicationStatus": "technical_ready",
        "publicCatalogSha256": digest(catalog_payload),
        "publicReleaseSha256": digest(release_payload),
        "publicZipSha256": public_zip_hash,
        "privateCatalogSha256": digest(private_catalog_payload),
        "sourcePublicZipSha256": file_digest(public_zip),
        "sourceJudgeZipSha256": file_digest(judge_zip),
        "taskCount": 170,
        "routeCounts": dict(sorted(Counter(route["mode"] for route in routes.values()).items())),
        "validatorCount": 67,
        "automaticTasks": list(AUTOMATIC_TASKS),
        "completionMetricExcludedTasks": list(ANSWER_ONLY_TASKS),
        "changedTasks": changed_questions,
        "preservedAttachments": sorted(preserved_attachments),
        "rightsReview": dataset["rightsReview"],
        "tasks": [
            {
                "id": task["id"],
                "questionNumber": task["questionNumber"],
                "oracleSha256": task["provenance"]["oracleSha256"],
                "routeMode": task["verification"]["judgeRoute"],
                "implementation": task["verification"]["implementation"],
            }
            for task in private_tasks
        ],
    }
    write_json(base_private.parent / version / "release-manifest.json", private_manifest)
    stage_judge(judge_files, args.stage_dir)

    print(json.dumps({
        "version": version,
        "evaluatorVersion": EVALUATOR_VERSION,
        "taskCount": len(entries),
        "publicFiles": len(public_output),
        "attachments": attachment_count,
        "changedQuestions": len(changed_questions),
        "preservedAttachments": len(preserved_attachments),
        "routeCounts": private_manifest["routeCounts"],
        "publicZipSha256": public_zip_hash,
        "stagedSuite": str(args.stage_dir.resolve()),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
