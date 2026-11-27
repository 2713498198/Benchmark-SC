import {createHash} from 'node:crypto';
import {readFile,readdir,stat} from 'node:fs/promises';
import {basename,extname,join,relative} from 'node:path';
import {runDocker} from './docker-process.mjs';

const ARTIFACT_SUFFIXES = new Set([
  '.md', '.txt', '.json', '.jsonl', '.csv', '.log', '.py', '.sage',
  '.c', '.cpp', '.h', '.rs', '.java', '.mzn', '.sh', '.ps1',
]);
const MAX_ARTIFACT_BYTES = 4_000_000;

export function normalizeDockerProfile(profile) {
  if (!profile || typeof profile !== 'object' || Array.isArray(profile)) {
    throw new Error('Docker Agent 配置必须是对象');
  }
  const image = String(profile.image || '').trim();
  if (!image) throw new Error('Docker Agent 配置缺少 image');
  const command = profile.command == null ? '' : String(profile.command).trim();
  const args = profile.args == null ? [] : profile.args;
  if (!Array.isArray(args) || args.some(value => typeof value !== 'string')) {
    throw new Error('Docker Agent 的 args 必须是字符串数组');
  }
  const stdin = String(profile.stdin || 'prompt').toLowerCase();
  if (!['prompt','json','none'].includes(stdin)) throw new Error('stdin 只能是 prompt、json 或 none');
  const output = String(profile.output || 'auto').toLowerCase();
  if (!['auto','text','json','jsonl'].includes(output)) throw new Error('output 只能是 auto、text、json 或 jsonl');
  const network = String(profile.network || 'none').toLowerCase();
  if (!['none','bridge'].includes(network)) throw new Error('network 只能是 none 或 bridge');
  return {
    ...profile,
    adapter: 'docker-command',
    image,
    command,
    args,
    stdin,
    output,
    network,
    cpus: String(profile.cpus || '2'),
    memory: String(profile.memory || '2g'),
    pidsLimit: String(profile.pidsLimit || '256'),
    tmpfsSize: String(profile.tmpfsSize || '512m'),
    workdir: String(profile.workdir || '/workspace'),
    timeoutSeconds: Math.max(1, Number(profile.timeoutSeconds || 300)),
    maxOutputBytes: Math.max(64 * 1024, Number(profile.maxOutputBytes || 8 * 1024 * 1024)),
    env: normalizeEnv(profile.env),
  };
}

export async function runDockerAgent(profile, request, workspace, options = {}) {
  const config = normalizeDockerProfile(profile);
  const prompt = [
    renderPrompt(request, config, workspace),
    answerInstruction(request),
  ].filter(Boolean).join('\n\n');
  const replacements = {
    prompt,
    taskJson: JSON.stringify(publicTask(request.task), null, 2),
    requestJson: JSON.stringify(publicRequest(request), null, 2),
    taskId: String(request.taskId || ''),
    runId: String(request.runId || ''),
    model: String(request.model || config.model || ''),
    workspace: config.workdir,
  };
  const args = buildDockerArgs(config, request, workspace, replacements);
  const secretValues = Object.entries(config.env)
    .filter(([key, value]) => /TOKEN|KEY|SECRET|PASSWORD|AUTH/i.test(key) && String(value).length >= 6)
    .map(([, value]) => String(value));
  const stream = progressStream(config.output, message => {
    options.onProgress?.(redact(String(message || ''), secretValues));
  });
  const started = Date.now();
  const result = await runDocker(args, {
    stdin: config.stdin === 'prompt' ? prompt : config.stdin === 'json' ? JSON.stringify(publicRequest(request)) : null,
    env: config.env,
    timeoutSeconds: request.budget?.timeoutSeconds || config.timeoutSeconds,
    maxOutputBytes: config.maxOutputBytes,
    onStdout: stream,
    onStderr: text => stream.stderr(text),
  });
  stream.flush();
  const parsed = parseOutput(result.stdout, config.output);
  const response = normalizeResponse(parsed, request, result.stderr);
  const files = await collectArtifacts(workspace);
  if (files.length) {
    response.artifact ||= {};
    response.artifact.files = mergeFiles(response.artifact.files, files);
  }
  const safeResponse = redact(response, secretValues);
  safeResponse.elapsedMs = Date.now() - started;
  safeResponse.meta = {
    adapter:'docker-command',
    image:config.image,
    network:config.network,
    cpus:config.cpus,
    memory:config.memory,
    pidsLimit:config.pidsLimit,
    command:basename(config.command || '(image entrypoint)'),
    outputFormat:config.output,
  };
  return safeResponse;
}

