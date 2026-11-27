import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,rm,writeFile,copyFile} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {dockerImageMetadata,dockerVersion} from './docker-process.mjs';
import {normalizeDockerProfile,runDockerAgent} from './docker-agent.mjs';
import {evaluateTask} from './evaluator.mjs';

export async function runOffline(args = {}) {
  if (args.help) {
    printUsage();
    return;
  }
  const startedAt=new Date().toISOString();
  const tasksPath=resolve(String(args.tasks || './tasks.json'));
  const agentPath=resolve(String(args['agent-config'] || './agent.json'));
  const outputRoot=resolve(String(args.output || `./cryptobench-offline-results/${stamp()}`));
  const scope=String(args.scope || 'sample');
  if (!['sample','full'].includes(scope)) throw new Error('--scope 只能是 sample 或 full');
  const limit=Number(args.limit || (scope === 'sample' ? 8 : Number.MAX_SAFE_INTEGER));
  const seed=Number(args.seed ?? 42);
  const budget=String(args.budget || 'standard');
  const judgeImage=String(args['judge-image'] || 'cryptobench-offline-judge:4.2.0');
  const publicRoot=resolve(String(args['public-root'] || dirname(tasksPath)));
  const keepWorkspace=Boolean(args['keep-workspace']);

  const catalog=await loadJson(tasksPath);
  if (!Array.isArray(catalog.tasks) || !catalog.tasks.length) throw new Error('题库文件没有 tasks 数组');
  const profile=selectProfile(await loadAgentConfig(agentPath),String(args.agent || ''));
  if (profile.network === 'bridge' && !args['allow-network']) {
    throw new Error('离线模式要求 Docker Agent 使用 --network none；如确需联网，请显式添加 --allow-network');
  }
  profile.cpus='2';
  profile.memory='2g';
  profile.pidsLimit='256';
  const runningDockerVersion=await dockerVersion();
  const imageInfo=await dockerImageMetadata(profile.image);
  const imageId=imageInfo.Id;
  const labels=imageInfo.Config?.Labels || {};
  if (labels['org.cryptobench.runtime'] !== 'cryptobench-agent-runtime'
      && !args['allow-nonstandard-runtime']) {
    throw new Error('Agent 镜像未继承 CryptoBench 标准运行环境；如需跳过检查，请显式添加 --allow-nonstandard-runtime');
  }
  const selected=catalog.tasks.slice(0,Math.max(1,Math.min(limit,catalog.tasks.length)));
  const runId='offline_'+randomUUID();
  const runRoot=join(outputRoot,runId);
  const workspacesRoot=join(runRoot,'workspaces');
  await mkdir(workspacesRoot,{recursive:true});

  console.log(`CryptoBench 离线测评：${catalog.datasetId || 'unknown'} v${catalog.version || 'unknown'}`);
  console.log(`Agent：${profile.name || profile.model || 'Docker Agent'}`);
  console.log(`镜像：${profile.image} (${imageId})`);
  console.log(`任务：${selected.length} · 随机种子：${seed} · 预算：${budget}`);
  console.log(`报告目录：${runRoot}`);
  if (profile.network === 'bridge') console.log('警告：当前 Agent 配置允许容器联网；这不是完整的离线执行。');

  const results=[];
  let passed=0;
  for (let index=0;index<selected.length;index++) {
    const task=selected[index];
    const taskLabel=`${String(index+1).padStart(3,'0')}/${String(selected.length).padStart(3,'0')} ${task.id}`;
    const workspace=join(workspacesRoot,safeName(task.id));
    const runRootTask=join(runRoot,'evaluation',safeName(task.id));
    await mkdir(workspace,{recursive:true});
    await materializeInputs(task,workspace,publicRoot);
    console.log(`任务开始：${taskLabel}`);
    const request={
      runId,
      taskId:task.id,
      attempt:1,
      agentName:profile.name || 'Docker Agent',
      model:profile.model || 'docker-agent',
      seed,
      dataset:{id:catalog.datasetId,version:catalog.version},
      task:{
        id:task.id,
        question:task.question,
        input:task.input,
        category:task.category,
        publicFiles:task.publicFiles,
        files:await inputMetadata(task,publicRoot),
      },
      budget:{timeoutSeconds:timeoutForTask(task,budget)},
      captureWorkspace:true,
    };
    const item={taskId:task.id,category:task.category};
    try {
      const response=await runDockerAgent(profile,request,workspace,{
        onProgress:message => console.log(`任务进度：${task.id} · ${message}`),
      });
      const verification=await evaluateTask(task,response,{
        evaluatorVersion:catalog.evaluatorVersion,
        judgeImage,
        runRoot:runRootTask,
        validationTimeoutSeconds:180,
      });
      item.status=verification.status;
      item.passed=verification.passed;
      item.score=verification.score;
      item.response=response;
      item.verification=verification;
      if (verification.passed) passed++;
      console.log(`任务完成：${task.id} · ${verification.status} · ${verification.score}`);
    } catch (error) {
      item.status=error.code === 'agent_timeout' ? 'timeout' : 'failed';
      item.passed=false;
      item.score=0;
      item.error={code:error.code || 'offline_error',message:String(error.message || error).slice(0,1000)};
      console.log(`任务失败：${task.id} · ${item.error.code} · ${item.error.message}`);
    } finally {
      results.push(item);
      if (!keepWorkspace) await rm(workspace,{recursive:true,force:true});
    }
  }

  const report={
    schema:'cryptobench-offline-report-v1',
    runId,
    createdAt:startedAt,
    finishedAt:new Date().toISOString(),
    datasetId:catalog.datasetId,
    version:catalog.version,
    evaluatorVersion:catalog.evaluatorVersion,
    seed,
    scope,
    budget,
    network:profile.network,
    dockerVersion:runningDockerVersion,
    agent:{
      name:profile.name || 'Docker Agent',
      model:profile.model || 'docker-agent',
      version:profile.version || 'unknown',
      image:profile.image,
      imageId,
      runtime:labels['org.cryptobench.runtime'] || 'nonstandard',
      runtimeVersion:labels['org.cryptobench.runtime.version'] || 'unknown',
    },
    summary:{
      total:results.length,
      completed:results.filter(item => ['passed','failed'].includes(item.status)).length,
      passed,
      failed:results.filter(item => item.status !== 'passed').length,
      score:results.length ? passed/results.length : 0,
    },
    results,
  };
  await mkdir(runRoot,{recursive:true});
  await writeFile(join(runRoot,'report.json'),JSON.stringify(report,null,2)+'\n','utf8');
  await writeFile(join(runRoot,'report.md'),markdownReport(report),'utf8');
  console.log(`离线测评完成：${passed}/${results.length} · ${(report.summary.score*100).toFixed(1)}%`);
  console.log(`报告：${join(runRoot,'report.json')}`);
}

