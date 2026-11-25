"""Stage the server-private part of a CryptoBench-SC V4 release for scoring.

For the 20261125-auto1 release judge, the complete maintainer package is the
runbook and is copied as-is. Legacy source releases continue through the
selective 4.1 private-material path below.

Usage:
    python scripts/prepare-v4-private-suite.py <release-root> <target-dir>
"""

import argparse
import shutil
from pathlib import Path


PRIVATE_UNDER_TASKS = ("hidden", "oracle")
PRIVATE_SCRIPTS = ("run_task_validator.py", "validators", "tests")
PRIVATE_RESEARCH_FILES = (
    "research/replays/pride_cor10_compatibility/candidate_audit.csv",
    "research/replays/pride_cor10_compatibility/exact_replay.py",
)


def apply_release_patches(target: Path) -> None:
    """Apply reviewed fixes that are not present in the upstream snapshot."""
    qarma = target / "scripts" / "validators" / "qarmav2_partial_sum_cost.py"
    text = qarma.read_text(encoding="utf-8")
    qarma_marker = "    false_s5_global = "
    qarma_end = "    optimum_fraction = "
    if "false_s5_global = any(" not in text:
        start = text.find(qarma_marker)
        end = text.find(qarma_end, start + len(qarma_marker))
        replacement = """    false_s5_global = any(
        re.search(
            r"s\\s*=\\s*5[^\\n]{0,100}(?:global|overall|time).{0,30}(?:optimal|minimum)|"
            r"(?:global|overall|time).{0,30}(?:optimal|minimum)[^\\n]{0,100}s\\s*=\\s*5|"
            r"s\\s*=\\s*5[^\\n]{0,100}(?:\\u5168\\u5c40|\\u65f6\\u95f4).{0,20}(?:\\u6700\\u4f18|\\u6700\\u5c0f)|"
            r"(?:\\u5168\\u5c40|\\u65f6\\u95f4).{0,20}(?:\\u6700\\u4f18|\\u6700\\u5c0f)[^\\n]{0,100}s\\s*=\\s*5",
            line,
            re.I,
        )
        and not re.search(
            r"different|distinct|not\\s+the\\s+same|\\u4e0d\\u540c|\\u4e0d\\u76f8\\u540c|\\u533a\\u5206",
            line,
            re.I,
        )
        for line in final_text.splitlines()
    )

"""
        if start < 0 or end < 0:
            raise SystemExit("QARMA validator patch target was not found")
        qarma.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
    present = target / "scripts" / "validators" / "present80_key_recovery.py"
    text = present.read_text(encoding="utf-8")
    old_cost = "    cost_consistent = cost_nodes > 0 and not contradictions\n"
    new_cost = (
        "    cost_consistent = (cost_nodes > 0 and not contradictions) or "
        "(dot_edges >= len(EXPECTED_ALL) - 1 and dot_costs)\n"
    )
    if new_cost not in text:
        if old_cost not in text:
            raise SystemExit("PRESENT-80 validator patch target was not found")
        present.write_text(text.replace(old_cost, new_cost, 1), encoding="utf-8")
    gift = target / "scripts" / "validators" / "gift64_3differential.py"
    text = gift.read_text(encoding="utf-8")
    marker = "    final_nonzero_exact = any(\n"
    end_marker = "    joint_feasibility = "
    start = text.find(marker)
    end = text.find(end_marker, start + len(marker))
    replacement = """    final_nonzero_exact = any(
        has(
            r"(?:exact|joint|\\u7cbe\\u786e|\\u8054\\u5408).{0,40}(?:probability|event|count|\\u6982\\u7387|\\u4e8b\\u4ef6|\\u8ba1\\u6570)"
            r".{0,80}(?:9/2\\^?42|2\\^-?38\\.830|nonzero|\\u975e\\u96f6)",
            clause,
        )
        for clause in final_clauses
    )

"""
    if start < 0 or end < 0:
        raise SystemExit("GIFT-64 validator patch target was not found")
    if replacement not in text:
        gift.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
    alzette = target / "scripts" / "validators" / "alzette_monomial_degree.py"
    text = alzette.read_text(encoding="utf-8")
    alzette_marker = "    coefficient_one = has(\n"
    alzette_end = "    reachability_boundary = "
    start = text.find(alzette_marker)
    end = text.find(alzette_end, start + len(alzette_marker))
    replacement = """    coefficient_one = has(
        r"(?:coefficient|系数).{0,40}(?:(?:=|is|为|是|:)\\s*|\\s+)`?1\\b|"
        r"(?:appears?|present|出现|存在).{0,50}(?:ANF|algebraic normal|代数正规)|"
        r"(?:ANF|algebraic normal|代数正规).{0,50}(?:appears?|present|出现|存在)",
        corpus,
    )

"""
    if start < 0 or end < 0:
        raise SystemExit("Alzette ANF coefficient patch target was not found")
    if replacement not in text:
        alzette.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="release root with manifest.json and tasks/")
    parser.add_argument("target", type=Path, help="server-private suite directory")
    parser.add_argument("--version", default="4.2.0", help="V4 dataset version")
    args = parser.parse_args()
    source = args.source.resolve()
    target = args.target.resolve()
    if target == source or target.is_relative_to(source):
        raise SystemExit("target must be outside the source release")

    if (source / "scripts" / "grade_submission.py").is_file():
        shutil.copytree(source, target, dirs_exist_ok=True)
        validators = list((target / "tasks").glob("*/oracle/validator.py"))
        if len(validators) != 67:
            raise SystemExit(f"staged validator count is {len(validators)}, expected 67")
        print(f"Staged 20261125-auto1 release judge into {target}")
        return

    tasks_root = source / "tasks"
    scripts_root = source / "scripts"
    project_root = Path(__file__).resolve().parents[1]
    release_manifest = (
        project_root / "src" / "main" / "resources" / "evaluation"
        / "cryptobench-sc-v4" / args.version / "release-manifest.json"
    )
    if not release_manifest.is_file():
        raise SystemExit(f"release manifest not found: {release_manifest}")
    for task_dir in sorted(tasks_root.iterdir()):
        if not task_dir.is_dir():
            continue
        for sub in PRIVATE_UNDER_TASKS:
            src = task_dir / sub
            if src.is_dir():
                shutil.copytree(src, target / "tasks" / task_dir.name / sub,
                                dirs_exist_ok=True)
        attachments = task_dir / "public" / "attachments"
        if attachments.is_dir():
            shutil.copytree(
                attachments,
                target / "tasks" / task_dir.name / "public" / "attachments",
                dirs_exist_ok=True,
            )
    for name in PRIVATE_SCRIPTS:
        src = scripts_root / name
        if src.exists():
            dst = target / "scripts" / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, dst, dirs_exist_ok=True)
            else:
                shutil.copy2(src, dst)
    for relative in PRIVATE_RESEARCH_FILES:
        src = source / relative
        if not src.is_file():
            raise SystemExit(f"private suite test resource not found: {src}")
        dst = target / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    manifest_target = target / "release-manifest.json"
    manifest_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(release_manifest, manifest_target)
    contract_tool = project_root / "scripts" / "validate-v4-validators.py"
    shutil.copy2(contract_tool, target / "scripts" / "validate-v4-validators.py")
    validators = list((target / "tasks").glob("*/oracle/validator.py"))
    if len(validators) != 65:
        raise SystemExit(f"staged validator count is {len(validators)}, expected 65")
    apply_release_patches(target)
    print(f"Staged private V4 suite into {target}")


if __name__ == "__main__":
    main()
