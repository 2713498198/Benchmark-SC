import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {access,mkdir,mkdtemp,readFile,readdir,realpath,rm,writeFile} from 'node:fs/promises';
import {constants as fsConstants} from 'node:fs';
import {basename,dirname,delimiter,extname,isAbsolute,join,relative,resolve,sep} from 'node:path';
import {tmpdir} from 'node:os';

const DEFAULT_TIMEOUT_SECONDS = 300;
const DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
const MAX_EVIDENCE_EVENTS = 300;
const MAX_EVIDENCE_TEXT = 8000;
const MAX_EVIDENCE_BYTES = 512 * 1024;
const MAX_ARTIFACT_FILE_BYTES = 1_500_000;
const MAX_ARTIFACT_BYTES = 4_000_000;
const ARTIFACT_SUFFIXES = new Set([
  '.md', '.txt', '.json', '.jsonl', '.csv', '.log', '.py', '.sage',
  '.c', '.cpp', '.h', '.rs', '.java', '.mzn', '.sh'
]);
const EXCLUDED_WORKSPACE_PARTS = new Set([
  '.git', '.venv', 'venv', '__pycache__', 'node_modules', 'site-packages', '.agent_runtime'
]);

/**
 * Execute an installed Agent CLI and normalize its output for the Runner API.
 * The website never provides an executable, shell command, or working directory.
 */
export async function runCommand(profile, request) {
  const config = normalizeProfile(profile, request);
  const hasTaskFiles = Array.isArray(request?.task?.files) && request.task.files.length > 0;
  const captureWorkspace = Boolean(request?.captureWorkspace) || hasTaskFiles;
  const isolated = captureWorkspace || !config.cwd;
  const workingDirectory = isolated ? await mkdtemp(join(tmpdir(), 'cryptobench-agent-')) : config.cwd;
  const started = Date.now();
  try {
    if (hasTaskFiles) await materializeTaskInputs(request, workingDirectory);
    const prompt = renderPrompt(config.promptTemplate, request, config, workingDirectory);
    const args = config.args.map(argument => renderTemplate(argument, {prompt, request, profile: config, workspace: workingDirectory}));
    const result = await spawnAgent({...config, cwd: workingDirectory}, args, prompt, request);
    const parsed = parseOutput(result.stdout, config.output);
    const secrets = [...secretValues({...process.env, ...config.env}), ...argumentSecretValues(args)];
    const evidence = normalizeEvidence(parsed.events, result.stderr, config);
    const usage = normalizeUsage(parsed.usage, Date.now() - started);
    const captured = captureWorkspace ? await collectWorkspaceArtifacts(workingDirectory) : [];
    const artifact = mergeArtifactFiles(parsed.artifact, captured);

    return redact({
      status: 'completed',
      artifact,
      evidence,
      usage,
      elapsedMs: Date.now() - started,
      meta: {
        adapter: 'command',
        executable: basename(config.command),
        argumentCount: config.args.length,
        outputFormat: config.output,
        isolatedWorkingDirectory: isolated,
        deliveredInputFiles: Array.isArray(request?.task?.files) ? request.task.files.length : 0,
        capturedOutputFiles: captured.length,
        agentVersion: config.version || 'unknown',
        model: config.model || request.model || 'unknown'
      }
    }, secrets);
  } finally {
    if (isolated) await rm(workingDirectory, {recursive:true,force:true}).catch(() => {});
  }
}

