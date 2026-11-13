"""Import only the public portion of a CryptoBench-SC release into this site."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

SITE = Path(__file__).resolve().parents[1] / 'src' / 'main' / 'resources' / 'static'
DATASET_ID = 'cryptobench-sc-v4'
FIELDS = ('task_id', 'source_task_id', 'question_number', 'title', 'cipher',
          'difficulty', 'budget_class', 'task_family', 'attack_type', 'is_aggregate_stress')
ALLOWED_PUBLIC_SUFFIXES = {'.md', '.json', '.txt', '.pdf', '.csv'}


def load_public_overlay(overlay_root, task_ids, source_files):
    """Return a safe replacement map for empty public attachments.

    A correction package is deliberately narrower than the source release: it
    may only contain ``tasks/<id>/public/attachments/<file>`` entries that are
    already present and empty in the source.  This prevents an overlay from
    adding questions, replacing non-empty files, or smuggling private material
    into the generated public package.
    """
    overlay_root = overlay_root.resolve()
    if overlay_root.is_symlink() or not overlay_root.is_dir():
        raise ValueError('Public overlay must be a real directory: ' + str(overlay_root))
    tasks_root = overlay_root / 'tasks'
    if not tasks_root.is_dir() or tasks_root.is_symlink():
        raise ValueError('Public overlay must contain a real tasks directory')

    replacements = {}
    for path in tasks_root.rglob('*'):
        if path.is_dir():
            if path.is_symlink():
                raise ValueError('Public overlay directories must not be symlinks: ' + str(path))
            continue
        relative = path.relative_to(overlay_root)
        parts = relative.parts
        if len(parts) < 5 or parts[0] != 'tasks' or parts[2] != 'public' or parts[3] != 'attachments':
            raise ValueError('Overlay may only contain tasks/<id>/public/attachments files: '
                             + relative.as_posix())
        task_id = parts[1]
        if task_id not in task_ids:
            raise ValueError('Overlay references an unknown task: ' + task_id)
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(overlay_root):
            raise ValueError('Overlay file escapes its root: ' + relative.as_posix())
        if path.suffix.lower() not in ALLOWED_PUBLIC_SUFFIXES or any(part.startswith('.') for part in parts[3:]):
            raise ValueError('Unexpected overlay file: ' + relative.as_posix())
        key = relative.as_posix()
        if key in replacements:
            raise ValueError('Duplicate overlay file: ' + key)
        if key not in source_files:
            raise ValueError('Overlay file is not an existing source attachment: ' + key)
        if source_files[key]:
            raise ValueError('Overlay may only replace an empty source attachment: ' + key)
        content = path.read_bytes()
        if not content:
            raise ValueError('Overlay attachment is empty: ' + key)
        replacements[key] = content
    return replacements


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Directory containing manifest.json and tasks/')
    parser.add_argument('--dataset-id', default=DATASET_ID,
                        help=f'Published dataset ID (default: {DATASET_ID})')
    parser.add_argument('--version', default='4.0.0',
                        help='Immutable published version (default: 4.0.0)')
    parser.add_argument('--output-dir', type=Path,
                        help='Explicit public output directory; defaults to a versioned site path')
    parser.add_argument('--public-overlay', type=Path,
                        help=('Optional correction directory containing only '
                              'tasks/<id>/public/attachments files that replace empty source attachments'))
    args = parser.parse_args()
    source = args.source.resolve()
    dataset_id = args.dataset_id.strip()
    version = args.version.strip()
    public_prefix = f'data/{dataset_id}/{version}' if version != '4.0.0' else f'data/{dataset_id}'
    if not re.fullmatch(r'[a-z][a-z0-9-]{1,60}', dataset_id):
        raise ValueError('Unsafe dataset ID: ' + dataset_id)
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Version must use semantic versioning: ' + version)
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8-sig'))
    tasks = manifest['tasks']
    if len(tasks) != manifest['task_count'] or len({t['task_id'] for t in tasks}) != len(tasks):
        raise ValueError('Manifest task count or unique task IDs do not match')
    # Validate all inputs before publishing any files. Never traverse hidden/ or oracle/.
    entries, public_files, file_records, empty_attachment_keys = [], {}, {}, set()
    task_ids = {task['task_id'] for task in tasks}
    for task in tasks:
        task_id = task['task_id']
        if not re.fullmatch(r'[A-Za-z0-9_-]+', task_id) or task['directory_name'] != task_id:
            raise ValueError('Unsafe or mismatched task directory: ' + task_id)
        public = source / 'tasks' / task_id / 'public'
        if public.is_symlink():
            raise ValueError('Public directories must not be symlinks')
        question = public / 'question.md'
        if not question.is_file():
            raise ValueError('Missing public question: ' + task_id)
        entry = {k: task[k] for k in FIELDS}
        entry['question'] = question.read_text(encoding='utf-8-sig')
        entry['files'] = []
        for file in sorted(public.rglob('*')):
            if not file.is_file():
                continue
            if file.is_symlink() or not file.resolve().is_relative_to(public.resolve()):
                raise ValueError('File escapes public directory: ' + str(file))
            rel = file.relative_to(public).as_posix()
            if file.suffix.lower() not in ALLOWED_PUBLIC_SUFFIXES or any(p.startswith('.') for p in file.relative_to(public).parts):
                raise ValueError('Unexpected public file: ' + rel)
            content = file.read_bytes()
            key = f'tasks/{task_id}/public/{rel}'
            if 'attachments' in file.relative_to(public).parts and not content:
                empty_attachment_keys.add(key)
            public_files[key] = content
            entry['files'].append({'name': rel, 'url': f'{public_prefix}/{key}',
                                   'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
            file_records[key] = entry['files'][-1]
        entries.append(entry)
    if args.public_overlay:
        replacements = load_public_overlay(args.public_overlay, task_ids, public_files)
        for key, content in replacements.items():
            public_files[key] = content
            file_records[key]['bytes'] = len(content)
            file_records[key]['sha256'] = hashlib.sha256(content).hexdigest()
        empty_attachment_keys.difference_update(replacements)
    if empty_attachment_keys:
        sample = sorted(empty_attachment_keys)
        suffix = '' if len(sample) <= 5 else f' (and {len(sample) - 5} more)'
        raise ValueError('Empty public attachment(s): ' + ', '.join(sample[:5]) + suffix)
    aggregate_stress_tasks = sum(bool(task.get('is_aggregate_stress')) for task in tasks)
    commit = None
    toplevel = subprocess.run(['git', '-C', str(source), 'rev-parse', '--show-toplevel'],
                              capture_output=True, text=True).stdout.strip()
    if toplevel and Path(toplevel).resolve() == source:
        commit = subprocess.run(['git', '-C', str(source), 'rev-parse', 'HEAD'],
                                capture_output=True, text=True).stdout.strip() or None
    dataset = dict(id=dataset_id, name=f'CryptoBench-SC V4 · {len(entries)} 题', subtitle='BLOCK CIPHER CRYPTANALYSIS',
                   type='分组密码', algorithm='多算法 · AES / PRESENT / GIFT / SPECK 等', tasks=len(entries),
                   version=version, difficulty='入门 → 高难度', tags=['真实题库', '分组密码分析', '公开任务'],
                   description=f'{len(entries)} 道密码分析任务，覆盖差分、线性、积分、相关密钥与证明任务。支持浏览题干和下载公开附件。',
                   icon='layers', color='blue', published=True, demo=False, executionReady=False,
                   suiteId=manifest['suite_id'], releaseDate=manifest['created_at'],
                   sourceUrl='https://gitee.com/xiandianzidaxue/cryptobench-sc', sourceCommit=commit,
                   catalogUrl=f'{public_prefix}/catalog.json', downloadUrl=f'{public_prefix}/public-tasks.zip',
                   scoring={'modes': ['final_only', 'process_plus_final'], 'finalWeight': 0.65,
                            'processWeight': 0.35, 'passThreshold': 0.99},
                   aggregateStressTasks=aggregate_stress_tasks)
    catalog = {'schema': 'cryptobench-public-catalog-v1', 'dataset': dataset, 'tasks': entries}
    encoded = (json.dumps(catalog, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    target = (args.output_dir.resolve() if args.output_dir else
              SITE / 'data' / dataset_id / (version if version != '4.0.0' else ''))
    target.mkdir(parents=True, exist_ok=True)
    for rel, content in public_files.items():
        dest = target / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(content)
    (target / 'catalog.json').write_bytes(encoded)
    with ZipFile(target / 'public-tasks.zip', 'w', compression=ZIP_DEFLATED) as archive:
        release_date = manifest.get('created_at', '2026-01-01')
        year, month, day = (int(part) for part in release_date.split('-', 2))
        for rel, content in {'catalog.json': encoded, **public_files}.items():
            info = ZipInfo(rel, date_time=(year, month, day, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, content)
    metadata_name = 'cryptobench-v4.js' if version == '4.0.0' else f'cryptobench-v4-{version.replace(".", "-")}.js'
    export_name = 'cryptobenchV4' if version == '4.0.0' else f'cryptobenchV4_{version.replace(".", "_")}'
    (SITE / 'js' / metadata_name).write_text(
        '// Generated by scripts/import_cryptobench.py. Public metadata only.\nexport const ' + export_name + ' = '
        + json.dumps(dataset, ensure_ascii=False, indent=2) + ';\n', encoding='utf-8')
    print(f'Imported {len(entries)} tasks and {len(public_files)} public files into {target}')


if __name__ == '__main__':
    main()
