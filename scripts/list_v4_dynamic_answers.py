"""Emit a per-task answer-confirmation sheet for the non-deterministic V4 tasks.

For each task the website cannot score declaratively, print the task identity and
every answer-bearing scalar field present in its hidden oracle. This is the exact
material an upstream maintainer must confirm as the machine-checkable final
answer, after which the deterministic catalog generator can be extended.
"""

import argparse
import json
import numbers
from pathlib import Path


def scalar(value):
    return isinstance(value, (str, int, float, bool)) and not isinstance(value, bool)


def scalar_fields(node, prefix=""):
    if isinstance(node, dict):
        result = []
        for key, value in node.items():
            if scalar(value):
                result.append((prefix + key, value))
            elif isinstance(value, dict):
                result.extend(scalar_fields(value, prefix + key + "."))
        return result
    return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8-sig"))
    for task in manifest["tasks"]:
        task_id = task["task_id"]
        oracle = json.loads((source / "tasks" / task["directory_name"] / "hidden" / "oracle.json").read_text(encoding="utf-8"))
        schema = oracle.get("schema_version")
        candidates = []
        for field in ("expected", "expected_core_answer", "oracle_answer"):
            if isinstance(oracle.get(field), dict):
                candidates.extend(scalar_fields(oracle[field]))
        replay = oracle.get("oracle_replay")
        if isinstance(replay, dict) and isinstance(replay.get("objective"), numbers.Number):
            candidates.append(("oracle_replay.objective", replay["objective"]))
        if not candidates:
            continue
        print(json.dumps({
            "task_id": task_id,
            "title": task.get("title"),
            "cipher": task.get("cipher"),
            "schema": schema,
            "answer_candidates": [
                {"field": field, "value": value} for field, value in candidates
            ],
        }, ensure_ascii=False))


if __name__ == "__main__":
    main()
