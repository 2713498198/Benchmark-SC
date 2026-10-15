"""Verify that the website release contains complete public files and no private materials."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
release = root / 'data' / 'cryptobench-sc-v4'
catalog_bytes = (release / 'catalog.json').read_bytes()
catalog = json.loads(catalog_bytes)
tasks = catalog['tasks']
assert len(tasks) == 127
assert len({t['task_id'] for t in tasks}) == 127
assert sorted(t['question_number'] for t in tasks) == list(range(1, 128))
assert sum(t['is_aggregate_stress'] for t in tasks) == 2
allowed = {'task_id', 'source_task_id', 'question_number', 'title', 'cipher',
           'difficulty', 'budget_class', 'task_family', 'attack_type',
           'is_aggregate_stress', 'question', 'files'}
expected = {'catalog.json'}
for task in tasks:
    assert set(task) == allowed
    question = release / 'tasks' / task['task_id'] / 'public' / 'question.md'
    assert task['question'] == question.read_text(encoding='utf-8-sig')
    for file in task['files']:
        path = root / file['url']
        rel = path.relative_to(release).as_posix()
        assert rel.startswith('tasks/' + task['task_id'] + '/public/')
        assert path.suffix in {'.md', '.json', '.txt'}
        content = path.read_bytes()
        assert file['bytes'] == len(content)
        assert file['sha256'] == hashlib.sha256(content).hexdigest()
        expected.add(rel)
assert len(expected) == 154  # 127 questions + 26 attachments + index.
with ZipFile(release / 'public-tasks.zip') as archive:
    assert set(archive.namelist()) == expected
    assert len(archive.namelist()) == len(expected)
    for name in expected:
        assert archive.read(name) == (release / name).read_bytes()
assert {p.relative_to(release).as_posix() for p in release.rglob('*') if p.is_file()} == expected | {'public-tasks.zip'}
print('PASS: 127 tasks, 26 attachments, metadata allowlist, file hashes and public ZIP contents.')
