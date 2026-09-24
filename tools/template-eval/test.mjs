// WebView template.js harness: runs the production compat/template.js on Node
// with the real ST-Prompt-Template ejs build, asserting the same scenarios as
// PromptInjectionCompatTest.kt (which covers the JVM fallback path).
//
// Run: node tools/template-eval/test.mjs   (from the tellev repo root)
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);

// ── globals the template WebView would have (globals.js provides these) ──
globalThis.window = globalThis;
globalThis.console = console;

// Minimal lodash surface used by template.js (real globals.js bundles lodash).
globalThis._ = {
  get(obj, path, defs) {
    if (path == null) return defs;
    let cur = obj;
    for (const part of String(path).split('.')) {
      if (cur == null) { cur = undefined; break; }
      cur = cur[part];
    }
    return cur === undefined ? defs : cur;
  },
  set(obj, path, value) {
    const parts = String(path).split('.');
    let cur = obj;
    for (const part of parts.slice(0, -1)) {
      if (typeof cur[part] !== 'object' || cur[part] === null) cur[part] = {};
      cur = cur[part];
    }
    cur[parts[parts.length - 1]] = value;
    return obj;
  },
  unset(obj, path) {
    const parts = String(path).split('.');
    let cur = obj;
    for (const part of parts.slice(0, -1)) {
      if (cur == null) return true;
      cur = cur[part];
    }
    if (cur != null) delete cur[parts[parts.length - 1]];
    return true;
  },
  isPlainObject(v) {
    return v !== null && typeof v === 'object' && !Array.isArray(v);
  },
};

// Real ejs build used inside the app (bundled by globals.js).
const ejsModule = require(path.resolve(repoRoot, '../ST-Prompt-Template/src/3rdparty/ejs.js'));
globalThis.ejs = ejsModule.default ?? ejsModule;

// Load the production script (plain script: assigns window.__tellevTemplate…).
new Function(readFileSync(path.join(repoRoot, 'app/src/main/assets/compat/template.js'), 'utf8'))();

const render = async (template, extra = {}) => {
  const result = await window.__tellevTemplate({
    template,
    local: {}, global: {}, definitions: {},
    context: {
      user: '旅人', name1: '旅人', userName: '旅人',
      char: '玄泽', name2: '玄泽', charName: '玄泽', assistantName: '玄泽',
      lastMessage: 'hi', lastUserMessage: 'hi', lastCharMessage: 'greet',
      lastMessageId: 3, lastUserMessageId: 2, lastCharMessageId: 3,
      characterId: 'char-1', model: 'test-model',
      runType: 'generate', generateType: 'normal',
      charLoreBook: 'char-book', userLoreBook: null, chatLoreBook: null,
      groups: [], groupId: '',
    },
    worldCatalog: [], currentWorldBookId: null,
    ...extra,
  });
  return result.content;
};

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('injectPrompt registers and getPromptsInjected collects in order', async () => {
  // ST generate-phase default: bare calls return the placeholder; the
  // end-of-build outlet scan collects. Pass outlet=false for inline reads.
  const out = await render(
    "<% injectPrompt('cot', 'second', 200) %><% injectPrompt('cot', 'first', 100) %>" +
    "<%= getPromptsInjected('cot') %>");
  assert.equal(window.__tellevTemplateOutlet(out), 'first\nsecond');
  const inline = await render(
    "<% injectPrompt('cot2', 'second', 200) %><% injectPrompt('cot2', 'first', 100) %>" +
    "<%= getPromptsInjected('cot2', [], false) %>");
  assert.equal(inline, 'first\nsecond');
});

test('injectPrompt deduplicates identical content by uid', async () => {
  const out = await render(
    "<% injectPrompt('k', 'same') %><% injectPrompt('k', 'same') %><%= getPromptsInjected('k', [], false) %>");
  assert.equal(out, 'same');
});

test('explicit uid overrides content hash dedup', async () => {
  const out = await render(
    "<% injectPrompt('k2', 'a', 100, 0, 'u1') %><% injectPrompt('k2', 'b', 100, 0, 'u1') %>" +
    "<%= getPromptsInjected('k2', [], false) %>");
  assert.equal(out, 'b');
});

