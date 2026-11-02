import assert from 'node:assert/strict';
import {readFile,readdir,stat} from 'node:fs/promises';
import path from 'node:path';

const root=path.resolve(process.argv[2]||'output/desktop/CryptoBench');
assert.ok((await stat(path.join(root,'CryptoBench.exe'))).isFile());
assert.ok((await stat(path.join(root,'runtime','bin','java.exe'))).isFile(),'Bundled Java runtime is required');
const payload=path.join(root,'app'),names=await readdir(payload);
const allowed=new Set(['cryptobench-1.0.0-SNAPSHOT.jar','CryptoBench.cfg','.jpackage.xml','.package']);
assert.ok(names.includes('cryptobench-1.0.0-SNAPSHOT.jar'));
assert.ok(names.includes('CryptoBench.cfg'));
assert.deepEqual(names.filter(name=>!allowed.has(name)),[],'Only the primary JAR and jpackage metadata may enter the app payload');
for(const name of names)assert.ok((await stat(path.join(payload,name))).isFile(),'No build/runtime directory may enter the payload');
if(names.includes('.package'))assert.equal((await readFile(path.join(payload,'.package'),'utf8')).trim(),'CryptoBench','Installed package marker must be jpackage metadata');
const cfg=await readFile(path.join(payload,'CryptoBench.cfg'),'utf8');
assert.match(cfg,/app\.mainclass=org\.springframework\.boot\.loader\.launch\.PropertiesLauncher/);
assert.match(cfg,/-Dloader\.main=com\.cryptobench\.desktop\.DesktopLauncher/);
console.log('PASS Windows package contents: desktop entry, bundled runtime, primary JAR whitelist; excludes target test classes/logs/databases/outbox');
