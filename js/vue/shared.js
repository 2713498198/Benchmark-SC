import {inject,h} from '/node_modules/vue/dist/vue.esm-browser.prod.js';
import {iconBody} from '../core.js';
export const appKey=Symbol('cryptobench');
export const useApp=()=>inject(appKey);
export const SvgIcon={props:{name:String,size:{default:20}},render(){return h('svg',{width:this.size,height:this.size,viewBox:'0 0 24 24',fill:'none',stroke:'currentColor','stroke-width':'1.7','stroke-linecap':'round','stroke-linejoin':'round','aria-hidden':'true',innerHTML:iconBody(this.name)});}};
