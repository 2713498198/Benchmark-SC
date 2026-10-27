import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, mkdir, writeFile, rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const root = await mkdtemp(path.join(os.tmpdir(), 'cryptobench-v4-materials-'));
const materials = path.join(root, 'private-materials');
const publicPath = path.join(root, 'public.json');
const manifestPath = path.join(root, 'manifest.json');
const validator = path.resolve('scripts/validate-v4-materials.mjs');
const hash = value => createHash('sha256').update(value).digest('hex');
const run = () => spawnSync(process.execPath, [validator, manifestPath,
  '--public-catalog', publicPath, '--materials-root', materials], {encoding: 'utf8'});

try {
  await mkdir(path.join(materials, '001'), {recursive: true});
  await mkdir(path.join(materials, '002'), {recursive: true});
  const oracle = 'oracle-001';
  const answer = 'answer-002';
  await writeFile(path.join(materials, '001', 'oracle.bin'), oracle);
  await writeFile(path.join(materials, '002', 'answer.json'), answer);
  const publicCatalog = {dataset: {id: 'cryptobench-sc-v4', version: '4.0.0'},
    tasks: [{task_id: '001'}, {task_id: '002'}]};
  await writeFile(publicPath, JSON.stringify(publicCatalog));
  const publicCatalogSha256 = hash(JSON.stringify(publicCatalog));
  await writeFile(manifestPath, JSON.stringify({
    datasetId: 'cryptobench-sc-v4', version: '4.0.0',
    evaluatorVersion: 'cryptobench-sc-v4-evaluator-1.0.0', publicCatalogSha256,
    tasks: [
      {id: '001', verifierRef: 'fixture.Verifier', materials: [{kind: 'oracle', path: '001/oracle.bin', sha256: hash(oracle)}]},
      {id: '002', verifierRef: 'fixture.Verifier', materials: [{kind: 'answer', path: '002/answer.json', sha256: hash(answer)}]}
    ]
  }));
  const valid = run();
  assert.equal(valid.status, 0, valid.stderr);
  assert.equal(JSON.parse(valid.stdout).materialCount, 2);

  await writeFile(path.join(materials, '002', 'answer.json'), 'tampered');
  const tampered = run();
  assert.notEqual(tampered.status, 0);
  assert.match(`${tampered.stdout}\n${tampered.stderr}`, /hash mismatch/i);
} finally {
  await rm(root, {recursive: true, force: true});
}

console.log('V4 material manifest checks passed.');
