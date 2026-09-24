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

window.__tellevTemplate = async function (request) {
  const local = request.local || {}, global = request.global || {}, definitions = request.definitions || {};
  const stack = [];
  const merged = () => Object.assign({}, global, local);
  const scope = options => options?.scope === 'global' ? global : local;
  const getvar = (key, options = {}) => _.get(options.scope ? scope(options) : merged(), key, options.defaults);
  const setvar = (key, value, options = {}) => {
    const target = scope(options);
    _.set(target, key, value); return value;
  };
  const env = Object.assign({}, request.context, definitions, {
    variables: merged(), getvar, setvar,
    getVar: getvar, setVar: setvar,
    getLocalVar: (k, o) => getvar(k, {...o,scope:'local'}),
    getGlobalVar: (k, o) => getvar(k, {...o,scope:'global'}),
    setLocalVar: (k,v,o) => setvar(k,v,{...o,scope:'local'}),
    setGlobalVar: (k,v,o) => setvar(k,v,{...o,scope:'global'}),
    incvar: (k,v=1,o) => setvar(k,Number(getvar(k,o)||0)+Number(v),o),
    decvar: (k,v=1,o) => setvar(k,Number(getvar(k,o)||0)-Number(v),o),
    delvar: (k,o) => _.unset(scope(o),k),
    getAllVariables: merged,
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
  const content = await render(request.template);
  return {content, local, global, definitions};
};
