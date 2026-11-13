"""Build the private CryptoBench-SC V4.1 evaluator catalog from a staged release.

The generator intentionally extracts only unambiguous, deterministic final
answers from each task's hidden oracle. Everything else is emitted as a
``dynamic`` rule that the evaluator fails closed on; the suite must stay
``executionReady=false`` until all 170 tasks carry a deterministic rule.
"""

import argparse
import json
import numbers
from pathlib import Path


DATASET_ID = "cryptobench-sc-v4"
VERSION = "4.1.0"
EVALUATOR_VERSION = "cryptobench-sc-v4-evaluator-1.0.0"
OUTPUT = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "main"
    / "resources"
    / "evaluation"
    / "cryptobench-sc-v4"
    / "4.1.0"
    / "tasks.json"
)


def scalar(v):
    return isinstance(v, (str, int, float, bool)) and not isinstance(v, bool)


def numeric_rule(value, absolute=0.0, relative=0.0):
    if isinstance(value, bool) or not isinstance(value, numbers.Number):
        return None
    return {
        "type": "numeric",
        "expected": value,
        "absoluteTolerance": absolute,
        "relativeTolerance": relative,
    }


def string_rule(value):
    if not isinstance(value, str):
        return None
    return {"type": "string", "expected": value, "ignoreCase": True}


def boolean_rule(value):
    if not isinstance(value, bool):
        return None
    return {"type": "boolean", "expected": value}


def derive_rule(oracle):
    """Return (rule, expected_string) or (None, None) when not deterministic."""
    schema = oracle.get("schema_version")

    if schema == "hard_failure_oracle_v2":
        oa = oracle.get("oracle_answer") or {}
        if isinstance(oa, dict):
            value = oa.get("primary_answer")
            if value is None:
                value = oa.get("active_sbox_count")
            if value is None:
                value = oa.get("primary_numeric_answer")
            if scalar(value):
                if isinstance(value, bool):
                    return boolean_rule(value), str(value)
                if isinstance(value, numbers.Number):
                    return numeric_rule(value), str(value)
                return string_rule(value), value
        eao = oracle.get("expected_agent_observable") or {}
        if isinstance(eao, dict) and scalar(eao.get("primary_answer")):
            value = eao["primary_answer"]
            if isinstance(value, numbers.Number) and not isinstance(value, bool):
                return numeric_rule(value), str(value)
            return string_rule(str(value)), str(value)
        return None, None

    if schema == "hidden_oracle_v2":
        oa = oracle.get("oracle_answer") or {}
        if isinstance(oa, dict) and isinstance(oa.get("reachable"), bool):
            return boolean_rule(oa["reachable"]), str(oa["reachable"])
        return None, None

    # hard_task_oracle_v1 and hard_failure_oracle_v1 answer schemas describe
    # multi-field, replay, or semantically scored results. They do not expose a
    # single unambiguous final value, so they stay dynamic rather than risk a
    # wrong automatic extraction.

    return None, None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="staged release root with manifest.json and tasks/")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    source = args.source.resolve()
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8-sig"))
    tasks = []
    counts = {}
    for manifest_task in manifest["tasks"]:
        task_id = manifest_task["task_id"]
        task_dir = source / "tasks" / manifest_task["directory_name"]
        question = (task_dir / "public" / "question.md").read_text(encoding="utf-8-sig")
        public_files = []
        attachments = task_dir / "public" / "attachments"
        if attachments.is_dir():
            for path in sorted(attachments.iterdir()):
                if path.is_file():
                    public_files.append(f"tasks/{task_id}/public/attachments/{path.name}")
        oracle = json.loads((task_dir / "hidden" / "oracle.json").read_text(encoding="utf-8"))
        rule, expected = derive_rule(oracle)
        rule_type = rule["type"] if rule else "dynamic"
        counts[rule_type] = counts.get(rule_type, 0) + 1
        tasks.append({
            "id": task_id,
            "question": question,
            "expectedAnswer": expected or "",
            "publicFiles": public_files,
            "input": {},
            "category": manifest_task.get("task_family", ""),
            "verification": rule or {"type": "dynamic"},
        })
    catalog = {
        "datasetId": DATASET_ID,
        "version": VERSION,
        "evaluatorVersion": EVALUATOR_VERSION,
        "tasks": tasks,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total = len(tasks)
    dynamic = counts.get("dynamic", 0)
    print(json.dumps({
        "output": str(args.output),
        "taskCount": total,
        "deterministic": total - dynamic,
        "dynamic": dynamic,
        "ruleTypes": counts,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
