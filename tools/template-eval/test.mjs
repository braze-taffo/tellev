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
  cloneDeep(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  },
  isEqual(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
  },
  escapeRegExp(s) {
    return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  },
};

// Real ejs build used inside the app (bundled by globals.js).
const ejsModule = require(path.resolve(repoRoot, '../ST-Prompt-Template/src/3rdparty/ejs.js'));
globalThis.ejs = ejsModule.default ?? ejsModule;

// globals.js bundles the `yaml` package as window.YAML. The harness takes a
// real yaml from whichever local install exists (tools/mvu needs `npm i` once;
// the main checkout usually has it).
const yamlCandidates = [
  path.resolve(repoRoot, 'tools/mvu/node_modules/yaml'),
  path.resolve(repoRoot, '../tellev/tools/mvu/node_modules/yaml'),
];
const yamlPath = yamlCandidates.find(p => { try { require.resolve(p); return true; } catch (_) { return false; } });
if (!yamlPath) throw new Error('yaml package not found; run `npm i yaml` in tools/mvu');
const yamlModule = require(yamlPath);
globalThis.YAML = yamlModule.default ?? yamlModule;

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

test('parseJSON repairs trailing commas and unquoted keys', async () => {
  assert.equal(await render("<%= parseJSON('{\"a\": 5,}').a %>"), '5');
  assert.equal(await render("<% const p = parseJSON('{status: \"ok\"}') %><%= p.status %>"), 'ok');
});

test('jsonPatch applies RFC 6902 ops without mutating the source', async () => {
  const out = await render(
    "<% const doc = {a: 1, list: ['x']} %>" +
    "<% const patched = jsonPatch(doc, [{op: 'replace', path: '/a', value: 2}, {op: 'add', path: '/list/-', value: 'y'}]) %>" +
    "<%= patched.a %><%= patched.list[1] %><%= doc.a %>");
  assert.equal(out, '2y1');
});

test('patchVariables writes the patched document', async () => {
  const out = await render(
    "<% setvar('stat', {hp: 10}) %>" +
    "<% patchVariables('stat', [{op: 'replace', path: '/hp', value: 88}]) %>" +
    "<%= getvar('stat').hp %>");
  assert.equal(out, '88');
});

test('getChatMessages reads request chat floors', async () => {
  const out = await render("<%= getChatMessage(0, 'user') %>|<%= getChatMessages(-1) %>", {
    chat: [
      { id: 0, is_user: false, is_system: false, name: '玄泽', mes: 'greeting' },
      { id: 1, is_user: true, is_system: false, mes: 'hello there' },
    ],
  });
  assert.equal(out, 'hello there|hello there');
});

test('matchChatMessages scans the default last-two window', async () => {
  const out = await render("<%= matchChatMessages('hello') %>|<%= matchChatMessages('greeting') %>", {
    chat: [
      { id: 0, is_user: false, is_system: false, mes: 'greeting' },
      { id: 1, is_user: true, is_system: false, mes: 'hello there' },
      { id: 2, is_user: false, is_system: false, mes: 'reply two' },
    ],
  });
  // Default window is the last 2 messages (start=-2): greeting is outside.
  assert.equal(out, 'true|false');
});

test('isolated floor self-collection survives the registry rollback', async () => {
  // A historical floor that injects AND collects within itself: ST keeps
  // registrations alive until the end-of-pass scan, so isolation must resolve
  // the floor's own placeholders before rolling the registry back.
  const out = await window.__tellevTemplate({
    template: "<% injectPrompt('mid', 'V_FROM_MID') %>[<%= getPromptsInjected('mid') %>]",
    local: {}, global: {}, definitions: {}, context: {},
    worldCatalog: [], currentWorldBookId: null, chat: [], isolated: true,
  });
  assert.equal(out.content, '[V_FROM_MID]');
  // The rollback still happened: nothing leaked into later renders.
  assert.equal(await render("<%= getPromptsInjected('mid', [], false) %>"), '');
});

// ── Part A1: character data + nested-template helpers ──────────────────────
const CHARACTER = {
  name: '玄泽', description: 'desc-text', personality: 'personality-text',
  scenario: 'scenario-text', first_mes: 'hi', mes_example: '<START>\n{{user}}: hi\n{{char}}: yo',
  creatorcomment: 'note', data: {
    system_prompt: 'sys', post_history_instructions: 'phi',
    alternate_greetings: ['g1'], creator: 'someone', depth_prompt: null,
  },
};

