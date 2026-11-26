#!/usr/bin/env python3
"""Check whether the staged CryptoBench-SC V4.2 release is safe to publish."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / "private" / "v4-suite"
PUBLIC_RELEASE = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "static"
    / "data"
    / "cryptobench-sc-v4"
    / "4.2.0"
    / "release.json"
)
APPROVAL_SCHEMA = "cryptobench-v4-publish-approval-v1"
SAMPLE_VALIDATORS = (
    "106_CBSC-V4-PB-GIFT64-3DIFF-ZERO-016",
    "114_CBSC-V4-PD-MIDORI64-AFFINEKEY5-005",
    "115_CBSC-V4-PD-RECT64-QDT14-017",
    "116_CBSC-V4-PD-SIMECK64-ID17-INDIRECT-023",
    "120_CBSC-V4-PD-SPEEDY192-JOINTZERO-021",
    "147_CBSC-V4-PI-SKINNY64-TK1-INT10-043",
    "151_CBSC-V4-PD-PRIDE-COR10-AUDIT-047",
)
DEPENDENCY_TASK = "165_CBSC-V4-PI-LBLOCK17-DP-AUDIT-061"
GENERIC_TASK = "001_CBSC-V2-NL-A01"


def ensure_judge_runtime() -> None:
    if importlib.util.find_spec("pysat") is not None:
        return
    windows_python = ROOT / ".tools" / "v4-judge-venv" / "Scripts" / "python.exe"
    posix_python = ROOT / ".tools" / "v4-judge-venv" / "bin" / "python"
    candidate = windows_python if windows_python.is_file() else posix_python
    if candidate.is_file() and Path(sys.executable).resolve() != candidate.resolve():
        os.execv(str(candidate), [str(candidate), str(Path(__file__).resolve()), *sys.argv[1:]])


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str], *, cwd: Path = ROOT) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=180,
    )


def check_dependency() -> dict:
    try:
        from pysat.solvers import Solver
        import pysat
    except ImportError as error:
        return {"status": "failed", "error": str(error)}
    solver = Solver(name="cadical195")
    try:
        solver.add_clause([1])
        solved = solver.solve()
        version = getattr(pysat, "__version__", "unknown")
        solver_name = "cadical195"
    finally:
        solver.delete()
    if not solved:
        return {"status": "failed", "error": "CaDiCaL rejected a satisfiable smoke clause"}
    return {
        "status": "passed",
        "python": sys.executable,
        "pythonSat": version,
        "solver": solver_name,
    }


def check_suite_inventory() -> dict:
    checks = read_json(SUITE / "SHA256.json")
    if len(checks) != 571:
        raise ValueError(f"expected 571 judge files, found {len(checks)}")
    for relative, expected in checks.items():
        path = SUITE / relative
        if not path.is_file() or digest(path) != expected:
            raise ValueError(f"judge inventory mismatch: {relative}")
    return {"status": "passed", "verifiedFiles": len(checks)}


def check_task165() -> dict:
    with tempfile.TemporaryDirectory(prefix="cbsc-v4-task165-") as temp:
        run_dir = Path(temp) / "run"
        run_dir.mkdir()
        (run_dir / "final_answer.md").write_text("", encoding="utf-8")
        output = Path(temp) / "report.json"
        process = run(
            [
                sys.executable,
                str(SUITE / "scripts" / "run_task_validator.py"),
                "--suite-root",
                str(SUITE),
                "--task-id",
                DEPENDENCY_TASK,
                "--run-dir",
                str(run_dir),
                "--out",
                str(output),
            ],
            cwd=SUITE,
        )
        if not output.is_file():
            raise ValueError(f"task 165 did not produce a report: {process.stderr[-500:]}")
        report = read_json(output)
    status = report.get("status")
    if status == "validator_error":
        raise ValueError(f"task 165 dependency check failed: {report.get('error')}")
    if status not in {"failed", "partial", "passed"}:
        raise ValueError(f"task 165 returned an unexpected status: {status!r}")
    return {
        "status": "passed",
        "validatorStatus": status,
        "pysat": True,
        "cadical195": True,
    }


def check_generic_judge() -> dict:
    with tempfile.TemporaryDirectory(prefix="cbsc-v4-generic-") as temp:
        run_dir = Path(temp) / "run"
        run_dir.mkdir()
        (run_dir / "final_answer.md").write_text(
            "The minimum active S-box count is 25.",
            encoding="utf-8",
        )
        output = Path(temp) / "report.json"
        process = run(
            [
                sys.executable,
                str(SUITE / "scripts" / "grade_submission.py"),
                "--suite-root",
                str(SUITE),
                "--task-id",
                GENERIC_TASK,
                "--run-dir",
                str(run_dir),
                "--out",
                str(output),
            ],
            cwd=SUITE,
        )
        if process.returncode != 0 or not output.is_file():
            raise ValueError(f"generic judge failed: {process.stderr[-500:]}")
        report = read_json(output)
    if report.get("final_score") != 1.0 or report.get("completion_status") != "answer_scored":
        raise ValueError("generic judge did not score the known-valid answer")
    return {
        "status": "passed",
        "taskId": GENERIC_TASK,
        "score": report["final_score"],
        "completionStatus": report["completion_status"],
    }


def check_sampled_validators() -> dict:
    with tempfile.TemporaryDirectory(prefix="cbsc-v4-samples-") as temp:
        output = Path(temp) / "samples.json"
        command = [
            sys.executable,
            str(ROOT / "scripts" / "validate-v4-validators.py"),
            str(SUITE),
            "--out",
            str(output),
            "--timeout",
            "120",
        ]
        for task_id in SAMPLE_VALIDATORS:
            command.extend(["--task", task_id])
        process = run(command)
        if process.returncode != 0 or not output.is_file():
            raise ValueError(f"sampled validator regression failed: {process.stdout[-1000:]}")
        report = read_json(output)
    if report.get("status") != "passed" or report.get("passedCount") != len(SAMPLE_VALIDATORS):
        raise ValueError("sampled validator report is incomplete")
    return {
        "status": "passed",
        "validatorCount": report["validatorCount"],
        "passedCount": report["passedCount"],
        "taskIds": list(SAMPLE_VALIDATORS),
    }


def check_public_contract() -> dict:
    process = run([sys.executable, str(ROOT / "tests" / "verify-v4-2-import.py")])
    if process.returncode != 0:
        raise ValueError(f"V4.2 public contract failed: {process.stdout[-1000:]}")
    release = read_json(PUBLIC_RELEASE)
    public_catalog = read_json(PUBLIC_RELEASE.with_name("catalog.json"))
    attribution = read_json(PUBLIC_RELEASE.with_name("attribution.json"))
    public_ids = {task["task_id"] for task in public_catalog["tasks"]}
    attribution_ids = {task["taskId"] for task in attribution["tasks"]}
    if attribution_ids != public_ids or len(attribution_ids) != 170:
        raise ValueError("attribution coverage differs from the 170 public tasks")
    paper_ids = {
        task["taskId"][:3]: task.get("sourcePaperId", "")
        for task in attribution["tasks"]
        if task["taskId"][:3] in {"148", "149", "150", "151"}
    }
    if set(paper_ids) != {"148", "149", "150", "151"} or not all(paper_ids.values()):
        raise ValueError("paper attribution is incomplete for tasks 148 through 151")
    rights = SUITE / "RIGHTS.md"
    if not rights.is_file() or not rights.read_text(encoding="utf-8").strip():
        raise ValueError("private V4 rights notice is missing")
    return {
        "status": "passed",
        "taskCount": release["taskCount"],
        "publicFileCount": release["publicFileCount"],
        "attachmentCount": release["attachmentCount"],
        "attributionCount": len(attribution_ids),
    }


def check_approval(path: Path | None) -> dict:
    if path is None or not path.is_file():
        return {
            "status": "pending",
            "reason": "No rights-holder publication approval was supplied.",
        }
    approval = read_json(path)
    problems = []
    if approval.get("schema") != APPROVAL_SCHEMA:
        problems.append("unsupported approval schema")
    if approval.get("status") != "approved":
        problems.append("approval status is not approved")
    for field in ("rightsHolder", "scope", "evidence", "approvedBy", "approvedAt"):
        if not isinstance(approval.get(field), str) or not approval[field].strip():
            problems.append(f"{field} is required")
    try:
        approved_on = date.fromisoformat(str(approval.get("approvedAt", "")))
        if approved_on > date.today():
            problems.append("approvedAt is in the future")
    except ValueError:
        problems.append("approvedAt must use YYYY-MM-DD")
    if problems:
        return {"status": "failed", "problems": problems}
    return {
        "status": "approved",
        "rightsHolder": approval["rightsHolder"],
        "scope": approval["scope"],
        "approvedBy": approval["approvedBy"],
        "approvedAt": approval["approvedAt"],
    }


def main() -> int:
    ensure_judge_runtime()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--approval",
        type=Path,
        default=ROOT / "private" / "v4-publish-approval.json",
        help="rights-holder approval JSON; see docs/v4-publish-approval.example.json",
    )
    parser.add_argument(
        "--require-publication",
        action="store_true",
        help="fail unless technical checks and rights-holder approval both pass",
    )
    args = parser.parse_args()

    result = {
        "schema": "cryptobench-v4-release-readiness-v1",
        "version": "4.2.0",
        "technical": {},
        "approval": {},
    }
    try:
        result["technical"]["dependency"] = check_dependency()
        result["technical"]["suiteInventory"] = check_suite_inventory()
        result["technical"]["task165"] = check_task165()
        result["technical"]["genericJudge"] = check_generic_judge()
        result["technical"]["sampledValidators"] = check_sampled_validators()
        result["technical"]["publicContract"] = check_public_contract()
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        result["technical"] = {"status": "failed", "error": str(error)}
    else:
        result["technical"]["status"] = "passed"

    result["approval"] = check_approval(args.approval.resolve() if args.approval else None)
    result["technicalReady"] = result["technical"].get("status") == "passed"
    result["publicationReady"] = (
        result["technicalReady"] and result["approval"].get("status") == "approved"
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))

    if not result["technicalReady"]:
        return 1
    if args.require_publication and not result["publicationReady"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
