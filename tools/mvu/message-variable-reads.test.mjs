import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const script = readFileSync(new URL('../../app/src/main/assets/compat/message.js', import.meta.url), 'utf8');

test('variable helpers use compact reads and never fetch the full context', () => {
  let hp = 10;
  const calls = [];
  const page = { TavernHelper: {}, SillyTavern: {}, __tellevRequest() {}, getCurrentMessageId: () => 0,
    TellevMessage: {
      getContext() { throw new Error('full context must not be built'); },
      readVariables(raw) {
        const request = JSON.parse(raw);
        calls.push(request);
        return JSON.stringify({ ok: true, value: request.kind === 'last' ? 199 : { hp } });
      },
    },
  };
  page.window = page;
  vm.runInNewContext(script, page);
  const first = page.getVariables();
  first.hp = 999;
  assert.equal(page.getVariables().hp, 10);
  hp = 20;
  assert.equal(page.getVariables({ type: 'message', message_id: -1 }).hp, 20);
  assert.equal(page.getAllVariables().hp, 20);
  assert.equal(page.getLastMessageId(), 199);
  assert.deepEqual(calls.map(x => x.kind), ['scope', 'scope', 'scope', 'all', 'last']);
  assert.deepEqual(calls[2].options, { type: 'message', message_id: -1 });
});

test('invalid compact reads preserve a throwing JS API', () => {
  const page = { TavernHelper: {}, SillyTavern: {}, __tellevRequest() {},
    TellevMessage: { readVariables: () => JSON.stringify({ ok: false, error: 'Invalid message_id: 999' }) },
  };
  page.window = page;
  vm.runInNewContext(script, page);
  assert.throws(() => page.getVariables({ type: 'message', message_id: 999 }), /Invalid message_id/);
});

test('message and binding helpers project fields but explicit context stays complete', () => {
  let text = 'before';
  const calls = [];
  const page = { TavernHelper: {}, SillyTavern: {}, __tellevRequest() {},
    __tellevGetChatMessages: chat => chat,
    TellevMessage: {
      getContext() { calls.push('full'); return JSON.stringify({ marker: 'full' }); },
      getContextFields(raw) {
        const fields = JSON.parse(raw); calls.push(fields);
        return JSON.stringify({ chat: [{ mes: text }], characterWorldbookBindings: { primary: 'world', additional: [] },
          chat_metadata: { world_info: 'chat-world' } });
      },
    },
  };
  page.window = page;
  vm.runInNewContext(script, page);
  assert.equal(page.getChatMessages()[0].mes, 'before');
  text = 'after';
  assert.equal(page.getChatMessages()[0].mes, 'after');
  assert.equal(page.getCharWorldbookNames().primary, 'world');
  assert.equal(page.getChatWorldbookName(), 'chat-world');
  assert.deepEqual(calls, [['chat'], ['chat'], ['characterWorldbookBindings'], ['chat_metadata']]);
  assert.ok(!calls.includes('full'));
  assert.equal(page.SillyTavern.getContext().marker, 'full');
  assert.equal(calls.at(-1), 'full');
});