/** Resolve an Agent profile without accepting per-job command overrides from the website. */
export function normalizeProfile(profile, request = {}) {
  if (!profile || typeof profile !== 'object' || Array.isArray(profile)) {
    throw adapterError('invalid_command_profile', '命令 Adapter 配置必须是对象');
  }
  const command = String(profile.command || '').trim();
  if (!command) throw adapterError('invalid_command_profile', '命令 Adapter 缺少 command');
  if (command.includes('\0')) throw adapterError('invalid_command_profile', 'command 包含非法字符');

  const args = profile.args == null ? [] : profile.args;
  if (!Array.isArray(args) || args.some(value => typeof value !== 'string' || value.includes('\0'))) {
    throw adapterError('invalid_command_profile', 'args 必须是字符串数组');
  }

  const stdin = String(profile.stdin || 'prompt').toLowerCase();
  if (!['prompt', 'json', 'none'].includes(stdin)) {
    throw adapterError('invalid_command_profile', 'stdin 只能是 prompt、json 或 none');
  }
  const output = String(profile.output || 'auto').toLowerCase();
  if (!['auto', 'text', 'json', 'jsonl'].includes(output)) {
    throw adapterError('invalid_command_profile', 'output 只能是 auto、text、json 或 jsonl');
  }

  const requestedTimeoutMs = Number.isFinite(Number(profile.timeoutMs))
    ? Number(profile.timeoutMs)
    : Number(request.budget?.timeoutSeconds || profile.timeoutSeconds || DEFAULT_TIMEOUT_SECONDS) * 1000;
  const timeoutMs = Math.max(100, Math.min(requestedTimeoutMs, 24 * 60 * 60 * 1000));
  const maxOutputBytes = Number(profile.maxOutputBytes || DEFAULT_MAX_OUTPUT_BYTES);
  const root = profile.cwd == null || String(profile.cwd).trim() === '' ? null : String(profile.cwd);

  return {
    ...profile,
    command,
    args,
    stdin,
    output,
    cwd: root == null ? null : isAbsolute(root) ? root : resolve(process.cwd(), root),
    model: String(profile.model || request.model || 'unknown'),
    version: String(profile.version || ''),
    shellMode: process.platform === 'win32' && /\.(cmd|bat)$/i.test(command) ? 'cmd' : 'direct',
    timeoutMs,
    maxOutputBytes: Number.isFinite(maxOutputBytes)
      ? Math.max(16 * 1024, Math.min(maxOutputBytes, 32 * 1024 * 1024))
      : DEFAULT_MAX_OUTPUT_BYTES,
    promptTemplate: String(profile.promptTemplate || defaultPromptTemplate()),
    env: normalizeEnvironment(profile.env)
  };
}

/**
 * Load one or more command profiles. Both a single profile and an
 * {"agents": [...]} manifest are accepted.
 */
