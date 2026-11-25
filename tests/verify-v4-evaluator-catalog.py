"""Validate the committed private V4 evaluator catalog invariants."""

import json
import math
import re
import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "src" / "main" / "resources" / "static" / "data" / "cryptobench-sc-v4" / "4.1.0" / "catalog.json"
PRIVATE = ROOT / "src" / "main" / "resources" / "evaluation" / "cryptobench-sc-v4" / "4.1.0" / "tasks.json"
MANIFEST = PRIVATE.with_name("release-manifest.json")
PUBLIC_RELEASE = PUBLIC.with_name("release.json")
PUBLIC_ZIP = PUBLIC.with_name("public-tasks.zip")

public = json.loads(PUBLIC.read_text(encoding="utf-8"))
private = json.loads(PRIVATE.read_text(encoding="utf-8"))
manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

public_ids = {task["task_id"] for task in public["tasks"]}
tasks = private["tasks"]
assert private["datasetId"] == "cryptobench-sc-v4"
assert private["version"] == "4.1.0"
assert private["schema"] == "cryptobench-v4-evaluator-catalog-v2"
assert private["evaluatorVersion"] == "cryptobench-sc-v4-evaluator-1.0.1"
assert len(tasks) == 170 == len(public_ids)
assert manifest["schema"] == "cryptobench-v4-private-release-v1"
assert manifest["taskCount"] == 170
assert manifest["validatorCount"] == 65
assert manifest["rightsReview"]["status"] == "attribution_recorded"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


assert manifest["publicReleaseSha256"] == sha256(PUBLIC_RELEASE)
assert manifest["publicCatalogSha256"] == sha256(PUBLIC)
assert manifest["publicZipSha256"] == sha256(PUBLIC_ZIP)
assert manifest["privateCatalogSha256"] == sha256(PRIVATE)

allowed = {"numeric", "string", "one-of", "boolean", "exact-json", "dynamic",
           "workspace-validator", "fields", "piecewise"}
ids = set()
deterministic = 0
for task in tasks:
    assert task["id"] in public_ids, f"private task not in public catalog: {task['id']}"
    assert task["id"] not in ids, f"duplicate id: {task['id']}"
    ids.add(task["id"])
    rule = task["verification"]
    rule_type = rule.get("type")
    assert task["sourceTaskId"], f"{task['id']}: missing sourceTaskId"
    assert isinstance(task["questionNumber"], int), f"{task['id']}: missing questionNumber"
    assert task["submissionMode"] in ("final_answer", "workspace")
    answer_format = task["input"]["answerFormat"]
    expected_kind = {
        "numeric": "number",
        "string": "string",
        "boolean": "boolean",
        "fields": "object",
        "piecewise": "piecewise_formula",
        "workspace-validator": "workspace",
    }.get(rule_type)
    assert answer_format["kind"] == expected_kind, f"{task['id']}: answer format mismatch"
    provenance = task["provenance"]
    assert re.fullmatch(r"[a-f0-9]{64}", provenance["oracleSha256"])
    assert re.fullmatch(r"[a-f0-9]{64}", provenance["questionSha256"])
    for attachment in provenance["attachments"]:
        assert attachment["bytes"] >= 0
        assert re.fullmatch(r"[a-f0-9]{64}", attachment["sha256"])
    assert rule_type in allowed, f"{task['id']}: bad rule type {rule_type}"
    if rule_type in ("dynamic", "workspace-validator"):
        assert task["expectedAnswer"] == "", f"{task['id']}: {rule_type} task must not carry an answer"
        if rule_type == "workspace-validator":
            assert task["validator"]["ref"], f"{task['id']}: missing validator ref"
            assert re.fullmatch(r"[a-f0-9]{64}", task["validator"]["sha256"])
            assert task["submissionMode"] == "workspace"
        continue
    deterministic += 1
    assert task["expectedAnswer"] != "", f"{task['id']}: deterministic task missing expectedAnswer"
    if rule_type == "numeric":
        assert "expected" in rule and isinstance(rule["expected"], (int, float))
        assert math.isfinite(float(rule["expected"]))
        assert rule.get("absoluteTolerance", 0) >= 0
        assert rule.get("relativeTolerance", 0) >= 0
    elif rule_type in ("string", "one-of", "exact-json", "boolean"):
        assert "expected" in rule or rule_type == "one-of"
    elif rule_type == "fields":
        assert isinstance(rule.get("fields"), dict) and rule["fields"], f"{task['id']}: fields rule empty"
    elif rule_type == "piecewise":
        cases = rule.get("cases")
        assert isinstance(cases, list) and cases, f"{task['id']}: piecewise rule empty"
        for case in cases:
            assert all(case.get(key) for key in ("condition", "y", "advantage")), \
                f"{task['id']}: piecewise case incomplete"

assert ids == public_ids, "private catalog must cover exactly the public tasks"
assert deterministic >= 65, f"deterministic coverage regressed: {deterministic}"
assert all(task["verification"]["type"] != "dynamic" for task in tasks), \
    "every V4.1 task must have a deterministic or workspace-validator rule"

from collections import Counter
breakdown = Counter(task["verification"]["type"] for task in tasks)
print(f"PASS: V4 evaluator catalog covers {len(tasks)} tasks, rule types {dict(breakdown)}, "
      "and exposes no dynamic/workspace-task answers.")