export function buildDockerArgs(config, request, workspace, replacements = {}) {
  const mount = `type=bind,source=${workspace.replaceAll('\\','/')},target=/workspace`;
  const args = [
    'run','--rm','-i',
    '--network',config.network,
    '--cpus',config.cpus,
    '--memory',config.memory,
    '--pids-limit',config.pidsLimit,
    '--read-only',
    '--cap-drop','ALL',
    '--security-opt','no-new-privileges',
    '--tmpfs',`/tmp:rw,nosuid,nodev,size=${config.tmpfsSize}`,
    '--mount',mount,
    '-w',config.workdir,
    '-e',`CRYPTOBENCH_RUN_ID=${request.runId || ''}`,
    '-e',`CRYPTOBENCH_TASK_ID=${request.taskId || ''}`,
    '-e',`CRYPTOBENCH_ATTEMPT=${request.attempt || 1}`,
    '-e','CRYPTOBENCH_WORKSPACE=/workspace',
  ];
  for (const key of Object.keys(config.env)) args.push('-e', key);
  if (config.user) args.push('--user', String(config.user));
  args.push(config.image);
  if (config.command) args.push(config.command);
  for (const argument of config.args) {
    args.push(renderTemplate(argument, replacements));
  }
  return args;
}

function renderPrompt(request, config, workspace) {
  const template = String(config.promptTemplate || [
    '你正在 Docker 沙箱中完成一道 CryptoBench 任务。',
    'Run ID: {runId}',
    'Task ID: {taskId}',
    '任务内容：',
    '{taskJson}',
    '工作区：/workspace',
    '题目附件位于 /workspace/attachments/。',
    '请直接给出最终答案，并保留可复核的代码、日志和结构化结果。',
  ].join('\n'));
  return renderTemplate(template, {
    runId:String(request.runId || ''),
    taskId:String(request.taskId || ''),
    model:String(request.model || config.model || ''),
    taskJson:JSON.stringify(publicTask(request.task), null, 2),
    requestJson:JSON.stringify(publicRequest(request), null, 2),
    workspace:'/workspace',
  });
}

function renderTemplate(template, values) {
  return String(template).replace(/\{(runId|taskId|model|taskJson|requestJson|workspace|prompt)\}/g,
    (_, key) => String(values[key] ?? ''));
}

function publicTask(task = {}) {
  return {
    id:task.id,
    question:task.question,
    input:task.input,
    category:task.category,
    publicFiles:task.publicFiles,
    files:Array.isArray(task.files) ? task.files.map(({content,...file}) => file) : [],
  };
}

function publicRequest(request) {
  return {...request, task:publicTask(request.task)};
}

function answerInstruction(request) {
  const format = request?.task?.input?.answerFormat || {};
  const kind = String(format.kind || '').toLowerCase();
  if (!kind) return '';
  let sample = 'final answer';
  if (kind === 'number') sample = 0;
  else if (kind === 'boolean') sample = true;
  else if (kind === 'object' || kind === 'piecewise_formula') sample = {};
  return `最终输出必须包含 JSON：{"status":"completed","artifact":{"answer":${JSON.stringify(sample)}}}。answer 类型必须符合 ${kind}。`;
}

