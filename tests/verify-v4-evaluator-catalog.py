"""Validate the committed private V4 evaluator catalog invariants."""

import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "src" / "main" / "resources" / "static" / "data" / "cryptobench-sc-v4" / "4.1.0" / "catalog.json"
PRIVATE = ROOT / "src" / "main" / "resources" / "evaluation" / "cryptobench-sc-v4" / "4.1.0" / "tasks.json"

public = json.loads(PUBLIC.read_text(encoding="utf-8"))
private = json.loads(PRIVATE.read_text(encoding="utf-8"))

public_ids = {task["task_id"] for task in public["tasks"]}
tasks = private["tasks"]
assert private["datasetId"] == "cryptobench-sc-v4"
assert private["version"] == "4.1.0"
assert private["evaluatorVersion"] == "cryptobench-sc-v4-evaluator-1.0.0"
assert len(tasks) == 170 == len(public_ids)

allowed = {"numeric", "string", "one-of", "boolean", "exact-json", "dynamic", "workspace-validator"}
ids = set()
deterministic = 0
for task in tasks:
    assert task["id"] in public_ids, f"private task not in public catalog: {task['id']}"
    assert task["id"] not in ids, f"duplicate id: {task['id']}"
    ids.add(task["id"])
    rule = task["verification"]
    rule_type = rule.get("type")
    assert rule_type in allowed, f"{task['id']}: bad rule type {rule_type}"
    if rule_type in ("dynamic", "workspace-validator"):
        assert task["expectedAnswer"] == "", f"{task['id']}: {rule_type} task must not carry an answer"
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

assert ids == public_ids, "private catalog must cover exactly the public tasks"
assert deterministic >= 65, f"deterministic coverage regressed: {deterministic}"

from collections import Counter
breakdown = Counter(task["verification"]["type"] for task in tasks)
print(f"PASS: V4 evaluator catalog covers {len(tasks)} tasks, rule types {dict(breakdown)}, "
      "and exposes no dynamic/workspace-task answers.")
