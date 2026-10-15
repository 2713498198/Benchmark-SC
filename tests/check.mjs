import {readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=fileURLToPath(new URL('../',import.meta.url));
async function files(dir){const entries=await readdir(dir,{withFileTypes:true});return (await Promise.all(entries.filter(e=>!['node_modules','.git','artifacts'].includes(e.name)).map(e=>e.isDirectory()?files(resolve(dir,e.name)):/\.m?js$/.test(e.name)?[resolve(dir,e.name)]:[]))).flat();}
const list=await files(root);
for(const file of list){const r=spawnSync(process.execPath,['--check',file],{encoding:'utf8'});if(r.status!==0){process.stderr.write(r.stderr||String(r.error));process.exit(1);}}
console.log('Syntax checked '+list.length+' JavaScript modules.');