function parseOutput(stdout, output) {
  const text = String(stdout || '').trim();
  const format = output === 'auto' ? detectFormat(text) : output;
  if (format === 'json') {
    const value = JSON.parse(text);
    return {response:value, events:value.events || value.trajectory || [], usage:value.usage || {}};
  }
  if (format === 'jsonl') {
    const events = text.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
    return {response:{answer:extractAnswer(events)}, events, usage:extractUsage(events)};
  }
  return {response:{answer:text}, events:text ? [{type:'cli_output',content:text}] : [], usage:{}};
}

function normalizeResponse(parsed, request, stderr) {
  const value = parsed.response;
  let response;
  if (value && typeof value === 'object' && !Array.isArray(value) && (
    value.artifact || value.status || value.answer
  )) {
    response = {...value};
    if (!response.artifact && 'answer' in response) response.artifact = {answer:response.answer};
    delete response.answer;
  } else {
    response = {status:'completed', artifact:{answer:value?.answer ?? ''}};
  }
  response.status ||= 'completed';
  response.artifact ||= {answer:''};
  response.artifact.answer = normalizeAnswer(response.artifact.answer, request.task?.input?.answerFormat);
  const evidence = [];
  for (const event of parsed.events.slice(0, 300)) {
    evidence.push({type:event.type || 'agent_event', content:event});
  }
  if (stderr.trim()) evidence.push({type:'cli_stderr', content:stderr.trim().slice(-8000)});
  if (evidence.length) response.evidence = evidence;
  if (parsed.usage && Object.keys(parsed.usage).length) response.usage = parsed.usage;
  return response;
}

