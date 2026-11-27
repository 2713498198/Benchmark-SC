import {mkdir,readFile,readdir,writeFile} from 'node:fs/promises';
import {dirname,join,relative} from 'node:path';
import {runDocker} from './docker-process.mjs';

export async function evaluateTask(task, response, options = {}) {
  const result = {
    evaluatorVersion: String(options.evaluatorVersion || 'offline-v1'),
    status: 'invalid',
    passed: false,
    score: 0,
  };
  if (!response || response.status !== 'completed' || !response.artifact || !('answer' in response.artifact)) {
    result.reason = 'Agent 需返回 completed 状态和 artifact.answer';
    return result;
  }
  const rule = task.verification || {};
  const type = String(rule.type || '');
  if (type === 'workspace-validator') {
    return evaluateWorkspace(task,response,options);
  }
  let passed = false;
  const actual = response.artifact.answer;
  if (type === 'numeric') passed = numericMatch(rule,actual);
  else if (type === 'string') passed = stringMatch(rule,actual);
  else if (type === 'one-of') passed = oneOfMatch(rule,actual);
  else if (type === 'boolean') passed = typeof actual === 'boolean' && actual === Boolean(rule.expected);
  else if (type === 'exact-json') passed = canonical(rule.expected) === canonical(actual);
  else if (type === 'fields') passed = fieldsMatch(rule,actual);
  else if (type === 'piecewise') passed = piecewiseMatch(rule,actual);
  result.status = passed ? 'passed' : 'failed';
  result.passed = passed;
  result.score = passed ? 1 : 0;
  if (!passed) result.reason = type === 'dynamic'
    ? '该题没有本地可执行的确定性评分规则'
    : '提交结果未满足本地评分规则';
  return result;
}

async function evaluateWorkspace(task, response, options) {
  const judgeImage = String(options.judgeImage || 'cryptobench-offline-judge:4.2.0');
  const runRoot = options.runRoot;
  if (!runRoot) throw new Error('工作区验证需要 runRoot');
  const runDir = join(runRoot,'run');
  const workspace = join(runDir,'workspace');
  await mkdir(workspace,{recursive:true});
  await writeFile(join(runDir,'final_answer.md'),text(response.artifact.answer),'utf8');
  await writeArtifactFiles(runDir,workspace,response.artifact);
  await writeEvidence(runDir,response.evidence);
  const resultPath = join(runRoot,'validator_report.json');
  const suiteRoot='/opt/cryptobench/suite';
  const gradeEntry=`${suiteRoot}/scripts/grade_submission.py`;
  const fallbackEntry=`${suiteRoot}/scripts/run_task_validator.py`;
  const mountSource=runRoot.replaceAll('\\','/');
  const command = [
    'run','--rm',
    '--network','none',
    '--cpus','2',
    '--memory','2g',
    '--pids-limit','256',
    '--read-only',
    '--cap-drop','ALL',
    '--security-opt','no-new-privileges',
    '--tmpfs','/tmp:rw,nosuid,nodev,size=512m',
    '--mount',`type=bind,source=${mountSource},target=/run`,
    '-w','/run',
    judgeImage,
    'sh','-lc',
    `if [ -f ${gradeEntry} ]; then python3 -B ${gradeEntry} --suite-root ${suiteRoot} --task-id "${shell(task.id)}" --run-dir /run/run --out /run/validator_report.json; else python3 -B ${fallbackEntry} --suite-root ${suiteRoot} --task-id "${shell(task.id)}" --run-dir /run/run --out /run/validator_report.json; fi`,
  ];
  try {
    await runDocker(command,{timeoutSeconds:Number(options.validationTimeoutSeconds || 180)});
  } catch (error) {
    return {
      evaluatorVersion:String(options.evaluatorVersion || 'offline-v1'),
      status:'failed',
      passed:false,
      score:0,
      reason:`本地工作区验证器执行失败：${error.message}`,
    };
  }
  let report;
  try {report=JSON.parse(await readFile(resultPath,'utf8'));} catch {
    return {
      evaluatorVersion:String(options.evaluatorVersion || 'offline-v1'),
      status:'failed',
      passed:false,
      score:0,
      reason:'本地工作区验证器没有生成有效报告',
    };
  }
  const finalScore=number(report.final_score,0);
  const releaseStatus=String(report.release_check_status || '');
  const passed=releaseStatus ? releaseStatus === 'passed' : finalScore >= 0.9999;
  const status=String(report.status || '') === 'validator_error'
    ? 'validator_error' : passed ? 'passed' : 'failed';
  return {
    evaluatorVersion:String(options.evaluatorVersion || 'offline-v1'),
    status,
    passed,
    score:finalScore,
    completionStatus:report.completion_status,
    completionMetricEligible:report.completion_metric_eligible,
    releaseCheckStatus:releaseStatus || undefined,
    releaseCheckScope:report.release_check_scope,
    reason:passed ? undefined : status === 'validator_error'
      ? '本地工作区验证器执行失败'
      : '提交未通过本地工作区验证器',
  };
}

function numericMatch(rule, actual) {
  if (typeof actual !== 'number' || !Number.isFinite(actual)) return false;
  const expected=number(rule.expected,0);
  const absolute=Math.abs(number(rule.absoluteTolerance,0));
  const relative=Math.abs(number(rule.relativeTolerance,0)*expected);
  return Math.abs(actual-expected)<=Math.max(absolute,relative);
}

