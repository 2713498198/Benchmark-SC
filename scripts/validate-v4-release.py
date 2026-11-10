"""Validate a staged CryptoBench-SC release before website import.

This is intentionally structural: it never reads hidden oracle values into the
public catalog and reports validator coverage separately from package validity.
"""
import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path, help='staged release directory')
    parser.add_argument('--require-validators', action='store_true')
    args = parser.parse_args()
    root = args.source.resolve()
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8-sig'))
    tasks = [path for path in (root / 'tasks').iterdir() if path.is_dir()]
    task_ids = [task.name for task in tasks]
    assert len(tasks) == manifest['task_count'] == 170
    assert len(set(task_ids)) == len(task_ids)
    numbers = []
    validator_count = 0
    validator_tasks = set()
    for task in tasks:
        release = json.loads((task / 'release.json').read_text(encoding='utf-8'))
        assert release['task_id'] == task.name
        numbers.append(release['question_number'])
        for relative in ('public/question.md', 'hidden/guardrail.json',
                         'hidden/oracle.json', 'oracle/source_extracts.md',
                         'docs/design_notes.md', 'release.json'):
            assert (task / relative).is_file(), f'{task.name}: missing {relative}'
        if (task / 'oracle/validator.py').is_file():
            validator_count += 1
            validator_tasks.add(task.name)
    assert sorted(numbers) == list(range(1, 171))
    missing = sorted(set(task_ids) - validator_tasks)
    result = {
        'suiteId': manifest['suite_id'],
        'taskCount': len(tasks),
        'validatorCount': validator_count,
        'validatorCoverage': validator_count / len(tasks),
        'missingValidatorTaskIds': missing,
        'executionReady': validator_count == len(tasks),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_validators and missing:
        raise SystemExit(f"release gate failed: {len(missing)} task validators missing")


if __name__ == '__main__':
    main()