function normalizeAnswer(answer, format = {}) {
  if (typeof answer !== 'string') return answer;
  const text = answer.trim();
  const kind = String(format.kind || '').toLowerCase();
  try {
    const object = JSON.parse(text);
    if (object && typeof object === 'object' && 'answer' in object) return object.answer;
    if (kind === 'object' || kind === 'piecewise_formula') return object;
  } catch {}
  const labelled = text.match(/(?:最终答案|最终结果|答案|final answer)\s*(?:是|为)?\s*[:：]?\s*([\s\S]*)/i)?.[1]?.trim() || text;
  if (kind === 'number') {
    const match = labelled.match(/^[\s*_`]*(-?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?)/i);
    if (match && Number.isFinite(Number(match[1]))) return Number(match[1]);
  }
  if (kind === 'boolean') {
    const match = labelled.match(/^[\s*_`]*(true|false|yes|no|是|否)/i);
    if (match) return ['true','yes','是'].includes(match[1].toLowerCase());
  }
  if (kind === 'string') return labelled.split(/\r?\n/)[0].replace(/^[\s*_`"“”]+|[\s*_`"“”]+$/g,'');
  return text;
}

function detectFormat(text) {
  if (!text) return 'text';
  try {JSON.parse(text);return 'json';} catch {}
  const lines = text.split(/\r?\n/).filter(Boolean);
  if (lines.length && lines.every(line => {try{JSON.parse(line);return true;}catch{return false;}})) return 'jsonl';
  return 'text';
}

function extractAnswer(events) {
  const answers = [];
  const visit = item => {
    if (!item || typeof item !== 'object') return;
    const type = String(item.type || item.event || item.kind || '').toLowerCase();
    const nested = item.item && typeof item.item === 'object' ? item.item : null;
    if (/agent_message|assistant|final_answer|response\.completed/.test(type)) {
      const value = item.text || item.content || item.message || nested?.text || nested?.content;
      if (typeof value === 'string' && value.trim()) answers.push(value.trim());
    }
    if (nested) visit(nested);
  };
  events.forEach(visit);
  return answers.at(-1) || '';
}

function extractUsage(events) {
  const usage = {};
  for (const event of events) {
    const value = event?.usage || event?.metrics || event?.response?.usage || event?.item?.usage;
    if (!value || typeof value !== 'object') continue;
    if (Number.isFinite(Number(value.input_tokens ?? value.inputTokens))) usage.inputTokens = Number(value.input_tokens ?? value.inputTokens);
    if (Number.isFinite(Number(value.output_tokens ?? value.outputTokens))) usage.outputTokens = Number(value.output_tokens ?? value.outputTokens);
    if (Number.isFinite(Number(value.total_tokens ?? value.totalTokens))) usage.totalTokens = Number(value.total_tokens ?? value.totalTokens);
  }
  return usage;
}

function progressStream(format, onProgress) {
  let buffer = '';
  const stderrBuffer = {value:''};
  const emit = text => {
    const lines = text.split(/\r?\n/);
    buffer += lines.shift();
    for (const line of lines) processLine(line, format, onProgress);
    buffer = lines.join('\n');
  };
  emit.stderr = text => {
    stderrBuffer.value += text;
    const lines = stderrBuffer.value.split(/\r?\n/);
    stderrBuffer.value = lines.pop() || '';
    for (const line of lines) if (line.trim()) onProgress(`stderr：${line.trim().slice(0,800)}`);
  };
  emit.flush = () => {
    if (buffer.trim()) processLine(buffer, format, onProgress);
    if (stderrBuffer.value.trim()) onProgress(`stderr：${stderrBuffer.value.trim().slice(0,800)}`);
    buffer = '';
    stderrBuffer.value = '';
  };
  return emit;
}

function processLine(line, format, onProgress) {
  if (!line.trim()) return;
  if (!['json','jsonl','auto'].includes(format)) {
    onProgress(`Agent 输出：${line.trim().slice(0,800)}`);
    return;
  }
  let event;
  try {event = JSON.parse(line);} catch {
    onProgress(`Agent 输出：${line.trim().slice(0,800)}`);
    return;
  }
  const item = event.item || event;
  if (item.type === 'agent_message') onProgress(`Agent：${String(item.text || item.content || '').slice(0,800)}`);
  else if (item.type === 'reasoning') onProgress(`分析：${String(item.text || item.content || '').slice(0,800)}`);
  else if (item.type === 'command_execution') {
    const label = event.type === 'item.completed' ? '命令完成' : '执行命令';
    onProgress(`${label}：${String(item.command || '').slice(0,600)}`);
  } else if (event.type === 'turn.started') onProgress('Agent 开始分析');
}

async function collectArtifacts(root) {
  const files = [];
  let total = 0;
  const walk = async directory => {
    let entries;
    try {entries = await readdir(directory,{withFileTypes:true});} catch {return;}
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const full = join(directory,entry.name);
      const rel = relative(root,full).replaceAll('\\','/');
      if (rel.startsWith('attachments/') || rel === '.cryptobench' || rel.startsWith('.cryptobench/')) continue;
      if (entry.isDirectory()) {await walk(full);continue;}
      if (!entry.isFile() || !ARTIFACT_SUFFIXES.has(extname(entry.name).toLowerCase())) continue;
      const info = await stat(full);
      if (info.size > 1_500_000 || total + info.size > MAX_ARTIFACT_BYTES) continue;
      const content = await readFile(full,'utf8');
      if (content.includes('\0')) continue;
      total += Buffer.byteLength(content);
      files.push({path:rel,content});
    }
  };
  await walk(root);
  return files;
}

function mergeFiles(existing, captured) {
  const files = Array.isArray(existing) ? [...existing] : [];
  const paths = new Set(files.map(file => file?.path).filter(Boolean));
  for (const file of captured) if (!paths.has(file.path)) files.push(file);
  return files;
}

function normalizeEnv(value) {
  if (value == null) return {};
  if (typeof value !== 'object' || Array.isArray(value)) throw new Error('env 必须是字符串对象');
  return Object.fromEntries(Object.entries(value).map(([key, item]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || typeof item !== 'string') {
      throw new Error(`无效的环境变量：${key}`);
    }
    return [key,item];
  }));
}

function redact(value, secrets) {
  if (typeof value === 'string') {
    return secrets.filter(Boolean).reduce((text,secret) => text.split(secret).join('[redacted]'), value);
  }
  if (Array.isArray(value)) return value.map(item => redact(item,secrets));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key,item]) => [key,redact(item,secrets)]));
  }
  return value;
}
