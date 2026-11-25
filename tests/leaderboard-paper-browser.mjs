import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

// Read-only browser acceptance for the paper-derived leaderboard baseline.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/leaderboard-paper');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({
  headless:true,
  executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',
});
const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
const checks=[],errors=[];
const check=(name,result)=>{assert.ok(result,name);checks.push(name);};
const rows=()=>page.locator('tbody tr[data-rank-record]');
page.on('pageerror',error=>errors.push(error.message));
await page.route('**/api/bootstrap',route=>route.fulfill({
  json:{user:null,reports:[],connections:[],customDatasets:[],publicReports:[]},
}));

async function go(route='/leaderboard'){
  await page.goto(base+'/#'+route);
  await page.reload();
  await page.waitForLoadState('networkidle');
  await page.waitForFunction(
    route=>document.querySelector('#main')?.dataset.route===route,
    route,
  );
}
async function settle(){
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(
    ()=>requestAnimationFrame(resolve),
  )));
}
async function noOverflow(){
  return page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1);
}

try{
  await go();
  check('Default leaderboard is the official version',
    await page.locator('[data-rank="type"]').inputValue()==='official');
  check('Official version uses the complete evaluation budget',
    await page.locator('[data-rank="budget"]').inputValue()==='official');
  check('Official leaderboard opens on the complete data ranking',
    await rows().count()===24&&
    await page.locator('[data-official-bar-chart] .official-bar-group').count()===24&&
    await page.locator('[data-official-category-chart]').count()===4&&
    await page.locator('[data-official-line-series]').count()===24);
  check('The two analysis charts follow the ranking table',
    await page.locator('#rank-data-section').evaluate((table,analysis)=>{
      const tableBox=table.getBoundingClientRect();
      const analysisBox=analysis.getBoundingClientRect();
      return analysisBox.top>tableBox.bottom;
    },await page.locator('#rank-analysis-section').elementHandle()));

  const summary=await page.locator('.rank-summary').innerText();
  check('Summary states the leading VCR',summary.includes('37.1%'));
  check('Summary states 24 combinations and 170 tasks',
    summary.includes('24')&&summary.includes('170'));
  check('Summary states six models and four frameworks',
    summary.includes('6')&&summary.includes('4'));

  const headers=await page.locator('.leaderboard-table thead th').allTextContents();
  for(const metric of ['VCR','FullAcc','VPR','Process']){
    check(`Table exposes ${metric}`,headers.some(text=>text.includes(metric)));
  }

  const first=page.locator('[data-rank-record="official-claude-opus-5-5-cc"]');
  const firstText=await first.innerText();
  check('First row matches paper rank one',
    firstText.includes('Claude Opus 5.5')&&firstText.includes('Claude Code'));
  check('First row reports VCR and 63 of 170 tasks',
    firstText.includes('37.1%')&&firstText.includes('63')&&firstText.includes('170'));
  check('First row reports FullAcc, VPR and Process',
    firstText.includes('42.9%')&&firstText.includes('72.9%')&&firstText.includes('85.3'));

  const second=page.locator('[data-rank-record="official-gpt-5-6-sol-cx"]');
  const secondText=await second.innerText();
  check('Second row matches paper rank two',
    secondText.includes('GPT-5.6-sol')&&secondText.includes('Codex CLI'));
  check('Second row reports 36.5% VCR and 62 of 170 tasks',
    secondText.includes('36.5%')&&secondText.includes('62'));

  const warning=await page.locator('.rank-trust-note').innerText();
  check('Official data uses formal evaluation wording',
    warning.includes('正式版本')&&warning.includes('170 道任务')&&
    !warning.includes('论文')&&!warning.includes('Table')&&!warning.includes('Figure'));

  await first.locator('.rank-name').click();
  const detail=page.locator('[id^="rank-evidence-"]');
  check('Expanded row shows all four official categories',
    await detail.locator('.paper-category-table tbody tr').count()===4);
  const categoryText=await detail.locator('.paper-category-table').innerText();
  check('Category rows preserve official task totals',
    categoryText.includes('74')&&categoryText.includes('47')&&
    categoryText.includes('26')&&categoryText.includes('23'));
  check('Expanded row explains VCR and VPR evidence rules',
    (await detail.innerText()).includes('VCR')&&
    (await detail.innerText()).includes('VPR'));

  await page.locator('[data-compare]').nth(0).check();
  await page.locator('[data-compare]').nth(1).check();
  await page.locator('[data-open-compare]').click();
  const comparison=page.locator('.rank-comparison');
  const comparisonText=await comparison.innerText();
  check('Official comparison identifies model and framework',
    comparisonText.includes('Claude Opus 5.5 / Claude Code')&&
    comparisonText.includes('GPT-5.6-sol / Codex CLI'));
  const comparisonBody=await comparison.locator('tbody').innerText();
  check('Official comparison includes all metrics',
    await comparison.locator('tbody tr').count()===13&&
    ['FullAcc','VPR','Process'].every(metric=>comparisonBody.includes(metric)));
  await page.locator('[data-close-modal]').click();
  await page.locator('[data-clear-compare]').click();

  await settle();
  check('Ranking page renders all 24 grouped bar combinations below the table',
    await page.locator('[data-official-bar-chart] .official-bar-group').count()===24);
  check('Bar chart uses one colored bar per combination',
    await page.locator('[data-official-bar-chart] .official-bar-fill').count()===24&&
    await page.locator('[data-bar-metric="vcr"]').getAttribute('aria-selected')==='true');
  const barColors=await page.locator('[data-official-bar-chart] .official-bar-fill').evaluateAll(
    bars=>[...new Set(bars.map(bar=>bar.getAttribute('fill')))],
  );
  check('Bar chart uses the reference-derived six-color model palette',
    barColors.length===6&&
    ['#cc785c','#1f1f1f','#736cd3','#047afe','#34a853','#f32e02'].every(
      color=>barColors.includes(color),
    ));
  check('Bar chart shows framework and provider marks with three-line labels',
    await page.locator('[data-official-bar-chart] .official-model-icon').count()===24&&
    await page.locator('[data-official-bar-chart] [data-brand-mark]').count()===48&&
    await page.locator('[data-official-bar-chart] .official-x-label').count()===24&&
    await page.locator('[data-official-bar-chart] .official-x-label tspan').count()===72);
  check('Difference view starts above zero and explains the truncated axis',
    await page.locator('[data-official-bar-chart] .official-bar-svg').getAttribute('data-y-min')==='15'&&
    await page.locator('[data-official-bar-chart] .official-bar-svg').getAttribute('data-y-max')==='40'&&
    (await page.locator('[data-official-bar-chart] .official-chart-note').innerText()).includes('起点不为 0'));
  await page.locator('[data-bar-metric="fullAcc"]').click();
  await settle();
  check('Bar chart switches to FullAcc values',
    await page.locator('[data-bar-metric="fullAcc"]').getAttribute('aria-selected')==='true'&&
    (await page.locator('[data-official-bar-chart]').textContent()).includes('FullAcc 均值')&&
    await page.locator('[data-official-bar-chart] .official-bar-svg').getAttribute('data-y-min')==='20'&&
    await page.locator('[data-official-bar-chart] .official-bar-svg').getAttribute('data-y-max')==='50');
  await page.locator('[data-bar-metric="vcr"]').click();
  const categoryCharts=page.locator('[data-official-category-chart]');
  check('Four category bar charts follow the overall chart',
    await categoryCharts.count()===4&&
    await page.locator('[data-official-category-chart] .official-bar-group').count()===96&&
    await page.locator('[data-official-category-chart] .official-model-icon').count()===96&&
    await page.locator('[data-official-category-chart] [data-brand-mark]').count()===192&&
    await page.locator('[data-official-category-chart] .official-x-label tspan').count()===288);
  const categoryTitles=await categoryCharts.locator('.official-chart-heading h3').allInnerTexts();
  const categoryChartsText=(await categoryCharts.allInnerTexts()).join(' ');
  check('Category charts use the four paper categories and task totals',
    ['Relations','Search','Exact','Bounds'].every(title=>categoryTitles.some(text=>text.includes(title)))&&
    ['74','47','26','23'].every(total=>categoryChartsText.includes(total)));
  const categoryAxes=await categoryCharts.locator('.official-bar-svg').evaluateAll(
    svgs=>svgs.map(svg=>({min:Number(svg.dataset.yMin),max:Number(svg.dataset.yMax)})),
  );
  check('Every category uses an explained truncated difference axis',
    categoryAxes.every(axis=>axis.min>0&&axis.max>axis.min)&&
    (await categoryCharts.locator('.official-chart-note').allInnerTexts()).every(text=>text.includes('起点不为 0')));
  const relations=page.locator('[data-official-category-chart="relations"]');
  check('Relations starts with Claude Opus 5.5 / Claude Code at 36.5%',
    (await relations.locator('.official-bar-value').first().textContent()).includes('36.5%')&&
    (await relations.locator('.official-bar-group').first().textContent()).includes('Claude Opus 5.5'));
  const fiveChartStyles=await page.locator('[data-official-bar-chart],[data-official-category-chart]').evaluateAll(
    charts=>charts.map(chart=>{
      const style=getComputedStyle(chart);
      return [style.backgroundColor,style.borderRadius,style.borderTopWidth,style.paddingTop,style.boxShadow].join('|');
    }),
  );
  check('All five bar charts share one visual system',
    fiveChartStyles.length===5&&new Set(fiveChartStyles).size===1);
  await page.locator('[data-official-category-charts]').screenshot({
    path:resolve(out,'paper-category-bar-charts-1440.png'),
    animations:'disabled',
  });
  check('Analysis renders six model panels and 24 framework curves',
    await page.locator('[data-line-panel]').count()===6&&
    await page.locator('[data-official-line-series]').count()===24);
  check('Line chart exposes five elapsed-time ticks per panel',
    await page.locator('.official-line-x text').count()===30);
  const microFigure=page.locator('[data-paper-micro-macro-figure]');
  const tokenFigure=page.locator('[data-paper-token-figure]');
  check('The two scientific figures are appended below the trend chart',
    await page.locator('.paper-research-figure').count()===2&&
    await microFigure.locator('[data-paper-micro-point]').count()===24&&
    await tokenFigure.locator('[data-paper-token-point]').count()===24);
  check('Scientific figures follow the trend chart in order',
    await page.locator('[data-official-line-chart]').evaluate((trend,micro)=>{
      const trendBox=trend.getBoundingClientRect();
      const microBox=micro.getBoundingClientRect();
      const tokenBox=document.querySelector('[data-paper-token-figure]').getBoundingClientRect();
      return microBox.top>=trendBox.bottom&&tokenBox.top>=microBox.bottom;
    },await microFigure.elementHandle()));
  check('Broken token axis keeps every tick label separated',
    await tokenFigure.locator('.paper-x-tick').evaluateAll(labels=>{
      const boxes=labels.map(label=>label.getBoundingClientRect()).sort((a,b)=>a.left-b.left);
      return boxes.every((box,index)=>!index||box.left>=boxes[index-1].right+2);
    }));
  check('Scientific figures preserve exact highlighted values',
    (await page.locator('[data-paper-micro-point="official-claude-opus-5-5-cc"] title').textContent()).includes('36.9%')&&
    (await page.locator('[data-paper-micro-point="official-claude-opus-5-5-cc"] title').textContent()).includes('49.3%')&&
    (await page.locator('[data-paper-token-point="official-claude-opus-5-5-cc"] title').textContent()).includes('414,000')&&
    (await page.locator('[data-paper-token-point="official-claude-opus-5-5-cc"] title').textContent()).includes('37.1%'));
  check('Scientific figures reproduce model and framework legends',
    await microFigure.locator('.paper-model-legend span').count()===6&&
    await microFigure.locator('.paper-shape-legend span').count()===4&&
    await tokenFigure.locator('.paper-model-legend span').count()===6&&
    await tokenFigure.locator('.paper-shape-legend span').count()===4);
  check('Scientific figures include axes, annotations and formal captions',
    (await microFigure.textContent()).includes('Micro VCR (%)')&&
    (await microFigure.textContent()).includes('Direction-macro VCR (%)')&&
    (await microFigure.textContent()).includes('Claude Opus 5.5 + Claude Code')&&
    (await tokenFigure.textContent()).includes('Mean tokens per task')&&
    (await tokenFigure.textContent()).includes('Claude Code (37.1%)')&&
    (await tokenFigure.locator('figcaption').innerText()).includes('资源–完成率前沿'));
  check('Inline analysis includes both chart types and fixed axes',
    await page.locator('[data-official-bar-chart]').count()===1&&
    await page.locator('[data-official-category-chart]').count()===4&&
    await page.locator('[data-official-line-chart]').count()===1&&
    await page.locator('.paper-research-figure').count()===2&&
    await page.locator('.official-grid').count()===11);
  check('Analysis chart starts with the official leader',
    (await page.locator('[data-official-bar-chart]').innerText()).includes('Opus 5.5'));
  await page.locator('[data-official-bar-chart]').screenshot({
    path:resolve(out,'paper-bar-chart-1440.png'),
    animations:'disabled',
  });
  await microFigure.screenshot({
    path:resolve(out,'paper-micro-macro-figure-1440.png'),
    animations:'disabled',
  });
  await tokenFigure.screenshot({
    path:resolve(out,'paper-token-figure-1440.png'),
    animations:'disabled',
  });
  await page.screenshot({
    path:resolve(out,'paper-ranking-charts-1440.png'),
    fullPage:true,
    animations:'disabled',
  });

  for(const width of [1024,768,390,360]){
    await page.setViewportSize({width,height:width<=390?844:1000});
    await settle();
    check(`${width}px ranking and charts have no page overflow`,await noOverflow());
    check(`${width}px ranking keeps all 24 rows`,await rows().count()===24);
    check(`${width}px ranking keeps all five bar charts and the trend chart`,
      await page.locator('[data-official-bar-chart] .official-bar-group').count()===24&&
      await page.locator('[data-official-category-chart]').count()===4&&
      await page.locator('[data-official-category-chart] .official-bar-group').count()===96&&
      await page.locator('[data-official-line-series]').count()===24);
    check(`${width}px bottom figures retain all 24 points`,
      await page.locator('[data-paper-micro-point]').count()===24&&
      await page.locator('[data-paper-token-point]').count()===24);
    if(width===390){
      await page.locator('[data-official-bar-chart]').screenshot({
        path:resolve(out,'paper-bar-chart-390.png'),
        animations:'disabled',
      });
      await page.locator('[data-official-category-charts]').screenshot({
        path:resolve(out,'paper-category-bar-charts-390.png'),
        animations:'disabled',
      });
      await page.screenshot({
        path:resolve(out,'paper-ranking-charts-390.png'),
        fullPage:true,
        animations:'disabled',
      });
    }
    if(width<=390){
      check(`${width}px line chart keeps all category labels within the card`,
        await page.locator('.official-line-x text').evaluateAll((labels,card)=>{
          const bounds=card.getBoundingClientRect();
          return labels.every(label=>{
            const box=label.getBoundingClientRect();
            return box.left>=bounds.left&&box.right<=bounds.right;
          });
        },await page.locator('[data-official-line-chart]').elementHandle()));
    }
  }
  check('No browser runtime errors',errors.length===0);
  await writeFile(
    resolve(out,'paper-browser-results.json'),
    JSON.stringify({passed:checks.length,checks,errors},null,2),
  );
  console.log(`PASS ${checks.length} paper leaderboard browser checks.`);
}finally{
  await browser.close();
}
