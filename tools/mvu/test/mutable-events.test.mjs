import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from './host-fixture.mjs';

test('native dispatch waits for asynchronous listeners and returns the same edited request', async () => {
  const host = await createHost({ chat: [] });
  try {
    const seen = [];
    host.w.eventOn('chat_completion_prompt_ready', async data => {
      await Promise.resolve();
      data.chat[0].content += ' first';
      seen.push(data.dryRun);
    });
    host.w.eventOn('chat_completion_prompt_ready', data => {
      seen.push(data.chat[0].content);
      data.chat.push({ role: 'system', content: 'injected' });
    });
    const payload = { args: [{ chat: [{ role: 'user', content: 'input' }], dryRun: false }] };
    const result = await host.w.__tellevDispatch('chat_completion_prompt_ready', JSON.stringify(payload));
    assert.deepEqual(seen, [false, 'input first']);
    assert.deepEqual(JSON.parse(JSON.stringify(result)).args[0].chat, [
      { role: 'user', content: 'input first' }, { role: 'system', content: 'injected' },
    ]);
    assert.equal(payload.args[0].chat[0].content, 'input');
    assert.deepEqual(host.errors, []);
  } finally { host.close(); }
});
