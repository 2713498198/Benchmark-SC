"""Exercise the restricted public-attachment overlay import path."""

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
IMPORTER = ROOT / 'scripts' / 'import_cryptobench.py'
TASK_ID = '001_TEST'


def make_source(root: Path, attachment: bytes) -> None:
    task = root / 'tasks' / TASK_ID / 'public' / 'attachments'
    task.mkdir(parents=True)
    (root / 'tasks' / TASK_ID / 'public' / 'question.md').write_text('# Test\n', encoding='utf-8')
    (task / 'input.json').write_bytes(attachment)
    manifest = {
        'suite_id': 'TEST-SUITE',
        'created_at': '2026-01-01',
        'task_count': 1,
        'tasks': [{
            'task_id': TASK_ID,
            'directory_name': TASK_ID,
            'source_task_id': TASK_ID,
            'question_number': 1,
            'title': 'Test',
            'cipher': 'Test',
            'difficulty': 'easy',
            'budget_class': 'easy',
            'task_family': 'test',
            'attack_type': 'test',
            'is_aggregate_stress': False,
        }],
    }
    (root / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')


def run_import(source: Path, output: Path, overlay: Path | None = None) -> subprocess.CompletedProcess:
    command = [sys.executable, str(IMPORTER), str(source), '--version', '4.1.0',
               '--output-dir', str(output)]
    if overlay is not None:
        command.extend(['--public-overlay', str(overlay)])
    return subprocess.run(command, capture_output=True, text=True)


with tempfile.TemporaryDirectory(prefix='cryptobench-overlay-') as temporary:
    root = Path(temporary)
    source = root / 'source'
    output = root / 'output'
    overlay = root / 'overlay'
    make_source(source, b'')

    replacement = overlay / 'tasks' / TASK_ID / 'public' / 'attachments'
    replacement.mkdir(parents=True)
    replacement_file = replacement / 'input.json'
    replacement_file.write_bytes(b'{"public":true}\n')

    imported = run_import(source, output, overlay)
    assert imported.returncode == 0, imported.stderr
    catalog = json.loads((output / 'catalog.json').read_text(encoding='utf-8'))
    file_entry = next(file for file in catalog['tasks'][0]['files']
                      if file['name'] == 'attachments/input.json')
    assert file_entry['name'] == 'attachments/input.json'
    assert file_entry['bytes'] == replacement_file.stat().st_size
    assert file_entry['sha256'] == hashlib.sha256(replacement_file.read_bytes()).hexdigest()
    with ZipFile(output / 'public-tasks.zip') as archive:
        assert archive.read(f'tasks/{TASK_ID}/public/attachments/input.json') == replacement_file.read_bytes()

    extra = root / 'extra-overlay'
    extra_file = extra / 'tasks' / TASK_ID / 'public'
    extra_file.mkdir(parents=True)
    (extra_file / 'question.md').write_text('replacement', encoding='utf-8')
    rejected = run_import(source, root / 'rejected-output', extra)
    assert rejected.returncode != 0
    assert 'attachments' in (rejected.stderr + rejected.stdout)

    conflict_source = root / 'conflict-source'
    make_source(conflict_source, b'source-already-present')
    conflict = run_import(conflict_source, root / 'conflict-output', overlay)
    assert conflict.returncode != 0
    assert 'empty source attachment' in (conflict.stderr + conflict.stdout)

print('PASS: public overlay replaces only empty attachments and rejects extra public files.')
