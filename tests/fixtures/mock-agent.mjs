import http from 'node:http';

const portFlag = process.argv.indexOf('--port');
const port = Number((portFlag >= 0 ? process.argv[portFlag + 1] : null) || process.env.MOCK_AGENT_PORT || 8000);
const host = process.env.MOCK_AGENT_HOST || '127.0.0.1';
const delayMs = Number(process.env.MOCK_AGENT_DELAY_MS || 200);
const answers = {
  'mini-spn-001': 'active_sbox_bound=4',
  'mini-spn-002': 'active_sbox_bound=6',
  'mini-spn-003': 'trail_weight=-8',
  'mini-spn-004': 'key_bits=8',
  'mini-spn-005': 'linear_bias=0.25',
  'mini-spn-006': 'rounds=4',
  'mini-spn-007': 'candidate_count=2',
  'mini-spn-008': 'verified=true'
};

function send(response, status, body) {
  const value = JSON.stringify(body);
  response.writeHead(status, {'content-type': 'application/json'});
  response.end(value);
}

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    return send(response, 200, {status: 'ok', version: 'mock-agent-1.0.0'});
  }
  if (request.method === 'GET' && request.url === '/capabilities') {
    return send(response, 200, {protocolVersion: '1.0', agentVersion: 'mock-agent-1.0.0', models: ['GPT-4.1', 'Claude Sonnet 4', 'DeepSeek V3', 'Qwen3', 'Gemini 2.5 Pro', 'research-local-model', 'mock-model'], tools: ['python'], datasets: [{id: 'mini-spn', version: '1.0.0'}, {id: 'custom-spn', version: '1.0.0'}, {id: 'custom-spn', version: '2.0.0'}]});
  }
  if (request.method === 'POST' && request.url === '/analyze') {
    let body = '';
    for await (const chunk of request) body += chunk;
    const input = JSON.parse(body || '{}');
    if (delayMs > 0) await new Promise(resolve => setTimeout(resolve, delayMs));
    const answer = answers[input.taskId] || 'verified=true';
    return send(response, 200, {status: 'completed', artifact: {answer}, evidence: [{type: 'mock', content: 'deterministic fixture'}], usage: {agentCalls: 1, inputTokens: 10, outputTokens: 10, costUsd: 0}, elapsedMs: 5});
  }
  send(response, 404, {error: 'not found'});
});

server.listen(port, host, () => {
  console.log(`Mock Agent listening at http://${host}:${port}`);
});

process.on('SIGINT', () => server.close(() => process.exit(0)));
