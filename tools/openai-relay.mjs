#!/usr/bin/env node

import http from 'node:http';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, stat, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 8787;
const DEFAULT_UPSTREAM = 'https://api.deepseek.com';
const SENSITIVE_HEADERS = new Set(['authorization', 'proxy-authorization', 'x-api-key', 'api-key']);
const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'content-length',
  'host',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);

function parseArgs(argv) {
  const options = {
    host: DEFAULT_HOST,
    port: DEFAULT_PORT,
    upstream: process.env.OPENAI_RELAY_UPSTREAM || DEFAULT_UPSTREAM,
    captureDir: process.env.OPENAI_RELAY_CAPTURE_DIR || 'captures/openai-relay',
    apkPath: process.env.OPENAI_RELAY_APK || 'app/build/outputs/apk/debug/app-debug.apk',
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (key === '--host' && value) options.host = value;
    else if (key === '--port' && value) options.port = Number(value);
    else if (key === '--upstream' && value) options.upstream = value;
    else if (key === '--capture-dir' && value) options.captureDir = value;
    else if (key === '--apk' && value) options.apkPath = value;
    else if (key === '--help' || key === '-h') options.help = true;
    else continue;
    index += key.startsWith('--') && key !== '--help' ? 1 : 0;
  }
  if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) {
    throw new Error(`Invalid --port: ${options.port}`);
  }
  return options;
}

function helpText() {
  return `Transparent OpenAI-compatible capture relay

Usage:
  node tools/openai-relay.mjs [options]

Options:
  --host <host>             Listen host (default: ${DEFAULT_HOST})
  --port <port>             Listen port (default: ${DEFAULT_PORT})
  --upstream <url>          Upstream API base (default: ${DEFAULT_UPSTREAM})
  --capture-dir <path>      Capture directory (default: captures/openai-relay)
  --apk <path>              Debug APK served at /__relay/tellev-debug.apk

Client-labelled base URLs:
  Tellev:       http://127.0.0.1:${DEFAULT_PORT}/tellev
  SillyTavern:  http://127.0.0.1:${DEFAULT_PORT}/sillytavern/v1

Authorization/API-key headers are forwarded but never written to capture files.`;
}

function sanitizeLabel(value) {
  return String(value || 'unknown').replace(/[^a-zA-Z0-9_.-]/g, '_').slice(0, 48) || 'unknown';
}

function splitClientPath(pathname, headerLabel) {
  const segments = pathname.split('/').filter(Boolean);
  const pathLabel = segments[0]?.toLowerCase();
  const knownPathLabel = pathLabel === 'tellev' || pathLabel === 'sillytavern';
  const client = sanitizeLabel(knownPathLabel ? pathLabel : headerLabel);
  const apiSegments = knownPathLabel ? segments.slice(1) : segments;
  return { client, apiPath: `/${apiSegments.join('/')}` };
}

function joinUpstreamUrl(upstream, apiPath, search) {
  const target = new URL(upstream);
  const basePath = target.pathname.replace(/\/$/, '');
  let suffix = apiPath.startsWith('/') ? apiPath : `/${apiPath}`;
  if (basePath.toLowerCase().endsWith('/v1') && suffix.toLowerCase().startsWith('/v1/')) {
    suffix = suffix.slice(3);
  }
  target.pathname = `${basePath}${suffix}` || '/';
  target.search = search;
  return target;
}

function forwardedRequestHeaders(headers) {
  const result = {};
  for (const [name, value] of Object.entries(headers)) {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP_HEADERS.has(lower) || lower === 'x-relay-client') continue;
    if (value !== undefined) result[name] = value;
  }
  result['accept-encoding'] = 'identity';
  return result;
}

function capturedHeaders(headers) {
  const result = {};
  for (const [name, value] of Object.entries(headers)) {
    result[name] = SENSITIVE_HEADERS.has(name.toLowerCase()) ? '[REDACTED]' : value;
  }
  return result;
}

function parseJsonBody(buffer) {
  if (!buffer.length) return null;
  try {
    return JSON.parse(buffer.toString('utf8'));
  } catch {
    return buffer.toString('utf8');
  }
}

function requestSummary(body) {
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  const totalCharacters = messages.reduce((sum, message) => {
    const content = typeof message?.content === 'string'
      ? message.content
      : JSON.stringify(message?.content ?? '');
    return sum + content.length;
  }, 0);
  return {
    model: body?.model ?? null,
    stream: body?.stream ?? null,
    messageCount: messages.length,
    totalCharacters,
    roles: messages.reduce((counts, message) => {
      const role = String(message?.role || 'unknown');
      counts[role] = (counts[role] || 0) + 1;
      return counts;
    }, {}),
  };
}

function digest(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

function responseHeadersForClient(upstreamHeaders) {
  const result = {};
  upstreamHeaders.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP_HEADERS.has(lower) || lower === 'content-encoding') return;
    result[name] = value;
  });
  result['access-control-allow-origin'] = '*';
  return result;
}

async function readIncomingBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

