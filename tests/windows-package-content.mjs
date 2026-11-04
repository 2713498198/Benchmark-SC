import assert from 'node:assert/strict';
import {readFile,readdir,stat,lstat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';

const root=path.resolve(process.argv[2]||'output/desktop/CryptoBench');
const applicationName=process.argv[3]||'CryptoBench';
assert.match(applicationName,/^[A-Za-z][A-Za-z0-9-]{0,40}$/);
assert.ok((await stat(path.join(root,applicationName+'.exe'))).isFile());
assert.ok((await stat(path.join(root,'runtime','bin','java.exe'))).isFile(),'Bundled Java runtime is required');
const payload=path.join(root,'app'),names=await readdir(payload);
const allowed=new Set(['cryptobench-1.0.0-SNAPSHOT.jar',applicationName+'.cfg','.jpackage.xml','.package']);
assert.ok(names.includes('cryptobench-1.0.0-SNAPSHOT.jar'));
assert.ok(names.includes(applicationName+'.cfg'));
assert.deepEqual(names.filter(name=>!allowed.has(name)),[],'Only the primary JAR and jpackage metadata may enter the app payload');
for(const name of names)assert.ok((await stat(path.join(payload,name))).isFile(),'No build/runtime directory may enter the payload');
if(names.includes('.package'))assert.equal((await readFile(path.join(payload,'.package'),'utf8')).trim(),applicationName,'Installed package marker must be jpackage metadata');
const cfg=await readFile(path.join(payload,applicationName+'.cfg'),'utf8');
assert.match(cfg,/app\.mainclass=org\.springframework\.boot\.loader\.launch\.PropertiesLauncher/);
assert.match(cfg,/-Dloader\.main=com\.cryptobench\.desktop\.DesktopLauncher/);
assert.match(cfg,/-Dspring\.profiles\.active=desktop/);
assert.match(cfg,/-Dcryptobench\.desktop\.home=\$APPDIR\/\.\./);
const rootNames=await readdir(root);
assert.deepEqual(rootNames.filter(name=>![applicationName+'.exe','app','runtime','support','.cryptobench-payload.json','var'].includes(name)),[],'Only approved payload roots are allowed');
const supportFiles=['build-info.json','desktop-data.ps1','prepare-desktop-docker.ps1','INSTALL-WINDOWS.md','docker'];
assert.deepEqual((await readdir(path.join(root,'support'))).sort(),supportFiles.sort());
assert.deepEqual((await readdir(path.join(root,'support','docker'))).sort(),['agent-proxy.Dockerfile','agent-proxy.mjs','model-gateway.Dockerfile','model-gateway.mjs','mock-agent.Dockerfile','mock-agent.mjs'].sort());
const manifest=JSON.parse((await readFile(path.join(root,'.cryptobench-payload.json'),'utf8')).replace(/^\uFEFF/,''));
assert.equal(manifest.schema,'cryptobench-payload-v1');
const expected=new Map(manifest.files.map(file=>[file.path,file.sha256.toLowerCase()]));
assert.equal(expected.size,manifest.files.length,'No duplicate payload paths');
// jpackage replaces its build-time XML with an application-name marker in MSI installs.
// Permit only that known transformation; every executable/support file stays hashed.
if(names.includes('.package')&&!names.includes('.jpackage.xml'))expected.delete('app/.jpackage.xml');
async function inspect(directory,relative=''){
  for(const name of await readdir(directory)){
    const key=relative?relative+'/'+name:name;
    if(key==='var'||key==='.cryptobench-payload.json'||key==='app/.package')continue;
    const file=path.join(directory,name),entry=await lstat(file);
    assert.ok(!entry.isSymbolicLink(),'Payload cannot include links');
    if(entry.isDirectory()){await inspect(file,key);continue;}
    assert.ok(expected.has(key),'Unlisted payload file: '+key);
    assert.equal(createHash('sha256').update(await readFile(file)).digest('hex'),expected.get(key),'Payload hash: '+key);
    expected.delete(key);
  }
}
await inspect(root);
assert.equal(expected.size,0,'All manifest files must exist: '+[...expected.keys()].join(', '));
console.log('PASS Windows package contents: desktop entry, bundled runtime, primary JAR whitelist; excludes target test classes/logs/databases/outbox');
