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
