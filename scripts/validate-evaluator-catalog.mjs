import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

const positional = [];
let publicCatalogPath;
for (let index = 2; index < process.argv.length; index += 1) {
  const argument = process.argv[index];
  if (argument === '--public-catalog') {
    publicCatalogPath = process.argv[++index];
    if (!publicCatalogPath) throw new Error('--public-catalog requires a file path.');
  } else {
    positional.push(argument);
  }
}

const [catalogPath, expectedDataset, expectedVersion] = positional;
if (!catalogPath) {
  console.error('Usage: node scripts/validate-evaluator-catalog.mjs <private-tasks.json> [datasetId] [version] [--public-catalog public-catalog.json]');
  process.exit(2);
}

const absolutePath = path.resolve(catalogPath);
const normalizedPath = absolutePath.replaceAll('\\', '/').toLowerCase();
if (normalizedPath.includes('/resources/static/')) {
  throw new Error('Evaluator catalogs must stay outside resources/static.');
}

async function readJson(file, label) {
  try {
    return JSON.parse(await readFile(path.resolve(file), 'utf8'));
  } catch (error) {
    throw new Error(`Cannot read ${label}: ${error.message}`);
  }
}

const root = await readJson(absolutePath, 'evaluator catalog');
let publicRoot;
if (publicCatalogPath) publicRoot = await readJson(publicCatalogPath, 'public catalog');

function requiredText(value, label) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${label} must be a non-empty string.`);
  return value.trim();
}

const datasetId = requiredText(root.datasetId, 'datasetId');
const version = requiredText(root.version, 'version');
const evaluatorVersion = requiredText(root.evaluatorVersion, 'evaluatorVersion');
if (expectedDataset && datasetId !== expectedDataset) throw new Error(`datasetId mismatch: expected ${expectedDataset}, got ${datasetId}.`);
if (expectedVersion && version !== expectedVersion) throw new Error(`version mismatch: expected ${expectedVersion}, got ${version}.`);
if (!Array.isArray(root.tasks) || root.tasks.length === 0) throw new Error('tasks must be a non-empty array.');

const ids = new Set();
for (const [index, task] of root.tasks.entries()) {
  const prefix = `tasks[${index}]`;
  const id = requiredText(task?.id, `${prefix}.id`);
  requiredText(task?.question, `${prefix}.question`);
  if (ids.has(id)) throw new Error(`Duplicate task id: ${id}`);
  ids.add(id);
  if (task.publicFiles !== undefined && !Array.isArray(task.publicFiles)) {
    throw new Error(`${prefix}.publicFiles must be an array when present.`);
  }
  for (const file of task.publicFiles || []) {
    if (typeof file !== 'string' || !file.trim() || path.posix.isAbsolute(file) || file.includes('..')) {
      throw new Error(`${prefix}.publicFiles contains an unsafe path.`);
    }
    const lower = file.toLowerCase();
    if (lower.includes('hidden') || lower.includes('oracle') || lower.includes('answer')) {
      throw new Error(`${prefix}.publicFiles must not expose hidden verification material.`);
    }
  }
}

let coverageComplete;
if (publicRoot) {
  const publicDataset = publicRoot.dataset || publicRoot;
  const publicDatasetId = requiredText(publicDataset.id || publicRoot.datasetId, 'public catalog dataset id');
  const publicVersion = requiredText(publicDataset.version || publicRoot.version, 'public catalog version');
  if (publicDatasetId !== datasetId || publicVersion !== version) {
    throw new Error(`Public catalog identity mismatch: expected ${datasetId}@${version}, got ${publicDatasetId}@${publicVersion}.`);
  }
  if (!Array.isArray(publicRoot.tasks) || publicRoot.tasks.length === 0) {
    throw new Error('public catalog tasks must be a non-empty array.');
  }
  const publicIds = new Set();
  for (const [index, task] of publicRoot.tasks.entries()) {
    const id = requiredText(task?.id || task?.task_id, `public.tasks[${index}].id`);
    if (publicIds.has(id)) throw new Error(`Duplicate public task id: ${id}`);
    publicIds.add(id);
  }
  const missing = [...publicIds].filter(id => !ids.has(id));
  const extra = [...ids].filter(id => !publicIds.has(id));
  if (missing.length || extra.length) {
    const parts = [];
    if (missing.length) parts.push(`missing ${missing.slice(0, 10).join(', ')}${missing.length > 10 ? '…' : ''}`);
    if (extra.length) parts.push(`unexpected ${extra.slice(0, 10).join(', ')}${extra.length > 10 ? '…' : ''}`);
    throw new Error(`Task coverage mismatch (${parts.join('; ')}).`);
  }
  coverageComplete = true;
}

const digest = createHash('sha256').update(await readFile(absolutePath)).digest('hex');
console.log(JSON.stringify({datasetId, version, evaluatorVersion, taskCount: root.tasks.length,
  ...(coverageComplete === undefined ? {} : {publicTaskCount: publicRoot.tasks.length, coverageComplete}),
  catalogSha256: digest}));
