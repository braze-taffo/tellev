// Message WebView input-shim harness: runs the production
// `app/src/main/assets/compat/message-host.js` inside jsdom together with the
// real 梦鲸思客 思客大调查 frontend (the preset's [🦋美化]思客大调查 regex output)
// and drives its option buttons the way a finger would.
//
// It exists because the failure it guards against is invisible to unit tests
// that only string-match the script: SillyTavern frontends fill the chat input
// by writing `#send_textarea`, and in a Tellev message WebView that element used
// to be missing, so every click-to-fill UI silently did nothing.
//
// Run: node tools/message-host-eval/test.mjs   (from the tellev repo root)
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);

const jsdomPath = path.resolve(repoRoot, 'tools/mvu/node_modules/jsdom');
if (!existsSync(jsdomPath)) throw new Error(`jsdom not installed; run \`npm i\` in tools/mvu`);
const { JSDOM } = require(jsdomPath);

const asset = name => readFileSync(path.join(repoRoot, 'app/src/main/assets/compat', name), 'utf8');
// Injected the way ChatWebViewPanel.wrapTavernHtml does it: message-host.js
// first (it installs the shims), then message.js (which refines TavernHelper).
const HEAD_SCRIPTS = [asset('message-host.js'), asset('message.js'), asset('chat.js')];

const fixturePath = path.join(repoRoot, 'tools/message-host-eval/fixtures/dream-big-discuss-rule.json');
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));

// The AI output the rule rewrites. Shape copied from the preset's own format
// instructions: one <q content="问题"> with three <a> answers per question.
const AI_MESSAGE = `<dream_body>
正文正文。
</dream_body>
<dream_big_discuss>
<q content="接下来碎纸，替沈掌书去外城查那条茶肆线索"><a>去吧，先看看外城的茶肆</a><a>先问清楚条件和干系</a><a>推回去，今日只管演武坪晨课</a></q>
<q content="「垣钮这个机缘落在谁手里最合你的意？」"><a>沉香茶肆常四眼的旧件摊上</a><a>沈掌书从藏书阁旧柜底拿一枚给他</a><a>泥淖巷安人匠人处的废品</a></q>
</dream_big_discuss>`;

function applyFrontendRule(message, rule = fixture) {
  const [, pattern, flags] = rule.findRegex.match(/^\/(.*)\/([a-z]*)$/s);
  return message.replace(new RegExp(pattern, flags), rule.replaceString);
}

/**
 * Mounts the frontend HTML in a jsdom message document with the production
 * compat scripts and a `TellevMessage` stub standing in for the Android bridge.
 * `state.input` is the app composer: the shim reads it and `setInput` writes it,
 * exactly as ChatTavernAdapter/TavernMessageBridge do (writes hop through the
 * request channel, so the stub resolves them like the real evaluateJavascript
 * callback does).
 */
async function mountMessage(html) {
  const state = { input: '', calls: [] };
  const dom = new JSDOM(
    `<!doctype html><html><head>${HEAD_SCRIPTS.map(source => `<script>${source}</script>`).join('')}</head>` +
    `<body>${html}</body></html>`,
    { runScripts: 'dangerously' },
  );
  const { window } = dom;
  window.TellevMessage = {
    getInput: () => state.input,
    getCurrentMessageId: () => 0,
    getAllVariables: () => '{}',
    getContext: () => '{}',
    request: (id, operation, payloadJson) => {
      const payload = JSON.parse(payloadJson || '{}');
      state.calls.push({ operation, payload });
      if (operation === 'setInput') state.input = payload.text;
      queueMicrotask(() => window.__tellevMessageResolve(id, true, '{"ok":true}'));
    },
  };
  await new Promise(resolve => {
    if (window.document.readyState === 'complete') return resolve();
    window.addEventListener('load', resolve, { once: true });
  });
  return { window, document: window.document, state };
}

const answerText = (element) =>
  element.querySelector('.dream-big-discuss-ui__answer-text').textContent.trim();

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('compat layer gives the message document an ST-shaped #send_textarea', async () => {
  const { document } = await mountMessage(applyFrontendRule(AI_MESSAGE));
  const shim = document.getElementById('send_textarea');

  assert.ok(shim, '#send_textarea was not installed');
  assert.equal(shim.tagName, 'TEXTAREA');
  assert.equal(document.querySelector('textarea#send_textarea'), shim);
  assert.equal(document.querySelector('textarea[name=send_textarea]'), shim);
  // The resize/layout scripts measure body flow children; a fixed-position shim
  // stays out of that measurement.
  assert.equal(shim.style.position, 'fixed');
  // focus() on the off-screen shim must not raise the IME for this WebView.
  shim.focus();
  assert.notEqual(document.activeElement, shim);
});

