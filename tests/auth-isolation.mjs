import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

// Run against an already started server:
//   $env:TEST_URL='http://127.0.0.1:4173'; node tests/auth-isolation.mjs
const base=(process.env.TEST_URL||'http://127.0.0.1:4173').replace(/\/$/,'');

class Client {
  constructor(){this.cookies=new Map();}
  async request(path, options={}){
    const headers={...(options.body?{'content-type':'application/json'}:{}),...(options.headers||{})};
    if(!['GET','HEAD','OPTIONS'].includes((options.method||'GET').toUpperCase())){
      const {body:csrf}=await this.request('/api/auth/csrf');
      headers[csrf.headerName]=csrf.token;
    }
    if(this.cookies.size)headers.cookie=[...this.cookies].map(([name,value])=>name+'='+value).join('; ');
    const init={...options,headers};
    if(init.body&&typeof init.body!=='string')init.body=JSON.stringify(init.body);
    const response=await fetch(base+path,init);
    for(const cookie of response.headers.getSetCookie()){
      const [name,...value]=cookie.split(';',1)[0].split('=');this.cookies.set(name,value.join('='));
    }
    let body=null;try{body=await response.json();}catch{}
    return {response,body};
  }
  async json(path, options={}){
    const result=await this.request(path,options);
    assert.equal(result.response.ok,true,`${options.method||'GET'} ${path}: ${JSON.stringify(result.body)}`);
    return result.body;
  }
}

const a=new Client(),b=new Client();
const suffix=randomUUID().slice(0,8);
const aEmail=`isolation-a-${suffix}@example.com`,bEmail=`isolation-b-${suffix}@example.com`;
const password='isolation-test-password';
const reportId=`isolation-report-${suffix}`;

await a.json('/api/auth/register',{method:'POST',body:{name:'隔离用户 A',email:aEmail,password}});
await b.json('/api/auth/register',{method:'POST',body:{name:'隔离用户 B',email:bEmail,password}});
const connection=await a.json('/api/connections',{method:'POST',body:{name:'A Agent',url:'http://127.0.0.1:9000',model:'model-a'}});
const report=await a.json('/api/reports',{method:'POST',body:{id:reportId,kind:'evaluation',datasetId:'mini-spn',version:'1.0.0',budget:'standard',scope:'full',agent:'A Agent',model:'model-a',total:1,passed:1,score:100,synced:false,published:false}});

const bBootstrap=await b.json('/api/bootstrap');
assert.equal(bBootstrap.connections.length,0,'B must not receive A connections');
assert.equal(bBootstrap.reports.length,0,'B must not receive A private reports');
assert.equal(bBootstrap.publicReports.some(x=>x.id===reportId),false,'private reports must not be public');

const privateRead=await b.request(`/api/reports/${encodeURIComponent(report.id)}`);
assert.equal(privateRead.response.status,404,'B must not read A private report');
const overwrite=await b.request('/api/reports',{method:'POST',body:{id:report.id,kind:'evaluation',scope:'full'}});
assert.equal(overwrite.response.status,409,'B must not overwrite A report by ID');
const deleteConnection=await b.request(`/api/connections/${encodeURIComponent(connection.id)}`,{method:'DELETE'});
assert.equal(deleteConnection.response.status,404,'B must not delete A connection');

const researcherDataset=await b.request('/api/datasets/custom',{method:'POST',body:{id:`private-${suffix}`,version:'1.0.0',tasks:[{id:'task-1'}]}});
assert.equal(researcherDataset.response.status,403,'researchers must not publish custom datasets');

const admin=new Client();
await admin.json('/api/auth/demo',{method:'POST',body:{role:'admin'}});
const dataset=await admin.json('/api/datasets/custom',{method:'POST',body:{id:`admin-${suffix}`,version:'1.0.0',name:'Admin dataset',tasks:[{id:'task-1'}]}});
assert.equal(dataset.published,true,'admin publication must be public');
const anonymous=new Client();
const publicBootstrap=await anonymous.json('/api/bootstrap');
assert.equal(publicBootstrap.customDatasets.some(x=>x.id===dataset.id),true,'published datasets must be publicly readable');
const databaseFile=await anonymous.request('/data/runtime/db.json');
assert.ok(databaseFile.response.status>=400,'runtime database must not be exposed as a static asset');

await a.json(`/api/reports/${encodeURIComponent(report.id)}/sync`,{method:'POST'});
await a.json(`/api/reports/${encodeURIComponent(report.id)}/publish`,{method:'POST'});
const publicReport=await anonymous.json(`/api/reports/${encodeURIComponent(report.id)}`);
assert.equal(publicReport.id,report.id,'published reports must be publicly readable');

await a.json('/api/auth/logout',{method:'POST'});
const afterLogout=await a.request('/api/connections');
assert.equal(afterLogout.response.status,401,'logout must invalidate the session');

console.log(`Auth isolation checks passed against ${base}`);
