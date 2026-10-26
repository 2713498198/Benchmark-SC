import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

const [catalogPath, expectedDataset, expectedVersion] = process.argv.slice(2);
if (!catalogPath) {
  console.error('Usage: node scripts/validate-evaluator-catalog.mjs <private-tasks.json> [datasetId] [version]');
  process.exit(2);
}

const absolutePath = path.resolve(catalogPath);
const normalizedPath = absolutePath.replaceAll('\\', '/').toLowerCase();
if (normalizedPath.includes('/resources/static/')) {
  throw new Error('Evaluator catalogs must stay outside resources/static.');
}

let root;
try {
  root = JSON.parse(await readFile(absolutePath, 'utf8'));
} catch (error) {
  throw new Error(`Cannot read evaluator catalog: ${error.message}`);
}

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

const digest = createHash('sha256').update(await readFile(absolutePath)).digest('hex');
console.log(JSON.stringify({datasetId, version, evaluatorVersion, taskCount: root.tasks.length, catalogSha256: digest}));
