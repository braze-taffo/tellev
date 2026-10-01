import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from './host-fixture.mjs';

const messages = () => [
  { mes: 'greeting', name: 'Alice', is_user: false, swipe_id: 0, variables: [{}] },
  { mes: 'user', name: 'Bob', is_user: true, swipe_id: 0, variables: [{}] },
  { mes: 'reply', name: 'Alice', is_user: false, swipe_id: 0, variables: [{}] },
];
const rule = (findRegex, replaceString, extra = {}) => ({ id: findRegex, scriptName: findRegex,
  findRegex, replaceString, placement: [2], markdownOnly: true, trimStrings: [], ...extra });

test('deprecated regex scope does not silently read or overwrite a character', async () => {
  const host = await createHost({ chat: messages(), card: { regexScripts: [rule('/x/g', 'y')] } });
  try {
    for (const option of [{ scope: 'global' }, { scope: 'all' }, {}, undefined, { type: 'global' }, { type: 'preset' }]) {
      await assert.rejects(host.w.getTavernRegexes(option), /does not support/);
      await assert.rejects(host.w.replaceTavernRegexes([], option), /does not support/);
    }
    assert.equal(host.savedCharacter, null);
    assert.equal((await host.w.getTavernRegexes('fixture'))[0].replaceString, 'y');
  } finally { host.close(); }
});

test('deprecated character scope filters enabled state and marks returned scope', async () => {
  const host = await createHost({ chat: messages(), card: { regexScripts: [rule('/x/g', 'y'), rule('/a/g', 'b', { disabled: true })] } });
  try {
    const enabled = await host.w.getTavernRegexes({ scope: 'character', enable_state: 'enabled' });
    assert.equal(enabled.length, 1); assert.equal(enabled[0].scope, 'character');
    assert.equal((await host.w.getTavernRegexes({ scope: 'character', enable_state: 'disabled' })).length, 1);
    await assert.rejects(host.w.getTavernRegexes({ scope: 'character', enable_state: 'invalid' }), /enable_state/);
    await assert.rejects(host.w.replaceTavernRegexes([], { scope: 'invalid' }), /Invalid regex scope/);
    await host.w.replaceTavernRegexes(enabled, { scope: 'character' });
    assert.equal(host.savedCharacter.raw.data.extensions.regex_scripts.length, 1);
  } finally { host.close(); }
});

test('display formatting returns synchronous Markdown HTML and validates upstream floor options', async () => {
  const host = await createHost({ chat: messages() });
  try {
    assert.equal(host.w.formatAsDisplayedMessage('**bold**'), '<p><strong>bold</strong></p>');
    assert.equal(typeof host.w.formatAsDisplayedMessage('text'), 'string');
    for (const message_id of ['invalid', 100, -4, 0.5, NaN]) assert.throws(() => host.w.formatAsDisplayedMessage('x', { message_id }), /message_id|Message floor/);
    assert.equal(host.w.formatAsDisplayedMessage('**bold**', { message_id: -1 }), '<p><strong>bold</strong></p>');
  } finally { host.close(); }
});

test('display formatting applies preset before character regex at the selected role and depth', async () => {
  const host = await createHost({ chat: messages(), card: { presetRegexScripts: [rule('/a/g', 'b')], regexScripts: [rule('/b/g', 'c', { maxDepth: 0 })] } });
  try {
    assert.equal(host.w.formatAsDisplayedMessage('a', { message_id: 'last_char' }), '<p>c</p>');
    assert.equal(host.w.formatAsDisplayedMessage('a', { message_id: 'last_user' }), '<p>a</p>');
    assert.equal(host.w.formatAsDisplayedMessage('a', { message_id: 0 }), '<p>b</p>');
  } finally { host.close(); }
});

test('display macros run on first bot floor and replacement fragments after captures', async () => {
  const host = await createHost({ chat: messages(), card: { regexScripts: [rule('/TOKEN/g', '{{getvar::score}}'), rule('/(\\{\\{user\\}\\})/', '$1')] } });
  try {
    host.native.stReplaceVariables = s => s.replaceAll('{{getvar::score}}', '10').replaceAll('{{user}}', 'Bob');
    assert.equal(host.w.formatAsDisplayedMessage('TOKEN'), '<p>10</p>');
    assert.equal(host.w.formatAsDisplayedMessage('{{user}}'), '<p>Bob</p>');
    assert.equal(host.w.formatAsDisplayedMessage('{{getvar::score}}', { message_id: 0 }), '<p>10</p>');
    assert.equal(host.w.formatAsDisplayedMessage('{{getvar::score}}', { message_id: 2 }), '<p>{{getvar::score}}</p>');
  } finally { host.close(); }
});

test('display fails when a selected role is missing rather than using the wrong floor', async () => {
  const host = await createHost({ chat: [messages()[0]] });
  try { assert.throws(() => host.w.formatAsDisplayedMessage('x', { message_id: 'last_user' }), /floor not found/); }
  finally { host.close(); }
  const empty = await createHost({ chat: [] });
  try { assert.throws(() => empty.w.formatAsDisplayedMessage('x'), /No chat messages/); }
  finally { empty.close(); }
});

test('display sanitizes HTML and formats speech with the locked ST Markdown options', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const html = host.w.formatAsDisplayedMessage('<img src="x" onerror="bad()"><script>bad()</script>');
    assert.equal(html.includes('onerror'), false); assert.equal(html.includes('<script'), false);
    assert.equal(host.w.formatAsDisplayedMessage('「你好」'), '<p><q>「你好」</q></p>');
    assert.equal(host.w.formatAsDisplayedMessage('`"code"`'), '<p><code>"code"</code></p>');
    const highlighted = host.w.formatAsDisplayedMessage('```js\nconst x = 1;\n```');
    assert.match(highlighted, /hljs-keyword/);
    const frontend = host.w.formatAsDisplayedMessage('```html\n<html><body>frontend</body></html>\n```');
    assert.equal(frontend.includes('hljs-keyword'), false);
  } finally { host.close(); }
});