test('getCharacterData resolves the active character; unknown names are null', async () => {
  const out = await render(
    "[<%= JSON.stringify(getCharacterData()?.name) %>]" +
    "[<%= JSON.stringify(getCharacterData('玄泽')?.name) %>]" +
    "[<%= JSON.stringify(getCharacterData(0)?.name) %>]" +
    "[<%= JSON.stringify(getCharacterData('别人')) %>]" +
    "[<%= JSON.stringify(getCharaData(0)?.name) %>]" +
    "[<%= JSON.stringify(getCharData(0)?.name) %>]",
    { character: CHARACTER });
  assert.equal(out, '["玄泽"]["玄泽"]["玄泽"][null]["玄泽"]["玄泽"]');
  // Bare getCharData must not throw when no character is in the request.
  assert.equal(await render("<%= JSON.stringify(getCharacterData()) %>"), 'null');
});

test('getCharacterDefine maps v1 fields and keeps mes_example macros raw', async () => {
  const out = await render(
    "<% const d = getCharacterDefine('玄泽') %>" +
    "[<%= d.name %>][<%= d.description %>][<%= d.scenario %>][<%= d.first_message %>]" +
    "[<%= d.system_prompt %>][<%= d.creator %>][<%= JSON.stringify(d.alternate_greetings) %>]\n" +
    "EXAMPLE:<%= d.message_example %>",
    { character: CHARACTER });
  assert.equal(out,
    '[玄泽][desc-text][scenario-text][hi][sys][someone][["g1"]]\n' +
    'EXAMPLE:{{user}}: hi\n{{char}}: yo');
});

test('getchr renders the default define with macros substituted', async () => {
  const out = await render("<%- await getchr() %>", { character: CHARACTER });
  assert.equal(out,
    '<玄泽>\nSystem: sys\nname: 玄泽\npersonality: personality-text\n' +
    'description: desc-text\nexample:\n旅人: hi\n玄泽: yo\n</玄泽>');
});

test('getchr accepts a custom template plus data and returns empty for unknown', async () => {
  const out = await render(
    "<%- await getchr('玄泽', 'NAME=<%= chara_name %> USER={{user}} EXTRA=<%= extra %>', { extra: 'E1' }) %>" +
    "|<%= JSON.stringify(await getchr('不存在')) %>",
    { character: CHARACTER });
  assert.equal(out, 'NAME=玄泽 USER=旅人 EXTRA=E1|""');
});

test('getchar and getChara are getchr aliases', async () => {
  const out = await render("<%- await getchar() %><%- await getChara() %>", { character: CHARACTER });
  assert.equal(out, await render("<%- await getchr() %><%- await getchr() %>", { character: CHARACTER }));
});

test('preset prompt and quick reply helpers degrade to empty results', async () => {
  const out = await render(
    "[<%- await getprp('主提示') %>][<%- await getpreset('主提示') %>][<%- await getPresetPrompt('主提示') %>]" +
    "[<%- await getqr('集合', '标签') %>][<%- await getQuickReply('集合', '标签') %>]" +
    "[<%= JSON.stringify(getQuickReplyData('集合')) %>]",
    { character: CHARACTER });
  assert.equal(out, '[][][][][][null]');
});

test('evalTemplate evaluates nested templates and passes data', async () => {
  const out = await render(
    "[<%= await evalTemplate('<%= 1+1 %>') %>]" +
    "[<%= await evalTemplate(42) %>]" +
    "[<%= await evalTemplate('no markers') %>]" +
    "[<%= await evalTemplate('<%= v %>+<%= char %>', { v: 7 }) %>]",
    { character: CHARACTER });
  assert.equal(out, '[2][42][no markers][7+玄泽]');
});

test('applyVarYamlAnnotate dumps values and fills a schema document', async () => {
  const out = await render(
    "<% setvar('state', { hp: 30 }) %>" +
    "[<%= applyVarYamlAnnotate('state.hp') %>]" +
    "[<%= applyVarYamlAnnotate('state', 'hp: 0\\nmp: 0') %>]");
  assert.equal(out, '[30\n][hp: 30\nmp: 0\n]');
});

test('setVariableSchema and findVariables degrade without throwing', async () => {
  const out = await render(
    "[<%= JSON.stringify(setVariableSchema({})) %>]" +
    "[<%= JSON.stringify(findVariables('x', 2)) %>]" +
    "[<%= getUserAvatarURL() %>][<%= getCharacterAvaterURL() %>]");
  assert.equal(out, '[][{}][][]');
});