export async function loadAgentProfiles(configPath) {
  const path = resolve(String(configPath));
  let text;
  try {
    text = await readFile(path, 'utf8');
  } catch (error) {
    throw adapterError('agent_config_unreadable', `无法读取 Agent 配置：${path}`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw adapterError('invalid_agent_config', `Agent 配置不是有效 JSON：${path}`);
  }
  const profiles = Array.isArray(value) ? value : Array.isArray(value?.agents) ? value.agents : [value];
  if (!profiles.length) throw adapterError('invalid_agent_config', 'Agent 配置没有 profile');
  return profiles.map((item, index) => {
    const adapter = String(item?.adapter || (item?.command ? 'command' : 'http')).toLowerCase();
    const normalized = adapter === 'command' ? normalizeProfile({...item, adapter:'command'}) : {...item, adapter};
    if (!normalized.name) normalized.name = `Local Agent ${index + 1}`;
    return normalized;
  });
}

/** Validate the executable before pairing so most configuration mistakes fail immediately. */
export async function validateCommandProfile(profile) {
  const normalized = normalizeProfile(profile);
  const workingDirectory = normalized.cwd || process.cwd();
  try {
    await realpath(workingDirectory);
  } catch {
    throw adapterError('invalid_working_directory', `工作目录不存在：${workingDirectory}`);
  }
  const executable = await resolveExecutable(normalized.command, workingDirectory);
  if (!executable) {
    throw adapterError('agent_not_found', `找不到 Agent 可执行文件：${normalized.command}`);
  }
  return {...normalized, command:executable};
}

async function resolveExecutable(command, cwd) {
  const hasPath = isAbsolute(command) || command.includes('/') || command.includes('\\');
  const candidates = hasPath
    ? [isAbsolute(command) ? command : resolve(cwd, command)]
    : String(process.env.PATH || '').split(delimiter).filter(Boolean).map(directory => join(directory, command));
  const extensions = process.platform === 'win32'
    ? String(process.env.PATHEXT || '.EXE;.CMD;.BAT;.COM').split(';').filter(Boolean)
    : [''];
  for (const candidate of candidates) {
    const names = process.platform === 'win32' && !extname(candidate)
      ? extensions.map(extension => candidate + extension.toLowerCase())
      : [candidate];
    for (const name of names) {
      try {
        await access(name, process.platform === 'win32' ? fsConstants.F_OK : fsConstants.X_OK);
        return name;
      } catch {}
    }
  }
  return '';
}

function spawnAgent(config, args, prompt, request) {
  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false;
    let stdout = '';
    let stderr = '';
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let timedOut = false;
    let outputExceeded = false;
    let stopping = false;
    const executable = config.shellMode === 'cmd' ? String(process.env.ComSpec || 'cmd.exe') : config.command;
    const processArgs = config.shellMode === 'cmd'
      ? ['/d', '/s', '/c', config.command, ...args]
      : args;
    const child = spawn(executable, processArgs, {
      cwd: config.cwd,
      env: {
        ...process.env,
        ...config.env,
        CRYPTOBENCH_RUN_ID: String(request.runId || ''),
        CRYPTOBENCH_TASK_ID: String(request.taskId || ''),
        CRYPTOBENCH_ATTEMPT: String(request.attempt || 1),
        CRYPTOBENCH_WORKSPACE: config.cwd
      },
      shell: false,
      windowsHide: true,
      detached: process.platform !== 'win32',
      stdio: [config.stdin === 'none' ? 'ignore' : 'pipe', 'pipe', 'pipe']
    });

    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (error) rejectPromise(error);
      else resolvePromise(value);
    };

    const stop = reason => {
      if (settled || stopping) return;
      stopping = true;
      if (reason === 'timeout') timedOut = true;
      if (reason === 'output_limit') outputExceeded = true;
      terminateChild(child);
    };

    const timeout = setTimeout(() => stop('timeout'), config.timeoutMs);
    timeout.unref?.();

    child.once('error', error => {
      finish(adapterError('agent_spawn_failed', `无法启动 Agent CLI：${error.message}`));
    });
    child.once('close', (code, signal) => {
      if (timedOut) {
        finish(adapterError('agent_timeout', `Agent CLI 超过 ${config.timeoutMs}ms 未完成`));
        return;
      }
      if (outputExceeded) {
        finish(adapterError('agent_output_limit', `Agent CLI 输出超过 ${config.maxOutputBytes} 字节`));
        return;
      }
      if (code !== 0) {
        const secrets = [...secretValues({...process.env, ...config.env}), ...argumentSecretValues(args)];
        const detail = redact({text: stderr.trim().slice(-2000)}, secrets).text;
        finish(adapterError('agent_exit_failed', `Agent CLI 退出码 ${code}${signal ? `（${signal}）` : ''}${detail ? `：${detail}` : ''}`));
        return;
      }
      finish(null, {stdout, stderr, elapsedMs: Date.now()});
    });

    child.stdout?.on('data', chunk => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > config.maxOutputBytes) return stop('output_limit');
      stdout += chunk;
    });
    child.stderr?.on('data', chunk => {
      stderrBytes += chunk.length;
      if (stderrBytes > config.maxOutputBytes) return stop('output_limit');
      stderr += chunk;
    });

    if (config.stdin === 'prompt') child.stdin.end(prompt);
    else if (config.stdin === 'json') child.stdin.end(JSON.stringify(request));
    else child.stdin?.end();
  });
}

function terminateChild(child) {
  if (!child?.pid) return;
  if (process.platform === 'win32') {
    const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
      windowsHide: true,
      stdio: 'ignore'
    });
    killer.unref();
    const directKill = setTimeout(() => {
      try { child.kill('SIGTERM'); } catch {}
      const force = setTimeout(() => {
        try { child.kill('SIGKILL'); } catch {}
      }, 500);
      force.unref?.();
    }, 250);
    directKill.unref?.();
    return;
  }
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch {
    child.kill('SIGTERM');
  }
  const force = setTimeout(() => {
    try {
      process.kill(-child.pid, 'SIGKILL');
    } catch {
      try { child.kill('SIGKILL'); } catch {}
    }
  }, 1000);
  force.unref?.();
}

function parseOutput(stdout, format) {
  const text = String(stdout || '').trim();
  let selected = format;
  if (selected === 'auto') selected = detectOutput(text);
  if (selected === 'json') return parseJsonOutput(text);
  if (selected === 'jsonl') return parseJsonLines(text);
  return {
    artifact: {answer: text},
    events: text ? [{type: 'cli_output', content: text}] : [],
    usage: {}
  };
}

