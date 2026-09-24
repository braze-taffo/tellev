import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { inflateSync } from 'node:zlib';

const path = process.argv[2];
const extractIndex = process.argv.indexOf('--extract-to');
const extractPath = extractIndex >= 0 ? process.argv[extractIndex + 1] : null;
if (!path) {
  console.error('Usage: node tools/mvu/card-inventory.mjs <character-card.png|character-card.json> [--extract-to <ignored-output.json>]');
  process.exit(2);
}
if (extractIndex >= 0 && !extractPath) throw Error('--extract-to requires a path');
const bytes = await readFile(path);
const sha256 = createHash('sha256').update(bytes).digest('hex');

function pngMetadata(png) {
  if (!png.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) throw Error('Invalid PNG');
  const fields = new Map();
  for (let offset = 8; offset + 12 <= png.length;) {
    const length = png.readUInt32BE(offset);
    const end = offset + 12 + length;
    if (end > png.length) throw Error('Truncated PNG chunk');
    const type = png.toString('ascii', offset + 4, offset + 8);
    const chunk = png.subarray(offset + 8, offset + 8 + length);
    const separator = chunk.indexOf(0);
    if (separator > 0 && ['tEXt', 'zTXt', 'iTXt'].includes(type)) {
      const key = chunk.toString('latin1', 0, separator);
      let value;
      if (type === 'tEXt') value = chunk.subarray(separator + 1).toString('utf8');
      if (type === 'zTXt' && chunk[separator + 1] === 0) value = inflateSync(chunk.subarray(separator + 2)).toString('utf8');
      if (type === 'iTXt') {
        const compressed = chunk[separator + 1] === 1;
        const languageEnd = chunk.indexOf(0, separator + 3);
        const translatedEnd = chunk.indexOf(0, languageEnd + 1);
        if (languageEnd > 0 && translatedEnd > 0) {
          const text = chunk.subarray(translatedEnd + 1);
          value = (compressed ? inflateSync(text) : text).toString('utf8');
        }
      }
      if (value !== undefined) fields.set(key, value);
    }
    offset = end;
    if (type === 'IEND') break;
  }
  return fields;
}

let card;
let metadataKeys = [];
if (bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) {
  const metadata = pngMetadata(bytes);
  metadataKeys = [...metadata.keys()];
  const encoded = metadata.get('ccv3') ?? metadata.get('chara');
  if (encoded) {
    const source = encoded.trim().startsWith('{') ? encoded : Buffer.from(encoded, 'base64').toString('utf8');
    card = JSON.parse(source);
  }
} else {
  card = JSON.parse(bytes.toString('utf8'));
}

const result = { sha256, metadataKeys, cardDataPresent: Boolean(card) };
if (card && extractPath) {
  await mkdir(dirname(extractPath), { recursive: true });
  await writeFile(extractPath, JSON.stringify(card), 'utf8');
  result.extractedTo = extractPath;
}
if (card) {
  const data = card.data ?? card;
  const scripts = [];
  function visit(items, enabled = true) {
    if (Array.isArray(items)) return items.forEach(item => visit(item, enabled));
    if (!items || typeof items !== 'object') return;
    if (items.type === 'folder') return visit(items.scripts ?? items.value, enabled && items.enabled !== false);
    const script = items.type === 'script' && items.value ? items.value : items;
    if (enabled && script.enabled === true && typeof script.content === 'string') scripts.push(script);
  }
  visit(data.extensions?.tavern_helper?.scripts);
  result.cardName = data.name;
  result.scripts = scripts.map(script => ({
    id: script.id, name: script.name, sourceBytes: Buffer.byteLength(script.content),
    uses: Object.fromEntries(['localStorage', 'sessionStorage', 'parent.document', 'window.parent',
      'frameElement', 'eventOn', 'getVariables'].map(key => [key, script.content.includes(key)])),
    importHosts: [...new Set([...script.content.matchAll(/https?:\/\/([\w.-]+)/g)].map(match => match[1]))],
  }));
}
console.log(JSON.stringify(result, null, 2));
if (!card) process.exitCode = 1;
