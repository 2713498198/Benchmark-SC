import {ref,computed,watch,nextTick,onBeforeUnmount} from '/vendor/vue/vue.esm-browser.prod.js';
import {SvgIcon} from './shared.js';
import {chartModel,chartAxes,bindRankChart} from '../leaderboard-chart.js';

export default {components:{SvgIcon},props:{rows:Array,metric:String,zoom:Boolean,title:String,description:String,fixedMetric:Boolean,hideTitle:Boolean},emits:['metric','zoom','locate'],setup(props,{emit}){
 const root=ref(null),model=computed(()=>chartModel(props.rows,props.metric));let cleanup=()=>{};
 function bind(){cleanup();if(!root.value)return;root.value.querySelector('.rank-chart-points')?.replaceChildren();root.value.querySelector('.rank-chart-labels')?.replaceChildren();root.value.querySelector('.rank-chart-tooltip').hidden=true;cleanup=bindRankChart(root.value,props.rows,props.metric,value=>emit('metric',value),{zoom:props.zoom,onZoom:value=>emit('zoom',value),...(props.fixedMetric?{}:{onLocate:id=>emit('locate',id)})});}
 watch(()=>[props.rows,props.metric,props.zoom],()=>nextTick(bind),{flush:'post',immediate:true});onBeforeUnmount(()=>cleanup());
 return {root,model,chartAxes};
},template:`<section ref="root" class="rank-chart" :class="{'rank-chart-compact':hideTitle}" data-rank-chart aria-labelledby="rank-chart-title">
 <div class="rank-chart-heading"><div :class="{'sr-only':hideTitle}"><h2 id="rank-chart-title">{{title || ('通过率与'+(metric==='cost'?'费用':'平均耗时'))}}</h2><p v-if="description">{{description}}</p></div><div class="rank-chart-controls"><label class="rank-chart-zoom"><input type="checkbox" class="checkbox" data-chart-zoom :checked="zoom"> 聚焦差异</label><div v-if="!fixedMetric" class="pill-switch" role="group" aria-label="图表横轴"><button v-for="key in ['cost','time']" :key="key" type="button" :data-chart-metric="key" :aria-pressed="String(metric===key)" :class="{active:metric===key}">{{key==='cost'?'费用':'平均耗时'}} <span>{{key==='cost'?'USD':'s'}}</span></button></div></div></div>
 <div class="rank-chart-meta"><div class="rank-chart-legend" aria-label="图例"><span><i class="rank-legend-frontier" aria-hidden="true"></i>帕累托前沿</span><span><i class="rank-legend-point" aria-hidden="true"></i>其他成绩</span></div><span>{{model.included}} / {{model.total}} 项成绩绘入图表</span></div>
 <div v-if="model.points.length" class="rank-chart-stage" role="group" :aria-label="'通过率与'+(metric==='cost'?'费用':'平均耗时')+'散点图，选择数据点查看详情'" aria-describedby="rank-chart-help"><canvas aria-hidden="true"></canvas><div class="rank-chart-points"></div><div class="rank-chart-labels" aria-hidden="true"></div></div>
 <div v-else class="rank-chart-empty"><h3>{{rows.length?'暂无可绘制的'+(metric==='cost'?'费用':'平均耗时')+'数据':'当前条件下暂无成绩'}}</h3><p>{{rows.length?(fixedMetric?'已有记录尚未提供有效费用或通过率。':'可切换横轴，或在排行榜查看已有成绩。'):'调整上方筛选，查看相同条件下的测评结果。'}}</p></div>
 <div class="rank-chart-tooltip" id="rank-chart-tooltip" role="dialog" aria-label="数据点详情" hidden></div>
 <div class="rank-chart-foot"><p><span v-if="zoom" class="rank-axis-note">纵轴已放大 · </span>{{model.excluded?model.excluded+' 项缺少有效'+(metric==='cost'?'费用':'平均耗时')+'或通过率':'全部成绩已绘入'}}</p><details><summary>图表说明</summary><p id="rank-chart-help">向左，{{metric==='cost'?'费用':'耗时'}}更低；向上，通过率更高。虚线连接当前筛选中的帕累托前沿：不存在另一项成绩，在{{metric==='cost'?'费用':'平均耗时'}}不增加时获得更高通过率，或在通过率不降低时使用更少{{metric==='cost'?'费用':'时间'}}。连线仅辅助比较，不代表未测量的结果。默认纵轴为 0–100%，可切换聚焦差异；点击或用 Tab 聚焦数据点查看详情，Esc 关闭。缺失费用或通过率的记录仅在排行榜中展示。</p></details></div>
 </section>`};
