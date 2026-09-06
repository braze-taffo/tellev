import { readFile } from 'node:fs/promises';
import { JSDOM, VirtualConsole } from 'jsdom';

export const readAsset = name => readFile(new URL(`../../../app/src/main/assets/compat/${name}`, import.meta.url), 'utf8');

// Executes the exported production bridge. The native boundary is an in-memory
// fixture; disk merge/commit behavior is covered separately by the Kotlin tests.
export async function createHost({ chat, card = {}, failWrite = () => null }) {
  const errors = [], scopes = { local: {}, global: {} };
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => errors.push(e.message));
  vc.on('error', (...args) => errors.push(args.join(' ')));
  const dom = new JSDOM('<html data-extension-id="fixture"><body></body></html>', {
    url: 'https://extensions.tellev.local/fixture/', runScripts: 'dangerously',
    pretendToBeVisual: true, virtualConsole: vc,
  });
  const w = dom.window;
  w.fetch = async () => ({ ok: true, json: async () => ({ pkgVersion: '1.18.0' }), text: async () => '' });
  w.tellevNative = {
    stGetContext: () => JSON.stringify({ chat, chatId: 'fixture', name1: 'User', name2: card.name || 'Fixture',
      characterWorldBooks: ['fixture'], globalWorldBooks: [],
      worldBooks: [{ name: 'fixture', entries: card.character_book?.entries || [] }] }),
    stGetVariablesForScope: s => JSON.stringify(scopes[s] || {}),
    stSetVariablesForScope: (s, v) => { scopes[s] = JSON.parse(v); },
    stGetAllVariables: () => JSON.stringify({ ...scopes.global, ...scopes.local }),
    stGetMessageVariables: i => JSON.stringify(chat.at(i)?.variables[chat.at(i).swipe_id || 0] || {}),
    stSetMessageVariables: (i, v) => { chat.at(i).variables[chat.at(i).swipe_id || 0] = JSON.parse(v); },
    stSetChatMessages: (id, data) => {
      const updates = JSON.parse(data), failure = failWrite(updates);
      if (!failure) for (const update of updates) {
        const dest = chat.at(update.message_id);
        if (!dest) continue;
        if (update.message !== undefined) dest.mes = update.message;
        if (update.swipes_data) dest.variables = update.swipes_data;
        if (update.data) dest.variables[dest.swipe_id || 0] = update.data;
      }
      queueMicrotask(() => w.__tellevWriteDone(id, failure));
    },
    log: (level, message) => { if (level === 'error') errors.push(message); },
    emitFromEventSource: () => {}, emit: () => {}, stReplaceVariables: s => s,
    getSettings: () => '{}', saveSettings: () => {}, registerCommand: () => {}, registerRoute: () => {},
    extensionReady: () => {}, extensionFailed: e => errors.push(e),
    apiCall: id => queueMicrotask(() => w.Tellev.onApiResult?.(id, 200, '{}')),
  };
  try {
    w.eval(await readAsset('globals.js'));
    const html = await readFile(new URL('../../../app/build/compat-host.html', import.meta.url), 'utf8');
    const legacy = html.slice(html.indexOf('__SHOWDOWN_SOURCE__'), html.indexOf('</script><script>__HOST_ADAPTER__'))
      .replace('__SHOWDOWN_SOURCE__', '').replaceAll('__EXTENSION_ID__', 'fixture')
      .replaceAll('__TOKEN__', 'token').replaceAll('__EJS_SETTINGS_JSON__', '{}').replaceAll('__TAVERN_HELPER_SETTINGS_JSON__', '{}');
    w.eval(legacy);
    w.eval(await readAsset('chat.js'));
    w.eval(await readAsset('host.js'));
    w.fetch = async () => ({ ok: true, json: async () => ({ pkgVersion: '1.18.0' }), text: async () => '' });
    return { w, chat, errors, close: () => w.close() };
  } catch (error) { w.close(); throw error; }
}
