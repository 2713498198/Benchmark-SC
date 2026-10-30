import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const windows = process.platform === 'win32';
const command = windows ? 'powershell.exe' : 'bash';
const args = windows
  ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', fileURLToPath(new URL('run.ps1', import.meta.url))]
  : [fileURLToPath(new URL('../start.sh', import.meta.url))];
const child = spawn(command, [...args, ...process.argv.slice(2)], {cwd: root, stdio: 'inherit', windowsHide: true});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
