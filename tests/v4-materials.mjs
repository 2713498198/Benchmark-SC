import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, mkdir, writeFile, readFile, rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const root = await mkdtemp(path.join(os.tmpdir(), 'cryptobench-v4-materials-'));
const materials = path.join(root, 'private-materials');
const validatorRoot = path.join(root, 'private-validators');
const publicPath = path.join(root, 'public.json');
const manifestPath = path.join(root, 'manifest.json');
const registryPath = path.join(root, 'validator-registry.json');
const validator = path.resolve('scripts/validate-v4-materials.mjs');
const hash = value => createHash('sha256').update(value).digest('hex');
const run = (withRegistry = false) => spawnSync(process.execPath, [validator, manifestPath,
  '--public-catalog', publicPath, '--materials-root', materials,
  ...(withRegistry ? ['--validator-registry', registryPath, '--validator-root', validatorRoot] : [])], {encoding: 'utf8'});

try {
  await mkdir(path.join(materials, '001'), {recursive: true});
  await mkdir(path.join(materials, '002'), {recursive: true});
  await mkdir(path.join(validatorRoot, 'validators'), {recursive: true});
  await mkdir(path.join(validatorRoot, 'tests'), {recursive: true});
  await writeFile(path.join(validatorRoot, 'validators', 'shared.py'), 'def validate(): pass');
  for (const file of ['001-positive.txt', '001-negative.txt', '001-boundary.txt',
    '002-positive.txt', '002-negative.txt', '002-boundary.txt']) {
    await writeFile(path.join(validatorRoot, 'tests', file), 'fixture');
  }
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
      {id: '001', verifierRef: 'validators/shared.py#validate', materials: [{kind: 'oracle', path: '001/oracle.bin', sha256: hash(oracle)}]},
      {id: '002', verifierRef: 'validators/shared.py#validate', materials: [{kind: 'answer', path: '002/answer.json', sha256: hash(answer)}]}
    ]
  }));
  await writeFile(registryPath, JSON.stringify({
    schema: 'cryptobench-v4-validator-registry-v1',
    tasks: [
      {task_id: '001', validator_ref: 'validators/shared.py#validate', positive_tests: ['tests/001-positive.txt'],
        negative_tests: ['tests/001-negative.txt'], boundary_tests: ['tests/001-boundary.txt']},
      {task_id: '002', validator_ref: 'validators/shared.py#validate', positive_tests: ['tests/002-positive.txt'],
        negative_tests: ['tests/002-negative.txt'], boundary_tests: ['tests/002-boundary.txt']}
    ]
  }));
  const valid = run();
  assert.equal(valid.status, 0, valid.stderr);
  assert.equal(JSON.parse(valid.stdout).materialCount, 2);

  const registryValid = run(true);
  assert.equal(registryValid.status, 0, registryValid.stderr);
  assert.equal(JSON.parse(registryValid.stdout).validatorRegistry.implementationCount, 1);

  const registryMismatch = JSON.parse(await readFile(registryPath, 'utf8'));
  registryMismatch.tasks[1].validator_ref = 'validators/other.py#validate';
  await writeFile(registryPath, JSON.stringify(registryMismatch));
  const registryInvalid = run(true);
  assert.notEqual(registryInvalid.status, 0);
  assert.match(`${registryInvalid.stdout}\n${registryInvalid.stderr}`, /reference mismatch/i);

  await writeFile(path.join(materials, '002', 'answer.json'), 'tampered');
  const tampered = run();
  assert.notEqual(tampered.status, 0);
  assert.match(`${tampered.stdout}\n${tampered.stderr}`, /hash mismatch/i);
} finally {
  await rm(root, {recursive: true, force: true});
}

console.log('V4 material manifest checks passed.');
