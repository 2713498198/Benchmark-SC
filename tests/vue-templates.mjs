import fs from 'node:fs';
import vm from 'node:vm';
import {compile} from '@vue/compiler-dom';

const files=['js/app.js',...fs.readdirSync('js/vue').filter(name=>name.endsWith('.js')).map(name=>'js/vue/'+name)];
let count=0;
for(const file of files){
 const source=fs.readFileSync(file,'utf8');
 let cursor=0;
 while((cursor=source.indexOf('template:`',cursor))!==-1){
  const start=cursor+'template:`'.length;
  const end=source.indexOf('`',start);
  if(end===-1)throw Error(`Unclosed template in ${file}`);
  try{new vm.Script('function __test(){\n'+compile(source.slice(start,end)).code+'\n}',{filename:file});}
  catch(error){console.error(error.stack);process.exitCode=1;}
  cursor=end+1;
  count++;
 }
}
console.log(`Compiled ${count} Vue templates.`);
