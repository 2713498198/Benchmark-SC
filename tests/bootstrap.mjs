// Integration coverage for tool preparation. The first run downloads the pinned
// JDK and Maven; subsequent cases must work without network access.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {cp, mkdir, mkdtemp, readFile, readdir, writeFile, chmod} from 'node:fs/promises';
import {createServer} from 'node:http';
import path from 'node:path';

const windows = process.platform === 'win32';
await mkdir('tmp', {recursive: true});
const root = path.resolve(await mkdtemp('tmp/bootstrap space 中文-'));
for (const file of ['start.cmd', 'start.sh', 'mvnw', 'mvnw.cmd', 'scripts/java-env.ps1', 'scripts/java-env.sh',
  'scripts/run.ps1', 'scripts/jdk-distributions.tsv', '.mvn/wrapper/maven-wrapper.properties', '.mvn/maven.config']) {
  await mkdir(path.dirname(path.join(root, file)), {recursive: true});
  await cp(file, path.join(root, file));
}
let commandNumber = 0;
function run(command, args, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, windowsHide: true, env: {...process.env, ...env}, stdio: ['ignore', 'pipe', 'pipe']});
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    child.on('error', reject);
    child.on('close', async code => {
      await writeFile(path.join(root, `command-${++commandNumber}.log`), output);
      resolve({code, output});
    });
  });
}
// Suppress discovery of host-installed JDKs, leaving real executable validation,
// download, SHA verification, extraction and Wrapper execution intact.
await writeFile(path.join(root, 'probe.ps1'), `
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/scripts/java-env.ps1"
function Get-Command { param($Name) if ($Name -eq 'java.exe') { return $null }; Microsoft.PowerShell.Core/Get-Command $Name }
try {
  Initialize-CryptoBenchJava $PSScriptRoot
  & "$PSScriptRoot/mvnw.cmd" --version
  exit $LASTEXITCODE
} catch { Write-Host $_.Exception.Message; exit 1 }
`);
await writeFile(path.join(root, 'probe.sh'), `#!/usr/bin/env bash
set -euo pipefail
source ./scripts/java-env.sh
command() { if [[ "$*" == '-v java' ]]; then return 1; else builtin command "$@"; fi; }
function /usr/libexec/java_home() { return 1; }
initialize_cryptobench_java "$PWD"
sh ./mvnw --version
`);
const command = windows ? 'powershell.exe' : 'bash';
const probeArgs = windows ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'probe.ps1')] : ['probe.sh'];
const probe = env => run(command, probeArgs, {JAVA_HOME: '', CRYPTOBENCH_NO_JDK_DOWNLOAD: '', ...env});

const missing = await probe({CRYPTOBENCH_NO_JDK_DOWNLOAD: '1'});
assert.notEqual(missing.code, 0);
assert.match(missing.output, /JDK 21 was not found/);

const badHome = path.join(root, 'wrong java 17');
await mkdir(path.join(badHome, 'bin'), {recursive: true});
if (windows) {
  await writeFile(path.join(root, 'make-old-java.ps1'), `Add-Type -TypeDefinition 'public class OldJava { public static void Main() { System.Console.WriteLine("openjdk 17.0.1"); } }' -OutputAssembly "$PSScriptRoot/wrong java 17/bin/java.exe" -OutputType ConsoleApplication`);
  const compiled = await run(command, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'make-old-java.ps1']);
  assert.equal(compiled.code, 0, compiled.output);
  await cp(path.join(badHome, 'bin/java.exe'), path.join(badHome, 'bin/javac.exe'));
} else {
  for (const name of ['java', 'javac']) {
    await writeFile(path.join(badHome, 'bin', name), `#!/bin/sh\necho '${name === 'java' ? 'openjdk' : 'javac'} 17.0.1'\n`);
    await chmod(path.join(badHome, 'bin', name), 0o755);
  }
}
const wrong = await probe({JAVA_HOME: badHome, CRYPTOBENCH_NO_JDK_DOWNLOAD: '1'});
assert.notEqual(wrong.code, 0);
assert.match(wrong.output, /JDK 21 was not found/);

// A corrupt response must fail before extraction or execution.
const manifestPath = path.join(root, 'scripts/jdk-distributions.tsv');
const manifest = await readFile(manifestPath, 'utf8');
const server = createServer((request, response) => { response.end('invalid archive'); });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
try {
  const tampered = manifest.replace(/https:\/\/[^\t\n]+/g, `http://127.0.0.1:${server.address().port}/jdk`);
  await writeFile(manifestPath, tampered);
  const corrupt = await probe({HTTPS_PROXY: '', https_proxy: '', HTTP_PROXY: '', http_proxy: '', ALL_PROXY: '', all_proxy: ''});
  assert.notEqual(corrupt.code, 0);
  assert.match(corrupt.output, /SHA-256 verification failed/);
  assert.deepEqual((await readdir(path.join(root, '.tools'))).filter(name => name.startsWith('.jdk-download-')), []);
} finally {
  await new Promise(resolve => server.close(resolve));
  await writeFile(manifestPath, manifest);
}
console.log('PASS absent JDK, Java 17 rejection, checksum rejection and staging cleanup');

const prepared = await probe({JAVA_HOME: badHome});
assert.equal(prepared.code, 0, prepared.output);
assert.match(prepared.output, /Apache Maven 3\.9\.11/);
assert.match(prepared.output, /Java version: 21\./);
assert.match(prepared.output, /Downloading Temurin/);
const cached = await probe({CRYPTOBENCH_NO_JDK_DOWNLOAD: '1'});
assert.equal(cached.code, 0, cached.output);
assert.doesNotMatch(cached.output, /Downloading Temurin/);

// A user-supplied valid JDK must work without a project JDK cache or download.
const cachedName = (await readdir(path.join(root, '.tools'))).find(name => name.startsWith('temurin-'));
const javaHome = path.join(root, '.tools', cachedName, ...(process.platform === 'darwin' ? ['Contents', 'Home'] : []));
await mkdir(path.join(root, 'existing only/scripts'), {recursive: true});
await cp(manifestPath, path.join(root, 'existing only/scripts/jdk-distributions.tsv'));
const existingArgs = windows
  ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', '. ./scripts/java-env.ps1; Initialize-CryptoBenchJava (Join-Path $PWD "existing only")']
  : ['-c', 'set -euo pipefail; source ./scripts/java-env.sh; initialize_cryptobench_java "$PWD/existing only"'];
const existing = await run(command, existingArgs, {JAVA_HOME: javaHome, CRYPTOBENCH_NO_JDK_DOWNLOAD: '1'});
assert.equal(existing.code, 0, existing.output);
assert.doesNotMatch(existing.output, /Downloading Temurin/);
assert.equal((await readdir(path.join(root, 'existing only'))).includes('.tools'), false);

// Exercise the user-facing entry point from a non-ASCII, space-containing path.
const launcherArgs = windows
  ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', '& ./start.cmd -PrepareOnly; exit $LASTEXITCODE']
  : ['start.sh', '--prepare-only'];
const launch = await run(command, launcherArgs, {JAVA_HOME: badHome, CRYPTOBENCH_NO_JDK_DOWNLOAD: '1'});
assert.equal(launch.code, 0, launch.output);
assert.match(launch.output, /Apache Maven 3\.9\.11/);
console.log(`PASS download, cache reuse and launcher in ${root}`);
