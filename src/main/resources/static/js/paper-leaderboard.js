// Official CryptoBench-SC V4 comparison dataset.
// VCR and FullAcc are percentages. Counts are the completed tasks per category.
const categoryNames = [
  'Relations / model auditing',
  'Search / optimization',
  'Exact quantities / enumeration',
  'Bounds / derivation',
];
const categoryTotals = [74, 47, 26, 23];
const frameworkNames = {
  CC: 'Claude Code',
  CX: 'Codex CLI',
  OC: 'OpenCode',
  QC: 'Qwen Code',
};

const combinations = [
  ['Claude Opus 5.5', 'CC', false, false, [27, 17, 11, 8], [36.5, 36.2, 42.3, 34.8], 37.1, 42.9, 72.9, 85.3],
  ['GPT-5.6-sol', 'CC', false, false, [25, 17, 9, 9], [33.8, 36.2, 34.6, 39.1], 35.3, 41.2, 60.0, 83.2],
  ['Grok 4.7', 'CC', false, false, [24, 16, 10, 9], [32.4, 34.0, 38.5, 39.1], 34.7, 40.6, 58.6, 74.3],
  ['Gemini 3.1 Pro', 'CC', false, false, [23, 16, 9, 8], [31.1, 34.0, 34.6, 34.8], 32.9, 40.6, 56.1, 70.7],
  ['GPT-5.6-sol', 'CX', false, true, [26, 17, 11, 8], [35.1, 36.2, 42.3, 34.8], 36.5, 38.8, 67.6, 84.5],
  ['Claude Opus 5.5', 'CX', false, true, [26, 16, 11, 7], [35.1, 34.0, 42.3, 30.4], 35.3, 40.6, 62.7, 80.4],
  ['Claude Opus 5.5', 'OC', false, true, [24, 17, 11, 7], [32.4, 36.2, 42.3, 30.4], 34.7, 40.6, 55.9, 77.3],
  ['Claude Opus 5.5', 'QC', false, true, [24, 16, 11, 6], [32.4, 34.0, 42.3, 26.1], 33.5, 39.4, 53.1, 75.2],
  ['GPT-5.6-sol', 'OC', false, true, [24, 15, 10, 7], [32.4, 31.9, 38.5, 30.4], 32.9, 39.4, 48.2, 76.7],
  ['Grok 4.7', 'CX', false, true, [23, 16, 10, 7], [31.1, 34.0, 38.5, 30.4], 32.9, 38.2, 50.9, 69.3],
  ['GPT-5.6-sol', 'QC', false, true, [23, 15, 9, 7], [31.1, 31.9, 34.6, 30.4], 31.8, 38.2, 45.7, 74.2],
  ['Grok 4.7', 'OC', false, true, [23, 15, 10, 6], [31.1, 31.9, 38.5, 26.1], 31.8, 38.2, 44.8, 64.9],
  ['Gemini 3.1 Pro', 'CX', false, true, [22, 16, 9, 6], [29.7, 34.0, 34.6, 26.1], 31.2, 38.8, 47.9, 65.7],
  ['Grok 4.7', 'QC', false, true, [22, 15, 8, 7], [29.7, 31.9, 30.8, 30.4], 30.6, 36.5, 42.4, 62.7],
  ['Gemini 3.1 Pro', 'OC', false, true, [22, 14, 9, 6], [29.7, 29.8, 34.6, 26.1], 30.0, 37.6, 42.9, 61.3],
  ['Gemini 3.1 Pro', 'QC', false, true, [20, 13, 8, 6], [27.0, 27.7, 30.8, 26.1], 27.6, 34.7, 40.7, 58.4],
  ['DeepSeek V4 Pro', 'CC', true, false, [24, 15, 11, 7], [32.4, 31.9, 42.3, 30.4], 33.5, 41.8, 61.9, 75.9],
  ['Qwen 3.8 Max', 'CC', true, false, [17, 12, 7, 6], [23.0, 25.5, 26.9, 26.1], 24.7, 32.9, 42.2, 55.0],
  ['DeepSeek V4 Pro', 'CX', true, true, [22, 15, 9, 8], [29.7, 31.9, 34.6, 34.8], 31.8, 38.8, 52.6, 70.8],
  ['DeepSeek V4 Pro', 'OC', true, true, [22, 14, 9, 7], [29.7, 29.8, 34.6, 30.4], 30.6, 38.2, 48.3, 67.2],
  ['DeepSeek V4 Pro', 'QC', true, true, [20, 14, 9, 6], [27.0, 29.8, 34.6, 26.1], 28.8, 35.9, 43.0, 63.8],
  ['Qwen 3.8 Max', 'CX', true, true, [16, 11, 6, 5], [21.6, 23.4, 23.1, 21.7], 22.4, 29.4, 36.4, 50.0],
  ['Qwen 3.8 Max', 'OC', true, true, [14, 10, 5, 5], [18.9, 21.3, 19.2, 21.7], 20.0, 27.1, 30.9, 44.1],
  ['Qwen 3.8 Max', 'QC', true, true, [13, 8, 6, 4], [17.6, 17.0, 23.1, 17.4], 18.2, 24.7, 28.8, 41.3],
];

export const paperBenchmark = {
  id: 'cryptobench-sc-v4-official',
  source: 'official',
  taskCount: 170,
  modelCount: 6,
  frameworkCount: 4,
  combinationCount: 24,
  budgetSeconds: 3600,
  tokenBudget: 2_000_000,
  categoryTotals,
  metricMeans: {
    vcr: 30.8,
    fullAcc: 37.3,
    vpr: 49.8,
    process: 68.0,
  },
};

export const paperLeaderboardRows = combinations.map(
  ([model, code, openWeights, openFramework, counts, rates, vcr, fullAcc, vpr, process]) => {
    const framework = frameworkNames[code];
    const passed = counts.reduce((sum, value) => sum + value, 0);
    return {
      id: `official-${model.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${code.toLowerCase()}`,
      agent: framework,
      framework,
      frameworkCode: code,
      model,
      datasetId: 'cryptobench-sc-v4',
      version: '4.2.0',
      budget: 'official',
      scope: 'full',
      kind: 'evaluation',
      source: 'official',
      demo: false,
      officialBaseline: true,
      published: true,
      total: 170,
      passed,
      score: vcr,
      vcr,
      fullAcc,
      vpr,
      process,
      openWeights,
      openFramework,
      cost: null,
      time: null,
      averageTimeSeconds: null,
      totalTimeSeconds: null,
      categories: categoryNames.map((name, index) => ({
        name,
        passed: counts[index],
        total: categoryTotals[index],
        score: rates[index],
      })),
      provenance: {
        release: 'CryptoBench-SC V4 official baseline',
        version: '4.2.0',
        budgetSeconds: 3600,
        tokenBudget: 2_000_000,
      },
    };
  },
);
