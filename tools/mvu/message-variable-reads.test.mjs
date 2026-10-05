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
