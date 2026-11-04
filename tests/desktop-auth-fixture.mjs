import assert from 'node:assert/strict';

export const desktopPassword='desktop-fixture-2026';
export const desktopAdmin='desktop-admin@example.test';
export async function verifyAccountsAndReport(base,{persisted=false,legacy=false}={}){
  const cookies=new Map();
  async function request(route,body){
    const headers={Cookie:[...cookies].map(([key,value])=>key+'='+value).join('; ')};
    if(body!==undefined){
      const csrf=await request('/api/auth/csrf');
      headers.Cookie=[...cookies].map(([key,value])=>key+'='+value).join('; ');
      headers['Content-Type']='application/json';headers[csrf.body.headerName]=csrf.body.token;
    }
    const response=await fetch(base+route,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10_000)});
    for(const cookie of response.headers.getSetCookie()){
      const pair=cookie.split(';')[0],separator=pair.indexOf('=');cookies.set(pair.slice(0,separator),pair.slice(separator+1));
    }
    const text=await response.text();
    let data;try{data=JSON.parse(text);}catch{data=text;}
    return {status:response.status,body:data};
  }
  assert.equal((await request('/api/auth/login',{email:desktopAdmin,password:desktopPassword})).status,200,'Real administrator can log in');
  if(legacy)assert.equal((await request('/api/admin/mail/status')).status,200,'Initialize the legacy mail key through its normal service');
  else assert.equal((await request('/api/auth/demo',{role:'admin'})).status,403,'Desktop disables passwordless demo login');
  assert.equal((await request('/api/auth/'+(persisted?'login':'register'),{name:'Desktop fixture',email:'desktop-fixture@example.test',password:desktopPassword})).status,persisted?200:201,'Research account survives upgrade');
  const report={id:'desktop-upgrade-report',kind:'evaluation',scope:'full',title:'Windows upgrade persistence fixture',summary:{total:7,correct:3}};
  if(!persisted)assert.equal((await request('/api/reports',report)).status,201,'Create a real private report');
  const saved=await request('/api/reports/'+report.id);
  assert.equal(saved.status,200,'Owner can read the report after upgrade');
  assert.equal(saved.body.title,report.title);
  assert.deepEqual(saved.body.summary,report.summary);
  assert.equal(saved.body.verified,false,'Persistence must not promote fixture results to verified scores');
  return request;
}
