import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { createRelayServer } from './openai-relay.mjs';

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

test('forwards labelled OpenAI requests and redacts authorization in captures', async () => {
  const captureDir = await mkdtemp(join(tmpdir(), 'tellev-relay-'));
  let upstreamRequest;
  const upstream = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    upstreamRequest = {
      url: request.url,
      authorization: request.headers.authorization,
      body: JSON.parse(Buffer.concat(chunks).toString('utf8')),
    };
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }));
  });
  const upstreamPort = await listen(upstream);
  const { server: relay } = createRelayServer({
    upstream: `http://127.0.0.1:${upstreamPort}`,
    captureDir,
    logger: { log() {}, error() {} },
  });
  const relayPort = await listen(relay);

  try {
    const response = await fetch(`http://127.0.0.1:${relayPort}/tellev/v1/chat/completions`, {
      method: 'POST',
      headers: {
        authorization: 'Bearer secret-value',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: 'deepseek-chat',
        messages: [{ role: 'user', content: 'hello' }],
      }),
    });
    assert.equal(response.status, 200);
    assert.equal(upstreamRequest.url, '/v1/chat/completions');
    assert.equal(upstreamRequest.authorization, 'Bearer secret-value');

    const requestFile = (await readdir(captureDir)).find((name) => name.endsWith('-tellev.request.json'));
    const capture = JSON.parse(await readFile(join(captureDir, requestFile), 'utf8'));
    assert.equal(capture.meta.headers.authorization, '[REDACTED]');
    assert.equal(capture.meta.summary.messageCount, 1);
    assert.equal(capture.request.messages[0].content, 'hello');
  } finally {
    await close(relay);
    await close(upstream);
    await rm(captureDir, { recursive: true, force: true });
  }
});
