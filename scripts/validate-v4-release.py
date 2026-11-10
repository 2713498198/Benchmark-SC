"""Validate a staged CryptoBench-SC release before website import.

This is intentionally structural: it never reads hidden oracle values into the
public catalog and reports validator coverage separately from package validity.
"""
import argparse
import json
import re
from pathlib import Path


REGISTRY_SCHEMA = 'cryptobench-v4-validator-registry-v1'
REGISTRY_TEST_FIELDS = ('positive_tests', 'negative_tests', 'boundary_tests')


def _source_file(source: Path, relative: str, label: str) -> Path:
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError(f'{label} must be a relative path')
    raw = source / relative
    candidate = raw.resolve()
    if raw.is_symlink() or not candidate.is_relative_to(source) or not candidate.is_file():
        raise ValueError(f'{label} is missing or escapes the release root: {relative}')
    return candidate


def load_validator_registry(path: Path, source: Path, task_ids: set[str]) -> tuple[set[str], set[str]]:
    registry = json.loads(path.read_text(encoding='utf-8-sig'))
    if registry.get('schema') != REGISTRY_SCHEMA:
        raise ValueError('Unsupported validator registry schema')
    entries = registry.get('tasks')
    if not isinstance(entries, list):
        raise ValueError('Validator registry tasks must be an array')
    seen: set[str] = set()
    implementations: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError('Validator registry entries must be objects')
        task_id = entry.get('task_id')
        if not isinstance(task_id, str) or task_id not in task_ids or task_id in seen:
            raise ValueError('Validator registry has an unknown or duplicate task ID: ' + str(task_id))
        seen.add(task_id)
        reference = entry.get('validator_ref')
        if not isinstance(reference, str) or not re.fullmatch(
                r'[A-Za-z0-9_.\-/]+(?:#[A-Za-z_][A-Za-z0-9_]*)?', reference):
            raise ValueError(f'{task_id}: invalid validator_ref')
        implementation, _, _entrypoint = reference.partition('#')
        _source_file(source, implementation, f'{task_id} validator_ref')
        implementations.add(reference)
        for field in REGISTRY_TEST_FIELDS:
            tests = entry.get(field)
            if not isinstance(tests, list) or not tests or not all(isinstance(item, str) for item in tests):
                raise ValueError(f'{task_id}: {field} must contain at least one path')
            for relative in tests:
                _source_file(source, relative, f'{task_id} {field}')
    missing = sorted(task_ids - seen)
    extra = sorted(seen - task_ids)
    if missing or extra or len(seen) != len(task_ids):
        raise ValueError(f'Validator registry coverage mismatch: missing={missing}, extra={extra}')
    return seen, implementations


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path, help='staged release directory')
    parser.add_argument('--require-validators', action='store_true')
    parser.add_argument('--validator-registry', type=Path,
                        help='private registry allowing shared implementations with per-task tests')
    parser.add_argument('--expected-task-count', type=int, default=170,
                        help='expected task count (default: 170; useful for fixture tests)')
    args = parser.parse_args()
    root = args.source.resolve()
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8-sig'))
    tasks = [path for path in (root / 'tasks').iterdir() if path.is_dir()]
    task_ids = [task.name for task in tasks]
    assert len(tasks) == manifest['task_count'] == args.expected_task_count
    assert len(set(task_ids)) == len(task_ids)
    numbers = []
    validator_count = 0
    validator_tasks = set()
    public_attachment_count = 0
    empty_public_attachment_paths = []
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
        public = task / 'public'
        for path in public.rglob('*'):
            if path.is_file() and 'attachments' in path.relative_to(public).parts:
                public_attachment_count += 1
                if path.stat().st_size == 0:
                    empty_public_attachment_paths.append(
                        f'tasks/{task.name}/public/{path.relative_to(public).as_posix()}'
                    )
    assert sorted(numbers) == list(range(1, args.expected_task_count + 1))
    missing = sorted(set(task_ids) - validator_tasks)
    validator_mode = 'task-local'
    implementation_count = validator_count
    if args.validator_registry:
        registry_tasks, implementations = load_validator_registry(
            args.validator_registry.resolve(), root, set(task_ids))
        validator_tasks = registry_tasks
        validator_count = len(registry_tasks)
        missing = sorted(set(task_ids) - validator_tasks)
        validator_mode = 'registry'
        implementation_count = len(implementations)
    result = {
        'suiteId': manifest['suite_id'],
        'taskCount': len(tasks),
        'validatorCount': validator_count,
        'validatorCoverage': validator_count / len(tasks),
        'validatorMode': validator_mode,
        'validatorImplementationCount': implementation_count,
        'missingValidatorTaskIds': missing,
        'publicAttachmentCount': public_attachment_count,
        'emptyPublicAttachmentCount': len(empty_public_attachment_paths),
        'emptyPublicAttachmentPaths': sorted(empty_public_attachment_paths),
        'executionReady': validator_count == len(tasks) and not empty_public_attachment_paths,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_validators and (missing or empty_public_attachment_paths):
        raise SystemExit(
            'release gate failed: '
            f'{len(missing)} task validators missing, '
            f'{len(empty_public_attachment_paths)} public attachments empty'
        )


if __name__ == '__main__':
    main()
