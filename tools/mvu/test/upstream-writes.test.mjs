import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from './host-fixture.mjs';

const setup = () => createHost({ chat: [], card: { worldBooks: [{
  id: 'book', name: 'book', raw: {}, entries: [
    { id: '0', keys: ['key'], content: 'original', comment: 'first', enabled: true,
      group: 'exclusive', groupWeight: 70, ignoreBudget: true, matchWholeWords: true,
      raw: { uid: 0, sticky: 3, custom: 'preserve' } },
    { id: '1', keys: ['second'], content: 'second', comment: 'second', enabled: true },
  ],
}] } });

test('upstream createChatMessages uses message as the text field', async () => {
  const h = await setup();
  try {
    await h.w.createChatMessages([{ role: 'assistant', message: 'expected text' }]);
    assert.equal(h.chat[0].mes, 'expected text');
  } finally { h.close(); }
});

test('setLorebookEntries patches one uid without deleting the other entries', async () => {
  const h = await setup();
  try {
    await h.w.setLorebookEntries('book', [{ uid: 0, content: 'updated' }]);
    assert.equal(h.worldsStore.get('book').entries.length, 2);
    assert.deepEqual(h.worldsStore.get('book').entries[0].keys, ['key']);
  } finally { h.close(); }
});

test('identity updateWorldbookWith preserves existing activation fields and raw data', async () => {
  const h = await setup();
  try {
    await h.w.updateWorldbookWith('book', entries => entries);
    const entry = h.worldsStore.get('book').entries[0];
    assert.deepEqual({ group: entry.group, groupWeight: entry.groupWeight,
      ignoreBudget: entry.ignoreBudget, matchWholeWords: entry.matchWholeWords, raw: entry.raw },
    { group: 'exclusive', groupWeight: 70, ignoreBudget: true, matchWholeWords: true,
      raw: { uid: 0, sticky: 3, custom: 'preserve' } });
  } finally { h.close(); }
});

test('upstream deleteWorldbookEntries accepts a predicate', async () => {
  const h = await setup();
  try {
    const result = await h.w.deleteWorldbookEntries('book', entry => entry.uid === 0);
    assert.equal(result.worldbook.length, 1);
  } finally { h.close(); }
});

test('upstream updateTavernRegexesWith accepts updater then options', async () => {
  const h = await setup();
  try {
    const result = await h.w.updateTavernRegexesWith(regexes => regexes,
      { type: 'character', name: 'current' });
    assert.equal(result.length, 0);
  } finally { h.close(); }
});

test('upstream updateWorldbookWith supports asynchronous updaters', async () => {
  const h = await setup();
  try {
    const result = await h.w.updateWorldbookWith('book', async entries => entries);
    assert.equal(result.length, 2);
  } finally { h.close(); }
});

test('native write failures must reject instead of reporting success', async () => {
  const h = await setup();
  try {
    const original = h.w.Tellev.apiCall;
    h.w.Tellev.apiCall = (method, ...args) => method === 'POST'
      ? Promise.resolve({ status: 500, body: { error: 'disk write failed' } })
      : original(method, ...args);
    await assert.rejects(h.w.createChatMessages([{ role: 'assistant', content: 'text' }]));
  } finally { h.close(); }
});

test('lorebook patches honor public field names and asynchronous return contracts', async () => {
  const h = await setup();
  try {
    const result = await h.w.setLorebookEntries('book', [{ uid: 0, enabled: false,
      keys: ['replacement'], position: 'at_depth_as_user', depth: 2, group_weight: 15,
      delay_until_recursion: true }]);
    assert.equal(result.length, 2);
    assert.equal(result[0].enabled, false);
    const stored = h.worldsStore.get('book').entries[0];
    assert.equal(stored.enabled, false);
    assert.equal(stored.position, 4);
    assert.equal(stored.role, 1);
    assert.equal(stored.groupWeight, 15);
    assert.equal(stored.delayUntilRecursion, 1);
    assert.equal(stored.keys[0], 'replacement');
    const updated = await h.w.updateLorebookEntriesWith('book', async entries => {
      entries[0].content = 'async update'; return entries;
    });
    assert.equal(updated[0].content, 'async update');
    const created = await h.w.createLorebookEntries('book', [{ comment: 'new', content: 'new text' }]);
    assert.equal(created.entries.length, 3);
    assert.equal(created.new_uids.length, 1);
    assert.equal(await h.w.deleteLorebookEntry('book', created.new_uids[0]), true);
    assert.equal(await h.w.deleteLorebookEntry('book', created.new_uids[0]), false);
    assert.equal((await h.w.deleteLorebookEntries('book', [999])).delete_occurred, false);
  } finally { h.close(); }
});

