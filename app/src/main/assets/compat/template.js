// EJS compilation options and unescaped output follow ST-Prompt-Template/ejs.ts.

// ── Prompt-injection registry (ST-Prompt-Template inject.ts parity) ──────
// Module-level state on purpose: it must survive across __tellevTemplate calls
// so a worldbook entry can injectPrompt() during the system-prompt render and a
// later message — or a later build when sticky > 0 — can collect it with
// getPromptsInjected(). ST clears expired entries once per generation
// (handler.ts:403); Tellev calls __tellevTemplateDeactivate() once per prompt
// build from DefaultPromptTemplateProcessor, which gives the same observable
// lifetime for entries registered during that build.
const promptInjected = new Map();

// FNV-1a 32-bit — uids only need to be stable within this registry for dedup;
// they never cross to ST or to the JVM fallback registry.
function hashUid(text) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return ('0000000' + hash.toString(16)).slice(-8);
}

function injectPrompt(key, prompt, order = 100, sticky = 0, uid = '') {
  if (!promptInjected.has(key)) promptInjected.set(key, new Map());
  if (!uid) uid = hashUid(`${key}#${prompt}`);
  promptInjected.get(key).set(uid, { prompt, order, sticky, uid });
  return '';
}

function getPromptsInjected(key, postprocess = [], outlet = false) {
  if (outlet && (!Array.isArray(postprocess) || postprocess.length <= 0)) {
    return `{{outletPromptsInjected:${key}}}`;
  }
  const inner = promptInjected.get(key);
  if (!inner) return '';
  let combined = Array.from(inner.values())
    .sort((a, b) => a.order - b.order)
    .map(p => p.prompt)
    .join('\n');
  for (const pp of (postprocess || [])) {
    if (!pp || typeof pp !== 'object') continue;
    combined = combined.replace(pp.search, pp.replace);
  }
  return combined;
}

function hasPromptsInjected(key) {
  return promptInjected.has(key);
}

// ST deactivatePromptInjection (inject.ts:102): sticky >= 0 survives, else dropped.
function deactivatePrompts(count = 1) {
  for (const key of Array.from(promptInjected.keys())) {
    const inner = promptInjected.get(key);
    const expired = [];
    inner.forEach((entry, uid) => {
      const next = entry.sticky - count;
      if (next >= 0) entry.sticky = next; else expired.push(uid);
    });
    if (expired.length === inner.size) promptInjected.delete(key);
    else {
      expired.forEach(uid => inner.delete(uid));
      if (inner.size === 0) promptInjected.delete(key);
    }
  }
  return '';
}

// ST applyOutletPromptsInjected (inject.ts:85): resolve
// {{outletPromptsInjected:key}} repeatedly until none remain.
function applyOutletPrompts(content, recursion = 41) {
  let result = String(content ?? '');
  for (let i = 0; i < recursion; i++) {
    if (!result.includes('{{outletPromptsInjected:')) break;
    result = result.replace(/\{\{outletPromptsInjected:(.+?)\}\}/g, (_, key) => getPromptsInjected(key));
  }
  return result;
}

window.__tellevTemplateDeactivate = function (count) { return deactivatePrompts(count || 1); };
window.__tellevTemplateOutlet = function (content) { return applyOutletPrompts(content); };

// ── Variable model (ST-Prompt-Template variables.ts parity) ──────────────
// Four layers, lowest to highest priority: global, local (chat), message
// (current-generation floor), plus the `cache` — the merged snapshot ST keeps
// in STATE.cacheVars. getvar without an explicit scope reads the cache;
// setvar syncs the cache and writes the target scope (default: message).
function normalizeVarOptions(options) {
  if (typeof options === 'string') {
    if (options === 'old' || options === 'new' || options === 'fullcache') return { results: options };
    if (options === 'nx' || options === 'xx' || options === 'nxs' || options === 'xxs' || options === 'n') return { flags: options };
    if (options === 'cache' || options === 'global' || options === 'local' || options === 'message' || options === 'initial') {
      return { scope: options, inscope: options, outscope: options };
    }
    return {};
  }
  // ST: dryRun=true means "permit writing during the preparation phase";
  // Tellev has no separate preparation phase, so it is a no-op here.
  if (typeof options === 'boolean') return { dryRun: options };
  return options || {};
}

function deepMergeValues(dst, src) {
  // lodash mergeWith(customizer: arrays take the source value) semantics.
  if (Array.isArray(src)) return src;
  if (dst !== null && src !== null && typeof dst === 'object' && typeof src === 'object'
    && !Array.isArray(dst) && !Array.isArray(src)) {
    const out = Object.assign({}, dst);
    for (const key of Object.keys(src)) out[key] = deepMergeValues(out[key], src[key]);
    return out;
  }
  return src === undefined ? dst : src;
}

