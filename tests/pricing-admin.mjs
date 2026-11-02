import assert from 'node:assert/strict';
import fs from 'node:fs';
import {ref,computed} from 'vue';
import {compile} from '@vue/compiler-dom';

const source=fs.readFileSync('src/main/resources/static/js/vue/PricingAdmin.js','utf8');
const requests=[],mounted=[];
let rejectActivation=false;
const current={version:'prices-current',revision:0,activeRevision:4,status:'active',prices:[
 {model:'model-a',inputPerMillionTokens:'0.50000000',outputPerMillionTokens:'1.00000000',currency:'USD'},
 {model:'model-b',inputPerMillionTokens:'2.00000000',outputPerMillionTokens:'3.00000000',currency:'USD'}
]};
const app={toast(){},async api(path,options={}){
 requests.push({path,...options});
 if(path.endsWith('/diff'))return {currentVersion:current.version,targetVersion:'prices-new',expectedRevision:4,expectedTableRevision:0,usdOnly:true,changes:[{kind:'removed',model:'model-b',before:current.prices[1],after:null}]};
 if(path.endsWith('/activate')){if(rejectActivation)throw Error('当前价格表已改变，请重新查看启用差异');return current;}
 if(options.method==='POST')return {...options.body,status:'draft',revision:0,editable:false};
 return path==='/api/pricing/active'?current:[current];
}};
const executable=source.replace(/^import .*;\r?\n/gm,'').replace('export default','return');
const component=new Function('ref','computed','onMounted','useApp',executable)(ref,computed,fn=>mounted.push(fn),()=>app);
const ui=component.setup();
await mounted[0]();
ui.populate(current);
assert.equal(ui.rows.value.length,2,'copy preserves every model');
ui.rows.value[0].inputPerMillionTokens='999999999999.99999999';
assert.equal(current.prices[0].inputPerMillionTokens,'0.50000000','form edits cannot mutate active data');
ui.version.value='prices-new';
await ui.save();
const saved=requests.find(r=>r.method==='POST'&&r.path==='/api/admin/pricing');
assert.equal(saved.body.prices.length,2);
assert.equal(saved.body.prices[0].inputPerMillionTokens,'999999999999.99999999','decimal strings preserve all digits');
assert.equal(ui.version.value,'','saved versions are never reopened for editing');
await ui.inspect({version:'prices-new'});
const before=requests.length;
await ui.activate();
assert.equal(requests.length,before,'removals require explicit acknowledgement');
ui.confirmRemoved.value=true;
rejectActivation=true;
await ui.activate();
const activation=requests.find(r=>r.path.endsWith('/activate'));
assert.deepEqual(activation.body,{expectedRevision:4,expectedTableRevision:0});
assert.equal(ui.preview.value,null,'stale preview must be refreshed, not automatically retried');
assert.match(ui.error.value,/当前价格表已改变/);
ui.populate({...current,prices:[{...current.prices[0],currency:'CNY'}]});
assert.equal(ui.rows.value[0].inputPerMillionTokens,'','copy never relabels a legacy currency amount as USD');
ui.version.value='prices-bad';
ui.rows.value=[{model:'a-b',inputPerMillionTokens:'1',outputPerMillionTokens:'1'}, {model:'A_B',inputPerMillionTokens:'1',outputPerMillionTokens:'1'}];
await ui.save();
assert.match(ui.error.value,/重复/);
new Function(compile(component.template).code);
console.log('Pricing admin: full-table copy, exact decimals, immutable save, activation diff/conflict and legacy currency checks passed.');
