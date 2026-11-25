import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

// Read-only acceptance for the paper leaderboard preview on the homepage.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/home-paper');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({
  headless:true,
  executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',
});
const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
const checks=[],errors=[];
const check=(name,result)=>{assert.ok(result,name);checks.push(name);};
page.on('pageerror',error=>errors.push(error.message));
await page.route('**/api/bootstrap',route=>route.fulfill({
  json:{user:null,reports:[],connections:[],customDatasets:[],publicReports:[]},
}));
await page.route('**/api/reports/public',route=>route.fulfill({json:[]}));

async function go(){
  await page.goto(base+'/#/');
  await page.reload();
  await page.waitForLoadState('networkidle');
  await page.evaluate(()=>document.fonts.ready);
  await page.locator('[data-home-paper-board]').waitFor();
}
async function settle(){
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(
    ()=>requestAnimationFrame(resolve),
  )));
}

try{
  await go();
  check('Homepage defaults to the official version',
    await page.locator('[data-home-filter="type"]').inputValue()==='official');
  check('Homepage defaults to the complete evaluation budget',
    await page.locator('[data-home-filter="budget"]').inputValue()==='official');
  check('Official version does not render the live cost chart',
    await page.locator('[data-chart-point]').count()===0);
  check('Official source filter is explicit',
    JSON.stringify(await page.locator('[data-home-filter="source"] option').allTextContents())===
    JSON.stringify(['全部来源','正式版本']));
  check('Homepage labels the data as the official version',
    (await page.locator('.home-cost-trust .badge').innerText())==='正式版本');

  const section=page.locator('[data-home-cost-chart]');
  await page.locator('.home-data-note summary').click();
  const text=await section.innerText();
  check('Homepage states the official evaluation scope',
    text.includes('170 道任务')&&text.includes('24 组组合')&&
    text.includes('3600s')&&text.includes('2M tokens'));
  check('Homepage uses formal evaluation wording',
    text.includes('正式版本')&&!text.includes('论文')&&
    !text.includes('Table')&&!text.includes('Figure'));
  await page.locator('.home-data-note summary').click();
  check('Overview shows the leading VCR and paper means',
    text.includes('37.1%')&&text.includes('30.8% VCR')&&
    text.includes('37.3')&&text.includes('49.8')&&text.includes('68.0'));
  check('Homepage preview lists six leading combinations',
    await page.locator('[data-home-paper-row]').count()===6);
  check('Homepage preserves tied paper ranks',
    JSON.stringify(await page.locator('.home-paper-place').allTextContents())===
    JSON.stringify(['01','02','03','03','05','05']));

  const first=page.locator('[data-home-paper-row="official-claude-opus-5-5-cc"]');
  const firstText=await first.innerText();
  check('Homepage first row matches paper rank one',
    firstText.includes('Claude Opus 5.5')&&firstText.includes('Claude Code'));
  check('Homepage first row exposes four paper metrics',
    firstText.includes('37.1%')&&firstText.includes('42.9%')&&
    firstText.includes('72.9%')&&firstText.includes('85.3'));
  const secondText=await page.locator('[data-home-paper-row="official-gpt-5-6-sol-cx"]').innerText();
  check('Homepage second row matches paper rank two',
    secondText.includes('GPT-5.6-sol')&&secondText.includes('Codex CLI')&&
    secondText.includes('36.5%'));
  check('Homepage shows all four task categories',
    ['74','47','26','23'].every(count=>text.includes(count))&&
    ['关系分析','搜索优化','精确计算','边界推导'].every(label=>text.includes(label)));

  await page.screenshot({
    path:resolve(out,'home-paper-1440.png'),
    fullPage:true,
    animations:'disabled',
  });
  const href=await page.locator('.home-paper-footer>.text-link').getAttribute('href');
  check('Full leaderboard link preserves official context',
    href.includes('type=official')&&href.includes('budget=official')&&href.includes('source=all'));
  await page.locator('.home-paper-footer>.text-link').click();
  await page.locator('.leaderboard-page').waitFor();
  check('Homepage link opens the complete official leaderboard',
    await page.locator('[data-rank="type"]').inputValue()==='official'&&
    await page.locator('tbody tr[data-rank-record]').count()===24);

  await page.setViewportSize({width:390,height:844});
  await go();
  await settle();
  check('390px homepage paper leaderboard has no horizontal overflow',
    await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  check('390px preview keeps all four metrics visible',
    await page.locator('[data-home-paper-row]').first().locator('.home-paper-metrics>div').count()===4&&
    await page.locator('[data-home-paper-row]').first().locator('.home-paper-metrics>div').evaluateAll(
      cells=>cells.every(cell=>{
        const box=cell.getBoundingClientRect();
        return box.width>0&&box.height>0&&box.right<=innerWidth+1;
      }),
    ));
  await page.screenshot({
    path:resolve(out,'home-paper-390.png'),
    fullPage:true,
    animations:'disabled',
  });
  check('No browser runtime errors',errors.length===0);
  await writeFile(
    resolve(out,'home-paper-results.json'),
    JSON.stringify({passed:checks.length,checks,errors},null,2),
  );
  console.log(`PASS ${checks.length} homepage paper leaderboard browser checks.`);
}finally{
  await browser.close();
}