window.__tellevTemplate = async function (request) {
  const local = request.local || {}, global = request.global || {}, definitions = request.definitions || {};
  const message = request.messageVariables || {};
  const cache = Object.assign({}, global, local, message);
  const stack = [];
  const scopeObject = s => s === 'global' ? global : s === 'local' ? local : s === 'message' ? message : undefined;
  const getvar = (key, options = {}) => {
    const o = normalizeVarOptions(options);
    const source = scopeObject(o.scope) ?? cache;
    return _.get(source, key, o.defaults);
  };
  const setvar = (key, value, options = {}) => {
    const o = normalizeVarOptions(options);
    if (key == null) return o.results === 'fullcache' ? cache : value;
    const writeTarget = scopeObject(o.scope || 'message');
    // ST checks nxs/xxs via getVariable with the same options: the write
    // scope when explicit, the cache for the default scope.
    const checkSource = o.scope ? (scopeObject(o.scope) ?? cache) : cache;
    if (o.flags === 'nxs' && _.get(checkSource, key) !== undefined) {
      return o.results === 'old' ? _.get(cache, key) : undefined;
    }
    if (o.flags === 'xxs' && _.get(checkSource, key) === undefined) {
      return o.results === 'old' ? _.get(cache, key) : undefined;
    }
    let newValue = value;
    const oldValue = (o.results === 'old' || o.merge) ? _.get(cache, key, undefined) : undefined;
    if (o.merge) {
      if ((oldValue === undefined || Array.isArray(oldValue)) && Array.isArray(value)) {
        newValue = (oldValue ?? []).concat(value);
      } else {
        newValue = deepMergeValues(oldValue ?? {}, value);
      }
    }
    if (newValue === undefined) _.unset(cache, key); else _.set(cache, key, newValue);
    if (writeTarget) {
      if (newValue === undefined) _.unset(writeTarget, key); else _.set(writeTarget, key, newValue);
    }
    return o.results === 'old' ? oldValue : o.results === 'fullcache' ? cache : newValue;
  };
  const incvar = (k, v = 1, o = {}) => {
    const opts = normalizeVarOptions(o);
    const source = scopeObject(opts.inscope) ?? cache;
    const target = scopeObject(opts.outscope || 'message');
    const next = Number(_.get(source, k, 0) || 0) + Number(v || 0);
    _.set(target, k, next); _.set(cache, k, next);
    return next;
  };
  const decvar = (k, v = 1, o = {}) => {
    const opts = normalizeVarOptions(o);
    const source = scopeObject(opts.inscope) ?? cache;
    const target = scopeObject(opts.outscope || 'message');
    const next = Number(_.get(source, k, 0) || 0) - Number(v || 0);
    _.set(target, k, next); _.set(cache, k, next);
    return next;
  };
  const delvar = (k, o = {}) => {
    const opts = normalizeVarOptions(o);
    const target = scopeObject(opts.scope || 'message');
    if (target) _.unset(target, k);
    _.unset(cache, k);
    return '';
  };
  const insvar = (k, v, i, o = {}) => {
    const opts = normalizeVarOptions(o);
    const target = scopeObject(opts.scope || 'message');
    const current = _.get(target, k);
    if (Array.isArray(current)) {
      const list = current.slice();
      const at = i == null || i === '' ? list.length
        : Number(i) < 0 ? Math.max(list.length + Number(i), 0) : Math.min(Number(i), list.length);
      list.splice(at, 0, v);
      _.set(target, k, list); _.set(cache, k, list);
    } else {
      _.set(target, k, v); _.set(cache, k, v);
    }
    return '';
  };
  const env = Object.assign({}, request.context, definitions, {
    variables: cache, getvar, setvar,
    // ST exposes SillyTavern.getContext() to templates; Tellev has no ST
    // internals, so stub it to a permissive empty context — bare references
    // and getContext() survive, deeper property access yields undefined.
    SillyTavern: { getContext: () => ({}) },
    // faker is not bundled; declaring it prevents a ReferenceError on bare
    // references (property access still throws, matching an absent lib).
    faker: undefined,
    getVar: getvar, setVar: setvar,
    getLocalVar: (k, o) => getvar(k, {...normalizeVarOptions(o), scope:'local'}),
    getGlobalVar: (k, o) => getvar(k, {...normalizeVarOptions(o), scope:'global'}),
    getMessageVar: (k, o) => getvar(k, {...normalizeVarOptions(o), scope:'message'}),
    setLocalVar: (k,v,o) => setvar(k,v,{...normalizeVarOptions(o), scope:'local'}),
    setGlobalVar: (k,v,o) => setvar(k,v,{...normalizeVarOptions(o), scope:'global'}),
    setMessageVar: (k,v,o) => setvar(k,v,{...normalizeVarOptions(o), scope:'message'}),
    incvar, decvar,
    incLocalVar: (k,v=1,o) => incvar(k,v,{...normalizeVarOptions(o), outscope:'local'}),
    incGlobalVar: (k,v=1,o) => incvar(k,v,{...normalizeVarOptions(o), outscope:'global'}),
    incMessageVar: (k,v=1,o) => incvar(k,v,{...normalizeVarOptions(o), outscope:'message'}),
    decLocalVar: (k,v=1,o) => decvar(k,v,{...normalizeVarOptions(o), outscope:'local'}),
    decGlobalVar: (k,v=1,o) => decvar(k,v,{...normalizeVarOptions(o), outscope:'global'}),
    decMessageVar: (k,v=1,o) => decvar(k,v,{...normalizeVarOptions(o), outscope:'message'}),
    delvar,
    delLocalVar: (k,o) => delvar(k,{...normalizeVarOptions(o), scope:'local'}),
    delGlobalVar: (k,o) => delvar(k,{...normalizeVarOptions(o), scope:'global'}),
    delMessageVar: (k,o) => delvar(k,{...normalizeVarOptions(o), scope:'message'}),
    insvar,
    insertLocalVar: (k,v,i,o) => insvar(k,v,i,{...normalizeVarOptions(o), scope:'local'}),
    insertGlobalVar: (k,v,i,o) => insvar(k,v,i,{...normalizeVarOptions(o), scope:'global'}),
    insertMessageVar: (k,v,i,o) => insvar(k,v,i,{...normalizeVarOptions(o), scope:'message'}),
    getAllVariables: () => cache,
    define: (name,value) => { _.set(definitions,name,value); _.set(env,name,value); return ''; },
    injectPrompt, getPromptsInjected, hasPromptsInjected,
  });
  const render = async (content, extra = {}) => {
    const data = Object.assign(env, extra);
    const fn = ejs.compile(content, {async:true,client:true,outputFunctionName:'print',_with:true});
    return await fn.call(data, data, value=>value, include);
  };
  const include = async (name, extra = {}) => {
    if (stack.includes(name)) throw new Error(`Recursive worldbook include: ${name}`);
    const entries = request.worldCatalog || [];
    const entry = entries.find(e => e.comment === name || e.title === name || e.id === name);
    if (!entry) throw new Error(`Unknown worldbook entry: ${name}`);
    stack.push(name);
    try { return await render(entry.content, extra); } finally { stack.pop(); }
  };
  const getwi = async (bookOrEntry, entryOrData = {}, data = {}) => {
    const short = _.isPlainObject(entryOrData);
    const book = short ? (env.world_info?.world || request.currentWorldBookId) :
      (bookOrEntry || env.world_info?.world || request.currentWorldBookId);
    const key = short ? bookOrEntry : entryOrData;
    const entry = (request.worldCatalog || []).find(e =>
      (!book || e.bookId === book || e.bookName === book) &&
      (key instanceof RegExp ? key.test(e.comment) : typeof key === 'number' ? String(e.id) === String(key) : e.comment === key || e.title === key));
    if (!entry) { console.warn(`Worldbook entry not found: ${book || ''}/${key}`); return ''; }
    const id = `${entry.bookId}/${entry.id}`;
    if (stack.includes(id)) throw new Error(`Recursive worldbook include: ${id}`);
    stack.push(id);
    const previous = env.world_info;
    try { return await render(entry.content, {...(short ? entryOrData : data),
      world_info:{world:entry.bookName || entry.bookId,uid:entry.id,comment:entry.comment}}); }
    finally { stack.pop(); env.world_info = previous; }
  };
  Object.assign(env, { getwi, getWorldInfo: getwi, include });
  const doRender = async () => {
    const content = await render(request.template);
    return {content, local, global, message, definitions};
  };
  // Isolated renders (historical floors) must not leave side effects behind:
  // restore the injection registry afterwards; variable scopes are discarded
  // by the caller (DefaultPromptTemplateProcessor renders them on a snapshot).
  if (request.isolated) {
    const snapshot = new Map();
    promptInjected.forEach((inner, key) => snapshot.set(key, new Map(inner)));
    try { return await doRender(); }
    finally { promptInjected.clear(); snapshot.forEach((inner, key) => promptInjected.set(key, inner)); }
  }
  return doRender();
};
