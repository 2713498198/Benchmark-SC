"""Verify the CryptoBench-SC V4.2 public and private release artifacts."""

import hashlib
import json
from collections import Counter
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "src/main/resources/static/data/cryptobench-sc-v4/4.2.0"
PRIVATE = ROOT / "src/main/resources/evaluation/cryptobench-sc-v4/4.2.0"
VERSION = "4.2.0"
EVALUATOR_VERSION = "cryptobench-sc-v4-evaluator-1.1.0"
SOURCE_PUBLIC_SHA256 = "6858d3ac8721aad066789ed70ef13fa1d44f4e0abeb1ef85fac354ca0de1fabe"
SOURCE_JUDGE_SHA256 = "ea6656e416b4b7a8eb31fadc3988cea9c5b8beb9591aaa2d9a561cb267e15756"
CHANGED_TASKS = {
    "023_CBSC-V2-NL-D11",
    "047_CBSC-V2-PS-2023-SPECK-ALGB-CASES-012",
    "053_CBSC-V2-PS-2405-GIFT64-FIDROW3-025",
    "054_CBSC-V2-PS-2405-GIFT64-FIDROW8-026",
    "055_CBSC-V2-PS-2405-XOR3-INEQ-027",
    "056_CBSC-V2-PS-2405-XOR4-DDT-028",
    "057_CBSC-V2-PS-2408-DL-TRANSFORM-022",
    "108_CBSC-V4-PB-SKINNY64-TK3-MID5-008",
    "110_CBSC-V4-PD-AES128-KEYPROJ2-010",
    "114_CBSC-V4-PD-MIDORI64-AFFINEKEY5-005",
    "121_CBSC-V4-PD-WARP-MP20-003",
    "129_CBSC-V4-PD-BAKSHEESH-OPT12-025",
    "130_CBSC-V4-PL-BAKSHEESH-KR20-026",
    "148_CBSC-V4-PB-CRAFT64-R7MATRIX-044",
    "149_CBSC-V4-PB-LEA128-RKID4-045",
    "150_CBSC-V4-PD-TWINE15-NLPROJ-046",
    "151_CBSC-V4-PD-PRIDE-COR10-AUDIT-047",
    "170_CBSC-V4-PD-SCREAM-AFFINE-WEAKKEY-066",
}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


catalog = read_json(PUBLIC / "catalog.json")
release = read_json(PUBLIC / "release.json")
attribution = read_json(PUBLIC / "attribution.json")
private = read_json(PRIVATE / "tasks.json")
manifest = read_json(PRIVATE / "release-manifest.json")
dataset = catalog["dataset"]
tasks = catalog["tasks"]
private_tasks = private["tasks"]

assert catalog["schema"] == "cryptobench-public-catalog-v2"
assert dataset["id"] == "cryptobench-sc-v4"
assert dataset["version"] == VERSION
assert dataset["releaseDate"] == dataset["releaseRevision"] == "2026-11-25"
assert dataset["upstreamRelease"] == "20261125-auto1"
assert dataset["evaluatorVersion"] == EVALUATOR_VERSION
assert dataset["executionReady"] is True
assert dataset["evaluationCoverage"] == {"judgeGeneric": 103, "judgeTaskSpecific": 67}
assert dataset["rightsReview"]["manifest"] == "data/cryptobench-sc-v4/4.2.0/attribution.json"
assert dataset["publicSourceZipSha256"] == SOURCE_PUBLIC_SHA256
assert dataset["judgeSourceZipSha256"] == SOURCE_JUDGE_SHA256

assert len(tasks) == 170
assert sorted(task["question_number"] for task in tasks) == list(range(1, 171))
assert len({task["task_id"] for task in tasks}) == 170
assert len({task["question"] for task in tasks}) == 170
assert all(task["question"].strip() for task in tasks)
assert release["version"] == VERSION
assert release["taskCount"] == 170
assert release["publicFileCount"] == 245
assert release["attachmentCount"] == 75
assert release["changedTaskCount"] == 18
assert set(release["changedTasks"]) == CHANGED_TASKS
assert release["publicCatalogSha256"] == sha256(PUBLIC / "catalog.json")
assert release["rightsReview"]["manifest"] == "data/cryptobench-sc-v4/4.2.0/attribution.json"
assert attribution["version"] == VERSION
assert len(attribution["tasks"]) == 170

