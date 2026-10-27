import assert from 'node:assert/strict';
import {mkdtemp, writeFile, rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const root = await mkdtemp(path.join(os.tmpdir(), 'cryptobench-evaluator-'));
const validator = path.resolve('scripts/validate-evaluator-catalog.mjs');
const publicPath = path.join(root, 'public.json');
const privatePath = path.join(root, 'private.json');

try {
  await writeFile(publicPath, JSON.stringify({
    dataset: {id: 'fixture-dataset', version: '1.0.0'},
    tasks: [{task_id: 'task-a'}, {task_id: 'task-b'}]
  }));
  const privateCatalog = {
    datasetId: 'fixture-dataset',
    version: '1.0.0',
    evaluatorVersion: 'fixture-evaluator-1',
    tasks: [
      {id: 'task-a', question: 'Question A', expectedAnswer: 'answer-a'},
      {id: 'task-b', question: 'Question B', expectedAnswer: 'answer-b'}
    ]
  };
  await writeFile(privatePath, JSON.stringify(privateCatalog));

  const valid = spawnSync(process.execPath, [validator, privatePath, 'fixture-dataset', '1.0.0',
    '--public-catalog', publicPath], {encoding: 'utf8'});
  assert.equal(valid.status, 0, valid.stderr);
  const summary = JSON.parse(valid.stdout);
  assert.equal(summary.coverageComplete, true);
  assert.equal(summary.publicTaskCount, 2);

  await writeFile(privatePath, JSON.stringify({...privateCatalog, tasks: [privateCatalog.tasks[0]]}));
  const incomplete = spawnSync(process.execPath, [validator, privatePath, 'fixture-dataset', '1.0.0',
    '--public-catalog', publicPath], {encoding: 'utf8'});
  assert.notEqual(incomplete.status, 0);
  assert.match(`${incomplete.stdout}\n${incomplete.stderr}`, /coverage mismatch/i);

  await writeFile(publicPath, JSON.stringify({
    dataset: {id: 'fixture-dataset', version: '1.0.0'},
    tasks: [{task_id: 'task-a'}, {task_id: 'task-a'}]
  }));
  await writeFile(privatePath, JSON.stringify(privateCatalog));
  const duplicatePublic = spawnSync(process.execPath, [validator, privatePath, 'fixture-dataset', '1.0.0',
    '--public-catalog', publicPath], {encoding: 'utf8'});
  assert.notEqual(duplicatePublic.status, 0);
  assert.match(`${duplicatePublic.stdout}\n${duplicatePublic.stderr}`, /duplicate public task id/i);
} finally {
  await rm(root, {recursive: true, force: true});
}

console.log('Evaluator catalog coverage checks passed.');
