import {spawn} from 'node:child_process';
import {StringDecoder} from 'node:string_decoder';

const DEFAULT_MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

export async function runDocker(args, options = {}) {
  const timeoutMs = Math.max(1000, Number(options.timeoutSeconds || 300) * 1000);
  const maxOutputBytes = Number(options.maxOutputBytes || DEFAULT_MAX_OUTPUT_BYTES);
  return new Promise((resolve, reject) => {
    let settled = false;
    let stopped = false;
    let timedOut = false;
    let stdout = '';
    let stderr = '';
    let stdoutBytes = 0;
    let stderrBytes = 0;
    const child = spawn(options.command || 'docker', args, {
      cwd: options.cwd,
      env: {...process.env, ...(options.env || {})},
      shell: false,
      windowsHide: true,
      stdio: [options.stdin == null ? 'ignore' : 'pipe', 'pipe', 'pipe'],
    });
    const stdoutDecoder = new StringDecoder('utf8');
    const stderrDecoder = new StringDecoder('utf8');

    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (error) reject(error);
      else resolve(value);
    };

    const timeout = setTimeout(() => {
      timedOut = true;
      if (!stopped) {
        stopped = true;
        child.kill('SIGTERM');
        const force = setTimeout(() => child.kill('SIGKILL'), 1000);
        force.unref?.();
      }
    }, timeoutMs);
    timeout.unref?.();

    child.on('error', error => finish(error));
    child.stdout.on('data', chunk => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > maxOutputBytes) {
        child.kill('SIGTERM');
        return finish(new Error(`Docker 输出超过 ${maxOutputBytes} 字节`));
      }
      const text = stdoutDecoder.write(chunk);
      stdout += text;
      options.onStdout?.(text);
    });
    child.stderr.on('data', chunk => {
      stderrBytes += chunk.length;
      if (stderrBytes > maxOutputBytes) {
        child.kill('SIGTERM');
        return finish(new Error(`Docker 错误输出超过 ${maxOutputBytes} 字节`));
      }
      const text = stderrDecoder.write(chunk);
      stderr += text;
      options.onStderr?.(text);
    });
    child.on('close', code => {
      stdout += stdoutDecoder.end();
      stderr += stderrDecoder.end();
      if (timedOut) return finish(Object.assign(new Error('Docker 任务超时'), {code:'agent_timeout'}));
      if (code !== 0) {
        return finish(Object.assign(
          new Error(`Docker 命令失败（${code}）：${stderr.trim().slice(-2000)}`),
          {code:'docker_failed', exitCode:code},
        ));
      }
      finish(null, {stdout, stderr, exitCode:code});
    });

    if (options.stdin == null) child.stdin?.end();
    else child.stdin.end(String(options.stdin));
  });
}

export async function dockerVersion() {
  try {
    const result = await runDocker(['version', '--format', '{{.Server.Version}}'], {timeoutSeconds:20});
    return result.stdout.trim();
  } catch (error) {
    if (error.code === 'ENOENT') {
      throw new Error('未找到 Docker。请先安装 Docker Desktop 并启动 Docker Engine');
    }
    throw new Error(`无法连接 Docker Engine：${error.message}`);
  }
}

export async function dockerImageId(image) {
  const result = await runDocker(['image', 'inspect', image, '--format', '{{.Id}}'], {timeoutSeconds:20});
  return result.stdout.trim();
}

export async function dockerImageMetadata(image) {
  const result = await runDocker(['image', 'inspect', image], {timeoutSeconds:20});
  const values = JSON.parse(result.stdout);
  if (!Array.isArray(values) || !values[0]) throw new Error(`无法读取 Docker 镜像：${image}`);
  return values[0];
}
