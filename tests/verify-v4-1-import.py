"""Verify the generated public CryptoBench-SC V4.1 release."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1] / 'src' / 'main' / 'resources' / 'static'
RELEASE = ROOT / 'data' / 'cryptobench-sc-v4' / '4.1.0'
CATALOG = json.loads((RELEASE / 'catalog.json').read_text(encoding='utf-8'))
DATASET = CATALOG['dataset']
TASKS = CATALOG['tasks']

assert DATASET['id'] == 'cryptobench-sc-v4'
assert DATASET['version'] == '4.1.0'
assert DATASET['tasks'] == len(TASKS) == 170
assert DATASET['suiteId'] == 'CBSC-V4-COMPREHENSIVE-BLOCK-CIPHER-170-20260805'
assert sorted(task['question_number'] for task in TASKS) == list(range(1, 171))
assert len({task['task_id'] for task in TASKS}) == 170
assert DATASET['executionReady'] is False

allowed = {'task_id', 'source_task_id', 'question_number', 'title', 'cipher',
           'difficulty', 'budget_class', 'task_family', 'attack_type',
           'is_aggregate_stress', 'question', 'files'}
expected = {'catalog.json'}
allowed_suffixes = {'.md', '.json', '.txt', '.pdf', '.csv'}
empty_attachments = []
for task in TASKS:
    assert set(task) == allowed
    task_root = RELEASE / 'tasks' / task['task_id'] / 'public'
    question = task_root / 'question.md'
    assert task['question'] == question.read_text(encoding='utf-8-sig')
    for file in task['files']:
        path = ROOT / file['url']
        assert path.is_file()
        assert path.suffix.lower() in allowed_suffixes
        relative = path.relative_to(RELEASE).as_posix()
        assert relative == f"tasks/{task['task_id']}/public/{file['name']}"
        content = path.read_bytes()
        if '/public/attachments/' in file['url'].replace('\\', '/'):
            if not content:
                empty_attachments.append(file['url'])
        assert file['bytes'] == len(content)
        assert file['sha256'] == hashlib.sha256(content).hexdigest()
        expected.add(relative)

with ZipFile(RELEASE / 'public-tasks.zip') as archive:
    assert set(archive.namelist()) == expected
    for name in expected:
        assert archive.read(name) == (RELEASE / name).read_bytes()

actual = {path.relative_to(RELEASE).as_posix() for path in RELEASE.rglob('*') if path.is_file()}
assert actual == expected | {'public-tasks.zip'}
assert not any('hidden' in name or 'oracle' in name or '.private' in name for name in actual)
assert not empty_attachments, (
    f'public attachments are empty ({len(empty_attachments)}): '
    + ', '.join(empty_attachments)
)
print('PASS: V4.1 has 170 public tasks, hashes, ZIP parity, and no hidden material.')
