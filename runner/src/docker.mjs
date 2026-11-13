import {spawn} from 'node:child_process';

// Start a short-lived Agent container with conservative resource and privilege limits.
export async function startSandbox(image){
 if(!image)throw new Error('Docker 沙箱缺少镜像名称');
 const id=(await command(['run','-d','--rm','--cpus','1','--memory','512m','--pids-limit','128','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','-p','127.0.0.1::8000',image])).trim();
 try{
  const published=(await command(['port',id,'8000/tcp'])).trim().split('\n')[0]||'';
  const match=published.match(/127\.0\.0\.1:(\d+)/);if(!match)throw new Error('无法读取 Docker Agent 映射端口');
  return {id,endpoint:'http://127.0.0.1:'+match[1]};
 }catch(error){await stopSandbox({id});throw error;}
}

// Always remove the temporary container after a task, including failed requests.
export async function stopSandbox(sandbox){if(sandbox?.id)try{await command(['stop','-t','2',sandbox.id]);}catch{} }

function command(args){return new Promise((resolve,reject)=>{const child=spawn('docker',args,{stdio:['ignore','pipe','pipe']});let stdout='',stderr='';child.stdout.on('data',chunk=>stdout+=chunk);child.stderr.on('data',chunk=>stderr+=chunk);child.on('error',reject);child.on('close',code=>code===0?resolve(stdout):reject(new Error(stderr.trim()||`docker 命令失败（${code}）`)));});}