test('clicking an option appends its dream_answer to the existing draft', async () => {
  const { document, state } = await mountMessage(applyFrontendRule(AI_MESSAGE));
  const cards = document.querySelectorAll('.dream-big-discuss-ui__card');
  assert.equal(cards.length, 2, 'expected the two questions to render as cards');

  state.input = '我自己先写了一句';
  const first = cards[0].querySelectorAll('.dream-big-discuss-ui__answer')[0];
  first.dispatchEvent(new document.defaultView.MouseEvent('click', { bubbles: true }));

  assert.equal(
    state.input,
    '我自己先写了一句\n<dream_answer q="接下来碎纸，替沈掌书去外城查那条茶肆线索">\n' +
    '去吧，先看看外城的茶肆\n</dream_answer>',
  );
});

test('a second click accumulates instead of replacing', async () => {
  const { document, state } = await mountMessage(applyFrontendRule(AI_MESSAGE));
  const cards = document.querySelectorAll('.dream-big-discuss-ui__card');
  const options = cards[0].querySelectorAll('.dream-big-discuss-ui__answer');
  const other = cards[1].querySelectorAll('.dream-big-discuss-ui__answer');

  options[1].click();
  other[2].click();

  assert.equal(state.calls.filter(call => call.operation === 'setInput').length, 2);
  assert.equal(
    state.input,
    `<dream_answer q="接下来碎纸，替沈掌书去外城查那条茶肆线索">\n${answerText(options[1])}\n</dream_answer>\n` +
    `<dream_answer q="「垣钮这个机缘落在谁手里最合你的意？」">\n${answerText(other[2])}\n</dream_answer>`,
  );
});

test('custom answer form appends its typed text', async () => {
  const { document, state } = await mountMessage(applyFrontendRule(AI_MESSAGE));
  const form = document.querySelector('.dream-big-discuss-ui__custom-form');
  const input = form.querySelector('.dream-big-discuss-ui__input');
  const question = document.querySelector('.dream-big-discuss-ui__card .dream-big-discuss-ui__question').textContent.trim();

  input.value = '我自己补一个回答';
  form.dispatchEvent(new document.defaultView.Event('submit', { bubbles: true, cancelable: true }));

  assert.equal(state.input, `<dream_answer q="${question}">\n我自己补一个回答\n</dream_answer>`);
  assert.equal(input.value, '', 'the custom input clears after confirming');
});

test('question header button appends an empty answer tag', async () => {
  const { document, state } = await mountMessage(applyFrontendRule(AI_MESSAGE));
  const question = document.querySelector('.dream-big-discuss-ui__question');
  const label = question.textContent.trim();

  question.click();

  assert.equal(state.input, `<dream_answer q="${label}">\n\n</dream_answer>`);
});

test('jQuery idiom `$("#send_textarea").val(x).trigger("input")` reaches the composer', async () => {
  const { window, document, state } = await mountMessage('<p>plain message</p>');

  window.$('#send_textarea').val('直接写入').trigger('input').trigger('change');

  assert.equal(state.input, '直接写入');
  assert.equal(document.querySelector('#send_textarea').value, '直接写入', 'value reads back the draft');
});

test('getContext().executeSlashCommands routes to the slash bridge', async () => {
  const { window, state } = await mountMessage('<p>plain message</p>');

  window.SillyTavern.getContext().executeSlashCommands('/setinput 走 slash 通道');

  await new Promise(resolve => setTimeout(resolve, 0));
  assert.deepEqual(state.calls.at(-1), { operation: 'triggerSlash', payload: { script: '/setinput 走 slash 通道' } });
});

test('fixture still matches the sibling 梦鲸思客 preset when it is present', async () => {
  const live = path.resolve(repoRoot, '../梦鲸思客V4-0915.json');
  if (!existsSync(live)) {
    console.log(`      (skipped: ${live} not found)`);
    return;
  }
  const preset = JSON.parse(readFileSync(live, 'utf8'));
  const rule = preset.extensions.regex_scripts.find(script => script.scriptName === fixture.scriptName);
  assert.ok(rule, `${fixture.scriptName} is gone from the live preset`);
  assert.equal(rule.findRegex, fixture.findRegex, 'live findRegex moved; refresh the fixture');
  assert.equal(rule.replaceString, fixture.replaceString, 'live frontend HTML changed; refresh the fixture');
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    await fn();
    console.log(`  ok  ${name}`);
  } catch (error) {
    failed++;
    console.error(`FAIL  ${name}\n      ${error?.message?.split('\n').slice(0, 6).join(' | ')}`);
  }
}
console.log(failed === 0
  ? `\nAll ${tests.length} message-host.js harness tests passed.`
  : `\n${failed}/${tests.length} FAILED`);
process.exit(failed === 0 ? 0 : 1);
