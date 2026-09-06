import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from './host-fixture.mjs';

const messages = () => [
  { name: 'Fixture', mes: 'Greeting', is_user: false, swipe_id: 0, variables: [{ stat_data: { hp: 100 }, schema: {} }] },
  { name: 'Fixture', mes: 'Generated reply', is_user: false, swipe_id: 0, variables: [] },
];

test('MVU rewrite failure reports the native cause without a 15 second timeout', { timeout: 10000 }, async () => {
  const host = await createHost({ chat: messages(), failWrite: () => 'Conflicting stale update at message[reply].raw.mes' });
  try {
    const { w } = host;
    w.Mvu = {};
    w.eventOn('message_received', async () => {
      await w.setChatMessages([{ message_id: -1, message: 'Generated reply <StatusPlaceHolderImpl/>' }]);
      w.replaceVariables({ stat_data: { hp: 85 } }, { type: 'message', message_id: 1 });
    });
    const dispatch = w.__tellevDispatch('message_received', JSON.stringify({ args: [1, 'normal'] }));
    const deadline = new Promise((_, reject) => w.setTimeout(() => reject(Error('Native failure was not forwarded promptly')), 1000));
    await assert.rejects(Promise.race([dispatch, deadline]),
      /Conflicting stale update at message\[reply\]\.raw\.mes/);
    assert.equal(host.chat[1].variables.length, 0);
  } finally { host.close(); }
});

test('successful MVU rewrite refreshes context and waits for the variable update', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const { w } = host;
    w.Mvu = {};
    w.eventOn('message_received', async () => {
      assert.equal(w.getChatMessages(1)[0].message, 'Generated reply');
      await w.setChatMessages([{ message_id: 1, message: 'Updated reply <StatusPlaceHolderImpl/>' }]);
      assert.equal(w.getChatMessages(1)[0].message, 'Updated reply <StatusPlaceHolderImpl/>');
      w.replaceVariables({ stat_data: { hp: 85 } }, { type: 'message', message_id: 1 });
    });
    await w.__tellevDispatch('message_received', JSON.stringify({ args: [1, 'normal'] }));
    assert.equal(host.chat[1].variables[0].stat_data.hp, 85);
    assert.equal(host.errors.length, 0, host.errors.join('\n'));
  } finally { host.close(); }
});
