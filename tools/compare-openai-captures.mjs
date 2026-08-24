#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

function parseArgs(argv) {
  const options = {
    dir: 'captures/openai-relay',
    left: 'tellev',
    right: 'sillytavern',
    output: 'captures/openai-relay/latest-comparison.md',
  };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (key === '--dir') options.dir = value;
    else if (key === '--left') options.left = value;
    else if (key === '--right') options.right = value;
    else if (key === '--output') options.output = value;
  }
  return options;
}

function contentText(content) {
  return typeof content === 'string' ? content : JSON.stringify(content ?? '');
}

function shortHash(text) {
  return createHash('sha256').update(text).digest('hex').slice(0, 12);
}

function summarize(capture) {
  const body = capture.request || {};
  const messages = Array.isArray(body.messages) ? body.messages : [];
  return {
    id: capture.meta.id,
    model: body.model ?? '',
    messages: messages.map((message, index) => {
      const content = contentText(message?.content);
      return {
        index: index + 1,
        role: message?.role || 'unknown',
        name: message?.name || '',
        characters: content.length,
        hash: shortHash(content),
        preview: content.replace(/\s+/g, ' ').slice(0, 100),
      };
    }),
  };
}

async function latestCapture(dir, client) {
  const names = (await readdir(dir))
    .filter((name) => name.endsWith(`-${client}.request.json`))
    .sort();
  if (!names.length) throw new Error(`No captures found for client '${client}' in ${dir}`);
  return JSON.parse(await readFile(resolve(dir, names.at(-1)), 'utf8'));
}

function escapeCell(value) {
  return String(value).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

function renderComparison(leftName, left, rightName, right) {
  const maxRows = Math.max(left.messages.length, right.messages.length);
  const rows = [];
  for (let index = 0; index < maxRows; index += 1) {
    const a = left.messages[index];
    const b = right.messages[index];
    rows.push(
      `| ${index + 1} | ${escapeCell(a?.role || '—')} | ${a?.characters ?? '—'} | ${a?.hash || '—'} |` +
      ` ${escapeCell(b?.role || '—')} | ${b?.characters ?? '—'} | ${b?.hash || '—'} |`,
    );
  }
  const leftChars = left.messages.reduce((sum, item) => sum + item.characters, 0);
  const rightChars = right.messages.reduce((sum, item) => sum + item.characters, 0);
  return `# OpenAI request comparison

| | ${leftName} | ${rightName} |
|---|---:|---:|
| Capture | \`${left.id}\` | \`${right.id}\` |
| Model | \`${left.model}\` | \`${right.model}\` |
| Messages | ${left.messages.length} | ${right.messages.length} |
| Total characters | ${leftChars} | ${rightChars} |

## Message structure

| # | ${leftName} role | chars | SHA-256 | ${rightName} role | chars | SHA-256 |
|---:|---|---:|---|---|---:|---|
${rows.join('\n')}

## ${leftName} previews

${left.messages.map((item) => `- #${item.index} **${item.role}** (${item.characters}): ${item.preview}`).join('\n')}

## ${rightName} previews

${right.messages.map((item) => `- #${item.index} **${item.role}** (${item.characters}): ${item.preview}`).join('\n')}
`;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const dir = resolve(options.dir);
  const left = summarize(await latestCapture(dir, options.left));
  const right = summarize(await latestCapture(dir, options.right));
  const report = renderComparison(options.left, left, options.right, right);
  const output = resolve(options.output);
  await writeFile(output, report, 'utf8');
  console.log(report);
  console.log(`\nWrote ${output}`);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
