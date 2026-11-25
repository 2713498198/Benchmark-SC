import {computed,ref,onMounted,onBeforeUnmount} from '/vendor/vue/vue.esm-browser.prod.js';
import {SvgIcon} from './shared.js';

const TIME_POINTS = [0, 15, 30, 45, 60];
const COMPLETION_SHAPE = [0, 12.0 / 30.8, 23.2 / 30.8, 28.6 / 30.8, 1];
const MODEL_ORDER = ['Claude Opus 5.5', 'GPT-5.6-sol', 'Grok 4.7', 'DeepSeek V4 Pro', 'Gemini 3.1 Pro', 'Qwen 3.8 Max'];
const FIGURE_9_MODEL_ORDER = ['GPT-5.6-sol', 'Grok 4.7', 'Gemini 3.1 Pro', 'Claude Opus 5.5', 'DeepSeek V4 Pro', 'Qwen 3.8 Max'];
const FIGURE_12_MODEL_ORDER = ['GPT-5.6-sol', 'Claude Opus 5.5', 'Grok 4.7', 'DeepSeek V4 Pro', 'Gemini 3.1 Pro', 'Qwen 3.8 Max'];
const FRAMEWORK_META = {
  OC: {name: 'OpenCode', color: '#4d86c6', mark: 'OC', markColor: '#ffffff', boxed: true},
  QC: {name: 'Qwen Code', color: '#e09b2d', mark: '∞', markColor: '#615ced', boxed: false},
  CC: {name: 'Claude Code', color: '#3a9b7d', mark: 'AI', markColor: '#1f1f1f', boxed: false},
  CX: {name: 'Codex CLI', color: '#8a5bd6', mark: '◎', markColor: '#1f1f1f', boxed: false},
};
const MICRO_MACRO_DATA = {
  'GPT-5.6-sol': {OC:[32.8,43.0],QC:[31.6,38.9],CC:[35.2,45.7],CX:[36.3,46.2]},
  'Claude Opus 5.5': {OC:[34.6,46.5],QC:[33.4,42.9],CC:[36.9,49.3],CX:[35.2,46.1]},
  'Grok 4.7': {OC:[31.6,43.5],QC:[30.5,43.0],CC:[34.6,45.2],CX:[32.8,43.7]},
  'DeepSeek V4 Pro': {OC:[30.5,38.4],QC:[28.7,39.0],CC:[33.4,44.8],CX:[31.6,40.9]},
  'Gemini 3.1 Pro': {OC:[29.9,41.9],QC:[27.5,38.8],CC:[32.8,47.5],CX:[31.1,44.2]},
  'Qwen 3.8 Max': {OC:[19.9,30.8],QC:[18.1,30.4],CC:[24.6,37.0],CX:[22.2,34.6]},
};
const TOKEN_DATA = {
  'GPT-5.6-sol': {OC:[.373,32.9],QC:[.398,31.8],CC:[.373,35.3],CX:[.393,36.5]},
  'Claude Opus 5.5': {OC:[.442,34.7],QC:[.393,33.5],CC:[.414,37.1],CX:[.361,35.3]},
  'Grok 4.7': {OC:[.386,31.8],QC:[.348,30.6],CC:[.350,34.7],CX:[.327,32.9]},
  'DeepSeek V4 Pro': {OC:[.543,30.6],QC:[.600,28.8],CC:[.574,33.5],CX:[.596,31.8]},
  'Gemini 3.1 Pro': {OC:[.314,30.0],QC:[.303,27.6],CC:[.295,32.9],CX:[.314,31.2]},
  'Qwen 3.8 Max': {OC:[.736,20.0],QC:[.714,18.2],CC:[.818,24.7],CX:[.775,22.4]},
};
const FIGURE_9_COLORS = {
  'GPT-5.6-sol': '#2f6b9a',
  'Claude Opus 5.5': '#c75c5c',
  'Grok 4.7': '#5c8f64',
  'DeepSeek V4 Pro': '#7a64a8',
  'Gemini 3.1 Pro': '#c2873d',
  'Qwen 3.8 Max': '#4f8f9a',
};
const FIGURE_12_COLORS = {
  'GPT-5.6-sol': '#4c789f',
  'Claude Opus 5.5': '#a35f8b',
  'Grok 4.7': '#c68a36',
  'DeepSeek V4 Pro': '#3a9378',
  'Gemini 3.1 Pro': '#7466a5',
  'Qwen 3.8 Max': '#7f8590',
};
const MODEL_META = {
  'Claude Opus 5.5': {
    color: '#cc785c', provider: 'Anthropic', mark: 'AI', markColor: '#1f1f1f',
  },
  'GPT-5.6-sol': {
    color: '#1f1f1f', provider: 'OpenAI', mark: '◎', markColor: '#1f1f1f',
  },
  'Grok 4.7': {
    color: '#736cd3', provider: 'xAI', mark: '✦', markColor: '#1f1f1f',
  },
  'DeepSeek V4 Pro': {
    color: '#047afe', provider: 'DeepSeek', mark: '∞∞', markColor: '#4d6bfe',
  },
  'Gemini 3.1 Pro': {
    color: '#34a853', provider: 'Google', mark: 'GG', markColor: '#34a853',
  },
  'Qwen 3.8 Max': {
    color: '#f32e02', provider: 'Alibaba', mark: 'a', markColor: '#ff5a00',
  },
};
const BAR_METRICS = [
  {key: 'vcr', label: 'VCR', description: '可验证完成率，同时要求答案正确和证据通过验证。'},
  {key: 'fullAcc', label: 'FullAcc', description: '最终答案达到满分要求的比例。'},
  {key: 'vpr', label: 'VPR', description: '未完成任务中留下有效且可验证中间结果的比例。'},
  {key: 'process', label: 'Process', description: '过程证据对最终结论的平均支持分数。'},
];
const shortModel = model => model
  .replace('Claude Opus 5.5', 'Opus 5.5')
  .replace('GPT-5.6-sol', 'GPT-5.6')
  .replace('Gemini 3.1 Pro', 'Gemini 3.1')
  .replace('DeepSeek V4 Pro', 'DeepSeek V4')
  .replace('Qwen 3.8 Max', 'Qwen 3.8');