// ── Part A2: worldbook entry/activation family ─────────────────────────────
const BOOK_ENTRIES = [
  { id: 'e1', comment: '自我介绍', content: '@@activate\n正文一', bookId: 'char-book', bookName: '玄泽书',
    raw: { uid: 1, key: ['玄泽'], keysecondary: [], constant: false, disable: false, order: 50 } },
  { id: 'e2', comment: '商店', content: '正文二', bookId: 'char-book', bookName: '玄泽书',
    raw: { uid: 2, key: ['/商店|店铺/'], constant: false, disable: false, order: 60 } },
  { id: 'e3', comment: '世界设定', content: '正文三', bookId: 'world-book', bookName: '世界书',
    raw: { uid: 3, key: [], constant: true, disable: false, order: 10 } },
  { id: 'e4', comment: '魔剑封印', content: '正文四', bookId: 'world-book', bookName: '世界书',
    raw: { uid: 4, key: ['魔剑'], disable: true, order: 20 } },
];

test('getWorldInfoData shapes catalog entries with raw fields and decorators', async () => {
  const out = await render(
    "<% const es = await getWorldInfoData() %>" +
    "[<%= es.length %>][<%= es[0].uid %> <%= es[0].world %>]" +
    "[<%= JSON.stringify(es[0].decorators) %>][<%= es[0].content %>]" +
    "[<%= es[0].key.join('+') %>]",
    { worldCatalog: BOOK_ENTRIES, context: { charLoreBook: 'char-book' } });
  assert.equal(out, '[2][1 玄泽书][["@@activate"]][正文一][玄泽]');
});

test('getWorldInfoEntries resolves by book name and sorts by order', async () => {
  const out = await render(
    "<% const es = await getWorldInfoEntries('世界书') %>" +
    "[<%= es.map(e => e.uid).join(',') %>]",
    { worldCatalog: BOOK_ENTRIES });
  assert.equal(out, '[3,4]');
});

test('getWorldInfoEntry and content resolve by comment, uid and regex', async () => {
  const out = await render(
    "[<%= (await getWorldInfoEntry('商店')).uid %>]" +
    "[<%= (await getWorldInfoEntry('世界书', 4)).comment %>]" +
    "[<%= (await getWorldInfoEntry('世界书', /^世界/)).uid %>]" +
    "[<%= await getWorldInfoEntryContent('商店') %>]" +
    "[<%= JSON.stringify(await getWorldInfoEntryContent('不存在')) %>]" +
    "[<%= (await getWorldInfoComments('世界书')).join('|') %>]" +
    "[<%= JSON.stringify(await getWorldInfoEntry('不存在条目')) %>]",
    { worldCatalog: BOOK_ENTRIES, context: { charLoreBook: 'char-book' } });
  assert.equal(out, '[2][魔剑封印][3][正文二][null][世界设定|魔剑封印][null]');
});

test('getEnabledLoreBooks lists distinct enabled books', async () => {
  const out = await render(
    "<%= getEnabledLoreBooks().join('|') %>",
    { worldCatalog: BOOK_ENTRIES, context: { charLoreBook: 'char-book' } });
  // charLoreBook is the book id; the catalog entry's name wins after dedupe.
  assert.equal(out, '玄泽书|世界书');
});

test('selectActivatedEntries matches keywords, regex, constants and conditions', async () => {
  const out = await render(
    "<% const es = await getEnabledWorldInfoEntries() %>" +
    "[<%= selectActivatedEntries(es, '他走进了商店买东西').map(e => e.uid).join(',') %>]" +
    "[<%= selectActivatedEntries(es, '').map(e => e.uid).join(',') %>]" +
    "[<%= selectActivatedEntries(es, '魔剑', { disabled: false }).map(e => e.uid).join(',') %>]",
    { worldCatalog: BOOK_ENTRIES });
  // e1 carries @@activate so it is always on; e3 is constant; e2 matches via
  // its /商店|店铺/ key; e4 is disabled and filtered by the condition.
  assert.equal(out, '[3,1,2][3,1][3,1]');
});

