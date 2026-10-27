import {createHash} from 'node:crypto';
import {readFile, realpath, stat} from 'node:fs/promises';
import path from 'node:path';

const positional = [];
const options = {};
for (let index = 2; index < process.argv.length; index += 1) {
  const argument = process.argv[index];
  if (argument.startsWith('--')) {
    const key = argument.slice(2);
    const value = process.argv[++index];
    if (!value || value.startsWith('--')) throw new Error(`--${key} requires a value.`);
    options[key] = value;
  } else {
    positional.push(argument);
  }
}

const [manifestPath] = positional;
if (!manifestPath || !options['public-catalog'] || !options['materials-root']) {
  console.error('Usage: node scripts/validate-v4-materials.mjs <manifest.json> --public-catalog <catalog.json> --materials-root <private-dir>');
  process.exit(2);
}

const manifestAbsolute = path.resolve(manifestPath);
const catalogAbsolute = path.resolve(options['public-catalog']);
const materialsAbsolute = path.resolve(options['materials-root']);
const normalizedManifest = manifestAbsolute.replaceAll('\\', '/').toLowerCase();
if (normalizedManifest.includes('/resources/static/') || normalizedManifest.endsWith('/resources/static')) {
  throw new Error('Private V4 material manifest must stay outside resources/static.');
}
const normalizedMaterials = materialsAbsolute.replaceAll('\\', '/').toLowerCase();
if (normalizedMaterials.includes('/resources/static/') || normalizedMaterials.endsWith('/resources/static')) {
  throw new Error('Private V4 materials must stay outside resources/static.');
}

async function jsonFile(file, label) {
  try {
    return JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    throw new Error(`Cannot read ${label}: ${error.message}`);
  }
}

async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex');
}

function requiredText(value, label) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${label} must be a non-empty string.`);
  return value.trim();
}

function requiredHash(value, label) {
  const hash = requiredText(value, label).toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(hash)) throw new Error(`${label} must be a SHA-256 hex digest.`);
  return hash;
}

function relativePath(value, label) {
  const file = requiredText(value, label).replaceAll('\\', '/');
  if (file.startsWith('/') || /^[a-z]:\//i.test(file) || file.split('/').includes('..')) {
    throw new Error(`${label} must be a relative path inside --materials-root.`);
  }
  return file;
}

async function containedFile(root, relative, label) {
  const rootReal = await realpath(root);
  const candidate = path.resolve(rootReal, relative);
  const escaped = path.relative(rootReal, candidate);
  if (escaped.startsWith('..') || path.isAbsolute(escaped)) {
    throw new Error(`${label} escapes --materials-root.`);
  }
  let candidateReal;
  try {
    candidateReal = await realpath(candidate);
    const info = await stat(candidateReal);
    if (!info.isFile()) throw new Error('is not a regular file');
  } catch (error) {
    throw new Error(`Cannot read ${label}: ${error.message}`);
  }
  const realRelative = path.relative(rootReal, candidateReal);
  if (realRelative.startsWith('..') || path.isAbsolute(realRelative)) {
    throw new Error(`${label} resolves outside --materials-root.`);
  }
  return candidateReal;
}

const manifest = await jsonFile(manifestAbsolute, 'V4 material manifest');
const catalog = await jsonFile(catalogAbsolute, 'public V4 catalog');
const dataset = catalog.dataset || catalog;
const datasetId = requiredText(manifest.datasetId, 'manifest.datasetId');
const version = requiredText(manifest.version, 'manifest.version');
const evaluatorVersion = requiredText(manifest.evaluatorVersion, 'manifest.evaluatorVersion');
if (datasetId !== requiredText(dataset.id || catalog.datasetId, 'public catalog dataset id')
    || version !== requiredText(dataset.version || catalog.version, 'public catalog version')) {
  throw new Error(`Manifest and public catalog identity differ: ${datasetId}@${version}.`);
}
if (!Array.isArray(catalog.tasks) || catalog.tasks.length === 0) throw new Error('Public V4 catalog has no tasks.');
if (!Array.isArray(manifest.tasks) || manifest.tasks.length === 0) throw new Error('manifest.tasks must be a non-empty array.');

const publicIds = new Set();
for (const [index, task] of catalog.tasks.entries()) {
  const id = requiredText(task?.id || task?.task_id, `public.tasks[${index}].id`);
  if (publicIds.has(id)) throw new Error(`Duplicate public task id: ${id}`);
  publicIds.add(id);
}
const privateIds = new Set();
const materialPaths = new Set();
let materialCount = 0;
for (const [index, task] of manifest.tasks.entries()) {
  const label = `manifest.tasks[${index}]`;
  const id = requiredText(task?.id, `${label}.id`);
  if (privateIds.has(id)) throw new Error(`Duplicate private task id: ${id}`);
  privateIds.add(id);
  requiredText(task?.verifierRef, `${label}.verifierRef`);
  if (!Array.isArray(task.materials) || task.materials.length === 0) {
    throw new Error(`${label}.materials must contain hidden verification files.`);
  }
  for (const [materialIndex, material] of task.materials.entries()) {
    const materialLabel = `${label}.materials[${materialIndex}]`;
    const kind = requiredText(material?.kind, `${materialLabel}.kind`).toLowerCase();
    if (!['hidden', 'oracle', 'answer', 'reference'].includes(kind)) {
      throw new Error(`${materialLabel}.kind is invalid.`);
    }
    const relative = relativePath(material?.path, `${materialLabel}.path`);
    if (materialPaths.has(relative)) throw new Error(`Duplicate private material path: ${relative}`);
    materialPaths.add(relative);
    const expected = requiredHash(material?.sha256, `${materialLabel}.sha256`);
    const file = await containedFile(materialsAbsolute, relative, materialLabel);
    const actual = await sha256(file);
    if (actual !== expected) throw new Error(`${materialLabel} hash mismatch.`);
    materialCount += 1;
  }
}
const missing = [...publicIds].filter(id => !privateIds.has(id));
const extra = [...privateIds].filter(id => !publicIds.has(id));
if (missing.length || extra.length) {
  const details = [];
  if (missing.length) details.push(`missing ${missing.slice(0, 10).join(', ')}${missing.length > 10 ? '…' : ''}`);
  if (extra.length) details.push(`unexpected ${extra.slice(0, 10).join(', ')}${extra.length > 10 ? '…' : ''}`);
  throw new Error(`V4 task coverage mismatch (${details.join('; ')}).`);
}

const publicCatalogSha256 = requiredHash(manifest.publicCatalogSha256, 'manifest.publicCatalogSha256');
const actualCatalogSha256 = await sha256(catalogAbsolute);
if (actualCatalogSha256 !== publicCatalogSha256) throw new Error('Public catalog SHA-256 does not match manifest.');
console.log(JSON.stringify({datasetId, version, evaluatorVersion, taskCount: privateIds.size,
  materialCount, publicCatalogSha256: actualCatalogSha256,
  manifestSha256: await sha256(manifestAbsolute)}));
