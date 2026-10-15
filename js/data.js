export const datasets=[
{id:'mini-spn',name:'Mini-SPN 差分分析',subtitle:'DIFFERENTIAL CRYPTANALYSIS',type:'分组密码',algorithm:'Mini-SPN · 4 轮',tasks:120,version:'1.0.0',difficulty:'入门 → 进阶',tags:['差分分析','S 盒','路径搜索'],description:'从差分分布表到多轮差分路径，系统评估智能体的建模、搜索与验证能力。',icon:'grid',color:'blue',published:true},
{id:'key-recovery',name:'约化轮密钥恢复',subtitle:'KEY RECOVERY',type:'分组密码',algorithm:'教学 SPN · 3 轮',tasks:80,version:'1.0.0',difficulty:'进阶',tags:['密钥恢复','候选筛选','实验验证'],description:'在给定明密文对与查询预算下，比较子密钥恢复策略及可验证的攻击结果。',icon:'lock',color:'violet',published:true},
{id:'linear-analysis',name:'线性密码分析',subtitle:'LINEAR CRYPTANALYSIS',type:'分组密码',algorithm:'Mini-SPN · 4 轮',tasks:100,version:'1.0.0',difficulty:'进阶',tags:['线性逼近','偏差估计','统计分析'],description:'构造线性逼近、估计偏差并验证统计结果，衡量分析方法的严谨性。',icon:'activity',color:'teal',published:true},
{id:'cipher-modeling',name:'密码结构与建模',subtitle:'CIPHER MODELING',type:'分组密码',algorithm:'SPN / Feistel · 教学结构',tasks:60,version:'1.0.0',difficulty:'入门',tags:['结构分析','约束建模','工具调用'],description:'理解轮函数与扩散结构，将分析任务转化为可执行、可复现的计算模型。',icon:'layers',color:'orange',published:true}
];
export const agents=[
{id:'cipher-pilot',name:'CipherPilot',model:'GPT-4.1',provider:'OpenAI',color:'#245bdb',version:'1.2.0'},
{id:'sbox-sage',name:'S-box Sage',model:'Claude Sonnet 4',provider:'Anthropic',color:'#a66b4b',version:'1.1.0'},
{id:'diff-hunter',name:'DiffHunter',model:'DeepSeek V3',provider:'DeepSeek',color:'#6073e6',version:'1.0.0'},
{id:'round-key',name:'RoundKey',model:'Qwen3',provider:'Qwen',color:'#8b5bd5',version:'1.0.0'},
{id:'linear-lab',name:'LinearLab',model:'Gemini 2.5 Pro',provider:'Google',color:'#389a83',version:'0.9.0'}
];
export const rankings=agents.map((a,i)=>({...a,id:'rank-'+a.id,rank:i+1,agent:a.name,score:[91.7,88.3,85.8,81.7,77.5][i],passed:[110,106,103,98,93][i],total:120,time:[38.6,42.1,35.4,31.8,46.2][i],cost:[2.84,3.16,0.76,0.62,null][i],source:i%2?'local':'cloud',datasetId:'mini-spn',version:'1.0.0',budget:'standard',trend:[2,0,1,-1,0][i]}));
export const models=agents.map(a=>a.model);
export const taskTypes=['差分分布表构造','多轮差分路径搜索','候选密钥验证','分析产物复现'];