test('v4 partial creation defaults, regex keys, effect changes, collisions and empty appends', async () => {
  const h = await setup();
  try {
    const created = await h.w.createWorldbookEntries('book', [{ name: 'only name' },
      { uid: 0, name: 'collision', strategy: { type: 'selective', keys: [/hello/i] },
        effect: { sticky: 4, cooldown: 2 } }]);
    assert.equal(created.new_entries[0].strategy.type, 'constant');
    assert.equal(created.new_entries[0].position.type, 'at_depth');
    assert.equal(new Set(created.worldbook.map(e => e.uid)).size, 4);
    assert.equal(created.new_entries[1].strategy.keys[0].test('HELLO'), true);
    assert.equal(created.new_entries[1].effect.sticky, 4);
    assert.equal((await h.w.createWorldbookEntries('book', [])).new_entries.length, 0);
    await h.w.updateWorldbookWith('book', entries => {
      entries[0].effect.sticky = null; entries[0].group = 'changed'; return entries;
    });
    assert.equal(h.worldsStore.get('book').entries[0].raw.sticky, null);
    assert.equal(h.worldsStore.get('book').entries[0].group, 'changed');
    assert.equal(h.worldsStore.get('book').entries[0].raw.custom, 'preserve');
  } finally { h.close(); }
});

test('message batch preserves hidden flags, variables and narrator metadata', async () => {
  const h = await setup();
  try {
    await h.w.createChatMessages([{ role: 'system', message: 'narration', data: { hp: 5 } },
      { role: 'assistant', message: 'hidden', is_hidden: true, extra: { custom: 3 } }]);
    assert.equal(h.chat[0].extra.type, 'narrator');
    assert.equal(h.chat[0].is_system, false);
    assert.equal(h.chat[0].variables[0].hp, 5);
    assert.equal(h.chat[1].is_system, true);
    assert.equal(h.chat[1].extra.custom, 3);
  } finally { h.close(); }
});

test('all helper writes propagate API failures and leave durable state unchanged', async () => {
  const h = await setup();
  try {
    const before = JSON.stringify([...h.worldsStore]);
    const original = h.w.Tellev.apiCall;
    h.w.Tellev.apiCall = (method, ...args) => method === 'GET' ? original(method, ...args)
      : Promise.resolve({ status: 500, body: { error: 'disk write failed' } });
    const actions = [
      () => h.w.setLorebookEntries('book', [{ uid: 0, content: 'lost' }]),
      () => h.w.deleteWorldbookEntries('book', () => true),
      () => h.w.deleteWorldbook('book'),
      () => h.w.rebindGlobalWorldbooks(['book']),
      () => h.w.replaceTavernRegexes([], { type: 'character' }),
      () => h.w.createChatMessages([{ role: 'user', message: 'one' }, { role: 'user', message: 'two' }]),
    ];
    for (const action of actions) await assert.rejects(action(), /disk write failed/);
    assert.equal(JSON.stringify([...h.worldsStore]), before);
    assert.equal(h.chat.length, 0);
    h.w.Tellev.apiCall = () => Promise.resolve({ status: 403, body: { error: 'access denied' } });
    await assert.rejects(h.w.createChatMessages([{ role: 'user', message: 'x' }]), /access denied/);
  } finally { h.close(); }
});