function detectOutput(text) {
  if (!text) return 'text';
  if (looksLikeJson(text)) return 'json';
  const lines = text.split(/\r?\n/).filter(line => line.trim());
  if (lines.length > 1 && lines.filter(line => looksLikeJson(line)).length >= Math.ceil(lines.length * 0.8)) {
    return 'jsonl';
  }
  return 'text';
}

function parseJsonOutput(text) {
  if (!text) throw adapterError('invalid_agent_output', 'Agent CLI 没有输出 JSON');
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw adapterError('invalid_agent_output', 'Agent CLI 输出不是有效 JSON');
  }
  const events = value?.events || value?.trajectory || value?.items || [];
  return {
    artifact: normalizeArtifact(value),
    events: Array.isArray(events) ? events.map(event => ({type: 'agent_event', content: event})) : [],
    usage: normalizeTokenUsage(value?.usage || value?.metrics || {})
  };
}

function parseJsonLines(text) {
  const events = [];
  for (const line of String(text || '').split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      events.push(JSON.parse(line));
    } catch {
      throw adapterError('invalid_agent_output', 'Agent CLI 的 JSONL 中存在无法解析的行');
    }
  }
  if (!events.length) throw adapterError('invalid_agent_output', 'Agent CLI 没有输出 JSONL 事件');
  return {
    artifact: {answer: extractAnswer(events) || ''},
    events: events.map(event => ({type: event?.type || 'agent_event', content: event})),
    usage: extractUsage(events)
  };
}

function normalizeArtifact(value) {
  if (value?.artifact && typeof value.artifact === 'object') return value.artifact;
  const answer = extractAnswer(value);
  return {answer: answer || ''};
}

function extractAnswer(value) {
  const events = Array.isArray(value) ? value : [value];
  const answers = [];
  const visit = item => {
    if (!item || typeof item !== 'object') return;
    const type = String(item.type || item.event || item.kind || '').toLowerCase();
    const nested = item.item && typeof item.item === 'object' ? item.item : null;
    if (/agent_message|assistant|final_answer|response\.completed/.test(type)) {
      const text = stringValue(item.text)
        || stringValue(item.content)
        || stringValue(item.message)
        || stringValue(nested?.text)
        || stringValue(nested?.content);
      if (text) answers.push(text);
    }
    for (const key of ['answer', 'output', 'response', 'result', 'content', 'text']) {
      if (typeof item[key] === 'string' && item[key].trim()) answers.push(item[key].trim());
    }
    if (nested) visit(nested);
  };
  events.forEach(visit);
  return answers.at(-1) || '';
}

function extractUsage(events) {
  const usage = {};
  for (const event of events) {
    const candidate = event?.usage || event?.metrics || event?.response?.usage || event?.item?.usage;
    if (candidate && typeof candidate === 'object') Object.assign(usage, normalizeTokenUsage(candidate));
  }
  return usage;
}

function normalizeTokenUsage(value) {
  const usage = {};
  assignNumber(usage, 'inputTokens', value?.inputTokens ?? value?.input_tokens ?? value?.promptTokens ?? value?.prompt_tokens);
  assignNumber(usage, 'outputTokens', value?.outputTokens ?? value?.output_tokens ?? value?.completionTokens ?? value?.completion_tokens);
  assignNumber(usage, 'totalTokens', value?.totalTokens ?? value?.total_tokens);
  assignNumber(usage, 'agentCalls', value?.agentCalls ?? value?.agent_calls);
  assignNumber(usage, 'costUsd', value?.costUsd ?? value?.cost_usd);
  return usage;
}

function normalizeUsage(usage, elapsedMs) {
  const normalized = normalizeTokenUsage(usage || {});
  if (normalized.inputTokens != null && normalized.outputTokens != null && normalized.totalTokens == null) {
    normalized.totalTokens = normalized.inputTokens + normalized.outputTokens;
  }
  normalized.agentCalls ??= 1;
  normalized.elapsedMs = elapsedMs;
  return normalized;
}

function normalizeEvidence(events, stderr, config) {
  const evidence = [];
  for (const event of Array.isArray(events) ? events.slice(0, MAX_EVIDENCE_EVENTS) : []) {
    const remaining = MAX_EVIDENCE_BYTES - Buffer.byteLength(JSON.stringify(evidence));
    if (remaining < 256) break;
    evidence.push({
      type: event?.type || 'agent_event',
      content: truncate(event?.content ?? event, Math.min(MAX_EVIDENCE_TEXT, remaining - 128))
    });
  }
  if (stderr.trim() && config.includeStderr !== false) {
    const remaining = MAX_EVIDENCE_BYTES - Buffer.byteLength(JSON.stringify(evidence));
    if (remaining > 256) evidence.push({
      type: 'cli_stderr',
      content: truncate(stderr.trim().slice(-MAX_EVIDENCE_TEXT), remaining - 128)
    });
  }
  return evidence;
}

