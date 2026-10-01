import { readFile } from 'node:fs/promises';
import { JSDOM, VirtualConsole } from 'jsdom';

export const readAsset = name => readFile(new URL(`../../../app/src/main/assets/compat/${name}`, import.meta.url), 'utf8');

// Executes the exported production bridge. The native boundary is an in-memory
// fixture; disk merge/commit behavior is covered separately by the Kotlin tests.
export async function createHost({ chat, card = {}, failWrite = () => null, failApi = () => null }) {
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
  // In-memory stores backing the virtual API routes the host's write family
  // calls; mirrored from WorldBookApiHandler/ChatApiHandler semantics.
  chat.forEach((m, i) => { if (m.id === undefined) m.id = `fixture-msg-${i}`; });
  const worldsStore = new Map();
  for (const book of card.worldBooks ?? [{ id: 'fixture', name: 'fixture', entries: card.character_book?.entries || [], raw: {} }]) {
    worldsStore.set(book.id, JSON.parse(JSON.stringify(book)));
  }
  const disabledWorlds = new Set();
  let savedCharacter = null;
  const presetsStore = new Map(Object.entries(card.presets || { A: { temperature:0.8, openai_max_tokens:200,
    prompts:[{identifier:'main',name:'Main',content:'system',role:'system',system_prompt:true}],
    prompt_order:[{character_id:100001,order:[{identifier:'main',enabled:true}]}],
    extensions:{tavern_helper:{variables:{},scripts:[]}}, reasoning_effort:'high',custom_unknown:{keep:true} } }));
  let selectedPreset = [...presetsStore.keys()][0];
  presetsStore.set('in_use',JSON.parse(JSON.stringify(presetsStore.get(selectedPreset))));
  const presetApi = (method,path,body) => {
    const failure=failApi(method,path,body); if(failure)return {status:failure.status||500,body:{error:failure.error||'failed'}};
    const pieces=path.split('/').map(decodeURIComponent);
    if(method==='GET' && path==='/api/presets')return {status:200,body:{presets:[...presetsStore.keys()].map(id=>({id,category:'openai'})),selected:{openai:selectedPreset}}};
    if(method==='GET')return presetsStore.has(pieces[4])?{status:200,body:presetsStore.get(pieces[4])}:{status:404,body:{error:'missing preset'}};
    if(pieces[3]==='load') {
      if(!presetsStore.has(body.name))return {status:404,body:{error:'missing preset'}};
      selectedPreset=body.name;presetsStore.set('in_use',JSON.parse(JSON.stringify(presetsStore.get(body.name))));
    }else if(pieces[3]==='delete')presetsStore.delete(body.name);
    else if(pieces[3]==='rename'){presetsStore.set(body.newName,presetsStore.get(body.name));presetsStore.delete(body.name);}
    else presetsStore.set(body.name,JSON.parse(JSON.stringify(body.preset)));
    return {status:200,body:{ok:true}};
  };
  const canonicalVariables = {character:structuredClone(card.variables || {} )};
  let characterBinding = {primary:'fixture',additional:[]}, chatMetadata={};

  const native = {
    stPresetCall:(method,path,body)=>JSON.stringify(presetApi(method,path,JSON.parse(body))),
    stCompatStorage:(operation,body)=> {
      const payload=JSON.parse(body);
      if(operation==='getVariables')return JSON.stringify({variables:payload.type==='preset'
        ? presetsStore.get('in_use').extensions?.tavern_helper?.variables || {} : canonicalVariables[payload.type] || {}});
      if(operation==='replaceVariables') {
        if(payload.type==='preset')for(const name of ['in_use',selectedPreset]) {
          const raw=presetsStore.get(name);raw.extensions ||= {};raw.extensions.tavern_helper ||= {};raw.extensions.tavern_helper.variables=payload.variables;
        } else canonicalVariables[payload.type]=payload.variables;
      } else if(operation==='getCharWorldbookNames')return JSON.stringify(characterBinding);
      else if(operation==='rebindCharWorldbooks')characterBinding=payload.binding;
      else if(operation==='rebindChatWorldbook')chatMetadata.world_info=payload.name;
      else if(operation==='getOrCreateChatWorldbook') {
        const name=chatMetadata.world_info || payload.name || 'chat_fixture';
        if(!worldsStore.has(name))worldsStore.set(name,{id:name,name,entries:[],raw:{}});
        chatMetadata.world_info=name;return JSON.stringify({name});
      }else throw new Error(`Unsupported fixture storage operation ${operation}`);
      return JSON.stringify({ok:true});
    },
    stGetContext: () => JSON.stringify({ chat, chatId: 'fixture', name1: 'User', name2: card.name || 'Fixture',
      characterId: 'fixture', characterWorldBooks: [characterBinding.primary,...characterBinding.additional].filter(Boolean),
      chat_metadata:chatMetadata,
      character: { data: { extensions: { regex_scripts: card.regexScripts ?? [] } } },
      chatCompletionSettings: { extensions: { regex_scripts: card.presetRegexScripts ?? [] } },
      globalWorldBooks: [...worldsStore.values()].filter(b => !disabledWorlds.has(b.id)).map(b => b.name),
      // Same projection as ChatTavernAdapter: never expose the stored model here.
      worldBooks: [...worldsStore.values()].map(b => ({ id: b.id, name: b.name,
        entries: b.entries.map(e => ({ ...e.raw, uid: e.id, comment: e.comment,
          content: e.content, disable: !e.enabled })) })) }),
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
    executeSlashCommands: (rid, raw) => queueMicrotask(() =>
      w.Tellev.onSlashCommandsResult(rid, JSON.stringify({ results: [], pipe: String(raw ?? '') }))),
    log: (level, message) => { if (level === 'error') errors.push(message); },
    emitFromEventSource: () => {}, emit: () => {}, stReplaceVariables: s => s,
    getSettings: () => '{}', saveSettings: () => {}, registerCommand: () => {}, registerRoute: () => {},
    extensionReady: () => {}, extensionFailed: e => errors.push(e),
    apiCall: (id, method, path, bodyJson) => queueMicrotask(() => {
      const respond = (status, body) => w.Tellev.onApiResponse?.(id, status, JSON.stringify(body ?? {}));
      const body = bodyJson ? JSON.parse(bodyJson) : null;
      const segments = path.replace(/^\/api\//, '').split('/').map(decodeURIComponent);
      const chatToSession = () => ({ id: 'fixture', messages: chat.map(m => ({
        id: m.id, role: m.is_user ? 'user' : (m.is_system ? 'system' : 'assistant'),
        name: m.name ?? '', content: m.mes ?? '' })) });
      try {
        const failure = failApi(method, path, body);
        if (failure) return respond(failure.status ?? 500, { error: failure.error ?? 'write failed' });
        if (path.startsWith('/api/presets')) {const result=presetApi(method,path,body);return respond(result.status,result.body);}
        if (path === '/api/backends/chat-completions/generate')return respond(200,{text:'fixture generation'});
        if (method === 'GET' && path === '/api/worlds') return respond(200, { worlds: [...worldsStore.values()] });
        if (method === 'GET' && segments[0] === 'worlds' && segments.length === 2) {
          const book = worldsStore.get(segments[1]);
          return book ? respond(200, book) : respond(404, { error: 'not found' });
        }
        if (method === 'POST' && path === '/api/worlds') {
          worldsStore.set(body.id, body); return respond(200, { ok: true });
        }
        if (method === 'DELETE' && segments[0] === 'worlds' && segments.length === 2) {
          worldsStore.delete(segments[1]); disabledWorlds.delete(segments[1]);
          return respond(200, { ok: true });
        }
        if (method === 'POST' && path === '/api/worldinfo/disabled') {
          for (const id of disabledWorlds) disabledWorlds.delete(id);
          for (const id of body.ids) disabledWorlds.add(id);
          return respond(200, { ok: true });
        }
        if (method === 'GET' && segments[0] === 'characters' && segments.length === 3 && segments[2] === 'regex') {
          const stored = savedCharacter?.raw?.data?.extensions?.regex_scripts ?? card.regexScripts ?? [];
          return respond(200, { regex_scripts: stored });
        }
        if (method === 'GET' && path === '/api/characters') {
          return respond(200, { characters: [{ id: 'fixture', name: card.name || 'Fixture' }] });
        }
        if (method === 'GET' && segments[0] === 'characters' && segments.length === 2) {
          return respond(200, savedCharacter ?? { id: 'fixture', name: card.name || 'Fixture', raw: { data: {} } });
        }
        if (method === 'POST' && segments[0] === 'characters' && segments[2] === 'tavern-helper') {
          savedCharacter ??= { id: 'fixture', name: card.name || 'Fixture', raw: { data: { extensions: {} } } };
          Object.assign(savedCharacter.raw.data.extensions, body);
          return respond(200, { ok: true });
        }
        if (method === 'POST' && segments[0] === 'characters' && segments.length === 1) {
          savedCharacter = body; return respond(200, body);
        }
        if (method === 'GET' && segments[0] === 'chats' && segments.length === 2) return respond(200, chatToSession());
        if (method === 'POST' && segments[0] === 'chats' && segments[2] === 'messages' && segments[3] === 'insert') {
          const at = Math.max(0, Math.min(chat.length, body.before));
          chat.splice(at, 0, ...(body.messages ?? [body.message]).map(message => ({ id: message.id, name: message.name,
            mes: message.content, is_user: message.role === 'user',
            is_system: message.isHidden ?? false, extra: message.metadata || {},
            swipe_id: 0, variables: message.variables || [] })));
          return respond(200, { ok: true });
        }
        if (method === 'POST' && segments[0] === 'chats' && segments[2] === 'messages' && segments[3] === 'delete') {
          const drop = new Set(body.message_ids);
          for (let i = chat.length - 1; i >= 0; i--) if (drop.has(chat[i].id)) chat.splice(i, 1);
          return respond(200, { ok: true });
        }
        respond(200, {});
      } catch (error) { respond(500, { error: String(error) }); }
    }),
  };
  w.tellevNative = native;
  try {
    w.eval(await readAsset('globals.js'));
    w.eval(await readFile(new URL('../../../app/src/main/assets/showdown/showdown.min.js', import.meta.url), 'utf8'));
    const html = await readFile(new URL('../../../app/build/compat-host.html', import.meta.url), 'utf8');
    const legacy = html.slice(html.indexOf('__SHOWDOWN_SOURCE__'), html.indexOf('</script><script>__HOST_ADAPTER__'))
      .replace('__SHOWDOWN_SOURCE__', '').replaceAll('__EXTENSION_ID__', 'fixture')
      .replaceAll('__TOKEN__', 'token').replaceAll('__EJS_SETTINGS_JSON__', '{}').replaceAll('__TAVERN_HELPER_SETTINGS_JSON__', '{}');
    w.eval(legacy);
    w.eval(await readAsset('chat.js'));
    w.eval(await readAsset('host.js'));
    w.fetch = async () => ({ ok: true, json: async () => ({ pkgVersion: '1.18.0' }), text: async () => '' });
    return { w, chat, errors, worldsStore, disabledWorlds, native, presetsStore, canonicalVariables, get savedCharacter() { return savedCharacter; }, close: () => w.close() };
  } catch (error) { w.close(); throw error; }
}