async function loadAgentConfig(path) {
  const value=await loadJson(path);
  const profiles=Array.isArray(value) ? value : Array.isArray(value?.agents) ? value.agents : [value];
  return profiles.filter(item => item && (item.adapter === 'docker-command' || item.image));
}

function selectProfile(profiles,name) {
  if (!profiles.length) throw new Error('Agent 配置中没有 Docker Agent profile');
  const selected=name ? profiles.find(profile => profile.name === name || profile.model === name) : profiles[0];
  if (!selected) throw new Error(`找不到 Agent profile：${name}`);
  return normalizeDockerProfile(selected);
}

async function materializeInputs(task,workspace,publicRoot) {
  const attachments=Array.isArray(task.provenance?.attachments) ? task.provenance.attachments : [];
  for (const file of attachments) {
    const relativePath=String(file.path || '');
    if (!relativePath || relativePath.split('/').includes('..')) continue;
    const source=join(publicRoot,relativePath);
    const target=join(workspace,'attachments',relativePath.split('/').at(-1));
    await mkdir(dirname(target),{recursive:true});
    await copyFile(source,target);
  }
}

async function inputMetadata(task,publicRoot) {
  const attachments=Array.isArray(task.provenance?.attachments) ? task.provenance.attachments : [];
  const files=[];
  for (const file of attachments) {
    const content=await readFile(join(publicRoot,file.path));
    files.push({
      path:`attachments/${String(file.path).split('/').at(-1)}`,
      encoding:'base64',
      bytes:content.length,
      sha256:createHash('sha256').update(content).digest('hex'),
      content:content.toString('base64'),
    });
  }
  return files;
}

function timeoutForTask(task,budget) {
  const answer=task.input?.budgetClass || task.budgetClass;
  if (budget === 'extended') return 600;
  return answer ? 300 : 300;
}

async function loadJson(path) {
  try {return JSON.parse(await readFile(path,'utf8'));}
  catch (error) {
    throw new Error(`无法读取 JSON ${path}：${error.message}`);
  }
}

function safeName(value) {return String(value).replace(/[^A-Za-z0-9._-]+/g,'_').slice(0,120);}
function stamp() {return new Date().toISOString().replace(/[:.]/g,'-');}

function markdownReport(report) {
  const lines=[
    '# CryptoBench 离线测评报告',
    '',
    `- Run ID：${report.runId}`,
    `- 题库：${report.datasetId} / ${report.version}`,
    `- Agent：${report.agent.name} / ${report.agent.model}`,
    `- Docker 镜像：${report.agent.image}`,
    `- 网络：${report.network}`,
    `- 成绩：${report.summary.passed} / ${report.summary.total}（${(report.summary.score*100).toFixed(1)}%）`,
    '',
    '| 任务 | 状态 | 分数 |',
    '|---|---|---:|',
  ];
  for (const item of report.results) lines.push(`| ${item.taskId} | ${item.status} | ${item.score ?? 0} |`);
  return lines.join('\n')+'\n';
}

function printUsage() {
  console.log(`CryptoBench 离线 Docker 测评

用法：
  node runner/src/cli.mjs offline --tasks tasks.json --agent-config agent.json [options]

选项：
  --scope sample|full        sample 默认前 8 题，full 运行全部题目
  --limit N                  限制本次任务数量
  --seed N                   记录随机种子，默认 42
  --budget standard|extended 预算方案
  --output DIR               报告输出目录
  --public-root DIR          公开题面和附件根目录
  --judge-image IMAGE        本地工作区验证器镜像
  --keep-workspace           保留每题工作区
  --agent NAME               多 Agent 配置中指定 profile
  --allow-nonstandard-runtime 允许运行未继承标准基础镜像的 Agent（不建议）
  --allow-network            允许 bridge 网络；会退出完整离线模式
`);
}