async function materializeTaskInputs(request, workspace) {
  const files = request?.task?.files;
  if (!Array.isArray(files) || files.length === 0) return;
  for (const input of files) {
    if (!input || typeof input !== 'object') throw adapterError('invalid_task_input', '任务附件格式无效');
    const relativePath = safeWorkspacePath(workspace, input.path);
    const encoding = String(input.encoding || '').toLowerCase();
    if (encoding !== 'base64' || typeof input.content !== 'string') {
      throw adapterError('invalid_task_input', `任务附件编码不受支持：${input.path || '(missing)'}`);
    }
    let content;
    try {
      content = Buffer.from(input.content, 'base64');
    } catch {
      throw adapterError('invalid_task_input', `任务附件 Base64 无效：${input.path}`);
    }
    if (Number.isFinite(Number(input.bytes)) && content.length !== Number(input.bytes)) {
      throw adapterError('invalid_task_input', `任务附件长度不一致：${input.path}`);
    }
    if (typeof input.sha256 === 'string' && input.sha256
        && createHash('sha256').update(content).digest('hex') !== input.sha256.toLowerCase()) {
      throw adapterError('invalid_task_input', `任务附件校验失败：${input.path}`);
    }
    const target = resolve(workspace, relativePath);
    await mkdir(dirname(target), {recursive: true});
    await writeFile(target, content);
  }
}

async function collectWorkspaceArtifacts(workspace) {
  const files = [];
  let total = 0;

  async function walk(directory, depth = 0) {
    if (depth > 12) return;
    let entries;
    try {
      entries = await readdir(directory, {withFileTypes: true});
    } catch {
      return;
    }
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const full = join(directory, entry.name);
      const relativePath = relative(workspace, full).replace(/\\/g, '/');
      const parts = relativePath.split('/');
      if (parts.some(part => EXCLUDED_WORKSPACE_PARTS.has(part))) continue;
      if (parts[0] === 'attachments' || relativePath === 'final_answer.md') continue;
      if (entry.isDirectory()) {
        await walk(full, depth + 1);
        continue;
      }
      if (!entry.isFile() || !ARTIFACT_SUFFIXES.has(extname(entry.name).toLowerCase())) continue;
      let content;
      try {
        content = await readFile(full);
      } catch {
        continue;
      }
      if (!content.length || content.length > MAX_ARTIFACT_FILE_BYTES || total + content.length > MAX_ARTIFACT_BYTES) continue;
      const text = content.toString('utf8');
      if (text.includes('\0')) continue;
      total += content.length;
      files.push({path: relativePath, content: text});
    }
  }

  await walk(workspace);
  return files;
}

function mergeArtifactFiles(artifact, captured) {
  const result = artifact && typeof artifact === 'object' && !Array.isArray(artifact)
    ? {...artifact}
    : {answer: String(artifact || '')};
  const files = Array.isArray(result.files) ? result.files.filter(item => item && typeof item === 'object') : [];
  const seen = new Set(files.map(item => String(item.path || '')).filter(Boolean));
  for (const item of captured) {
    if (!seen.has(item.path)) {
      files.push(item);
      seen.add(item.path);
    }
  }
  if (files.length) result.files = files;
  return result;
}

function safeWorkspacePath(workspace, value) {
  if (typeof value !== 'string' || !value.trim() || isAbsolute(value)) {
    throw adapterError('invalid_task_input', '任务附件路径无效');
  }
  const normalized = value.replace(/\\/g, '/');
  if (normalized.split('/').some(part => part === '..')) {
    throw adapterError('invalid_task_input', `任务附件路径越界：${value}`);
  }
  const target = resolve(workspace, normalized);
  const root = resolve(workspace);
  if (target !== root && !target.startsWith(root.endsWith(sep) ? root : root + sep)) {
    throw adapterError('invalid_task_input', `任务附件路径越界：${value}`);
  }
  return normalized;
}

