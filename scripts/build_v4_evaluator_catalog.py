"""Build the private CryptoBench-SC V4.1 evaluator catalog from a staged release.

The generator intentionally extracts only unambiguous, deterministic final
answers from each task's hidden oracle. Everything else is emitted as a
``dynamic`` rule that the evaluator fails closed on. Tasks whose upstream
scoring depends on a workspace replay are emitted as ``workspace-validator``.
"""

import argparse
import hashlib
import json
import numbers
from pathlib import Path


DATASET_ID = "cryptobench-sc-v4"
VERSION = "4.1.0"
EVALUATOR_VERSION = "cryptobench-sc-v4-evaluator-1.0.1"
CATALOG_SCHEMA = "cryptobench-v4-evaluator-catalog-v2"
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


NESTED_EXCLUDE = {"reason", "claim", "interpretation", "description", "condition",
                  "note", "rationale", "source", "paper_location", "task_id",
                  "schema_version", "semantics", "position"}


def nested_scalars(node, prefix="", out=None):
    if out is None:
        out = {}
    if isinstance(node, dict):
        for key, value in node.items():
            if key in METADATA_KEYS or key.startswith("source_"):
                continue
            if scalar(value):
                if key not in NESTED_EXCLUDE:
                    out[prefix + key] = value
            elif isinstance(value, dict):
                nested_scalars(value, prefix + key + ".", out)
    return out


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


METADATA_KEYS = {
    "answer_type", "cipher", "rounds", "search_time", "anti_trivial_condition",
    "method", "workflow", "notation", "reference", "algorithm", "setting",
    "source", "status", "note", "paper_seed", "configuration", "key", "mode",
    "task_id", "source_task_id", "title", "schema_version", "taxonomy",
    "task_type", "query", "evidence_nodes", "scoring_notes", "strategy_tiers",
    "score_tiers", "tolerances", "oracle_policy", "accepted_answer_policy",
    "oracle_replay", "target", "publication_status", "benchmark_author_provenance",
    "derivation_metadata_source", "expected_intermediate_facts", "oracle_answer",
    "expected", "expected_core_answer", "expected_agent_observable",
}


def answer_fields(oracle):
    """Return the authoritative scalar answer fields for a task, when derivable."""
    schema = oracle.get("schema_version")
    source = None
    if schema == "hard_failure_oracle_v1":
        source = oracle.get("expected")
    elif schema == "hard_task_oracle_v1":
        core = oracle.get("expected_core_answer")
        notes = oracle.get("scoring_notes") or {}
        fields = notes.get("core_answer_fields")
        if isinstance(core, dict) and isinstance(fields, list) and fields:
            return {key: core[key] for key in fields if scalar(core.get(key))}
        if isinstance(core, dict) and any(scalar(v) and k not in METADATA_KEYS for k, v in core.items()):
            source = core
        else:
            source = oracle.get("oracle_answer") or oracle.get("expected_agent_observable")
    elif schema == "hidden_oracle_v2":
        source = oracle.get("oracle_answer")
    elif schema == "hard_failure_oracle_v2":
        source = oracle.get("oracle_answer") or oracle.get("expected_agent_observable") or oracle.get("expected")
    if not isinstance(source, dict):
        return {}
    top = {key: value for key, value in source.items()
           if scalar(value) and key not in METADATA_KEYS and not key.startswith("source_")}
    if top:
        return top
    return nested_scalars(source)


def fields_rule(oracle):
    fields = answer_fields(oracle)
    if not fields:
        return None, None, {}
    numeric_tolerance = 0.0
    tolerances = oracle.get("tolerances") or {}
    if isinstance(tolerances, dict) and isinstance(tolerances.get("numeric_abs"), (int, float)):
        numeric_tolerance = tolerances["numeric_abs"]
    return ({"type": "fields", "fields": fields, "numericTolerance": numeric_tolerance},
            json.dumps(fields, ensure_ascii=False, sort_keys=True),
            {key: ("boolean" if isinstance(value, bool) else "number" if isinstance(value, numbers.Number) else "string")
             for key, value in fields.items()})


def piecewise_rule(expected):
    """Build a deterministic rule for a piecewise-formula answer."""
    cases = expected.get("cases")
    if not isinstance(cases, list) or not cases:
        return None, None, {}
    normalized = []
    for case in cases:
        if not isinstance(case, dict):
            return None, None, {}
        condition = case.get("condition")
        y = case.get("y")
        advantage = case.get("advantage")
        if not all(isinstance(value, str) and value for value in (condition, y, advantage)):
            return None, None, {}
        normalized.append({"condition": condition, "y": y, "advantage": advantage})
    rule = {"type": "piecewise", "cases": normalized}
    answer_format = {
        "kind": "piecewise_formula",
        "value": {"cases": [{"condition": "string", "y": "string", "advantage": "string"}]},
    }
    return rule, json.dumps(normalized, ensure_ascii=False, sort_keys=True), answer_format