expected = {"catalog.json", "release.json", "attribution.json"}
attachment_count = 0
for task in tasks:
    for file in task["files"]:
        path = ROOT / "src/main/resources/static" / file["url"]
        assert path.is_file(), file["url"]
        assert path.stat().st_size == file["bytes"]
        assert sha256(path) == file["sha256"]
        relative = path.relative_to(PUBLIC).as_posix()
        expected.add(relative)
        if "/public/attachments/" in f"/{relative}":
            attachment_count += 1
            assert path.read_bytes()
assert attachment_count == 75
assert (PUBLIC / "tasks/023_CBSC-V2-NL-D11/public/question.md").read_text(
    encoding="utf-8-sig"
).find("characteristic.json") >= 0
assert (PUBLIC / "tasks/170_CBSC-V4-PD-SCREAM-AFFINE-WEAKKEY-066/public/question.md").read_text(
    encoding="utf-8-sig"
).find("verification.json") >= 0
for number in (148, 149, 150, 151):
    task = next(task for task in tasks if task["question_number"] == number)
    assert any(file["name"] == "attachments/reference_paper.pdf" for file in task["files"])

with ZipFile(PUBLIC / "public-tasks.zip") as archive:
    assert set(archive.namelist()) == expected
    for name in expected:
        assert archive.read(name) == (PUBLIC / name).read_bytes()
actual = {path.relative_to(PUBLIC).as_posix() for path in PUBLIC.rglob("*") if path.is_file()}
assert actual == expected | {"public-tasks.zip"}
assert not any("hidden" in name or "oracle" in name for name in actual)

assert private["schema"] == "cryptobench-v4-evaluator-catalog-v3"
assert private["version"] == VERSION
assert private["evaluatorVersion"] == EVALUATOR_VERSION
assert len(private_tasks) == 170
assert {task["id"] for task in private_tasks} == {task["task_id"] for task in tasks}
assert all(task["verification"]["type"] == "workspace-validator" for task in private_tasks)
assert all(task["expectedAnswer"] == "" for task in private_tasks)
assert all(task["submissionMode"] == "workspace" for task in private_tasks)
routes = Counter(task["verification"]["judgeRoute"] for task in private_tasks)
assert routes == {"generic": 103, "task_specific": 67}
assert {task["question"] for task in private_tasks} == {task["question"] for task in tasks}
public_by_id = {task["task_id"]: task for task in tasks}
for task in private_tasks:
    question_file = next(file for file in public_by_id[task["id"]]["files"] if file["name"] == "question.md")
    assert task["provenance"]["questionSha256"] == question_file["sha256"]
assert len(manifest["automaticTasks"]) == 8
assert len(manifest["completionMetricExcludedTasks"]) == 4
assert manifest["taskCount"] == 170
assert manifest["validatorCount"] == 67
assert manifest["routeCounts"] == {"generic": 103, "task_specific": 67}
assert manifest["publicCatalogSha256"] == sha256(PUBLIC / "catalog.json")
assert manifest["publicReleaseSha256"] == sha256(PUBLIC / "release.json")
assert manifest["publicZipSha256"] == sha256(PUBLIC / "public-tasks.zip")
assert manifest["privateCatalogSha256"] == sha256(PRIVATE / "tasks.json")
assert manifest["sourcePublicZipSha256"] == SOURCE_PUBLIC_SHA256
assert manifest["sourceJudgeZipSha256"] == SOURCE_JUDGE_SHA256

staged = ROOT / "private" / "v4-suite"
if staged.is_dir():
    checks = read_json(staged / "SHA256.json")
    assert len(checks) == 571
    for name, expected_hash in checks.items():
        path = staged / name
        assert path.is_file(), name
        assert sha256(path) == expected_hash, name
    for task in private_tasks:
        assert task["provenance"]["oracleSha256"] == sha256(staged / task["provenance"]["oraclePath"])
        assert task["validator"]["sha256"] == sha256(staged / task["validator"]["ref"])
    assert (staged / "scripts/grade_submission.py").is_file()
    assert (staged / "metadata/release_scoring_policy.json").is_file()

print(
    "PASS: V4.2 contains 170 tasks, 245 public files, 75 attachments, "
    "103 generic routes and 67 task-specific routes."
)
