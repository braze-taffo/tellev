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

// ── Part B1/B2: worldbook write family + chat message create/delete ────────
const cardWithBook = () => ({
  name: 'Fixture',
  character_book: { entries: [
    { id: '0', keys: ['玄泽'], content: '正文零', comment: '自我介绍', enabled: true,
      constant: false, selective: true, insertion_order: 100, position: 0 },
    { id: '1', keys: ['/商店|店铺/'], content: '正文一', comment: '商店', enabled: false,
      constant: false, selective: true, insertion_order: 50, position: 1 },
  ] },
  worldBooks: [
    { id: 'fixture', name: 'fixture', entries: [
      { id: '0', keys: ['玄泽'], content: '正文零', comment: '自我介绍', enabled: true,
        constant: false, selective: true, insertion_order: 100, position: 0 },
      { id: '1', keys: ['/商店|店铺/'], content: '正文一', comment: '商店', enabled: false,
        constant: false, selective: true, insertion_order: 50, position: 1 },
    ], raw: {} },
    { id: 'world', name: '世界书', entries: [
      { id: '7', keys: [], content: '常量正文', comment: '世界设定', enabled: true, constant: true },
    ], raw: {} },
  ],
});

test('getLorebookEntries returns ST-shaped entries with numeric uids', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w } = host;
    const entries = await w.getLorebookEntries('fixture');
    assert.equal(JSON.stringify(entries.map(e => [e.uid, e.comment, e.disable, e.order])),
      '[[0,"自我介绍",false,100],[1,"商店",true,50]]');
    assert.equal(JSON.stringify(entries[1].key), '["/商店|店铺/"]');
    const v4 = await w.getWorldbook('fixture');
    assert.equal(v4[1].strategy.keys[0], '/商店|店铺/');
    assert.equal(v4[1].strategy.keys_secondary.logic, 'and_any');
    assert.equal(v4[1].position.type, 'after_character_definition');
    assert.equal(v4[1].enabled, false);
  } finally { host.close(); }
});

test('createWorldbookEntries appends with fresh uids and reports the worldbook', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w, worldsStore } = host;
    const result = await w.createWorldbookEntries('fixture', [
      { name: '新条目', content: '新正文', strategy: { type: 'selective', keys: ['新钥匙'],
        keys_secondary: { logic: 'not_any', keys: ['禁词'] } } },
    ]);
    assert.equal(result.new_entries[0].uid, 2);
    assert.equal(result.worldbook.length, 3);
    assert.equal(result.new_entries[0].strategy.keys_secondary.logic, 'not_any');
    const stored = worldsStore.get('fixture').entries.at(-1);
    assert.equal(stored.keys[0], '新钥匙');
    assert.equal(stored.secondaryKeys[0], '禁词');
    assert.equal(stored.enabled, true);
    assert.equal(stored.selectiveLogic, 2);
    // Re-read shows the created entry with the ST shape.
    const reread = await w.getLorebookEntries('fixture');
    assert.equal(reread.length, 3);
    assert.equal(reread[2].uid, 2);
  } finally { host.close(); }
});

test('deleteWorldbookEntries removes by uid and deleteWorldbook removes the book', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w, worldsStore } = host;
    const result = await w.deleteWorldbookEntries('fixture', [0]);
    assert.equal(JSON.stringify(result.worldbook.map(e => e.uid)), '[1]');
    assert.equal(JSON.stringify(result.deleted_entries.map(e => e.uid)), '[0]');
    assert.equal(worldsStore.get('fixture').entries.length, 1);
    assert.equal(await w.deleteWorldbook('世界书'), true);
    assert.equal(worldsStore.has('world'), false);
    assert.equal(await w.deleteWorldbook('不存在'), false);
  } finally { host.close(); }
});

test('updateWorldbookWith mutates the v4 worldbook and persists', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w, worldsStore } = host;
    const updated = await w.updateWorldbookWith('fixture', wb => {
      wb[0].name = '改名';
      wb[0].strategy.type = 'constant';
      return wb;
    });
    assert.equal(updated[0].name, '改名');
    assert.equal(updated[0].comment, undefined);
    assert.equal(updated[0].strategy.type, 'constant');
    const stored = worldsStore.get('fixture').entries[0];
    assert.equal(stored.comment, '改名');
    assert.equal(stored.constant, true);
    assert.equal(stored.selectiveLogic, 0);
  } finally { host.close(); }
});

test('rebindGlobalWorldbooks writes the disabled set', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w, disabledWorlds } = host;
    await w.rebindGlobalWorldbooks(['fixture']);
    assert.deepEqual([...disabledWorlds].sort(), ['world']);
    await w.rebindGlobalWorldbooks(['fixture', '世界书']);
    assert.deepEqual([...disabledWorlds].sort(), []);
  } finally { host.close(); }
});

test('createChatMessages inserts floors with role-derived names', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const { w, chat } = host;
    await w.createChatMessages([
      { role: 'system', content: '规则注入' },
      { role: 'user', content: '用户补语' },
      { role: 'assistant', content: '角色补语' },
    ], { insert_before: 1 });
    assert.equal(chat.length, 5);
    assert.equal(chat[1].mes, '规则注入');
    assert.equal(chat[1].name, 'system');
    assert.equal(chat[2].name, 'User');
    assert.equal(chat[3].mes, '角色补语');
    assert.equal(chat[3].name, 'Fixture');
    // Default is append at the end.
    await w.createChatMessages([{ role: 'system', content: '末尾' }]);
    assert.equal(chat.at(-1).mes, '末尾');
  } finally { host.close(); }
});

test('deleteChatMessages removes floors by normalized index', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const { w, chat } = host;
    await w.deleteChatMessages([0]);
    assert.equal(chat.length, 1);
    assert.equal(chat[0].mes, 'Generated reply');
    // Negative indexes count from the end; duplicates and out-of-range are fine.
    await w.deleteChatMessages([-1, -1, 99]);
    assert.equal(chat.length, 0);
  } finally { host.close(); }
});