export function createRelayServer({
  upstream = DEFAULT_UPSTREAM,
  captureDir = 'captures/openai-relay',
  fetchImpl = fetch,
  clock = () => new Date(),
  logger = console,
  apkPath = 'app/build/outputs/apk/debug/app-debug.apk',
} = {}) {
  const absoluteCaptureDir = resolve(captureDir);
  const absoluteApkPath = resolve(apkPath);
  let sequence = 0;

  const server = http.createServer(async (request, response) => {
    const startedAt = clock();
    const incomingUrl = new URL(request.url || '/', 'http://relay.local');

    if (incomingUrl.pathname === '/__relay/status') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({
        ok: true,
        upstream,
        captureDir: absoluteCaptureDir,
        apkDownload: '/__relay/tellev-debug.apk',
      }));
      return;
    }
    if (incomingUrl.pathname === '/__relay/tellev-debug.apk') {
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        response.writeHead(405, { allow: 'GET, HEAD' });
        response.end();
        return;
      }
      try {
        const apk = await stat(absoluteApkPath);
        response.writeHead(200, {
          'content-type': 'application/vnd.android.package-archive',
          'content-length': apk.size,
          'content-disposition': 'attachment; filename="tellev-debug.apk"',
          'cache-control': 'no-store',
        });
        if (request.method === 'HEAD') response.end();
        else createReadStream(absoluteApkPath).pipe(response);
      } catch {
        response.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
        response.end(JSON.stringify({ error: 'Debug APK not found. Run :app:assembleDebug first.' }));
      }
      return;
    }
    if (request.method === 'OPTIONS') {
      response.writeHead(204, {
        'access-control-allow-origin': '*',
        'access-control-allow-headers': '*',
        'access-control-allow-methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
      });
      response.end();
      return;
    }

    sequence += 1;
    const { client, apiPath } = splitClientPath(
      incomingUrl.pathname,
      request.headers['x-relay-client'],
    );
    const targetUrl = joinUpstreamUrl(upstream, apiPath, incomingUrl.search);
    const requestBody = await readIncomingBody(request);
    const parsedRequestBody = parseJsonBody(requestBody);
    const stamp = startedAt.toISOString().replace(/[:.]/g, '-');
    const captureBase = `${stamp}-${String(sequence).padStart(4, '0')}-${client}`;
    await mkdir(absoluteCaptureDir, { recursive: true });

    const requestCapture = {
      meta: {
        id: captureBase,
        client,
        capturedAt: startedAt.toISOString(),
        method: request.method,
        incomingPath: incomingUrl.pathname + incomingUrl.search,
        upstreamUrl: targetUrl.toString(),
        headers: capturedHeaders(request.headers),
        bodySha256: digest(requestBody),
        summary: requestSummary(parsedRequestBody),
      },
      request: parsedRequestBody,
    };
    await writeJson(resolve(absoluteCaptureDir, `${captureBase}.request.json`), requestCapture);

    const summary = requestCapture.meta.summary;
    logger.log(
      `[relay] ${captureBase} ${request.method} ${apiPath}` +
      ` messages=${summary.messageCount} chars=${summary.totalCharacters}`,
    );

    try {
      const upstreamResponse = await fetchImpl(targetUrl, {
        method: request.method,
        headers: forwardedRequestHeaders(request.headers),
        body: ['GET', 'HEAD'].includes(request.method || '') ? undefined : requestBody,
        redirect: 'manual',
      });
      response.writeHead(upstreamResponse.status, responseHeadersForClient(upstreamResponse.headers));

      const responseChunks = [];
      if (upstreamResponse.body) {
        const reader = upstreamResponse.body.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = Buffer.from(value);
          responseChunks.push(chunk);
          response.write(chunk);
        }
      }
      response.end();

      const responseBody = Buffer.concat(responseChunks);
      const contentType = upstreamResponse.headers.get('content-type') || '';
      const responsePath = resolve(
        absoluteCaptureDir,
        `${captureBase}.response.${contentType.includes('text/event-stream') ? 'sse' : 'json'}`,
      );
      if (contentType.includes('text/event-stream')) {
        await writeFile(responsePath, responseBody);
      } else {
        await writeJson(responsePath, {
          meta: {
            requestId: captureBase,
            status: upstreamResponse.status,
            durationMs: clock().getTime() - startedAt.getTime(),
            headers: capturedHeaders(Object.fromEntries(upstreamResponse.headers.entries())),
            bodySha256: digest(responseBody),
          },
          response: parseJsonBody(responseBody),
        });
      }
      logger.log(`[relay] ${captureBase} -> ${upstreamResponse.status}`);
    } catch (error) {
      const payload = {
        error: {
          message: error instanceof Error ? error.message : String(error),
          type: 'relay_upstream_error',
        },
      };
      await writeJson(resolve(absoluteCaptureDir, `${captureBase}.response.json`), {
        meta: {
          requestId: captureBase,
          status: 502,
          durationMs: clock().getTime() - startedAt.getTime(),
        },
        response: payload,
      });
      if (!response.headersSent) response.writeHead(502, { 'content-type': 'application/json' });
      if (!response.writableEnded) response.end(JSON.stringify(payload));
      logger.error(`[relay] ${captureBase} failed: ${payload.error.message}`);
    }
  });

  return { server, captureDir: absoluteCaptureDir };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log(helpText());
    return;
  }
  const { server, captureDir } = createRelayServer(options);
  server.listen(options.port, options.host, () => {
    console.log(`[relay] listening on http://${options.host}:${options.port}`);
    console.log(`[relay] upstream ${options.upstream}`);
    console.log(`[relay] captures ${captureDir}`);
    console.log(`[relay] Tellev base URL: http://127.0.0.1:${options.port}/tellev`);
    console.log(`[relay] SillyTavern base URL: http://127.0.0.1:${options.port}/sillytavern/v1`);
  });
}

const isEntryPoint = process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isEntryPoint) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
