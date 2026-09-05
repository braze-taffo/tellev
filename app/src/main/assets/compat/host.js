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
    error ? pending.reject(new Error(error)) : pending.resolve();
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
      variableWrites.get(id)?.();
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
    writes.set(id, {resolve, reject});
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
  expose('getLorebookEntries', async name => {
    const book = context().worldBooks?.find(b => b.name === name || b.id === name);
    if (!book) throw new Error(`Unknown worldbook: ${name}`);
    return clone(book.entries);
  });
  expose('getWorldbook', name => getLorebookEntries(name));
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
    for (let index = 0; index < ready.length; index++) await ready[index];
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
  window.__tellevLoadScripts = async scripts => {
    try {
      await Promise.all(scripts.map(async script => {
        const entry=document.createElement('div'); entry.dataset.scriptId=script.id; registry.append(entry);
        const api=window.__tellevScriptApi(script);
        const prelude=`const {${Object.keys(api).join(',')}}=window.__tellevScriptApi(${JSON.stringify({id:script.id,name:script.name})});\n`;
        const url=URL.createObjectURL(new Blob([prelude,script.content],{type:'application/javascript'}));
        try { await import(url); } catch(error) { throw new Error(`${script.name}: ${error.stack||error}`); }
        finally { URL.revokeObjectURL(url); }
      }));
    } catch(error) { throw error; }
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
      variableWrites.set(id,resolve);
      timer=setTimeout(()=>reject(new Error(`MVU did not commit floor ${id} within 15 seconds`)),15000);
    }) : Promise.resolve();
    try {
      await eventSource._fireNative(name, payload);
      await committed;
    } finally { clearTimeout(timer); variableWrites.delete(id); }
  };
  EjsTemplate.evalTemplate = EjsTemplate.evaltemplate = async (code, env = {}, options = {}) =>
    ejs.render(code, env, { ...options, async: true });
})();
