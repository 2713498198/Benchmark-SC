import fs from 'node:fs';
import vm from 'node:vm';
import {compile} from '@vue/compiler-dom';

const root='src/main/resources/static/js';
const files=[root+'/app.js',...fs.readdirSync(root+'/vue').filter(name=>name.endsWith('.js')).map(name=>root+'/vue/'+name)];
let count=0;
for(const file of files){
 const source=fs.readFileSync(file,'utf8');
 const templates=/\btemplate\s*:\s*`/g;
 let match;
 while((match=templates.exec(source))!==null){
  const start=templates.lastIndex;
  const end=source.indexOf('`',start);
  if(end===-1)throw Error(`Unclosed template in ${file}`);
  try{new vm.Script('function __test(){\n'+compile(source.slice(start,end)).code+'\n}',{filename:file});}
  catch(error){console.error(error.stack);process.exitCode=1;}
  templates.lastIndex=end+1;
  count++;
 }
}
console.log(`Compiled ${count} Vue templates.`);
