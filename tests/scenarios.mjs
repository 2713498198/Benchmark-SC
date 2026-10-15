import assert from 'node:assert/strict';

export async function runScenarios({page,check,go,shot}) {
 const latest=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('cryptobench.v1')).reports[0]);
 const reportCount=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('cryptobench.v1')).reports.length);
 const fresh=async route=>{await go(route);await page.reload();await page.locator('#main h1').first().waitFor();};
 const openReport=async()=>{const link=page.locator('a[href^="#/report/"]').first();await link.waitFor();await link.click();await page.locator('[data-report-json]').waitFor();return latest();};
 const ready=async ({scope='full',failure='none',dataset,model='GPT-4.1'}={})=>{
  await fresh('/evaluate');
  await page.locator('[data-form="model"]').selectOption(model);
  await page.locator('[data-check-connection]').click();
  await page.locator('[data-eval-next]').click();
  if(dataset)await page.locator('[data-form="datasetId"]').selectOption(dataset);
  await page.locator('[data-form="scope"]').selectOption(scope);
  await page.locator('[data-form="failure"]').selectOption(failure);
  await page.locator('[data-eval-next]').click();
 };
 const start=()=>page.locator('[data-eval-next]').click();
 const downloadText=async selector=>{const event=page.waitForEvent('download');await page.locator(selector).click();const d=await event,s=await d.createReadStream(),chunks=[];for await(const chunk of s)chunks.push(chunk);return {name:d.suggestedFilename(),text:Buffer.concat(chunks).toString('utf8').replace(/^\uFEFF/,'')};};
 const environment=async value=>{await page.locator('[data-environment]').click();await page.locator('[data-env="'+value+'"]').click();};

 await go('/evaluate?environment=local');check('direct local interface',await page.locator('[data-eval-source="local"]').getAttribute('class')==='active'&&await page.locator('[data-form="endpoint"]').isVisible());
 await page.locator('[data-eval-source="cloud"]').click();check('evaluation source switch',await page.locator('[data-eval-source="cloud"]').getAttribute('class')==='active');
 // Saved connections can name models outside the example catalog.
 await go('/workspace?tab=connections');
 await page.locator('[data-add-connection]').click();
 await page.locator('#connection-form [name="name"]').fill('自定义模型 Agent');
 await page.locator('#connection-form [name="url"]').fill('http://127.0.0.1:9000');
 await page.locator('#connection-form [name="model"]').fill('research-local-model');
 await page.locator('#connection-form button[type="submit"]').click();
 check('saved connection',await page.locator('[data-remove-connection]').count()===1);
 await fresh('/evaluate');
 const connectionId=await page.locator('#saved-connection option').nth(1).getAttribute('value');
 await page.locator('#saved-connection').selectOption(connectionId);
 check('custom model remains selected',await page.locator('[data-form="model"]').inputValue()==='research-local-model');
 await page.locator('[data-form="endpoint"]').fill('ftp://example.org');
 await page.locator('[data-check-connection]').click();
 check('reject non-http connection',await page.locator('#connection-status').innerText().then(t=>t.includes('有效')));
 await page.locator('[data-eval-next]').click();
 check('invalid connection blocks next',await page.locator('[data-form="endpoint"]').isVisible());
 await page.locator('[data-form="endpoint"]').fill('http://127.0.0.1:9000');
 await page.locator('[data-check-connection]').click();
 await page.locator('[data-form="endpoint"]').fill('http://127.0.0.1:9001');
 check('connection edit invalidates check',await page.locator('#connection-status').innerText().then(t=>t.includes('重新检查')));
 await page.locator('[aria-label="访问凭证"]').fill('test-only-credential-should-not-persist');
 await page.locator('[data-check-connection]').click();
 await page.locator('[data-eval-next]').click();
 await page.locator('[data-eval-next]').click();
 await start();const custom=await openReport();
 check('custom model in report',custom.model==='research-local-model');
 check('credential not persisted',await page.evaluate(()=>!localStorage.getItem('cryptobench.v1').includes('test-only-credential-should-not-persist')));
 const json=await downloadText('[data-report-json]');
 check('JSON report download',json.name===custom.id+'.json'&&JSON.parse(json.text).id===custom.id&&JSON.parse(json.text).demo===true);
 const csv=await downloadText('[data-report-csv]');
 check('CSV report download',csv.name===custom.id+'.csv'&&csv.text.includes('"passed_a","passed_b","total","demo"'));
 await page.locator('[data-sync-report]').click();await page.locator('#confirm-sync').click();
 check('sync preserves private visibility',await latest().then(r=>r.synced&&!r.published));

 // Identical rules apply to local and cloud sources.
 await environment('cloud');await ready();await start();const cloud=await openReport();
 await environment('local');await ready();await start();const local=await openReport();
 check('local and cloud use same scoring',cloud.source==='cloud'&&local.source==='local'&&cloud.passed===local.passed&&cloud.total===local.total&&cloud.version===local.version);
 await ready({scope:'sample'});await start();const sample=await openReport();
 check('sample accounting',sample.total===8&&sample.categories.reduce((n,c)=>n+c.total,0)===8&&sample.categories.reduce((n,c)=>n+c.passed,0)===sample.passed);
 check('sample cannot enter leaderboard',await page.locator('[data-publish-report]').isDisabled());
 const beforeFailure=await reportCount();
 for(const [failure,title] of [['error','Agent 连接失败'],['timeout','任务执行超时']]){
  await ready({failure});await start();await page.getByRole('heading',{name:title,exact:true}).waitFor();
  check('run '+failure,await reportCount()===beforeFailure);
 }
 await ready();await start();await page.locator('[data-cancel-run]').click();
 check('cancel stops run',await page.locator('.run-heading h3').innerText()==='任务已取消'&&await reportCount()===beforeFailure);

 // Published task versions cannot overwrite each other or share a leaderboard.
 await go('/admin');await page.locator('[data-template]').click();
 const pack=JSON.parse(await page.locator('#dataset-json').inputValue());
 await page.locator('[data-validate]').click();
 check('published version cannot be overwritten',await page.locator('#import-error').innerText().then(t=>t.includes('已经发布')));
 pack.version='2.0.0';pack.tasks.push({...pack.tasks[0],id:'task-002'});
 await page.locator('#dataset-json').fill(JSON.stringify(pack));await page.locator('[data-validate]').click();
 pack.name='自定义 SPN 新版本';await page.locator('#dataset-json').fill(JSON.stringify(pack));
 check('editing import invalidates preview',await page.locator('[data-publish-dataset]').isDisabled());
 await page.locator('[data-validate]').click();await page.locator('[data-publish-dataset]').click();
 check('multiple dataset versions persist',await page.evaluate(()=>JSON.parse(localStorage.getItem('cryptobench.v1')).customDatasets.filter(d=>d.id==='custom-spn').length===2));
 await go('/datasets/custom-spn?version=2.0.0');
 check('dataset detail keeps version',await page.locator('.detail-title-meta').innerText().then(t=>t.includes('2.0.0')&&t.includes('2 个任务')));
 await page.locator('[data-detail-tab="conditions"]').click();
 const task=JSON.parse((await downloadText('[data-download-dataset]')).text);
 check('task sample keeps version',task.dataset==='custom-spn'&&task.version==='2.0.0');
 await page.locator('a[href^="#/evaluate?dataset="]').click();
 await page.locator('[data-check-connection]').click();await page.locator('[data-eval-next]').click();
 check('dataset version passed to evaluation',await page.locator('[data-form="datasetId"]').inputValue()==='custom-spn@2.0.0');
 await page.locator('[data-form="datasetId"]').selectOption('mini-spn@1.0.0');await page.locator('[data-eval-next]').click();
 check('changing dataset overrides entry query',await page.locator('.info-list').innerText().then(t=>t.includes('Mini-SPN')&&t.includes('120 项')));
 await ready({dataset:'custom-spn@2.0.0'});await start();const versioned=await openReport();
 check('report pins selected task version',versioned.datasetId==='custom-spn'&&versioned.version==='2.0.0'&&versioned.total===2);
 await page.locator('[data-sync-report]').click();await page.locator('#confirm-sync').click();await page.locator('[data-publish-report]').click();await page.locator('#confirm-publish').click();
 await go('/leaderboard?dataset=custom-spn&version=2.0.0');
 check('leaderboard version entry',await page.locator('[data-rank="version"]').inputValue()==='2.0.0'&&await page.locator('tbody tr').count()===1);
 await page.locator('[data-rank="version"]').selectOption('1.0.0');
 check('leaderboard versions remain isolated',await page.locator('tbody tr').count()===0);
 await page.locator('[data-rank="version"]').selectOption('2.0.0');
 const rankCsv=await downloadText('[data-export-rank]');
 check('leaderboard CSV respects version',rankCsv.text.includes('2.0.0')&&!rankCsv.text.includes('1.0.0'));
 await go('/datasets');await shot('datasets-desktop');

 // Distinct contenders and independent Agent metadata are retained.
 await fresh('/arena');
 const a=await page.locator('[data-duel="a"]').inputValue();
 await page.locator('[data-duel="b"]').selectOption(a);await page.locator('[data-start-pk]').click();
 check('duplicate contenders blocked',await page.locator('.run-heading').count()===0&&await page.locator('#toast').innerText().then(t=>t.includes('不同')));
 await page.locator('[data-pk-mode="agents"]').click();await shot('arena-desktop');
 await page.locator('[data-start-pk]').click();const pk=await openReport();
 check('Agent PK records both identities',pk.competitors.length===2&&pk.competitors[0].agent==='CipherPilot'&&pk.competitors[1].agent==='S-box Sage');
 check('Agent PK retains tool conditions',pk.competitors.every(c=>c.agentVersion&&c.tools.includes('Python')));
 check('PK time formatting',!await page.locator('#main').innerText().then(t=>t.includes('45.800000000')));
 await shot('arena-report');await page.reload();await page.locator('[data-report-json]').waitFor();
 check('report survives reload',await latest().then(r=>r.id===pk.id));
 await go('/workspace');await page.locator('a[href="#/evaluate?new=1"]').first().click();await page.locator('[data-form="endpoint"]').waitFor();check('workspace starts a new evaluation',await page.locator('[data-form="endpoint"]').isVisible());

 await go('/docs?tab=methodology');check('methodology documentation',await page.getByRole('heading',{name:'评分规则',exact:true}).isVisible());
 await go('/docs?tab=about');check('reference documentation',await page.locator('a[href="https://aider.chat/docs/leaderboards/"]').isVisible());
 await go('/%E0%A4%A');check('malformed route handled',await page.locator('#main h1').innerText()==='页面未找到');
 await go('/datasets/%25');check('encoded percent route handled',await page.locator('#main h1').innerText()==='测评集不存在');

 await go('/register');
 await page.locator('[name="name"]').fill('测试研究员');await page.locator('[name="email"]').fill('test@example.com');await page.locator('[name="password"]').fill('test-only-password');await page.locator('#auth-form button[type="submit"]').click();await page.locator('[data-logout]').waitFor();
 check('registration interface',await page.locator('#main h1').innerText().then(t=>t.includes('测试研究员')));
 check('password not persisted',await page.evaluate(()=>!localStorage.getItem('cryptobench.v1').includes('test-only-password')));
 await go('/forgot-password');await page.locator('[name="email"]').fill('test@example.com');await page.locator('#auth-form button[type="submit"]').click();await page.getByRole('heading',{name:'欢迎回来',exact:true}).waitFor();
 check('password reset interface',await page.locator('#main h1').innerText()==='欢迎回来');
 await page.locator('[data-demo-user="admin"]').click();
}
