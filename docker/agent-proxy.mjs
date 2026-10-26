import net from 'node:net';

const listenPort = Number(process.env.PROXY_PORT || 8000);
const targetHost = process.env.TARGET_HOST || 'agent';
const targetPort = Number(process.env.TARGET_PORT || 8000);

function forward(client) {
  let attempts = 0;
  let upstream;
  let connected = false;
  const connect = () => {
    attempts += 1;
    upstream = net.createConnection({host: targetHost, port: targetPort});
    upstream.once('connect', () => {
      connected = true;
      client.pipe(upstream);
      upstream.pipe(client);
    });
    upstream.once('error', () => {
      upstream.destroy();
      if (!connected && attempts < 30 && !client.destroyed) setTimeout(connect, 100);
      else if (!client.destroyed) client.destroy();
    });
    upstream.once('close', () => {
      if (!client.destroyed && connected) client.destroy();
    });
  };
  client.once('error', () => upstream?.destroy());
  client.once('close', () => upstream?.destroy());
  connect();
}

const server = net.createServer(forward);
server.listen(listenPort, '0.0.0.0', () => {
  console.log(`Agent proxy listening on ${listenPort}, target ${targetHost}:${targetPort}`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