def answer_format(rule: dict) -> dict:
    rule_type = rule["type"]
    if rule_type == "numeric":
        return {"kind": "number"}
    if rule_type == "string":
        return {"kind": "string"}
    if rule_type == "boolean":
        return {"kind": "boolean"}
    if rule_type == "fields":
        return {
            "kind": "object",
            "fields": {
                key: ("boolean" if isinstance(value, bool)
                      else "number" if isinstance(value, numbers.Number)
                      else "string")
                for key, value in rule["fields"].items()
            },
        }
    if rule_type == "piecewise":
        return {
            "kind": "piecewise_formula",
            "value": {
                "cases": [{
                    "condition": "string",
                    "y": "string",
                    "advantage": "string",
                }],
            },
        }
    if rule_type == "workspace-validator":
        return {
            "kind": "workspace",
            "answerFile": "final_answer.md",
            "artifactField": "artifact.files",
        }
    raise ValueError(f"Unsupported answer format rule: {rule_type}")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


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

    if schema == "hard_failure_oracle_v1":
        expected = oracle.get("expected") or {}
        if isinstance(expected, dict) and expected.get("answer_type") == "piecewise_formula":
            rule, expected_string, _ = piecewise_rule(expected)
            return rule, expected_string
        if isinstance(expected, dict) and isinstance(expected.get("probability_approx"), str):
            return string_rule(expected["probability_approx"]), expected["probability_approx"]
        return None, None

    if schema == "hard_task_oracle_v1":
        # A numeric oracle_replay.objective is the optimal trail weight, which
        # is the requested final value for the weight/optimum tasks. Multi-value
        # or witness-based tasks have no objective field and remain dynamic.
        replay = oracle.get("oracle_replay") or {}
        if isinstance(replay, dict) and isinstance(replay.get("objective"), numbers.Number) \
                and not isinstance(replay.get("objective"), bool):
            return numeric_rule(replay["objective"]), str(replay["objective"])
        return None, None

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
        has_validator = (task_dir / "oracle" / "validator.py").is_file()
        if has_validator:
            rule, expected = {"type": "workspace-validator"}, ""
        else:
            rule, expected = derive_rule(oracle)
            if rule is None:
                rule, expected, _ = fields_rule(oracle)
        rule_type = rule["type"] if rule else "dynamic"
        counts[rule_type] = counts.get(rule_type, 0) + 1
        final_rule = rule or {"type": "dynamic"}
        validator_path = task_dir / "oracle" / "validator.py"
        validator_ref = (
            f"tasks/{manifest_task['directory_name']}/oracle/validator.py"
            if has_validator else None
        )
        validator_tests = sorted(
            path.name
            for path in (task_dir / "oracle").glob("validator_selftest_*")
            if path.is_dir()
        ) if (task_dir / "oracle").is_dir() else []
        attachments = []
        for public_file in public_files:
            relative = public_file.split(f"/public/", 1)[1]
            path = task_dir / "public" / relative
            attachments.append({
                "path": public_file,
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
            })
        tasks.append({
            "id": task_id,
            "sourceTaskId": manifest_task.get("source_task_id", ""),
            "questionNumber": manifest_task.get("question_number"),
            "question": question,
            "expectedAnswer": expected or "",
            "publicFiles": public_files,
            "input": {"answerFormat": answer_format(final_rule)},
            "category": manifest_task.get("task_family", ""),
            "submissionMode": "workspace" if has_validator else "final_answer",
            "verification": final_rule,
            "provenance": {
                "oraclePath": f"tasks/{manifest_task['directory_name']}/hidden/oracle.json",
                "oracleSha256": sha256(task_dir / "hidden" / "oracle.json"),
                "questionSha256": hashlib.sha256(question.encode("utf-8")).hexdigest(),
                "attachments": attachments,
            },
            **({
                "validator": {
                    "ref": validator_ref,
                    "sha256": sha256(validator_path),
                    "tests": validator_tests,
                },
            } if has_validator else {}),
        })
    catalog = {
        "schema": CATALOG_SCHEMA,
        "datasetId": DATASET_ID,
        "version": VERSION,
        "evaluatorVersion": EVALUATOR_VERSION,
        "releaseRevision": "2026-11-25",
        "scoringModel": {
            "taskResult": "binary_pass_fail",
            "overallMetric": "passed_tasks/total_tasks",
            "sourcePolicyCapturedAsProvenance": True,
        },
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