function stringMatch(rule, actual) {
  if (typeof actual !== 'string') return false;
  const ignore=Boolean(rule.ignoreCase);
  let expected=normalize(rule.expected);
  let submitted=normalize(actual);
  if (ignore) {expected=expected.toLowerCase();submitted=submitted.toLowerCase();}
  if (expected===submitted) return true;
  const a=probabilityWeight(expected);
  const b=probabilityWeight(submitted);
  return a != null && b != null && Math.abs(a-b)<1e-9;
}

function oneOfMatch(rule, actual) {
  if (typeof actual !== 'string') return false;
  const submitted=rule.ignoreCase === false ? normalize(actual) : normalize(actual).toLowerCase();
  return (rule.options || []).some(option => {
    const expected=rule.ignoreCase === false ? normalize(option) : normalize(option).toLowerCase();
    return expected === submitted;
  });
}

function fieldsMatch(rule, actual) {
  if (!actual || typeof actual !== 'object' || Array.isArray(actual)) return false;
  if (!rule.fields || typeof rule.fields !== 'object') return false;
  const tolerance=number(rule.numericTolerance,0);
  return Object.entries(rule.fields).every(([key,expected]) => {
    const entry=Object.entries(actual).find(([name]) => normalizeKey(name)===normalizeKey(key));
    return entry && valueEquals(expected,entry[1],tolerance);
  });
}

function piecewiseMatch(rule, actual) {
  const expected=Array.isArray(rule.cases) ? rule.cases : [];
  const submitted=Array.isArray(actual) ? actual : Array.isArray(actual?.cases) ? actual.cases : [];
  if (!expected.length || expected.length !== submitted.length) return false;
  const used=new Set();
  return expected.every(item => {
    const index=submitted.findIndex((candidate,index) => {
      if (used.has(index)) return false;
      return symbol(candidate?.condition)===symbol(item.condition)
        && symbol(candidate?.y)===symbol(item.y)
        && symbol(candidate?.advantage)===symbol(item.advantage);
    });
    if (index<0) return false;
    used.add(index);
    return true;
  });
}

function valueEquals(expected, actual, tolerance) {
  if (typeof expected === 'boolean' || typeof actual === 'boolean') return expected === actual;
  const expectedWeight=probabilityWeight(expected);
  const actualWeight=probabilityWeight(actual);
  if (expectedWeight != null && actualWeight != null) return Math.abs(expectedWeight-actualWeight)<=tolerance;
  const expectedNumber=numericValue(expected);
  const actualNumber=numericValue(actual);
  if (expectedNumber != null && actualNumber != null) {
    return Math.abs(expectedNumber-actualNumber)<=Math.max(tolerance,1e-9);
  }
  return normalize(expected) === normalize(actual);
}

function numericValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value !== 'string') return null;
  const exponent=value.match(/2\s*\^\s*(-?\d+(?:\.\d+)?)/i);
  if (exponent) return Math.abs(Number(exponent[1]));
  const number=value.match(/-?\d+(?:\.\d+)?/);
  return number ? Number(number[0]) : null;
}

function probabilityWeight(value) {
  const text=String(value ?? '').replace(/[(){}]/g,'');
  let match=text.match(/2\s*\^\s*(-\d+(?:\.\d+)?)/i);
  if (match) return Math.abs(Number(match[1]));
  match=text.match(/1\s*\/\s*2\s*\^\s*(\d+(?:\.\d+)?)/i);
  return match ? Number(match[1]) : null;
}

async function writeArtifactFiles(runDir, workspace, artifact) {
  const files=Array.isArray(artifact?.files) ? artifact.files : [];
  for (const file of files) {
    const path=String(file?.path || '').replaceAll('\\','/');
    if (!path || path.startsWith('/') || path.split('/').includes('..')) continue;
    const target=join(workspace,path);
    const rel=relative(workspace,target);
    if (rel.startsWith('..')) continue;
    await mkdir(dirname(target),{recursive:true});
    await writeFile(target,text(file.content),'utf8');
    if (path.startsWith('logs/') || path === 'events.jsonl' || path === 'trajectory.jsonl') {
      const mirrored=join(runDir,path);
      await mkdir(dirname(mirrored),{recursive:true});
      await writeFile(mirrored,text(file.content),'utf8');
    }
  }
}

async function writeEvidence(runDir, evidence) {
  if (!Array.isArray(evidence) || !evidence.length) return;
  const content=evidence.map(item => text(item?.content ?? item)).filter(Boolean).join('\n');
  if (!content) return;
  await mkdir(join(runDir,'logs'),{recursive:true});
  const output=join(runDir,'logs','agent_stdout.txt');
  let existing='';
  try {existing=await readFile(output,'utf8');} catch {}
  await writeFile(output,existing ? `${existing}\n${content}` : content,'utf8');
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function normalize(value) {return String(value ?? '').replace(/\s+/g,'').trim();}
function normalizeKey(value) {return String(value ?? '').toLowerCase().replace(/[^a-z0-9]/g,'');}
function symbol(value) {
  return String(value ?? '').toLowerCase()
    .replaceAll('≥','>=').replaceAll('≤','<=')
    .replaceAll('≠','!=').replaceAll('−','-')
    .replaceAll('且','and').replaceAll('或','or')
    .replace(/\s+/g,'');
}
function number(value, fallback) {const result=Number(value);return Number.isFinite(result)?result:fallback;}
function text(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}
function shell(value) {return String(value).replace(/["`$\\]/g,'\\$&');}
