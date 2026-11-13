import {cryptobenchV4_4_1_0} from './cryptobench-v4-4-1-0.js';
export const datasets=[cryptobenchV4_4_1_0];
export const agents=[
{id:'cipher-pilot',name:'CipherPilot',model:'GPT-4.1',provider:'OpenAI',color:'#111111',version:'1.2.0'},
{id:'sbox-sage',name:'S-box Sage',model:'Claude Sonnet 4',provider:'Anthropic',color:'#3a3a3c',version:'1.1.0'},
{id:'diff-hunter',name:'DiffHunter',model:'DeepSeek V3',provider:'DeepSeek',color:'#555555',version:'1.0.0'},
{id:'round-key',name:'RoundKey',model:'Qwen3',provider:'Qwen',color:'#6e6e73',version:'1.0.0'},
{id:'linear-lab',name:'LinearLab',model:'Gemini 2.5 Pro',provider:'Google',color:'#86868b',version:'0.9.0'}
];
export const models=agents.map(a=>a.model);