test('hasPromptsInjected reflects registry state', async () => {
  await render("<% injectPrompt('probe', 'v') %>");
  assert.equal(await render("<%= hasPromptsInjected('probe') ? 'yes' : 'no' %>"), 'yes');
  assert.equal(await render("<%= hasPromptsInjected('absent') ? 'yes' : 'no' %>"), 'no');
});

test('sticky decay across deactivate calls', async () => {
  await render("<% injectPrompt('s', 'persist', 100, 1) %>");
  assert.equal(await render("<%= getPromptsInjected('s', [], false) %>"), 'persist');
  window.__tellevTemplateDeactivate();
  assert.equal(await render("<%= getPromptsInjected('s', [], false) %>"), 'persist');
  window.__tellevTemplateDeactivate();
  assert.equal(await render("<%= getPromptsInjected('s', [], false) %>"), '');
});

test('sticky zero entry dropped by one deactivate', async () => {
  await render("<% injectPrompt('once', 'v') %>");
  window.__tellevTemplateDeactivate();
  assert.equal(await render("<%= getPromptsInjected('once', [], false) %>"), '');
});

test('outlet flag emits placeholder and __tellevTemplateOutlet resolves it', async () => {
  await render("<% injectPrompt('oc', 'chained') %>");
  const placeholder = await render("<%= getPromptsInjected('oc', [], true) %>");
  assert.equal(placeholder, '{{outletPromptsInjected:oc}}');
  assert.equal(window.__tellevTemplateOutlet('A' + placeholder + 'B'), 'AchainedB');
});

test('outlet resolution recurses until stable', async () => {
  await render("<% injectPrompt('r1', 'inner') %>");
  await render("<% injectPrompt('r2', '{{outletPromptsInjected:r1}}') %>");
  assert.equal(window.__tellevTemplateOutlet('{{outletPromptsInjected:r2}}'), 'inner');
});

test('getPromptsInjected postprocess replaces first occurrence', async () => {
  await render("<% injectPrompt('pp', 'a-b-c') %>");
  const out = await render(
    "<%= getPromptsInjected('pp', [{search: '-', replace: '+'}], false) %>");
  assert.equal(out, 'a+b-c');
});

test('postprocess accepts RegExp search', async () => {
  await render("<% injectPrompt('pp2', 'x123y') %>");
  const out = await render(
    "<%= getPromptsInjected('pp2', [{search: /\\d+/, replace: 'N'}], false) %>");
  assert.equal(out, 'xNy');
});

test('unknown key collects to empty string', async () => {
  assert.equal(await render("<%= getPromptsInjected('never', [], false) %>"), '');
});

test('regression: getvar/setvar survive the additions', async () => {
  const out = await render("<% setvar('hp', 5) %><%= getvar('hp') %>");
  assert.equal(out, '5');
});

test('regression: getwi renders worldbook entries', async () => {
  const out = await render('X<%- await getwi("entry") %>Y', {
    currentWorldBookId: 'book1',
    worldCatalog: [{ id: 'e1', bookId: 'book1', bookName: 'book1', comment: 'entry', title: '', content: 'WI' }],
  });
  assert.equal(out, 'XWIY');
});

test('constants are bare identifiers with ST values', async () => {
  assert.equal(await render('<%= userName %> <%= charName %> <%= assistantName %>'), '旅人 玄泽 玄泽');
  assert.equal(await render('<%= lastMessageId + 1 %>'), '4');
  assert.equal(await render('<%= runType %>'), 'generate');
  assert.equal(await render('<%= charLoreBook %>'), 'char-book');
});

test('SillyTavern stub survives getContext calls', async () => {
  assert.equal(await render("<%= typeof SillyTavern.getContext() === 'object' ? 'ok' : 'bad' %>"), 'ok');
});

test('faker is declared but unbundled', async () => {
  assert.equal(await render("<%= faker ?? 'x' %>"), 'x');
});

test('execute stub resolves to empty pipe', async () => {
  assert.equal(await render("<%- await execute('/echo hi') %>after"), 'after');
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    await fn();
    console.log(`  ok  ${name}`);
  } catch (error) {
    failed++;
    console.error(`FAIL  ${name}\n      ${error?.message?.split('\n')[0]}`);
  }
}
console.log(failed === 0 ? `\nAll ${tests.length} template.js harness tests passed.` : `\n${failed}/${tests.length} FAILED`);
process.exit(failed === 0 ? 0 : 1);
