"""Verify shared validator implementations still require per-task coverage."""

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / 'scripts' / 'validate-v4-release.py'


def make_task(root: Path, task_id: str, question_number: int) -> None:
    task = root / 'tasks' / task_id
    for relative in ('public/question.md', 'hidden/guardrail.json', 'hidden/oracle.json',
                     'oracle/source_extracts.md', 'docs/design_notes.md'):
        path = task / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('{}' if path.suffix == '.json' else 'fixture', encoding='utf-8')
    (task / 'release.json').write_text(json.dumps({
        'task_id': task_id, 'question_number': question_number,
    }), encoding='utf-8')


with tempfile.TemporaryDirectory(prefix='cryptobench-registry-') as temporary:
    root = Path(temporary) / 'release'
    root.mkdir(parents=True)
    ids = ['001_TEST', '002_TEST']
    for number, task_id in enumerate(ids, start=1):
        make_task(root, task_id, number)

    for relative in ('scripts/shared_validator.py',
                     'tests/001-positive.txt', 'tests/001-negative.txt', 'tests/001-boundary.txt',
                     'tests/002-positive.txt', 'tests/002-negative.txt', 'tests/002-boundary.txt'):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('fixture', encoding='utf-8')

    (root / 'manifest.json').write_text(json.dumps({
        'task_count': 2, 'suite_id': 'TEST',
    }), encoding='utf-8')
    registry = root / 'validator-registry.json'
    registry.write_text(json.dumps({
        'schema': 'cryptobench-v4-validator-registry-v1',
        'tasks': [
            {'task_id': task_id, 'validator_ref': 'scripts/shared_validator.py#validate',
             'positive_tests': [f'tests/{number:03d}-positive.txt'],
             'negative_tests': [f'tests/{number:03d}-negative.txt'],
             'boundary_tests': [f'tests/{number:03d}-boundary.txt']}
            for number, task_id in enumerate(ids, start=1)
        ],
    }), encoding='utf-8')

    command = [sys.executable, str(VALIDATOR), str(root), '--require-validators',
               '--validator-registry', str(registry), '--expected-task-count', '2']
    valid = subprocess.run(command, capture_output=True, text=True)
    assert valid.returncode == 0, valid.stderr
    report = json.loads(valid.stdout)
    assert report['validatorMode'] == 'registry'
    assert report['validatorCount'] == 2
    assert report['validatorImplementationCount'] == 1
    assert report['validatorCoverage'] == 1

    invalid_registry = json.loads(registry.read_text(encoding='utf-8'))
    invalid_registry['tasks'].pop()
    registry.write_text(json.dumps(invalid_registry), encoding='utf-8')
    invalid = subprocess.run(command, capture_output=True, text=True)
    assert invalid.returncode != 0
    assert 'coverage mismatch' in (invalid.stderr + invalid.stdout).lower()

print('PASS: shared validator references are accepted only with complete per-task test coverage.')