function renderPrompt(template, request, profile, workspace) {
  return renderTemplate(template, {prompt: '', request, profile, task: request.task, workspace});
}

function renderTemplate(template, context) {
  const promptTask = taskForPrompt(context.request?.task);
  const promptRequest = context.request ? {...context.request, task: promptTask} : {};
  const replacements = {
    prompt: context.prompt ?? '',
    runId: String(context.request?.runId || ''),
    taskId: String(context.request?.taskId || ''),
    model: String(context.profile?.model || context.request?.model || ''),
    attempt: String(context.request?.attempt || 1),
    taskJson: JSON.stringify(promptTask, null, 2),
    requestJson: JSON.stringify(promptRequest, null, 2),
    workspace: String(context.workspace || ''),
    files: JSON.stringify(inputMetadata(context.request?.task?.files), null, 2)
  };
  return String(template).replace(/\{(prompt|runId|taskId|model|attempt|taskJson|requestJson|workspace|files)\}/g, (_, key) => replacements[key]);
}

function taskForPrompt(task) {
  if (!task || typeof task !== 'object') return {};
  return {
    ...task,
    ...(Array.isArray(task.files) ? {files: inputMetadata(task.files)} : {})
  };
}

function inputMetadata(files) {
  if (!Array.isArray(files)) return [];
  return files.map(file => {
    if (!file || typeof file !== 'object') return {};
    const {content, ...metadata} = file;
    return metadata;
  });
}

function defaultPromptTemplate() {
  return [
    '你正在完成一道密码分析基准任务。请独立分析题目，并给出尽可能明确、可复核的最终答案。',
    '',
    'Run ID: {runId}',
    'Task ID: {taskId}',
    '',
    '任务内容：',
    '{taskJson}',
    '',
    '工作区：{workspace}',
    '题目附件已解码到工作区的 attachments/ 目录；如果任务不需要附件，该目录不存在。',
    '',
    '要求：',
    '1. 最终答案必须直接回答题目，不要只描述方法。',
    '2. 将可复核的代码、日志和结构化结果保存在当前工作区，服务端会回收文本产物。',
    '3. 保留关键推导、工具调用、文件或版本观察，作为可核验证据。',
    '4. 如果结论依赖假设，请明确写出假设。',
    '5. 如果无法确定答案，请说明缺失的证据，不要猜测。'
  ].join('\n');
}

function normalizeEnvironment(env) {
  if (env == null) return {};
  if (typeof env !== 'object' || Array.isArray(env)) {
    throw adapterError('invalid_command_profile', 'env 必须是字符串对象');
  }
  return Object.fromEntries(Object.entries(env).map(([key, value]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || typeof value !== 'string' || value.includes('\0')) {
      throw adapterError('invalid_command_profile', `无效的环境变量：${key}`);
    }
    return [key, value];
  }));
}

function secretValues(env) {
  return Object.entries(env)
    .filter(([key, value]) => /TOKEN|KEY|SECRET|PASSWORD|AUTH/i.test(key) && String(value).length >= 6)
    .map(([, value]) => value);
}

function argumentSecretValues(args) {
  const values = [];
  for (let index = 0; index < args.length - 1; index++) {
    if (/^--?(?:api[-_]?key|token|secret|password|authorization)$/i.test(args[index])) {
      values.push(args[index + 1]);
    }
  }
  return values.filter(value => typeof value === 'string' && value.length >= 6);
}

function redact(value, secrets) {
  if (typeof value === 'string') {
    return secrets.reduce((text, secret) => text.split(secret).join('[redacted]'), value);
  }
  if (Array.isArray(value)) return value.map(item => redact(item, secrets));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, redact(item, secrets)]));
  }
  return value;
}

function truncate(value, limit = MAX_EVIDENCE_TEXT) {
  const maximum = Math.max(0, limit);
  if (typeof value === 'string') return value.length > maximum ? value.slice(0, maximum) + '\n[truncated]' : value;
  const text = JSON.stringify(value);
  return text.length > maximum ? text.slice(0, maximum) + '\n[truncated]' : value;
}

function looksLikeJson(value) {
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}

function stringValue(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : '';
}

function assignNumber(target, key, value) {
  const number = Number(value);
  if (value != null && Number.isFinite(number) && number >= 0) target[key] = number;
}

function adapterError(code, message) {
  return Object.assign(new Error(message), {code});
}
