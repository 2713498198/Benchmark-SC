import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';

// A local static server and browser-only API fixtures: never reads or writes a project database.
const root=path.resolve('src/main/resources/static');
const mime={'.js':'text/javascript','.css':'text/css','.html':'text/html','.svg':'image/svg+xml','.woff2':'font/woff2'};
const server=createServer(async(req,res)=>{try{const pathname=decodeURIComponent(new URL(req.url,'http://localhost').pathname);const file=path.resolve(root,'.'+(pathname==='/'?'/index.html':pathname));if(!file.startsWith(root+path.sep))throw Error('outside static');const body=await readFile(file);res.writeHead(200,{'content-type':mime[path.extname(file)]||'application/octet-stream'});res.end(body);}catch{res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port;
let browser;
const summaries=['1.9.0','1.10.0'].map(version=>({id:'custom-browser',version,name:'Browser fixture '+version,type:'分组密码',algorithm:'Teaching SPN',description:'Public fixture',tasks:1,custom:true,demo:false,published:true,executionReady:true,evaluatorVersion:'declarative-evaluator-1.0.0',catalogSha256:'a'.repeat(64),catalogUrl:'/api/datasets/custom-browser/versions/'+version+'/catalog'}));
let published=null,validatedBody=null;
const publicTask={id:'task-1',question:'Return the number of rounds.',category:'Structure',input:{rounds:4},requiredTools:[],requiredCapabilities:[]};
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/api/**',async route=>{
  const request=route.request(),url=new URL(request.url());
  if(url.pathname==='/api/bootstrap')return route.fulfill({json:{user:{id:'fixture-admin',name:'Fixture admin',role:'admin'},reports:[],connections:[],publicReports:[],customDatasets:summaries}});
  if(url.pathname==='/api/auth/csrf')return route.fulfill({json:{headerName:'X-CSRF-TOKEN',token:'fixture'}});
  if(url.pathname.endsWith('/catalog')){const summary=summaries.find(item=>item.catalogUrl===url.pathname);return route.fulfill({contentType:'application/json',body:JSON.stringify({dataset:summary,tasks:[{...publicTask,input:{rounds:4,decimal:'PRECISE_NUMBER'},question:'Version '+summary.version+' public question'}]}).replace('"PRECISE_NUMBER"','1.00000000000000001')});}
  if(url.pathname==='/api/datasets/custom/validate'){validatedBody=request.postDataJSON();return route.fulfill({json:{valid:true,errors:[],preview:{dataset:{...summaries[0],id:validatedBody.id,version:validatedBody.version,name:validatedBody.name},tasks:[publicTask]}}});}
  if(url.pathname==='/api/datasets/custom'){published=request.postDataJSON();return route.fulfill({status:201,json:{...summaries[0],id:published.id,version:published.version,name:published.name}});}
  if(url.pathname==='/api/runs')return route.fulfill({json:[]});
  return route.fulfill({json:[]});
 });
 await page.goto(base+'/#/datasets/custom-browser');await page.locator('[data-custom-suite]').waitFor();
 await page.getByText('Version 1.10.0 public question',{exact:true}).waitFor();
 assert.equal(await page.getByLabel('题库版本').inputValue(),'1.10.0','latest semantic version is selected');
 await page.getByLabel('题库版本').selectOption('1.9.0');await page.getByText('Version 1.9.0 public question',{exact:true}).waitFor();
 await page.getByText('Version 1.9.0 public question',{exact:true}).click();await page.locator('#suite-question').waitFor();
 assert.match(await page.locator('#suite-question').innerText(),/rounds/);
 assert.match(await page.locator('#suite-question pre').innerText(),/1\.00000000000000001/,'public input display preserves original decimal tokens');
 const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'下载公开目录',exact:true}).click();const download=await downloaded;assert.match(await readFile(await download.path(),'utf8'),/1\.00000000000000001/,'download preserves original decimal tokens');
 await page.locator('#custom-task-search').fill('missing');assert.equal(await page.locator('[data-custom-suite] tbody tr').count(),0);
 await page.goto(base+'/#/admin');await page.locator('[data-custom-dataset-admin]').waitFor();
 await page.locator('[data-template]').click();const pack=JSON.parse(await page.locator('#dataset-json').inputValue());pack.id='browser-published';pack.tasks[0].verification.expected='PRIVATE_STANDARD_MARKER';
 await page.locator('#dataset-json').fill(JSON.stringify(pack));await page.locator('[data-validate]').click();await page.locator('.admin-preview').waitFor();
 assert.equal(validatedBody.tasks[0].verification.expected,'PRIVATE_STANDARD_MARKER');
 assert.doesNotMatch(await page.locator('.admin-preview').innerText(),/PRIVATE_STANDARD_MARKER/);
 await page.locator('#dataset-json').fill(JSON.stringify({...pack,name:'Edited after preview'}));assert.equal(await page.locator('.admin-preview').count(),0,'editing invalidates server preview');
 await page.locator('[data-validate]').click();await page.locator('[data-publish-dataset]').waitFor();await page.locator('[data-publish-dataset]').click();await page.locator('.admin-preview').waitFor({state:'hidden'});
 assert.equal(published.name,'Edited after preview');assert.equal(await page.locator('#dataset-json').inputValue(),'');
 assert.equal(await page.evaluate(()=>Object.values(localStorage).join('').includes('PRIVATE_STANDARD_MARKER')),false,'private standards never enter browser storage');
 assert.deepEqual(errors,[]);console.log('PASS custom catalog version navigation, public tasks, validated publication, preview invalidation and private-data storage isolation');
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
