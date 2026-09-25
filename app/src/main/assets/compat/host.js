// Executed after the legacy bridge, before any character modules.
// Keep host adapters here so the same code can be exercised in browser replay tests.
(function () {
  const th = window.TavernHelper;
  const clone = value => JSON.parse(JSON.stringify(value));
  const context = () => window.getContext();
  const expose = (name, fn) => { th[name] = window[name] = fn; };
  const writes = new Map();
  const variableWrites = new Map();
  window.__tellevWriteDone = (id, error) => {
    const pending = writes.get(id);
    if (!pending) return;
    writes.delete(id);
    if (error) {
      const failure = new Error(error);
      // MVU rewrites the text before saving variables. If that write fails,
      // surface its cause instead of waiting for a variable commit that cannot happen.
      for (const floor of pending.messageIds) variableWrites.get(floor)?.reject(failure);
      pending.reject(failure);
    } else {
      window.__tellevInvalidateContext();
      pending.resolve();
    }
  };
  expose('getScriptId', () => document.documentElement.dataset.extensionId);
  expose('getScriptName', () => getScriptId());
  // Upstream uniqueness arbitration inspects these real runtime registrations.
  const registry = document.createElement('div');
  registry.id = 'tavern_helper';
  const registration = document.createElement('div');
  registration.dataset.scriptId = getScriptId();
  registry.append(registration);
  document.body.append(registry);
  SillyTavern.getCurrentChatId = () => context().chatId;
  SillyTavern.loadWorldInfo = async name => ({ entries: Object.fromEntries(
    (await getLorebookEntries(name)).map((entry, index) => [entry.uid ?? index, entry])) });
  const schemas = new Map();
  expose('registerVariableSchema', (schema, { type }) => { schemas.set(type, schema); });
  const oldGet = th.getVariables;
  const oldReplace = th.replaceVariables;
  const persistent = JSON.parse(tellevNative.getSettings() || '{}');
  const scopes = persistent.compatVariables ||= {};
  expose('getVariables', (option = { type: 'chat' }) => {
    if (['character','preset','script','extension'].includes(option.type)) {
      return clone(scopes[option.type + ':' + (option.script_id || option.extension_id || '')] || {});
    }
    if (option.type === 'message' && (option.message_id === undefined || option.message_id === 'latest')) {
      const chat = context().chat;
      const index = chat.findLastIndex(m => !m.is_system);
      if (index < 0) throw new Error('No non-system message exists');
      option = { ...option, message_id: index };
    }
    return clone(oldGet(option));
  });
  expose('replaceVariables', (data, option = { type: 'chat' }) => {
    if (['character','preset','script','extension'].includes(option.type)) {
      scopes[option.type + ':' + (option.script_id || option.extension_id || '')] = clone(data);
      tellevNative.saveSettings(JSON.stringify(persistent));
      return;
    }
    oldReplace(clone(data), option);
    window.__tellevInvalidateContext();
    if (option.type === 'message') {
      let id = option.message_id ?? -1;
      if (id === 'latest') id = -1;
      if (id < 0) id += context().chat.length;
      variableWrites.get(id)?.resolve();
    }
  });
  expose('updateVariablesWith', (updater, option) => {
    const result = updater(getVariables(option));
    const commit = data => { replaceVariables(data, option); return data; };
    return result?.then ? result.then(commit) : commit(result);
  });
  expose('insertOrAssignVariables', (data, option) => updateVariablesWith(old => _.mergeWith(old,data,(_lhs,rhs)=>Array.isArray(rhs)?rhs:undefined), option));
  expose('insertVariables', (data, option) => updateVariablesWith(old => _.mergeWith({},data,old,(_lhs,rhs)=>Array.isArray(rhs)?rhs:undefined), option));
  expose('deleteVariable', (path, option) => {
    const data = getVariables(option), existed = _.has(data, path);
    const removed=_.unset(data, path); replaceVariables(data, option); return {variables:data,delete_occurred:removed};
  });
  expose('getAllVariables', () => Object.assign({}, getVariables({type:'global'}),
    getVariables({type:'character'}), getVariables({type:'script'}), getVariables({type:'chat'})));
  expose('getLastMessageId', () => context().chat.length - 1);
  expose('getChatMessages', (range,options) => window.__tellevGetChatMessages(context().chat,range,options));
  expose('setChatMessages', (messages, options = {}) => new Promise((resolve, reject) => {
    const id = crypto.randomUUID();
    const length = context().chat.length;
    const messageIds = messages.map(m => m.message_id < 0 ? length + m.message_id : m.message_id);
    writes.set(id, {resolve, reject, messageIds});
    tellevNative.stSetChatMessages(id, JSON.stringify(messages), JSON.stringify(options));
  }));
  expose('setChatMessage', (fields, message_id, options) =>
    setChatMessages([{message_id, ...(typeof fields === 'string' ? {message:fields} : fields)}], options));
  expose('getLorebookSettings', async () => ({ selected_global_lorebooks: context().globalWorldBooks || [] }));
  expose('getCharLorebooks', async () => ({ primary: context().characterWorldBooks?.[0] ?? null,
    additional: context().characterWorldBooks?.slice(1) || [] }));
  expose('getCharWorldbookNames', () => ({primary: context().characterWorldBooks?.[0] ?? null,
    additional: context().characterWorldBooks?.slice(1) || []}));
  expose('getGlobalWorldbookNames', () => context().globalWorldBooks || []);

  // ── Worldbook read/write family (js-slash-runner worldbook.ts parity) ──
  // Three shapes meet here: Tellev's stored WorldBookEntry (keys/enabled/
  // insertionOrder...), the ST "lorebook entry" the old API and card scripts
  // use (uid/key/keysecondary/disable/order...), and the v4 WorldbookEntry
  // (strategy/position objects). Reads expose the ST/v4 shapes; writes
  // normalize back before POSTing the full book.
  const WI_LOGIC_NAMES = { 0: 'and_any', 1: 'not_all', 2: 'not_any', 3: 'and_all' };
  const WI_LOGIC_CODES = { and_any: 0, not_all: 1, not_any: 2, and_all: 3 };
  const WI_POSITION_TYPES = { 0: 'before_character_definition', 1: 'after_character_definition',
    2: 'before_author_note', 3: 'after_author_note', 4: 'at_depth',
    5: 'before_example_messages', 6: 'after_example_messages' };
  const WI_POSITION_CODES = Object.fromEntries(Object.entries(WI_POSITION_TYPES).map(([k, v]) => [v, Number(k)]));
  const WI_ROLES = ['system', 'user', 'assistant'];

  // ST embedded card books spell fields differently (secondary_keys,
  // insertion_order) and carry positions as strings; coerce defensively.
  const asInt = (value, fallback) => {
    const n = Number(value);
    return Number.isFinite(n) ? Math.trunc(n) : fallback;
  };
  const toLegacyEntry = e => {
    const raw = e.raw || e.extensions || {};
    return {
      uid: asInt(raw.uid ?? e.id ?? 0, 0),
      key: e.keys ?? raw.key ?? [],
      keysecondary: e.secondaryKeys ?? e.secondary_keys ?? raw.keysecondary ?? raw.secondary_keys ?? [],
      comment: e.comment ?? raw.comment ?? '',
      content: e.content ?? raw.content ?? '',
      disable: e.enabled === undefined ? Boolean(raw.disable) : !e.enabled,
      constant: Boolean(e.constant ?? raw.constant),
      selective: e.selective ?? raw.selective ?? true,
      selectiveLogic: asInt(e.selectiveLogic ?? raw.selectiveLogic, 0),
      order: asInt(e.insertionOrder ?? e.insertion_order ?? raw.insertion_order ?? e.order, 100),
      position: asInt(e.position ?? raw.position, 0),
      depth: asInt(e.depth ?? raw.depth, 4),
      role: asInt(e.role ?? raw.role, 0),
      probability: asInt(e.probability ?? raw.probability, 100),
      useProbability: e.useProbability ?? raw.useProbability ?? true,
      matchWholeWords: e.matchWholeWords ?? raw.matchWholeWords ?? false,
      caseSensitive: e.caseSensitive ?? raw.caseSensitive ?? false,
      excludeRecursion: e.excludeRecursion ?? raw.excludeRecursion ?? false,
      preventRecursion: e.preventRecursion ?? raw.preventRecursion ?? false,
      delayUntilRecursion: asInt(e.delayUntilRecursion ?? raw.delayUntilRecursion, 0),
      ignoreBudget: e.ignoreBudget ?? raw.ignoreBudget ?? false,
      group: e.group ?? raw.group ?? '',
      groupOverride: e.groupOverride ?? raw.groupOverride ?? false,
      groupWeight: asInt(e.groupWeight ?? raw.groupWeight, 100),
      useGroupScoring: e.useGroupScoring ?? raw.useGroupScoring ?? false,
    };
  };
  const legacyToV4 = l => ({
    uid: l.uid,
    name: l.comment,
    enabled: !l.disable,
    strategy: {
      type: l.constant ? 'constant' : 'selective',
      keys: l.key,
      keys_secondary: { logic: WI_LOGIC_NAMES[l.selectiveLogic] ?? 'and_any', keys: l.keysecondary },
      scan_depth: 'same_as_global',
    },
    position: {
      type: WI_POSITION_TYPES[l.position] ?? 'before_character_definition',
      role: WI_ROLES[l.role] ?? 'system',
      depth: l.depth,
      order: l.order,
    },
    content: l.content,
    probability: l.probability,
    recursion: { prevent_incoming: l.excludeRecursion, prevent_outgoing: l.preventRecursion,
      delay_until: l.delayUntilRecursion || null },
    effect: { sticky: null, cooldown: null, delay: null },
  });
  const isV4Entry = w => !!w?.strategy;
  const v4ToLegacy = v => ({
    uid: Number(v.uid ?? 0),
    key: Array.isArray(v.strategy?.keys) ? v.strategy.keys : [],
    keysecondary: Array.isArray(v.strategy?.keys_secondary?.keys) ? v.strategy.keys_secondary.keys : [],
    selectiveLogic: WI_LOGIC_CODES[v.strategy?.keys_secondary?.logic ?? 'and_any'] ?? 0,
    constant: v.strategy?.type === 'constant',
    comment: v.name ?? '',
    content: v.content ?? '',
    disable: v.enabled === undefined ? false : !v.enabled,
    position: WI_POSITION_CODES[v.position?.type] ?? 0,
    role: Math.max(0, WI_ROLES.indexOf(v.position?.role ?? 'system')),
    depth: v.position?.depth ?? 4,
    order: v.position?.order ?? 100,
    probability: v.probability ?? 100,
    excludeRecursion: !!v.recursion?.prevent_incoming,
    preventRecursion: !!v.recursion?.prevent_outgoing,
    delayUntilRecursion: v.recursion?.delay_until ?? 0,
  });
  // Upstream entries (v4 or ST-lorebook) → Tellev stored fields. Unknown
  // fields are dropped by the route's Json anyway; emitting exactly the model
  // keeps type coercion silent-but-correct.
  const toTellevEntry = (w, index) => {
    const l = isV4Entry(w) ? v4ToLegacy(w) : w;
    return {
      id: String(l.uid ?? index),
      keys: Array.isArray(l.key) ? l.key : [],
      secondaryKeys: Array.isArray(l.keysecondary) ? l.keysecondary : [],
      content: String(l.content ?? ''),
      enabled: l.disable === undefined ? true : !l.disable,
      selective: l.selective ?? true,
      constant: !!l.constant,
      insertionOrder: asInt(l.order, 100),
      depth: asInt(l.depth, 4),
      position: asInt(l.position, 0),
      role: asInt(l.role, 0),
      probability: asInt(l.probability, 100),
      useProbability: l.useProbability ?? true,
      selectiveLogic: asInt(l.selectiveLogic, 0),
      matchWholeWords: !!l.matchWholeWords,
      caseSensitive: !!l.caseSensitive,
      comment: String(l.comment ?? ''),
      excludeRecursion: !!l.excludeRecursion,
      preventRecursion: !!l.preventRecursion,
      delayUntilRecursion: asInt(l.delayUntilRecursion, 0),
      ignoreBudget: !!l.ignoreBudget,
      group: String(l.group ?? ''),
      groupOverride: !!l.groupOverride,
      groupWeight: asInt(l.groupWeight, 100),
      useGroupScoring: !!l.useGroupScoring,
      raw: l.raw ?? {},
    };
  };

  const findBook = name => context().worldBooks?.find(b => b.name === name || b.id === name);
  const bookEntries = name => {
    const book = findBook(name);
    if (!book) throw new Error(`Unknown worldbook: ${name}`);
    return book.entries || [];
  };
  const saveTellevEntries = async (name, tellevEntries) => {
    const book = findBook(name);
    if (!book) throw new Error(`Unknown worldbook: ${name}`);
    await window.Tellev.apiCall('POST', '/api/worlds', {
      id: book.id ?? name, name: book.name ?? name,
      entries: tellevEntries, raw: book.raw ?? {},
    });
    window.__tellevInvalidateContext();
  };
  const nextUids = (count, existing) => {
    const top = existing.reduce((m, e) => Math.max(m, Number(e.raw?.uid ?? e.id ?? 0)), 0);
    return Array.from({ length: count }, (_, i) => top + 1 + i);
  };

  expose('getLorebookEntries', async name => bookEntries(name).map(toLegacyEntry));
  expose('getWorldbook', async name => (await getLorebookEntries(name)).map(legacyToV4));
  expose('getWorldbookNames', async () => (context().worldBooks || []).map(b => b.name ?? b.id));

  expose('setLorebookEntries', async (name, entries) => {
    await saveTellevEntries(name, entries.map(toTellevEntry));
  });
  expose('replaceLorebookEntries', async (name, entries) => {
    await saveTellevEntries(name, entries.map(toTellevEntry));
    return entries;
  });
  expose('createLorebookEntry', async (name, entry) => {
    const existing = bookEntries(name);
    const [uid] = nextUids(1, existing);
    await saveTellevEntries(name, [...existing, toTellevEntry({ ...entry, uid }, existing.length)]);
    return uid;
  });
  expose('createLorebookEntries', async (name, entries) => {
    const existing = bookEntries(name);
    const uids = nextUids(entries.length, existing);
    const created = entries.map((entry, i) => toTellevEntry({ ...entry, uid: uids[i] }, existing.length + i));
    await saveTellevEntries(name, [...existing, ...created]);
    // Upstream returns the full worldbook after insertion plus the created ones.
    const worldbook = [...existing, ...created].map(e => legacyToV4(toLegacyEntry(e)));
    const newEntries = worldbook.slice(-created.length);
    return { worldbook, new_entries: newEntries };
  });
  expose('deleteLorebookEntries', async (name, uids) => {
    const drop = new Set(uids.map(Number));
    const existing = bookEntries(name);
    const kept = existing.filter(e => !drop.has(Number(e.raw?.uid ?? e.id ?? 0)));
    await saveTellevEntries(name, kept);
    return kept.length;
  });
  expose('deleteLorebookEntry', async (name, uid) =>
    deleteLorebookEntries(name, [uid]));
  expose('updateLorebookEntriesWith', async (name, updater) => {
    const legacy = bookEntries(name).map(toLegacyEntry);
    const updated = updater(legacy) || legacy;
    await saveTellevEntries(name, updated.map(toTellevEntry));
    return updated;
  });
  // Worldbook-level writes (v4 shape in/out, worldbook.ts:367+).
  expose('createWorldbookEntries', async (name, entries) =>
    createLorebookEntries(name, entries));
  expose('deleteWorldbookEntries', async (name, uids) => {
    const drop = new Set(uids.map(Number));
    const legacy = bookEntries(name).map(toLegacyEntry);
    const kept = legacy.filter(e => !drop.has(e.uid));
    const deleted = legacy.filter(e => drop.has(e.uid));
    await saveTellevEntries(name, kept.map(toTellevEntry));
    const remainingV4 = kept.map(legacyToV4);
    return { worldbook: remainingV4, deleted_entries: deleted.map(legacyToV4) };
  });
  expose('updateWorldbookWith', async (name, updater) => {
    const worldbook = await getWorldbook(name);
    const updated = updater(worldbook) || worldbook;
    await saveTellevEntries(name, updated.map(toTellevEntry));
    return updated;
  });
  expose('deleteWorldbook', async name => {
    const book = findBook(name);
    if (!book) return false;
    const result = await window.Tellev.apiCall('DELETE', `/api/worlds/${encodeURIComponent(book.id ?? name)}`);
    window.__tellevInvalidateContext();
    return result.status === 200 && result.body?.ok !== false;
  });
  expose('rebindGlobalWorldbooks', async names => {
    const wanted = new Set(names.map(String));
    const listing = await window.Tellev.apiCall('GET', '/api/worlds');
    const worlds = listing.body?.worlds || [];
    const disabled = worlds.filter(w => !wanted.has(String(w.name))).map(w => w.id);
    await window.Tellev.apiCall('POST', '/api/worldinfo/disabled', { ids: disabled });
    window.__tellevInvalidateContext();
  });

  // ── Chat-message create/delete (js-slash-runner chat_message.ts parity) ──
  const ROLE_TO_TELLEV = { system: 'system', user: 'user', assistant: 'assistant' };
  const sessionMessages = async chatId => {
    const r = await window.Tellev.apiCall('GET', `/api/chats/${encodeURIComponent(chatId)}`);
    return r.body?.messages || [];
  };
  const floorIdAt = (messages, index) => {
    const at = index < 0 ? messages.length + index : index;
    return messages[at]?.id;
  };
  // insert_before semantics (chat_message.ts:318): 'end' or an index into the
  // normalized floor range; negative indexes count from the end.
  expose('createChatMessages', async (messages, options = {}) => {
    const chatId = options.chat_id && options.chat_id !== 'current' ? options.chat_id : context().chatId;
    let insertBefore = options.insert_at ?? options.insert_before ?? 'end';
    const c = context();
    const tellevRole = { system: 'system', user: 'user', assistant: 'assistant' };
    const existing = await sessionMessages(chatId);
    insertBefore = insertBefore === 'end' ? existing.length
      : Math.max(-existing.length, Math.min(existing.length, Number(insertBefore)));
    const insertAt = insertBefore < 0 ? existing.length + insertBefore : insertBefore;
    const built = messages.map(m => ({
      id: crypto.randomUUID(),
      role: tellevRole[m.role] ?? 'system',
      name: m.name ?? (m.role === 'system' ? 'system' : m.role === 'user' ? c.name1 : c.name2),
      content: String(m.content ?? ''),
      createdAtMillis: Date.now(),
    }));
    for (let i = 0; i < built.length; i++) {
      await window.Tellev.apiCall('POST', `/api/chats/${encodeURIComponent(chatId)}/messages/insert`,
        { message: built[i], before: insertAt + i });
    }
    window.__tellevInvalidateContext();
  });
  expose('deleteChatMessages', async (messageIds, options = {}) => {
    const chatId = options.chat_id && options.chat_id !== 'current' ? options.chat_id : context().chatId;
    const messages = await sessionMessages(chatId);
    const normalized = [...new Set(messageIds
      .filter(id => id >= -messages.length && id < messages.length)
      .map(id => (id < 0 ? messages.length + id : id)))]
      .sort((a, b) => a - b);
    if (normalized.length === 0) return;
    const ids = normalized.map(i => floorIdAt(messages, i)).filter(Boolean);
    await window.Tellev.apiCall('POST', `/api/chats/${encodeURIComponent(chatId)}/messages/delete`, { message_ids: ids });
    window.__tellevInvalidateContext();
  });
  // Subscription objects match TavernHelper's EventOnReturn, including .stop().
  for (const [name, method] of [['eventOn','on'], ['eventOnce','once'], ['eventMakeFirst','makeFirst'], ['eventMakeLast','makeLast']]) {
    expose(name, (event, listener) => {
      const callback = eventSource[method](event, listener);
      return { stop: () => eventSource.removeListener(event, callback) };
    });
  }
  const ready = [];
  const originalReady = $.fn.ready;
  $.fn.ready = function (handler) {
    const pending = new Promise((resolve, reject) => {
      originalReady.call(this, () => Promise.resolve().then(() => handler($)).then(resolve, reject));
    });
    ready.push(pending);
    return this;
  };
  window.__tellevReady = async () => {
    // 一个抛错的 ready 处理器不得永久毒化事件总线：逐个等待并吞掉失败，后续派发照常进行。
    for (let index = 0; index < ready.length; index++) {
      try { await ready[index]; } catch (error) { console.error('[tellev] ready handler failed:', error); }
    }
  };
  window.__tellevScriptApi = script => {
    const option = o => o?.type === 'script' ? {...o,script_id:script.id} : o;
    return {
      getScriptId:()=>script.id, getScriptName:()=>script.name,
      getVariables:o=>getVariables(option(o)), replaceVariables:(v,o)=>replaceVariables(v,option(o)),
      updateVariablesWith:(f,o)=>updateVariablesWith(f,option(o)),
      insertVariables:(v,o)=>insertVariables(v,option(o)),
      insertOrAssignVariables:(v,o)=>insertOrAssignVariables(v,option(o)),
      deleteVariable:(p,o)=>deleteVariable(p,option(o)),
      getAllVariables:()=>Object.assign({}, getVariables({type:'global'}),getVariables({type:'character'}),
        getVariables({type:'script',script_id:script.id}),getVariables({type:'chat'})),
    };
  };
  // TavernHelper scripts run in distinct same-origin iframes. A module imported
  // in this parent window has no frameElement, and its parent.document points
  // at the hidden runtime itself instead of a script's shared host document.
  function scriptFrameBootstrap() {
    const frame = window.frameElement;
    const info = { id: frame.dataset.scriptId, name: frame.dataset.scriptName };
    const token = frame.dataset.loadToken;
    const host = window.parent;
    const api = host.__tellevScriptApi(info);
    // js-slash-runner's parent_jquery.js shares these globals with the host.
    // In particular, $('#tavern_helper') must query the parent registration DOM.
    window.$ = host.$;
    window.jQuery = host.jQuery;
    window._ = host._;
    window.EjsTemplate = host.EjsTemplate;
    window.YAML = host.YAML;
    window.showdown = host.showdown;
    window.z = host.z;
    window.Tellev = host.Tellev;
    window.SillyTavern = host.SillyTavern;
    window.eventSource = host.eventSource;
    window.event_types = host.event_types;
    window.tavern_events = host.tavern_events;
    window.getContext = host.getContext;
    window.fetch = host.fetch;
    window.registerVariableSchema = host.registerVariableSchema;
    window.__tellevScriptApi = host.__tellevScriptApi;
    if (!window.tellevNative) window.tellevNative = host.tellevNative;
    if (!window.toastr) window.toastr = host.toastr;
    const helper = window.TavernHelper = Object.assign(Object.create(host.TavernHelper), api);
    helper._bind = Object.assign({}, host.TavernHelper._bind, {
      _getIframeName: () => frame.id,
      _getScriptId: () => info.id,
      _getScriptName: () => info.name,
      _reloadIframe: () => window.location.reload(),
    });
    for (const key of Object.keys(host.TavernHelper)) {
      if (typeof helper[key] === 'function' && window[key] === undefined) window[key] = helper[key];
    }
    for (const [key, value] of Object.entries(helper._bind)) {
      if (typeof value === 'function') {
        const global = key.replace(/^_/, '');
        if (window[global] === undefined) window[global] = value.bind(window);
      }
    }
    Object.assign(window, api);
    Object.defineProperty(window, 'Mvu', {
      configurable: true, get: () => host.Mvu, set: value => { host.Mvu = value; },
    });
    window.addEventListener('error', e => {
      host.__tellevScriptFailed(token, e.error?.stack || e.message || 'Script error');
    });
    window.addEventListener('unhandledrejection', e => {
      host.__tellevScriptFailed(token, e.reason?.stack || e.reason || 'Unhandled promise rejection');
    });
    import(frame.dataset.moduleUrl).then(
      () => host.__tellevScriptLoaded(token),
      error => host.__tellevScriptFailed(token, error?.stack || error),
    );
  }
  let scriptLoadSerial = 0;
  const pendingScriptLoads = new Map();
  window.__tellevScriptLoaded = token => {
    const pending = pendingScriptLoads.get(token);
    if (!pending) return;
    pendingScriptLoads.delete(token);
    pending.resolve();
  };
  window.__tellevScriptFailed = (token, error) => {
    const pending = pendingScriptLoads.get(token);
    if (!pending) {
      tellevNative.log('error', String(error));
      return;
    }
    pendingScriptLoads.delete(token);
    pending.reject(new Error(`${pending.name}: ${error}`));
  };
  // Cards commonly put MVU and schema setup first; later scripts read Mvu
  // synchronously while evaluating. Respect the card's enabled-script order.
  window.__tellevLoadScripts = async scripts => {
    for (const script of scripts) {
    const token = `script_${++scriptLoadSerial}`;
    const api = window.__tellevScriptApi(script);
    const prelude = `const {${Object.keys(api).join(',')}}=window.__tellevScriptApi({id:${JSON.stringify(script.id)},name:${JSON.stringify(script.name)}});\n`;
    const url = URL.createObjectURL(new Blob([prelude, script.content], { type: 'application/javascript' }));
    // Upstream helper modules choose the active script from these registrations.
    const entry = document.createElement('div');
    entry.dataset.scriptId = script.id;
    registry.append(entry);
    const frame = document.createElement('iframe');
    frame.id = `TH-script--${script.name}--${script.id}`;
    frame.name = frame.id;
    frame.style.display = 'none';
    frame.dataset.scriptId = script.id;
    frame.dataset.scriptName = script.name;
    frame.dataset.loadToken = token;
    frame.dataset.moduleUrl = url;
    // Avoid a literal closing script tag in this file: it is inlined into the
    // extension HTML template and would terminate its enclosing script.
    const endScript = '</scr' + 'ipt>';
    frame.srcdoc = '<!doctype html><html><head><meta charset="utf-8">' +
      '<script src="https://extensions.tellev.local/compat/globals.js">' + endScript +
      '</head><body><script>try{(' + scriptFrameBootstrap.toString() + ')()}' +
      'catch(error){parent.__tellevScriptFailed(' + JSON.stringify(token) + ',error?.stack||error)}' + endScript +
      '</body></html>';
    const result = new Promise((resolve, reject) => {
      pendingScriptLoads.set(token, { resolve, reject, name: script.name });
    });
    registry.append(frame);
      try {
        await result;
        await window.__tellevReady();
      } finally { URL.revokeObjectURL(url); }
    }
  };
  window.__tellevDispatch = async (name, payload) => {
    window.__tellevInvalidateContext();
    await window.__tellevReady();
    const id = JSON.parse(payload).args?.[0];
    const chat = context().chat;
    const expectsMvuWrite = name === 'message_received' && window.Mvu &&
      chat[id]?.mes?.length >= 5 && chat.slice(0, Math.max(1,id)).some(m => m.variables?.[m.swipe_id || 0]?.stat_data);
    let timer;
    const committed = expectsMvuWrite ? new Promise((resolve,reject) => {
      variableWrites.set(id,{resolve,reject});
      timer=setTimeout(()=>reject(new Error(`MVU did not commit floor ${id} within 15 seconds`)),15000);
    }) : Promise.resolve();
    // A native failure can arrive while the event listener is still unwinding.
    committed.catch(() => {});
    try {
      await eventSource._fireNative(name, payload);
      await committed;
    } finally { clearTimeout(timer); variableWrites.delete(id); }
  };
  EjsTemplate.evalTemplate = EjsTemplate.evaltemplate = async (code, env = {}, options = {}) =>
    ejs.render(code, env, { ...options, async: true });
})();
