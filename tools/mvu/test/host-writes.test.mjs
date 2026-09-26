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

test('getLorebookEntries returns public helper entries while loadWorldInfo returns raw ST entries', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w } = host;
    const entries = await w.getLorebookEntries('fixture');
    assert.equal(JSON.stringify(entries.map(e => [e.uid, e.comment, e.enabled, e.order])),
      '[[0,"自我介绍",true,100],[1,"商店",false,50]]');
    assert.equal(entries[1].position, 'after_character_definition');
    assert.equal((await w.SillyTavern.loadWorldInfo('fixture')).entries[1].disable, true);
    assert.equal(JSON.stringify(entries[1].key), '["/商店|店铺/"]');
    const v4 = await w.getWorldbook('fixture');
    assert.equal(String(v4[1].strategy.keys[0]), '/商店|店铺/');
    assert.equal(v4[1].strategy.keys[0].test('商店'), true);
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

test('deleteWorldbookEntries removes by predicate and deleteWorldbook removes the book', async () => {
  const host = await createHost({ chat: messages(), card: cardWithBook() });
  try {
    const { w, worldsStore } = host;
    const result = await w.deleteWorldbookEntries('fixture', entry => entry.uid === 0);
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
    assert.equal(updated[0].strategy.keys_secondary.logic, 'and_any');
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
      { role: 'system', message: '规则注入' },
      { role: 'user', message: '用户补语' },
      { role: 'assistant', message: '角色补语' },
    ], { insert_before: 1 });
    assert.equal(chat.length, 5);
    assert.equal(chat[1].mes, '规则注入');
    assert.equal(chat[1].name, 'system');
    assert.equal(chat[2].name, 'User');
    assert.equal(chat[3].mes, '角色补语');
    assert.equal(chat[3].name, 'Fixture');
    // Default is append at the end.
    await w.createChatMessages([{ role: 'system', message: '末尾' }]);
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

// ── Part B3: remaining surface gaps ─────────────────────────────────────────
test('triggerSlashWithResult aliases triggerSlash and resolves the pipe', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const { w } = host;
    assert.equal(await w.triggerSlashWithResult('/echo hi'), '/echo hi');
    assert.equal(w.triggerSlashWithResult, w.TavernHelper.triggerSlashWithResult);
  } finally { host.close(); }
});

test('regex family accepts the upstream option object and persists updates', async () => {
  const host = await createHost({ chat: messages(), card: {
    name: 'Fixture',
    regexScripts: [{ id: 'r1', scriptName: 's1', findRegex: 'a', replaceString: 'b', placement: [2] }],
  } });
  try {
    const { w } = host;
    const listed = await w.getTavernRegexes({ type: 'character', name: 'current' });
    assert.equal(listed.length, 1);
    assert.equal(listed[0].source.ai_output, true);
    assert.equal(listed[0].find_regex, 'a');
    const updated = await w.updateTavernRegexesWith(
      regexes => [...regexes, { ...regexes[0], id: 'r2', script_name: 's2', find_regex: 'c', replace_string: 'd' }],
      { type: 'character', name: 'Fixture' });
    assert.equal(updated.length, 2);
    assert.equal(host.savedCharacter?.raw?.data?.extensions?.regex_scripts?.length, 2);
    assert.equal(host.savedCharacter.raw.data.extensions.regex_scripts[1].findRegex, 'c');
    // Legacy positional order (tellev) keeps working.
    const legacy = await w.getTavernRegexes('fixture');
    assert.equal(legacy.length, 2);
  } finally { host.close(); }
});

test('formatAsDisplayedMessage runs macro substitution and audio family resolves', async () => {
  const host = await createHost({ chat: messages() });
  try {
    const { w } = host;
    assert.equal(await w.formatAsDisplayedMessage('文本'), '文本');
    await w.audioPlay('x');
    await w.audioEnable('x', true);
    const proxies = await w.TavernHelper.getProxyPresetNames();
    assert.equal(proxies.length, 0);
  } finally { host.close(); }
});