test('selectActivatedEntries applies secondary-key logic', async () => {
  const entries = [
    { comment: 'and_any', content: 'A', raw: { uid: 1, key: ['剑'], keysecondary: ['火', '水'], selective: true, selectiveLogic: 0, disable: false } },
    { comment: 'not_any', content: 'B', raw: { uid: 2, key: ['剑'], keysecondary: ['冰'], selective: true, selectiveLogic: 2, disable: false } },
    { comment: 'not_all', content: 'C', raw: { uid: 3, key: ['剑'], keysecondary: ['火', '冰'], selective: true, selectiveLogic: 1, disable: false } },
  ];
  const out = await render(
    "<% const es = await getWorldInfoData('b') %>" +
    "[<%= selectActivatedEntries(es, '剑与火').map(e => e.comment).join(',') %>]" +
    "[<%= selectActivatedEntries(es, '剑与冰').map(e => e.comment).join(',') %>]",
    { worldCatalog: entries.map(e => ({ ...e, bookId: 'b' })) });
  // and_any's secondary keys are 火/水, so trigger 剑与冰 only leaves not_all.
  assert.equal(out, '[and_any,not_any,not_all][not_all]');
});

test('@@dont_activate suppresses activation while @@activate forces it', async () => {
  const entries = [
    { comment: 'off', content: '@@dont_activate\nX', bookId: 'b', raw: { uid: 1, key: ['剑'], disable: false } },
    { comment: 'on', content: '@@activate\nY', bookId: 'b', raw: { uid: 2, key: [], disable: false } },
  ];
  const out = await render(
    "<% const es = await getWorldInfoData('b') %>" +
    "[<%= selectActivatedEntries(es, '剑').map(e => e.comment).join(',') %>]",
    { worldCatalog: entries });
  assert.equal(out, '[on]');
});

test('activateWorldInfo registers entries readable through getActivatedWIEntries', async () => {
  const out = await render(
    "<% const e = await activewi('世界书', '世界设定') %>" +
    "[<%= e.uid %>][<%= getActivatedWIEntries().length %>]" +
    "<% deactivateActivateWorldInfo() %>[<%= getActivatedWIEntries().length %>]",
    { worldCatalog: BOOK_ENTRIES });
  assert.equal(out, '[3][1][0]');
});

test('render-time fields come from messageContext and stay unset for system', async () => {
  const out = await render(
    "[<%= message_id %>][<%= is_user %>][<%= is_system %>][<%= name %>][<%= is_last %>]",
    { messageContext: { message_id: 2, is_user: true, is_system: false, name: '旅人', is_last: false } });
  assert.equal(out, '[2][true][false][旅人][false]');
  const sys = await render(
    "[<%= message_id %>][<%= is_user %>][<%= name %>]");
  assert.equal(sys, '[][][]');
});

test('per-build deactivate hook clears the activation registry', async () => {
  await render("<% await activewi('世界书', '世界设定') %>", { worldCatalog: BOOK_ENTRIES });
  assert.equal(await render("<%= getActivatedWIEntries().length %>"), '1');
  window.__tellevTemplateDeactivate();
  assert.equal(await render("<%= getActivatedWIEntries().length %>"), '0');
});

test('activated entries keep force mutations and survive a second read', async () => {
  const out = await render(
    "<% await activateWorldInfo('玄泽书', 1, true) %>" +
    "<% const a = getActivatedWIEntries()[0] %>" +
    "[<%= a.constant %>][<%= a.group %>][<%= getActivatedWIEntries().length %>]",
    { worldCatalog: BOOK_ENTRIES });
  assert.equal(out, '[true][][1]');
});

test('activateWorldInfoByKeywords registers keyword matches', async () => {
  const out = await render(
    "<% const a = await activateWorldInfoByKeywords('商店') %>" +
    "[<%= a.map(e => e.uid).join(',') %>]" +
    "[<%= (await getWorldInfoActivatedData('char-book', '玄泽')).map(e => e.uid).join(',') %>]",
    { worldCatalog: BOOK_ENTRIES, context: { charLoreBook: 'char-book' } });
  // e3 constant, e1 @@activate, e2 keyword — e4 disabled stays out.
  assert.equal(out, '[3,1,2][1]');
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    await fn();
    console.log(`  ok  ${name}`);
  } catch (error) {
    failed++;
    console.error(`FAIL  ${name}\n      ${error?.message?.split('\n').slice(0, 4).join(' | ')}`);
  }
}
console.log(failed === 0 ? `\nAll ${tests.length} template.js harness tests passed.` : `\n${failed}/${tests.length} FAILED`);
process.exit(failed === 0 ? 0 : 1);
