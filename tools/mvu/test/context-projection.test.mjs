import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost } from './host-fixture.mjs';

test('live properties and helper reads avoid full context while explicit getContext remains complete', async () => {
  const host = await createHost({ chat: [{ mes: 'first', name: 'Alice', is_user: false, variables: [{}] }] });
  try {
    const { w, native, chat } = host;
    let fullCalls = 0;
    const full = native.stGetContext;
    native.stGetContext = () => { fullCalls++; return full(); };
    assert.equal(w.SillyTavern.characterId, 'fixture');
    assert.equal(w.SillyTavern.getCurrentChatId(), 'fixture');
    assert.equal(w.getChatMessages(0)[0].message, 'first');
    chat[0].mes = 'changed';
    // No timer advance: writes and provider state are visible immediately.
    assert.equal(w.SillyTavern.chat[0].mes, 'changed');
    assert.equal(JSON.stringify(w.getWorldbookNames()), '["fixture"]');
    assert.equal(fullCalls, 0);
    const context = w.SillyTavern.getContext();
    assert.equal(fullCalls, 1);
    assert.ok(context.character.data.extensions);
    assert.ok(Array.isArray(context.worldBooks));
    assert.equal(context.chat[0].mes, 'changed');
    assert.equal(typeof w.SillyTavern.saveChat, 'function');
    assert.equal(typeof w.SillyTavern.eventSource.on, 'function');
    const settings = w.SillyTavern.extensionSettings;
    settings.mvu_settings = { initialized: true };
    assert.equal(w.SillyTavern.extensionSettings, settings);
    assert.equal(w.SillyTavern.extensionSettings.mvu_settings.initialized, true);
    // Explicit reads stay fresh; known writes invalidate the per-task view.
    assert.equal(w.getContext().extensionSettings.mvu_settings, undefined);
    w.__tellevInvalidateContext();
    assert.equal(w.SillyTavern.extensionSettings.mvu_settings, undefined);
  } finally { host.close(); }
});

test('single worldbook reads stay durable across writes and never request the library body', async () => {
  const paths = [];
  const host = await createHost({ chat: [], failApi: (method, path) => { paths.push([method, path]); return null; } });
  try {
    const { w, worldsStore } = host;
    worldsStore.set('fixture', { id: 'fixture', name: '中文书名', entries: [
      { id: '17', content: 'before', comment: 'entry', enabled: true, keys: [], raw: { custom: { keep: true } } },
    ], raw: { unknown: 42 } });
    assert.equal((await w.getWorldbook('中文书名'))[0].content, 'before');
    await w.updateWorldbookWith('fixture', entries => entries.map(e => ({ ...e, content: 'after' })));
    assert.equal((await w.getWorldbook('中文书名'))[0].content, 'after');
    assert.equal(worldsStore.get('fixture').entries[0].raw.custom.keep, true);
    assert.equal(worldsStore.get('fixture').raw.unknown, 42);
    assert.ok(!paths.some(([method, path]) => method === 'GET' && path === '/api/worlds'));
    await assert.rejects(w.getWorldbook('missing'), /Unknown worldbook/);
  } finally { host.close(); }
});

test('legacy native bridges still support the full-context fallback', async () => {
  const host = await createHost({ chat: [] });
  try {
    delete host.native.stGetContextFields;
    assert.equal(host.w.getContext().characterId, 'fixture');
    assert.equal(host.w.SillyTavern.characterId, 'fixture');
    assert.equal(JSON.stringify(host.w.getWorldbookNames()), '["fixture"]');
  } finally { host.close(); }
});
