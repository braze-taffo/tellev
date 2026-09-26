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

  // apiCall resolves HTTP errors too. Never acknowledge a failed native write.
  const api = async (method, path, body) => {
    const result = await window.Tellev.apiCall(method, path, body);
    if (result.status < 200 || result.status >= 300 || result.body?.ok === false) {
      const error = new Error(result.body?.error || `${method} ${path} failed (${result.status})`);
      error.status = result.status;
      throw error;
    }
    return result.body;
  };

  // Keep the three contracts separate: stored WorldBookEntry, raw ST entry,
  // and the public LorebookEntry / WorldbookEntry APIs (pinned helper 4.8.11).
  const WI_LOGIC_NAMES = ['and_any', 'not_all', 'not_any', 'and_all'];
  const WI_POSITIONS = ['before_character_definition', 'after_character_definition',
    'before_author_note', 'after_author_note', 'at_depth',
    'before_example_messages', 'after_example_messages', 'outlet'];
  const WI_ROLES = ['system', 'user', 'assistant'];
  const WI_FIELDS = {
    key: 'keys', keysecondary: 'secondaryKeys', comment: 'comment', content: 'content',
    constant: 'constant', selective: 'selective', order: 'insertionOrder', depth: 'depth',
    position: 'position', role: 'role', probability: 'probability', useProbability: 'useProbability',
    selectiveLogic: 'selectiveLogic', matchWholeWords: 'matchWholeWords', caseSensitive: 'caseSensitive',
    excludeRecursion: 'excludeRecursion', preventRecursion: 'preventRecursion',
    delayUntilRecursion: 'delayUntilRecursion', ignoreBudget: 'ignoreBudget', group: 'group',
    groupOverride: 'groupOverride', groupWeight: 'groupWeight', useGroupScoring: 'useGroupScoring',
  };
  const WI_DEFAULTS = { key: [], keysecondary: [], comment: '', content: '', disable: false,
    constant: false, selective: true, vectorized: false, order: 100, depth: 4, position: 0, role: 0,
    probability: 100, useProbability: true, selectiveLogic: 0, matchWholeWords: false,
    caseSensitive: false, excludeRecursion: false, preventRecursion: false, delayUntilRecursion: 0,
    ignoreBudget: false, group: '', groupOverride: false, groupWeight: 100, useGroupScoring: false,
    scanDepth: null, sticky: null, cooldown: null, delay: null, automationId: '' };
  const toRawEntry = (entry, index = 0) => {
    const raw = { ...clone(WI_DEFAULTS), ...clone(entry.raw || entry.extensions || {}) };
    for (const [st, stored] of Object.entries(WI_FIELDS)) {
      if (entry[stored] !== undefined) raw[st] = clone(entry[stored]);
      else if (entry[st] !== undefined) raw[st] = clone(entry[st]);
    }
    raw.uid = Number(entry.raw?.uid ?? entry.uid ?? entry.id ?? index);
    if (!Number.isInteger(raw.uid) || raw.uid < 0) raw.uid = index;
    raw.disable = entry.enabled === undefined ? (entry.disable ?? raw.disable) : !entry.enabled;
    raw.keysecondary = entry.secondaryKeys ?? entry.secondary_keys ?? raw.keysecondary;
    raw.order = entry.insertionOrder ?? entry.insertion_order ?? raw.order;
    if (typeof raw.position === 'string') {
      raw.position = raw.position === 'after_char' ? 1 : raw.position === 'before_char' ? 0
        : Math.max(0, WI_POSITIONS.indexOf(raw.position));
    }
    raw.displayIndex ??= index;
    return raw;
  };
  const toStoredEntry = (raw, previous, index) => {
    const before = previous ? toRawEntry(previous, index) : {};
    const stored = previous ? clone(previous) : { id: String(raw.uid), raw: {} };
    stored.raw ||= {};
    // Preserve fields outside the helper contract, including Tellev-only
    // priority/useRegex and unknown raw extensions. Identity updates are lossless.
    for (const [key, value] of Object.entries(raw)) {
      if (_.isEqual(value, before[key])) continue;
      stored.raw[key] = clone(value);
      if (key === 'disable') stored.enabled = !value;
      else if (WI_FIELDS[key]) stored[WI_FIELDS[key]] = value == null
        ? WI_DEFAULTS[key] : clone(value);
    }
    return stored;
  };
  const parseKey = key => {
    const match = String(key).match(/^\/(.*)\/([a-z]*)$/s);
    if (match) { try { return new RegExp(match[1], match[2]); } catch (_) {} }
    return key;
  };
  const toWorldEntry = raw => ({
    ...Object.fromEntries(Object.entries(raw).filter(([key]) => ![
      'uid', 'comment', 'disable', 'constant', 'selective', 'vectorized', 'key', 'keysecondary',
      'selectiveLogic', 'scanDepth', 'position', 'role', 'depth', 'order', 'content',
      'excludeRecursion', 'preventRecursion', 'delayUntilRecursion', 'sticky', 'cooldown', 'delay',
    ].includes(key))),
    uid: raw.uid, name: raw.comment, enabled: !raw.disable,
    strategy: { type: raw.constant ? 'constant' : raw.vectorized ? 'vectorized' : 'selective',
      keys: raw.key.map(parseKey), keys_secondary: { logic: WI_LOGIC_NAMES[raw.selectiveLogic],
        keys: raw.keysecondary.map(parseKey) }, scan_depth: raw.scanDepth ?? 'same_as_global' },
    position: { type: WI_POSITIONS[raw.position], role: WI_ROLES[raw.role ?? 0],
      depth: raw.depth, order: raw.order }, content: raw.content,
    probability: raw.useProbability ? raw.probability : 100,
    recursion: { prevent_incoming: raw.excludeRecursion, prevent_outgoing: raw.preventRecursion,
      delay_until: raw.delayUntilRecursion || null },
    effect: { sticky: raw.sticky || null, cooldown: raw.cooldown || null, delay: raw.delay || null },
  });
  const loreFields = { display_index: 'displayIndex', comment: 'comment', content: 'content',
    order: 'order', probability: 'probability', group: 'group',
    group_prioritized: 'groupOverride', group_weight: 'groupWeight',
    exclude_recursion: 'excludeRecursion', prevent_recursion: 'preventRecursion',
    delay_until_recursion: 'delayUntilRecursion', sticky: 'sticky', cooldown: 'cooldown', delay: 'delay' };
  const globalFields = { scan_depth: 'scanDepth', case_sensitive: 'caseSensitive',
    match_whole_words: 'matchWholeWords', use_group_scoring: 'useGroupScoring' };
  const toLoreEntry = raw => ({
    uid: raw.uid, ...Object.fromEntries(Object.entries(loreFields).map(([k, v]) => [k, raw[v]])),
    enabled: !raw.disable, type: raw.constant ? 'constant' : raw.vectorized ? 'vectorized' : 'selective',
    position: raw.position === 4 ? `at_depth_as_${WI_ROLES[raw.role ?? 0]}` : WI_POSITIONS[raw.position],
    depth: raw.position === 4 ? raw.depth : null,
    key: clone(raw.key), keys: clone(raw.key), filter: clone(raw.keysecondary), filters: clone(raw.keysecondary),
    logic: WI_LOGIC_NAMES[raw.selectiveLogic], automation_id: raw.automationId || null,
    ...Object.fromEntries(Object.entries(globalFields).map(([k, v]) => [k, raw[v] ?? 'same_as_global'])),
  });
  const fromPublicEntry = (entry, previous, index, world) => {
    const before = previous ? toRawEntry(previous, index) : null;
    const oldPublic = before ? (world ? toWorldEntry(before) : toLoreEntry(before)) : {};
    const raw = before ? clone(before) : { ...clone(WI_DEFAULTS), uid: entry.uid, displayIndex: index };
    // Apply only changed public fields. This also preserves values whose public
    // representation is deliberately lossy (disabled probability, null depth).
    const set = (path, key, transform = v => v) => {
      const value = _.get(entry, path);
      if (value !== undefined && !_.isEqual(value, _.get(oldPublic, path))) raw[key] = transform(value);
    };
    raw.uid = entry.uid;
    if (world) {
      if (!before) { raw.constant = true; raw.selective = false; raw.position = 4; }
      set('name', 'comment'); set('enabled', 'disable', v => !v); set('content', 'content');
      set('strategy.type', 'constant', v => v === 'constant');
      set('strategy.type', 'vectorized', v => v === 'vectorized');
      set('strategy.type', 'selective', v => v === 'selective');
      set('strategy.keys', 'key', v => v.map(String));
      set('strategy.keys_secondary.keys', 'keysecondary', v => v.map(String));
      set('strategy.keys_secondary.logic', 'selectiveLogic', v => WI_LOGIC_NAMES.indexOf(v));
      set('strategy.scan_depth', 'scanDepth', v => v === 'same_as_global' ? null : v);
      set('position.type', 'position', v => WI_POSITIONS.indexOf(v));
      set('position.role', 'role', v => WI_ROLES.indexOf(v));
      set('position.depth', 'depth'); set('position.order', 'order');
      set('probability', 'probability');
      if (entry.probability !== undefined && entry.probability !== oldPublic.probability) raw.useProbability = true;
      set('recursion.prevent_incoming', 'excludeRecursion');
      set('recursion.prevent_outgoing', 'preventRecursion');
      set('recursion.delay_until', 'delayUntilRecursion', v => v ?? 0);
      for (const k of ['sticky', 'cooldown', 'delay']) set(`effect.${k}`, k);
      // Upstream passes implicit fields (group, case sensitivity, extra...) at
      // top level. Unknown fields are retained in raw, never discarded.
      for (const k of Object.keys(entry)) {
        if (!['uid','name','enabled','strategy','position','content','probability','recursion','effect'].includes(k)) set(k, k);
      }
    } else {
      for (const [k, v] of Object.entries(loreFields)) set(k, v);
      if (typeof raw.delayUntilRecursion === 'boolean') raw.delayUntilRecursion = Number(raw.delayUntilRecursion);
      for (const [k, v] of Object.entries(globalFields)) set(k, v, x => x === 'same_as_global' ? null : x);
      set('enabled', 'disable', v => !v);
      set('type', 'constant', v => v === 'constant'); set('type', 'vectorized', v => v === 'vectorized');
      set('position', 'position', v => v.startsWith('at_depth_as_') ? 4 : WI_POSITIONS.indexOf(v));
      if (entry.position?.startsWith('at_depth_as_')) set('position', 'role', v => WI_ROLES.indexOf(v.slice(12)));
      set('depth', 'depth', v => v ?? 4);
      set('key', 'key'); set('keys', 'key'); set('filter', 'keysecondary'); set('filters', 'keysecondary');
      set('logic', 'selectiveLogic', v => WI_LOGIC_NAMES.indexOf(v));
      set('automation_id', 'automationId', v => v ?? '');
    }
    return toStoredEntry(raw, previous, index);
  };

  const loadBook = async name => {
    // Context entries are ST projections, not stored WorldBookEntry objects;
    // load durable data so back-to-back calls cannot overwrite a stale snapshot.
    const listing = await api('GET', '/api/worlds');
    const book = listing.worlds?.find(b => b.name === name || b.id === name);
    if (!book) throw new Error(`Unknown worldbook: ${name}`);
    return book;
  };
  const saveBook = async book => {
    await api('POST', '/api/worlds', book);
    window.__tellevInvalidateContext();
  };
  const entriesAs = (book, world) => book.entries.map((e, i) => (world ? toWorldEntry : toLoreEntry)(toRawEntry(e, i)));
  const replaceEntries = async (book, entries, world) => {
    if (!Array.isArray(entries)) throw new TypeError('Worldbook updater must return an array');
    const previous = new Map(book.entries.map((e, i) => [toRawEntry(e, i).uid, e]));
    const used = new Set();
    const normalized = entries.map((entry, i) => {
      let uid = entry.uid;
      if (uid !== undefined && (!Number.isInteger(uid) || uid < 0)) throw new TypeError('Invalid entry uid');
      if (uid === undefined || used.has(uid)) {
        uid = 0;
        while (used.has(uid) || previous.has(uid) || entries.some(e => e.uid === uid)) uid++;
      }
      used.add(uid);
      return fromPublicEntry({ ...entry, uid }, previous.get(uid), i, world);
    });
    await saveBook({ ...book, entries: normalized });
    return entriesAs({ ...book, entries: normalized }, world);
  };
  expose('getLorebookEntries', async (name, { filter = 'none' } = {}) => {
    const entries = entriesAs(await loadBook(name), false);
    return filter === 'none' ? entries : entries.filter(entry => Object.entries(filter).every(([key, value]) =>
      Array.isArray(entry[key]) ? value.every(v => entry[key].includes(v))
        : typeof entry[key] === 'string' ? entry[key].includes(value) : entry[key] === value));
  });
  expose('getWorldbook', async name => entriesAs(await loadBook(name), true));
  expose('getWorldbookNames', () => [...new Set((context().worldBooks || []).map(b => b.name ?? b.id))]);
  SillyTavern.loadWorldInfo = async name => ({ entries: Object.fromEntries(
    (await loadBook(name)).entries.map((e, i) => { const raw = toRawEntry(e, i); return [raw.uid, raw]; })) });
  expose('setLorebookEntries', async (name, patches) => {
    const book = await loadBook(name);
    const entries = entriesAs(book, false);
    for (const patch of patches) {
      const entry = entries.find(e => e.uid === patch.uid);
      if (entry) _.merge(entry, patch);
    }
    return replaceEntries(book, entries, false);
  });
  expose('replaceLorebookEntries', async (name, entries) => { await replaceEntries(await loadBook(name), entries, false); });
  expose('replaceWorldbook', async (name, entries) => { await replaceEntries(await loadBook(name), entries, true); });
  expose('updateLorebookEntriesWith', async (name, updater) => {
    const book = await loadBook(name);
    return replaceEntries(book, await updater(entriesAs(book, false)), false);
  });
  expose('updateWorldbookWith', async (name, updater) => {
    const book = await loadBook(name);
    return replaceEntries(book, await updater(entriesAs(book, true)), true);
  });
  const createEntries = async (name, entries, world) => {
    const book = await loadBook(name);
    const old = entriesAs(book, world), used = new Set(old.map(e => e.uid));
    const added = entries.map(e => {
      let uid = world ? e.uid : undefined;
      if (uid === undefined || used.has(uid)) { uid = 0; while (used.has(uid)) uid++; }
      used.add(uid);
      return { ...e, uid };
    });
    const all = await replaceEntries(book, [...old, ...added], world);
    return world ? { worldbook: all, new_entries: all.slice(old.length) }
      : { entries: all, new_uids: added.map(e => e.uid) };
  };
  expose('createLorebookEntries', (name, entries) => createEntries(name, entries, false));
  expose('createWorldbookEntries', (name, entries) => createEntries(name, entries, true));
  expose('createLorebookEntry', async (name, entry) => (await createLorebookEntries(name, [entry])).new_uids[0]);
  expose('deleteLorebookEntries', async (name, uids) => {
    const book = await loadBook(name), drop = new Set(uids);
    const kept = book.entries.filter((e, i) => !drop.has(toRawEntry(e, i).uid));
    await saveBook({ ...book, entries: kept });
    return { entries: entriesAs({ ...book, entries: kept }, false), delete_occurred: kept.length !== book.entries.length };
  });
  expose('deleteLorebookEntry', async (name, uid) => (await deleteLorebookEntries(name, [uid])).delete_occurred);
  expose('deleteWorldbookEntries', async (name, predicate) => {
    const book = await loadBook(name), all = entriesAs(book, true), deleted = [], kept = [];
    all.forEach((entry, i) => { if (predicate(entry)) deleted.push(entry); else kept.push(book.entries[i]); });
    await saveBook({ ...book, entries: kept });
    return { worldbook: entriesAs({ ...book, entries: kept }, true), deleted_entries: deleted };
  });
  expose('deleteWorldbook', async name => {
    const listing = await api('GET', '/api/worlds');
    const book = listing.worlds?.find(b => b.name === name || b.id === name);
    if (!book) return false;
    await api('DELETE', `/api/worlds/${encodeURIComponent(book.id)}`);
    window.__tellevInvalidateContext();
    return true;
  });
  expose('rebindGlobalWorldbooks', async names => {
    const wanted = new Set(names.map(String));
    const { worlds } = await api('GET', '/api/worlds');
    await api('POST', '/api/worldinfo/disabled', { ids: worlds.filter(w => !wanted.has(w.name)).map(w => w.id) });
    window.__tellevInvalidateContext();
  });

  // ── Chat-message create/delete (js-slash-runner chat_message.ts parity) ──
  const sessionMessages = async chatId => {
    const r = await api('GET', `/api/chats/${encodeURIComponent(chatId)}`);
    return r.messages || [];
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
      content: String(m.message ?? m.content ?? ''),
      isHidden: m.is_hidden ?? false,
      variables: m.data === undefined ? [] : [clone(m.data)],
      metadata: { ...(m.role === 'system' ? { type: 'narrator' } : {}), ...(m.extra || {}) },
      createdAtMillis: Date.now(),
    }));
    if (!built.length) return;
    await api('POST', `/api/chats/${encodeURIComponent(chatId)}/messages/insert`,
      { messages: built, before: insertAt });
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
    await api('POST', `/api/chats/${encodeURIComponent(chatId)}/messages/delete`, { message_ids: ids });
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

  // ── Remaining surface gaps (Part B3) ────────────────────────────────────
  // Upstream alias (slash.ts): triggerSlashWithResult === triggerSlash.
  expose('triggerSlashWithResult', cmd => th.triggerSlash(cmd));

  // Character writes must patch raw.data.extensions, which is what the native
  // regex reader consumes. The old whole-card write used an ignored data field.
  const regexCharacterId = async option => {
    if (option && typeof option === 'object') {
      const c = context();
      if (option.type && option.type !== 'character') {
        throw new Error(`Tellev does not support ${option.type} regex writes through this API`);
      }
      if (option.name && option.name !== 'current') {
        const listing = await api('GET', '/api/characters');
        const character = listing.characters.find(x => x.name === option.name || x.id === option.name);
        if (!character) throw new Error(`Unknown character: ${option.name}`);
        return character.id;
      }
      return c.characterId;
    }
    return option ?? context().characterId;
  };
  const toHelperRegex = r => ({
    id: r.id, script_name: r.scriptName ?? '', enabled: !r.disabled,
    find_regex: r.findRegex, replace_string: r.replaceString, trim_strings: r.trimStrings || [],
    source: { user_input: r.placement?.includes(1) ?? false, ai_output: r.placement?.includes(2) ?? false,
      slash_command: r.placement?.includes(3) ?? false, world_info: r.placement?.includes(5) ?? false },
    destination: { display: !!r.markdownOnly, prompt: !!r.promptOnly }, run_on_edit: !!r.runOnEdit,
    min_depth: r.minDepth ?? null, max_depth: r.maxDepth ?? null,
  });
  const toStoredRegex = (r, previous = {}) => r.find_regex === undefined ? { ...previous, ...r } : ({
    ...previous, id: r.id, scriptName: r.script_name, disabled: !r.enabled,
    findRegex: r.find_regex, replaceString: r.replace_string, trimStrings: r.trim_strings || [],
    placement: [r.source?.user_input && 1, r.source?.ai_output && 2,
      r.source?.slash_command && 3, r.source?.world_info && 5].filter(Boolean),
    markdownOnly: !!r.destination?.display, promptOnly: !!r.destination?.prompt, runOnEdit: !!r.run_on_edit,
    minDepth: r.min_depth ?? null, maxDepth: r.max_depth ?? null,
  });
  expose('getTavernRegexes', async option => {
    const charId = await regexCharacterId(option);
    const result = await api('GET', `/api/characters/${encodeURIComponent(charId)}/regex`);
    return typeof option === 'string' ? result.regex_scripts : result.regex_scripts.map(toHelperRegex);
  });
  expose('replaceTavernRegexes', async (regexesOrOption, optionOrRegexes) => {
    // Upstream order (regexes, option); the old tellev order (charId, regexes)
    // is detected and honored so existing scripts keep working.
    const [regexes, option] = Array.isArray(regexesOrOption)
      ? [regexesOrOption, optionOrRegexes]
      : [optionOrRegexes, regexesOrOption];
    const charId = await regexCharacterId(option);
    const path = `/api/characters/${encodeURIComponent(charId)}`;
    const current = (await api('GET', `${path}/regex`)).regex_scripts;
    await api('POST', `${path}/tavern-helper`, {
      regex_scripts: regexes.map(r => toStoredRegex(r, current.find(x => x.id === r.id))),
    });
    window.__tellevInvalidateContext();
  });
  expose('updateTavernRegexesWith', async (updater, option) => {
    if (typeof updater !== 'function' && typeof option === 'function') [updater, option] = [option, updater];
    const regexes = await th.getTavernRegexes(option);
    const updated = await updater(regexes) || regexes;
    await th.replaceTavernRegexes(updated, option);
    return updated;
  });

  // formatAsDisplayedMessage (displayed_message.ts): macros + regex applied
  // over the raw text. Tellev applies card regex upstream already, so the
  // honest remaining step is macro substitution.
  expose('formatAsDisplayedMessage', mes => th.substitudeMacros(String(mes ?? '')));

  // Tellev has no proxy presets; the list is always empty.
  expose('getProxyPresetNames', () => Promise.resolve([]));

  // Audio control family: consistent with the existing playAudio no-ops —
  // Tellev's extension WebView has no audio pipeline.
  for (const name of ['audioEnable', 'audioImport', 'audioMode', 'audioPlay', 'audioSelect']) {
    if (typeof th[name] === 'undefined') expose(name, () => Promise.resolve());
  }
})();