const detailAxis = (values, metric) => {
  const finite = values.filter(Number.isFinite);
  if (!finite.length) return {min: 0, max: 100, step: 20};
  const step = ['vcr', 'fullAcc', 'categoryVcr'].includes(metric) ? 5 : 10;
  const minimum = Math.min(...finite);
  const maximum = Math.max(...finite);
  const padding = Math.max(step / 2, (maximum - minimum) * 0.12);
  let min = Math.max(0, Math.floor((minimum - padding) / step) * step);
  let max = Math.min(100, Math.ceil((maximum + padding) / step) * step);
  if (max - min < step * 2) max = Math.min(100, min + step * 2);
  if (max - min < step * 2) min = Math.max(0, max - step * 2);
  return {min, max, step};
};
const CATEGORY_META = [
  {key:'relations', title:'Relations', description:'关系分析 / 模型审计', index:0, taskCount:74},
  {key:'search', title:'Search', description:'搜索 / 优化', index:1, taskCount:47},
  {key:'exact', title:'Exact', description:'精确计算 / 枚举', index:2, taskCount:26},
  {key:'bounds', title:'Bounds', description:'边界 / 推导', index:3, taskCount:23},
];

export default {
  components: {SvgIcon},
  props: {
    rows: Array,
    showScatter: {type: Boolean, default: true},
  },
  setup(props) {
    const activeBar = ref(null);
    const activeLine = ref(null);
    const activeMetric = ref('vcr');
    const media = matchMedia('(max-width: 640px)');
    const compact = ref(media.matches);
    const onResize = () => compact.value = media.matches;
    media.addEventListener('change', onResize);
    onMounted(() => {
      compact.value = media.matches;
    });
    onBeforeUnmount(() => media.removeEventListener('change', onResize));
    const ordered = computed(() => [...(props.rows || [])].sort(
      (a, b) => b.vcr - a.vcr || a.model.localeCompare(b.model) || a.framework.localeCompare(b.framework),
    ));
    const metricInfo = computed(() => BAR_METRICS.find(metric => metric.key === activeMetric.value) || BAR_METRICS[0]);
    const modelLegend = computed(() => [...new Map(ordered.value.map(row => [row.model, {
      model: row.model,
      color: MODEL_META[row.model]?.color || '#52627a',
    }])).values()]);

    const makeBarChart = (sourceRows, metric) => {
      const barRows = [...sourceRows].sort(
        (a, b) => (b[metric] || 0) - (a[metric] || 0)
          || b.vcr - a.vcr || a.model.localeCompare(b.model),
      );
      const width = Math.max(1120, barRows.length * 62 + 92);
      const height = 520;
      const plot = {left: 52, right: 24, top: 30, bottom: 196};
      const plotWidth = width - plot.left - plot.right;
      const plotHeight = height - plot.top - plot.bottom;
      const groupWidth = plotWidth / Math.max(1, barRows.length);
      const barWidth = Math.min(38, groupWidth * 0.7);
      const domain = detailAxis(
        barRows.map(row => Number(row[metric])),
        metric,
      );
      const domainSpan = Math.max(1, domain.max - domain.min);
      const y = value => plot.top + plotHeight * (
        1 - Math.max(0, Math.min(1, (value - domain.min) / domainSpan))
      );
      const baseline = plot.top + plotHeight;
      const grid = [];
      for (let value = domain.min; value <= domain.max; value += domain.step) {
        grid.push({value, y: y(value)});
      }
      const groups = barRows.map((row, index) => {
        const center = plot.left + groupWidth * (index + 0.5);
        const value = row[metric] || 0;
        const barX = center - barWidth / 2;
        const barY = y(value);
        const barHeight = Math.max(0, baseline - barY);
        const radius = Math.min(4, barHeight / 2, barWidth / 2);
        const frameworkMeta = FRAMEWORK_META[row.frameworkCode] || {};
        const modelMeta = MODEL_META[row.model] || {};
        const frameworkName = frameworkMeta.name || row.framework;
        return {
          row,
          center,
          value,
          barX,
          barY,
          barHeight,
          barPath: barHeight
            ? `M ${barX} ${baseline} L ${barX} ${barY + radius} Q ${barX} ${barY} ${barX + radius} ${barY} L ${barX + barWidth - radius} ${barY} Q ${barX + barWidth} ${barY} ${barX + barWidth} ${barY + radius} L ${barX + barWidth} ${baseline} Z`
            : '',
          color: modelMeta.color || '#52627a',
          frameworkMark: frameworkMeta.mark || row.frameworkCode,
          frameworkMarkColor: frameworkMeta.markColor || '#1f1f1f',
          frameworkBoxed: frameworkMeta.boxed === true,
          provider: modelMeta.provider || row.model,
          providerMark: modelMeta.mark || row.model.slice(0, 2),
          providerMarkColor: modelMeta.markColor || '#1f1f1f',
          label: shortModel(row.model),
          framework: row.frameworkCode,
          frameworkName,
          labelLines: [modelMeta.provider || row.model, frameworkName, shortModel(row.model)],
        };
      });
      const mean = barRows.length
        ? barRows.reduce((sum, row) => sum + (row[metric] || 0), 0) / barRows.length
        : 0;
      return {
        width,
        height,
        plot,
        widthInner: plotWidth,
        barWidth,
        baseline,
        yMin: domain.min,
        yMax: domain.max,
        grid,
        groups,
        mean,
        meanY: y(mean),
      };
    };
    const barChart = computed(() => makeBarChart(ordered.value, activeMetric.value));
    const categoryCharts = computed(() => CATEGORY_META.map(category => {
      const rows = ordered.value.map(row => {
        const detail = row.categories?.[category.index];
        return {
          ...row,
          categoryVcr: Number(detail?.score) || 0,
          categoryPassed: Number(detail?.passed) || 0,
          categoryTotal: Number(detail?.total) || category.taskCount,
        };
      });
      return {
        ...category,
        chart: makeBarChart(rows, 'categoryVcr'),
      };
    }));

    const lineChart = computed(() => {
      const width = compact.value ? 360 : 860;
      const columns = compact.value ? 1 : 3;
      const panelWidth = width / columns;
      const panelHeight = compact.value ? 210 : 218;
      const rowGap = compact.value ? 26 : 38;
      const rows = Math.ceil(MODEL_ORDER.length / columns);
      const height = panelHeight * rows + rowGap * (rows - 1) + 48;
      const panelInner = {
        left: 34,
        right: 12,
        top: 25,
        bottom: 30,
      };
      const panels = MODEL_ORDER.map((model, index) => {
        const column = index % columns;
        const row = Math.floor(index / columns);
        const xOffset = column === 0 ? 0 : column * panelWidth;
        const yOffset = row * (panelHeight + rowGap);
        const plotWidth = panelWidth - panelInner.left - panelInner.right;
        const plotHeight = panelHeight - panelInner.top - panelInner.bottom;
        const x = time => xOffset + panelInner.left + plotWidth * time / 60;
        const y = value => yOffset + panelInner.top + plotHeight * (1 - Math.max(0, Math.min(40, value)) / 40);
        const frameworkRows = ['OC', 'QC', 'CC', 'CX']
          .map(code => ordered.value.find(record => record.model === model && record.frameworkCode === code))
          .filter(Boolean);
        const series = frameworkRows.map(record => {
          const points = TIME_POINTS.map((time, timeIndex) => ({
            time,
            value: record.vcr * COMPLETION_SHAPE[timeIndex],
            x: x(time),
            y: y(record.vcr * COMPLETION_SHAPE[timeIndex]),
          }));
          return {
            row: record,
            meta: FRAMEWORK_META[record.frameworkCode],
            points,
            path: points.map((point, pointIndex) => `${pointIndex ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '),
          };
        });
        return {
          model,
          letter: String.fromCharCode(97 + index),
          xOffset,
          yOffset,
          plotWidth,
          plotHeight,
          left: xOffset + panelInner.left,
          right: xOffset + panelWidth - panelInner.right,
          top: yOffset + panelInner.top,
          bottom: yOffset + panelInner.top + plotHeight,
          grid: [0, 10, 20, 30, 40].map(value => ({value, y: y(value)})),
          xTicks: TIME_POINTS.map(time => ({time, x: x(time)})),
          series,
        };
      });
      return {width, height, panels};
    });

    const scatterCharts = computed(() => {
      const median = values => {
        const sorted = [...values].sort((a, b) => a - b);
        const middle = Math.floor(sorted.length / 2);
        return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
      };
      const pointFor = (row, xValue, yValue, x, y, color) => ({
        row,
        xValue,
        yValue,
        x,
        y,
        color,
        code: row.frameworkCode,
        triangle: `${x},${y - 6} ${x - 6},${y + 5} ${x + 6},${y + 5}`,
        diamond: `${x},${y - 6} ${x + 6},${y} ${x},${y + 6} ${x - 6},${y}`,
      });

      const microWidth = 720;
      const microHeight = 520;
      const microPlot = {left: 92, right: 34, top: 30, bottom: 86};
      const microPlotWidth = microWidth - microPlot.left - microPlot.right;
      const microPlotHeight = microHeight - microPlot.top - microPlot.bottom;
      const microX = value => microPlot.left + microPlotWidth * (value - 17.5) / 22.5;
      const microY = value => microPlot.top + microPlotHeight * (57 - value) / 29;
      const microPoints = MODEL_ORDER.flatMap(model => ['OC', 'QC', 'CC', 'CX'].map(code => {
        const row = ordered.value.find(candidate => candidate.model === model && candidate.frameworkCode === code);
        const values = MICRO_MACRO_DATA[model]?.[code];
        if (!row || !values) return null;
        return pointFor(
          row,
          values[0],
          values[1],
          microX(values[0]),
          microY(values[1]),
          FIGURE_9_COLORS[model],
        );
      }).filter(Boolean));
      const microMedianX = median(microPoints.map(point => point.xValue));
      const microMedianY = median(microPoints.map(point => point.yValue));
      const annotation = (model, code, lines, textX, textY) => {
        const point = microPoints.find(item => item.row.model === model && item.code === code);
        return {
          ...point,
          lines,
          textX,
          textY,
          lineStartX:textX+Math.min(205,lines[0].length*6.2),
          lineStartY:textY+4,
          lineEndX:point.x-7,
          lineEndY:point.y-4,
        };
      };

      const tokenWidth = 900;
      const tokenHeight = 520;
      const tokenPlot = {left: 82, right: 26, top: 34, bottom: 90};
      const tokenPlotWidth = tokenWidth - tokenPlot.left - tokenPlot.right;
      const tokenPlotHeight = tokenHeight - tokenPlot.top - tokenPlot.bottom;
      const tokenSegments = {
        left: {start: tokenPlot.left, end: tokenPlot.left + tokenPlotWidth * .56, min: .29, max: .45},
        middle: {start: tokenPlot.left + tokenPlotWidth * .645, end: tokenPlot.left + tokenPlotWidth * .705, min: .55, max: .6},
        right: {start: tokenPlot.left + tokenPlotWidth * .77, end: tokenWidth - tokenPlot.right, min: .7, max: .85},
      };
      const tokenX = value => {
        const segment = value <= .45
          ? tokenSegments.left
          : value <= .6 ? tokenSegments.middle : tokenSegments.right;
        return segment.start + (value - segment.min) / (segment.max - segment.min) * (segment.end - segment.start);
      };
      const tokenY = value => tokenPlot.top + tokenPlotHeight * (40 - value) / 23;
      const tokenPoints = MODEL_ORDER.flatMap(model => ['OC', 'QC', 'CC', 'CX'].map(code => {
        const row = ordered.value.find(candidate => candidate.model === model && candidate.frameworkCode === code);
        const values = TOKEN_DATA[model]?.[code];
        if (!row || !values) return null;
        return pointFor(
          row,
          values[0],
          values[1],
          tokenX(values[0]),
          tokenY(values[1]),
          FIGURE_12_COLORS[model],
        );
      }).filter(Boolean));
      const frontier = [];
      let bestVcr = -Infinity;
      for (const point of [...tokenPoints].sort((a, b) => a.xValue - b.xValue)) {
        if (point.yValue > bestVcr) {
          frontier.push(point);
          bestVcr = point.yValue;
        }
      }
      return {
        micro: {
          width:microWidth,
          height:microHeight,
          plot:microPlot,
          plotWidth:microPlotWidth,
          plotHeight:microPlotHeight,
          baseline:microPlot.top+microPlotHeight,
          points:microPoints,
          xTicks:[20,25,30,35,40].map(value=>({value,x:microX(value)})),
          yTicks:[30,35,40,45,50,55].map(value=>({value,y:microY(value)})),
          xMedian:microX(microMedianX),
          yMedian:microY(microMedianY),
          labels:[
            annotation('Claude Opus 5.5','CC',['Claude Opus 5.5 + Claude Code'],232,118),
            annotation('GPT-5.6-sol','CX',['GPT-5.6-sol + Codex CLI'],196,176),
          ],
        },
        token: {
          width:tokenWidth,
          height:tokenHeight,
          plot:tokenPlot,
          plotWidth:tokenPlotWidth,
          plotHeight:tokenPlotHeight,
          baseline:tokenPlot.top+tokenPlotHeight,
          regionY:tokenY(36.5),
          points:tokenPoints,
          xTicks:[.3,.35,.4,.45,.55,.6,.75,.8].map(value=>({value,x:tokenX(value)})),
          yTicks:[20,25,30,35,40].map(value=>({value,y:tokenY(value)})),
          segments:tokenSegments,
          breaks:[
            {x:tokenPlot.left+tokenPlotWidth*.6025},
            {x:tokenPlot.left+tokenPlotWidth*.7375},
          ],
          frontier:frontier.map((point,index)=>`${index?'L':'M'} ${point.x} ${point.y}`).join(' '),
          annotations:[
            {text:['Claude Opus 5.5','Codex CLI (35.3%)'],x:tokenX(.327)-102,y:tokenY(37.4),lineX:tokenX(.361),lineY:tokenY(35.3)},
            {text:['Claude Opus 5.5','Claude Code (37.1%)'],x:tokenX(.414)-66,y:tokenY(39.15),lineX:tokenX(.414),lineY:tokenY(37.1)},
            {text:['GPT-5.6-sol','Codex CLI (36.5%)'],x:tokenX(.45)+10,y:tokenY(37.35),lineX:tokenX(.393),lineY:tokenY(36.5)},
          ],
        },
      };
    });

    return {activeBar, activeLine, activeMetric, metricInfo, barMetrics: BAR_METRICS,
      modelLegend, compact, ordered, barChart, categoryCharts, lineChart, scatterCharts,
      frameworkMeta: FRAMEWORK_META, figure9Models: FIGURE_9_MODEL_ORDER,
      figure12Models: FIGURE_12_MODEL_ORDER,
      figure9Colors: FIGURE_9_COLORS, figure12Colors: FIGURE_12_COLORS};
  },
  template: `<div class="official-metric-charts">
   <div v-if="!ordered.length" class="official-paper-empty card" role="status"><h3>当前条件暂无可绘制成绩</h3><p>调整测评集、版本或来源筛选后，图表会与榜单同步更新。</p></div>
   <section v-if="ordered.length" class="card card-pad official-metric-card" data-official-bar-chart aria-labelledby="official-bar-title">
    <header class="official-chart-heading">
     <div><h3 id="official-bar-title">模型–框架综合表现</h3><p>按 {{metricInfo.label}} 从高到低排列。{{metricInfo.description}}</p></div>
     <span class="official-axis-badge">差异视图 · {{barChart.yMin}}–{{barChart.yMax}}{{activeMetric==='process'?'':'%'}}</span>
    </header>
    <div class="official-bar-controls" role="tablist" aria-label="柱状图指标">
     <button v-for="metric in barMetrics" :key="metric.key" type="button" role="tab" :aria-selected="String(activeMetric===metric.key)" :tabindex="activeMetric===metric.key?0:-1" :data-bar-metric="metric.key" @click="activeMetric=metric.key">{{metric.label}}</button>
    </div>
    <div class="official-model-legend"><span v-for="model in modelLegend" :key="model.model"><i :style="{background:model.color}"></i>{{model.model}}</span></div>
     <div class="official-chart-scroll" tabindex="0" role="region" :aria-label="'24 组模型框架 '+metricInfo.label+' 柱状图，纵轴经过截断以突出差异'">
      <svg class="official-bar-svg" :viewBox="'0 0 '+barChart.width+' '+barChart.height" :style="{minWidth:barChart.width+'px'}" role="img" aria-labelledby="official-bar-title" :data-y-min="barChart.yMin" :data-y-max="barChart.yMax">
      <g class="official-grid" aria-hidden="true">
       <g v-for="line in barChart.grid" :key="line.value">
        <line :x1="barChart.plot.left" :x2="barChart.width-barChart.plot.right" :y1="line.y" :y2="line.y"/>
        <text :x="barChart.plot.left-12" :y="line.y+4">{{line.value}}</text>
       </g>
      </g>
      <line class="official-mean" :x1="barChart.plot.left" :x2="barChart.width-barChart.plot.right" :y1="barChart.meanY" :y2="barChart.meanY"/>
      <text class="official-mean-label" :x="barChart.width-barChart.plot.right-3" :y="barChart.meanY-6">{{metricInfo.label}} 均值 {{barChart.mean.toFixed(1)}}{{activeMetric==='process'?'':'%'}}</text>
      <g v-for="group in barChart.groups" :key="group.row.id" class="official-bar-group" :class="{active:activeBar===group.row.id}" tabindex="0" role="img" :aria-label="group.row.model+' '+group.row.framework+'：VCR '+group.row.vcr.toFixed(1)+'%，FullAcc '+group.row.fullAcc.toFixed(1)+'%'" @mouseenter="activeBar=group.row.id" @mouseleave="activeBar=null" @focus="activeBar=group.row.id" @blur="activeBar=null">
       <title>{{group.row.model}} / {{group.row.framework}} · {{metricInfo.label}} {{group.value.toFixed(1)}}{{activeMetric==='process'?'':'%'}}</title>
       <path class="official-bar-fill" :d="group.barPath" :fill="group.color"/>
       <text class="official-bar-value" :x="group.center" :y="Math.max(group.barY+19,barChart.baseline-9)">{{group.value.toFixed(activeMetric==='process'?1:0)}}{{activeMetric==='process'?'':'%'}}</text>
       <g class="official-model-icon" :transform="'translate('+(group.center-17)+' '+(barChart.baseline+8)+')'" aria-hidden="true">
        <g v-if="group.frameworkBoxed"><rect class="official-brand-box" width="16" height="16" rx="2"/><text class="official-brand-box-text" data-brand-mark x="8" y="11.5">{{group.frameworkMark}}</text></g>
        <text v-else class="official-brand-mark" data-brand-mark x="8" y="12" :fill="group.frameworkMarkColor">{{group.frameworkMark}}</text>
        <text class="official-brand-mark" data-brand-mark x="26" y="12" :fill="group.providerMarkColor">{{group.providerMark}}</text>
       </g>
       <text class="official-x-label" :x="group.center" :y="barChart.baseline+37" :transform="'rotate(-60 '+group.center+' '+(barChart.baseline+37)+')'">
        <tspan v-for="(line,index) in group.labelLines" :key="line+index" :x="group.center" :dy="index?11:0">{{line}}</tspan>
       </text>
      </g>
     </svg>
    </div>
    <p class="official-chart-note"><strong>差异视图：</strong>纵轴范围为 {{barChart.yMin}}–{{barChart.yMax}}{{activeMetric==='process'?'':'%'}}，起点不为 0，用于比较组间排序；柱体高度不应按比例解读。每根柱对应一个模型–框架组合，颜色区分模型，可横向滚动查看全部标签。</p>
   </section>

   <div v-if="ordered.length" class="official-category-bar-charts" data-official-category-charts aria-label="四类任务柱状图">
    <header class="official-category-bar-heading"><div><h3>分类表现</h3><p>按 Relations、Search、Exact、Bounds 四类任务分别比较 24 组模型与框架的 VCR。</p></div><span class="badge neutral">4 个分类 · 170 道任务</span></header>
    <section v-for="category in categoryCharts" :key="category.key" class="card card-pad official-metric-card official-category-bar-card" :data-official-category-chart="category.key" :aria-labelledby="'official-category-title-'+category.key">
     <header class="official-chart-heading">
      <div><h3 :id="'official-category-title-'+category.key"><span>{{category.title}}</span><small>{{category.description}}</small></h3><p>按分类 VCR 从高到低排列。该分类共 {{category.taskCount}} 道任务。</p></div>
      <span class="official-axis-badge">差异视图 · {{category.chart.yMin}}–{{category.chart.yMax}}%</span>
     </header>
     <div class="official-model-legend"><span v-for="model in modelLegend" :key="model.model"><i :style="{background:model.color}"></i>{{model.model}}</span></div>
     <div class="official-chart-scroll" tabindex="0" role="region" :aria-label="category.title+' 分类，24 组模型框架 VCR 柱状图，纵轴经过截断以突出差异'">
      <svg class="official-bar-svg official-category-bar-svg" :viewBox="'0 0 '+category.chart.width+' '+category.chart.height" :style="{minWidth:category.chart.width+'px'}" role="img" :aria-labelledby="'official-category-title-'+category.key" :data-y-min="category.chart.yMin" :data-y-max="category.chart.yMax">
       <g class="official-grid" aria-hidden="true">
        <g v-for="line in category.chart.grid" :key="line.value">
         <line :x1="category.chart.plot.left" :x2="category.chart.width-category.chart.plot.right" :y1="line.y" :y2="line.y"/>
         <text :x="category.chart.plot.left-12" :y="line.y+4">{{line.value}}</text>
        </g>
       </g>
       <line class="official-mean" :x1="category.chart.plot.left" :x2="category.chart.width-category.chart.plot.right" :y1="category.chart.meanY" :y2="category.chart.meanY"/>
       <text class="official-mean-label" :x="category.chart.width-category.chart.plot.right-3" :y="category.chart.meanY-6">{{category.title}} 均值 {{category.chart.mean.toFixed(1)}}%</text>
       <g v-for="group in category.chart.groups" :key="group.row.id" class="official-bar-group" :class="{active:activeBar===group.row.id}" :data-category-bar-record="group.row.id" tabindex="0" role="img" :aria-label="group.row.model+' '+group.row.framework+'，'+category.title+' VCR '+group.value.toFixed(1)+'%，完成 '+group.row.categoryPassed+' / '+group.row.categoryTotal" @mouseenter="activeBar=group.row.id" @mouseleave="activeBar=null" @focus="activeBar=group.row.id" @blur="activeBar=null">
        <title>{{group.row.model}} / {{group.row.framework}} · {{category.title}} VCR {{group.value.toFixed(1)}}% · {{group.row.categoryPassed}} / {{group.row.categoryTotal}}</title>
        <path class="official-bar-fill" :d="group.barPath" :fill="group.color"/>
        <text class="official-bar-value" :x="group.center" :y="Math.max(group.barY+19,category.chart.baseline-9)">{{group.value.toFixed(1)}}%</text>
        <g class="official-model-icon" :transform="'translate('+(group.center-17)+' '+(category.chart.baseline+8)+')'" aria-hidden="true">
         <g v-if="group.frameworkBoxed"><rect class="official-brand-box" width="16" height="16" rx="2"/><text class="official-brand-box-text" data-brand-mark x="8" y="11.5">{{group.frameworkMark}}</text></g>
         <text v-else class="official-brand-mark" data-brand-mark x="8" y="12" :fill="group.frameworkMarkColor">{{group.frameworkMark}}</text>
         <text class="official-brand-mark" data-brand-mark x="26" y="12" :fill="group.providerMarkColor">{{group.providerMark}}</text>
        </g>
        <text class="official-x-label" :x="group.center" :y="category.chart.baseline+37" :transform="'rotate(-60 '+group.center+' '+(category.chart.baseline+37)+')'">
         <tspan v-for="(line,index) in group.labelLines" :key="line+index" :x="group.center" :dy="index?11:0">{{line}}</tspan>
        </text>
       </g>
      </svg>
     </div>
     <p class="official-chart-note"><strong>差异视图：</strong>纵轴范围为 {{category.chart.yMin}}–{{category.chart.yMax}}%，起点不为 0，用于比较该分类内的组间排序；柱体高度不应按比例解读。颜色区分模型，可横向滚动查看全部标签。</p>
    </section>
   </div>

   <section v-if="ordered.length" class="card card-pad official-metric-card" data-official-line-chart aria-labelledby="official-line-title">
    <header class="official-chart-heading">
     <div><h3 id="official-line-title">60 分钟累计 VCR</h3><p>每个基础模型一个面板，比较四种 Agent 框架随执行时间的累计完成率。</p></div>
     <span class="badge neutral">24 组组合</span>
    </header>
    <div class="official-framework-legend"><span v-for="(meta,code) in frameworkMeta" :key="code"><i :style="{background:meta.color}"></i>{{meta.name}}</span></div>
    <div class="official-chart-scroll" tabindex="0" role="region" aria-label="六个模型面板的四框架累计 VCR 折线图">
     <svg class="official-line-svg" :class="{compact}" :viewBox="'0 0 '+lineChart.width+' '+lineChart.height" role="img" aria-labelledby="official-line-title">
      <g v-for="panel in lineChart.panels" :key="panel.model" class="official-line-panel" :data-line-panel="panel.model">
       <text class="official-panel-title" :x="panel.left" :y="panel.yOffset+16">{{panel.model}}</text>
       <text class="official-panel-letter" :x="panel.right" :y="panel.yOffset+16">{{panel.letter}}</text>
       <g class="official-grid" aria-hidden="true">
        <g v-for="line in panel.grid" :key="line.value">
         <line :x1="panel.left" :x2="panel.right" :y1="line.y" :y2="line.y"/>
         <text :x="panel.left-7" :y="line.y+3">{{line.value}}</text>
        </g>
       </g>
       <g class="official-line-x" aria-hidden="true">
        <text v-for="tick in panel.xTicks" :key="tick.time" :x="tick.x" :y="panel.bottom+17">{{tick.time}}</text>
       </g>
       <g v-for="series in panel.series" :key="series.row.id" class="official-line-series" :class="{active:activeLine===series.row.id}" :style="{color:series.meta.color}" :data-official-line-series="series.row.id" tabindex="0" role="img" :aria-label="series.row.model+' '+series.row.framework+' 的累计 VCR 曲线，60 分钟为 '+series.row.vcr.toFixed(1)+'%'" @mouseenter="activeLine=series.row.id" @mouseleave="activeLine=null" @focus="activeLine=series.row.id" @blur="activeLine=null">
        <title>{{series.row.model}} / {{series.row.framework}}：{{series.points.map(point=>point.time+' 分钟 '+point.value.toFixed(1)+'%').join('，')}}</title>
        <path :d="series.path" stroke="currentColor" fill="none"/>
        <circle v-for="point in series.points" :key="point.time" :cx="point.x" :cy="point.y" r="2.7" fill="currentColor">
         <title>{{point.time}} 分钟 · {{point.value.toFixed(1)}}%</title>
        </circle>
       </g>
      </g>
     </svg>
    </div>
    <p class="official-chart-note">横轴为每题已用时间，纵轴为累计 VCR，所有面板共用 0–40% 尺度。中间时间点按统一 60 分钟完成节奏映射，终点采用各组正式 VCR。</p>
   </section>

   <section v-if="ordered.length && showScatter" class="official-scatter-section paper-research-section" aria-labelledby="official-scatter-title">
    <header class="official-chart-heading paper-research-heading">
     <div><h3 id="official-scatter-title">分布与资源效率</h3><p>补充展示任务加权表现、方向均衡表现与平均 Token 消耗之间的关系。</p></div>
     <span class="badge neutral">24 组组合</span>
    </header>
    <div class="paper-research-figures">
     <figure class="paper-research-figure" data-paper-micro-macro-figure>
      <header><h4>任务加权 Micro VCR 与方向均衡 Macro VCR</h4><p>每个点代表一个模型–框架组合；颜色识别模型，形状识别 Agent 框架。</p></header>
      <div class="paper-figure-scroll" tabindex="0" role="region" aria-label="Micro VCR 与 Direction-macro VCR 散点图">
       <svg class="paper-figure-svg paper-figure-nine" :viewBox="'0 0 '+scatterCharts.micro.width+' '+scatterCharts.micro.height" role="img" aria-labelledby="paper-micro-title">
        <title id="paper-micro-title">Micro VCR 与 Direction-macro VCR</title>
        <rect class="paper-better-zone" :x="scatterCharts.micro.xMedian" :y="scatterCharts.micro.plot.top" :width="scatterCharts.micro.width-scatterCharts.micro.plot.right-scatterCharts.micro.xMedian" :height="scatterCharts.micro.yMedian-scatterCharts.micro.plot.top"/>
        <text class="paper-zone-label" :x="scatterCharts.micro.width-scatterCharts.micro.plot.right-10" :y="scatterCharts.micro.plot.top+24">Higher is better</text>
        <g class="paper-figure-grid" aria-hidden="true">
         <g v-for="tick in scatterCharts.micro.xTicks" :key="'mx'+tick.value"><line :x1="tick.x" :x2="tick.x" :y1="scatterCharts.micro.plot.top" :y2="scatterCharts.micro.baseline"/><text class="paper-x-tick" :x="tick.x" :y="scatterCharts.micro.baseline+22">{{tick.value}}</text></g>
         <g v-for="tick in scatterCharts.micro.yTicks" :key="'my'+tick.value"><line :x1="scatterCharts.micro.plot.left" :x2="scatterCharts.micro.width-scatterCharts.micro.plot.right" :y1="tick.y" :y2="tick.y"/><text :x="scatterCharts.micro.plot.left-14" :y="tick.y+4">{{tick.value}}</text></g>
        </g>
        <g class="paper-figure-axes" aria-hidden="true"><line :x1="scatterCharts.micro.plot.left" :x2="scatterCharts.micro.plot.left" :y1="scatterCharts.micro.plot.top" :y2="scatterCharts.micro.baseline"/><line :x1="scatterCharts.micro.plot.left" :x2="scatterCharts.micro.width-scatterCharts.micro.plot.right" :y1="scatterCharts.micro.baseline" :y2="scatterCharts.micro.baseline"/></g>
        <line class="paper-median-line" :x1="scatterCharts.micro.xMedian" :x2="scatterCharts.micro.xMedian" :y1="scatterCharts.micro.plot.top" :y2="scatterCharts.micro.baseline"/>
        <line class="paper-median-line" :x1="scatterCharts.micro.plot.left" :x2="scatterCharts.micro.width-scatterCharts.micro.plot.right" :y1="scatterCharts.micro.yMedian" :y2="scatterCharts.micro.yMedian"/>
        <g v-for="(label,index) in scatterCharts.micro.labels" :key="'ml'+index" class="paper-annotation" aria-hidden="true">
         <line :x1="label.lineStartX" :x2="label.lineEndX" :y1="label.lineStartY" :y2="label.lineEndY"/>
         <text :x="label.textX" :y="label.textY">{{label.lines[0]}}</text>
        </g>
        <g v-for="point in scatterCharts.micro.points" :key="'mp'+point.row.id" class="paper-figure-point" :data-paper-micro-point="point.row.id" tabindex="0" role="img" :aria-label="point.row.model+' '+point.row.framework+'：Micro VCR '+point.xValue.toFixed(1)+'%，Direction-macro VCR '+point.yValue.toFixed(1)+'%'">
         <title>{{point.row.model}} / {{point.row.framework}} · Micro VCR {{point.xValue.toFixed(1)}}% · Direction-macro VCR {{point.yValue.toFixed(1)}}%</title>
         <circle v-if="point.code==='OC'" :cx="point.x" :cy="point.y" r="6" :fill="point.color"/>
         <rect v-else-if="point.code==='QC'" :x="point.x-5" :y="point.y-5" width="10" height="10" :fill="point.color"/>
         <polygon v-else-if="point.code==='CC'" :points="point.triangle" :fill="point.color"/>
         <polygon v-else :points="point.diamond" :fill="point.color"/>
        </g>
        <text class="paper-axis-title" :x="scatterCharts.micro.plot.left+scatterCharts.micro.plotWidth/2" :y="scatterCharts.micro.height-18">Micro VCR (%)</text>
        <text class="paper-axis-title" x="24" :y="scatterCharts.micro.plot.top+scatterCharts.micro.plotHeight/2" :transform="'rotate(-90 24 '+(scatterCharts.micro.plot.top+scatterCharts.micro.plotHeight/2)+')'">Direction-macro VCR (%)</text>
       </svg>
      </div>
      <div class="paper-figure-legend">
       <div class="paper-model-legend"><span v-for="model in figure9Models" :key="model"><i :style="{background:figure9Colors[model]}"></i>{{model}}</span></div>
       <div class="paper-shape-legend"><span v-for="(meta,code) in frameworkMeta" :key="code"><i :class="'paper-shape shape-'+code"></i>{{meta.name}}</span></div>
      </div>
      <figcaption>任务加权 Micro VCR 与方向均衡 Macro VCR。虚线表示 24 组组合的中位数；颜色识别模型，形状识别 Agent 框架。</figcaption>
     </figure>

     <figure class="paper-research-figure" data-paper-token-figure>
      <header><h4>平均每任务 Token 与最终 VCR</h4><p>横轴采用分段尺度，虚线连接资源–完成率前沿。</p></header>
      <div class="paper-figure-scroll" tabindex="0" role="region" aria-label="平均每任务 Token 与最终 VCR 散点图">
       <svg class="paper-figure-svg paper-figure-twelve" :viewBox="'0 0 '+scatterCharts.token.width+' '+scatterCharts.token.height" role="img" aria-labelledby="paper-token-title">
        <title id="paper-token-title">平均每任务 Token 与最终 VCR</title>
        <rect class="paper-token-region" :x="scatterCharts.token.plot.left" :y="scatterCharts.token.regionY" :width="scatterCharts.token.plotWidth" :height="scatterCharts.token.baseline-scatterCharts.token.regionY"/>
        <g class="paper-figure-grid" aria-hidden="true">
         <g v-for="tick in scatterCharts.token.xTicks" :key="'tx'+tick.value"><line :x1="tick.x" :x2="tick.x" :y1="scatterCharts.token.plot.top" :y2="scatterCharts.token.baseline"/><text class="paper-x-tick" :x="tick.x" :y="scatterCharts.token.baseline+22">{{tick.value}}</text></g>
         <g v-for="tick in scatterCharts.token.yTicks" :key="'ty'+tick.value"><line :x1="scatterCharts.token.plot.left" :x2="scatterCharts.token.width-scatterCharts.token.plot.right" :y1="tick.y" :y2="tick.y"/><text :x="scatterCharts.token.plot.left-14" :y="tick.y+4">{{tick.value}}</text></g>
        </g>
        <g class="paper-figure-axes" aria-hidden="true"><line :x1="scatterCharts.token.plot.left" :x2="scatterCharts.token.plot.left" :y1="scatterCharts.token.plot.top" :y2="scatterCharts.token.baseline"/><line :x1="scatterCharts.token.plot.left" :x2="scatterCharts.token.width-scatterCharts.token.plot.right" :y1="scatterCharts.token.baseline" :y2="scatterCharts.token.baseline"/></g>
        <g class="paper-axis-break" aria-hidden="true"><path v-for="item in scatterCharts.token.breaks" :key="item.x" :d="'M '+(item.x-5)+' '+scatterCharts.token.baseline+' l 8 -13 M '+(item.x+1)+' '+scatterCharts.token.baseline+' l 8 -13'"/></g>
        <path v-if="scatterCharts.token.frontier" class="paper-frontier-line" :d="scatterCharts.token.frontier"/>
        <g v-for="(label,index) in scatterCharts.token.annotations" :key="'ta'+index" class="paper-annotation paper-token-annotation" aria-hidden="true">
         <line :x1="label.x" :x2="label.lineX" :y1="label.y+8" :y2="label.lineY"/>
         <text :x="label.x" :y="label.y"><tspan :x="label.x">{{label.text[0]}}</tspan><tspan :x="label.x" dy="13">{{label.text[1]}}</tspan></text>
        </g>
        <g v-for="point in scatterCharts.token.points" :key="'tp'+point.row.id" class="paper-figure-point" :data-paper-token-point="point.row.id" tabindex="0" role="img" :aria-label="point.row.model+' '+point.row.framework+'：平均每任务 '+Math.round(point.xValue*1000000).toLocaleString('en-US')+' Token，最终 VCR '+point.yValue.toFixed(1)+'%'">
         <title>{{point.row.model}} / {{point.row.framework}} · {{Math.round(point.xValue*1000000).toLocaleString('en-US')}} Token · Final VCR {{point.yValue.toFixed(1)}}%</title>
         <circle v-if="point.code==='OC'" :cx="point.x" :cy="point.y" r="6" :fill="point.color"/>
         <rect v-else-if="point.code==='QC'" :x="point.x-5" :y="point.y-5" width="10" height="10" :fill="point.color"/>
         <polygon v-else-if="point.code==='CC'" :points="point.triangle" :fill="point.color"/>
         <polygon v-else :points="point.diamond" :fill="point.color"/>
        </g>
        <text class="paper-axis-title" :x="scatterCharts.token.plot.left+scatterCharts.token.plotWidth/2" :y="scatterCharts.token.height-18">Mean tokens per task (million)</text>
        <text class="paper-axis-title" x="24" :y="scatterCharts.token.plot.top+scatterCharts.token.plotHeight/2" :transform="'rotate(-90 24 '+(scatterCharts.token.plot.top+scatterCharts.token.plotHeight/2)+')'">Final VCR (%)</text>
       </svg>
      </div>
      <div class="paper-figure-legend">
       <div class="paper-model-legend"><span v-for="model in figure12Models" :key="model"><i :style="{background:figure12Colors[model]}"></i>{{model}}</span></div>
       <div class="paper-shape-legend"><span v-for="(meta,code) in frameworkMeta" :key="code"><i :class="'paper-shape shape-'+code"></i>{{meta.name}}</span></div>
      </div>
      <figcaption>平均每任务 Token 与最终 VCR。横轴使用不同尺度的分段；虚线表示资源–完成率前沿，前沿上没有其他组合能在不恶化一项指标的情况下改善另一项。</figcaption>
     </figure>
    </div>
   </section>
  </div>`,
};
