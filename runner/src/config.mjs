import {homedir} from 'node:os';
import {dirname,join} from 'node:path';
import {mkdir,readFile,rename,writeFile} from 'node:fs/promises';

// Keep Runner credentials outside the repository and let the user override the path for CI.
export const configPath=process.env.CRYPTOBENCH_RUNNER_CONFIG||join(homedir(),'.cryptobench','runner.json');

// Load a local configuration or explain how to pair the Runner when none exists.
export async function loadConfig(){
 try{return JSON.parse(await readFile(configPath,'utf8'));}
 catch(error){if(error.code==='ENOENT')throw new Error('Runner 尚未配对，请先执行 node runner/src/cli.mjs pair --url <网站地址> --token <配对码>');throw error;}
}

// Write through a temporary file so an interrupted process cannot leave a half-written token.
export async function saveConfig(config){
 const directory=dirname(configPath);
 await mkdir(directory,{recursive:true});
 const temporary=configPath+'.tmp';
 await writeFile(temporary,JSON.stringify(config,null,2)+'\n',{encoding:'utf8',mode:0o600});
 await rename(temporary,configPath);
}
