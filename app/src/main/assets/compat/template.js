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

// ST activateWorldInfo registration map (worldinfo.ts:134). In ST the entries
// are applied to the generation through the WORLDINFO_FORCE_ACTIVATE event;
// Tellev's world scan has already run by the time templates render, so the
// registry is observable through getActivatedWIEntries() and
// activateWorldInfo's return value — it cannot retroactively extend the scan.
const activatedWorldEntries = new Map();

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

// ST generate-phase semantics (inject.ts:56 + handler.ts:247): with
// forceOutlet active, getPromptsInjected returns a placeholder instead of
// collecting inline, so injections registered later in the pass are still
// collected by the end-of-build scan (handler.ts:380). Every Tellev render
// happens inside the generate pass, so the default is outlet-on here;
// explicit arguments still win.
function getPromptsInjected(key, postprocess = [], outlet = true) {
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
// {{outletPromptsInjected:key}} repeatedly until none remain. ST bounds this
// by world_info_max_recursion_steps + 1 (that setting defaults to 3 with a
// slider cap of 100); Tellev fixes a safety superset of 101. ST turns
// forceOutlet OFF before scanning (handler.ts:376) — the resolver therefore
// collects inline (outlet=false), otherwise each pass would re-emit the
// placeholder it just consumed.
function applyOutletPrompts(content, recursion = 101) {
  let result = String(content ?? '');
  for (let i = 0; i < recursion; i++) {
    if (!result.includes('{{outletPromptsInjected:')) break;
    result = result.replace(/\{\{outletPromptsInjected:(.+?)\}\}/g, (_, key) => getPromptsInjected(key, [], false));
  }
  return result;
}

// ST substituteParams subset for nested-template helpers (boundedCharDef /
// boundedEvalTemplate re-substitute these two macros against the resolved
// character). The Kotlin macro engine already expanded the remaining macros
// upstream; replacing again is idempotent.
function substituteCharMacros(text, charName, userName) {
  return String(text ?? '')
    .replace(/\{\{char\}\}/gi, charName ?? '')
    .replace(/\{\{user\}\}/gi, userName ?? '');
}

// ST DEFAULT_CHAR_DEFINE (characters.ts): backslashes are line continuations
// in the upstream template literal, so output lines join without blanks.
const DEFAULT_CHAR_DEFINE = '<% if (name) { %><<%- name %>>\n' +
  '<% if (system_prompt) { %>System: <%- system_prompt %>\n<% } %>' +
  'name: <%- name %>\n' +
  '<% if (personality) { %>personality: <%- personality %>\n<% } %>' +
  '<% if (description) { %>description: <%- description %>\n<% } %>' +
  '<% if (message_example) { %>example:\n<%- message_example %>\n<% } %>' +
  '<% if (depth_prompt) { %>System: <%- depth_prompt %>\n<% } %>' +
  '</<%- name %>><% } %>';

// ── World-info entry helpers (ST-Prompt-Template worldinfo.ts parity) ─────

// ST parseRegexFromString (world-info.js:2821): `/pattern/flags` → RegExp
// with `\/` unescaped; anything else is a plain keyword, not a regex.
function parseRegexFromString(input) {
  const match = String(input).match(/^\/([\w\W]+?)\/([gimsuy]*)$/);
  if (!match) return null;
  if (match[1].match(/(^|[^\\])\//)) return null;
  try { return new RegExp(match[1].replace('\\/', '/'), match[2]); } catch (_) { return null; }
}

const KNOWN_DECORATORS = [
  '@@activate', '@@dont_activate', '@@message_formatting', '@@generate_before',
  '@@generate_after', '@@render_before', '@@render_after', '@@dont_preload',
  '@@initial_variables', '@@always_enabled', '@@only_preload', '@@iframe',
  '@@preprocessing', '@@if', '@@private',
];

// ST parseDecorators (worldinfo.ts:827): leading `@@...` lines are decorators,
// `@@@` is the escape form; an unknown decorator makes following decorators
// plain content (fallback).
function parseDecorators(content) {
  if (!String(content).startsWith('@@')) return [[], String(content)];
  const lines = String(content).split('\n');
  const decorators = [];
  let contentStartIndex = 0;
  let fallbacked = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('@@')) {
      if (line.startsWith('@@@') && !fallbacked) { contentStartIndex = i; break; }
      const candidate = line.startsWith('@@@') ? line.substring(1) : line;
      const base = candidate.indexOf(' ') === -1 ? candidate : candidate.substring(0, candidate.indexOf(' '));
      if (KNOWN_DECORATORS.includes(base)) { decorators.push(candidate); fallbacked = false; }
      else fallbacked = true;
    } else {
      contentStartIndex = i;
      break;
    }
  }
  return [decorators, lines.slice(contentStartIndex).join('\n')];
}

// ── JSON utilities (ST-Prompt-Template json-patch.ts parity) ─────────────
// parseJSON tries strict JSON first, then progressively tolerant repairs
// (trailing commas → unquoted keys → single-quoted strings). ST bundles the
// full jsonrepair lib; this subset covers the common LLM-output breakage.
function parseJSON(json) {
  const text = String(json ?? '').trim();
  try { return JSON.parse(text); } catch (_) {}
  const noTrailing = () => text.replace(/,\s*([}\]])/g, '$1');
  const unquotedKeys = () => noTrailing().replace(/([{,]\s*)([A-Za-z_$][\w$]*)\s*:/g, '$1"$2":');
  const singleQuoted = () => unquotedKeys().replace(/'((?:[^'"\\]|\\.)*)'/g, '"$1"');
  for (const attempt of [noTrailing, unquotedKeys, singleQuoted]) {
    try { return JSON.parse(attempt()); } catch (_) {}
  }
  throw new SyntaxError('parseJSON: unable to repair input');
}

// RFC 6901 JSON Pointer → lodash path segments (~0 → ~, ~1 → /).
function pointerToPath(pointer) {
  if (typeof pointer !== 'string') throw new Error('Path must be a string.');
  if (pointer === '') return [];
  if (pointer.charAt(0) !== '/') throw new Error('Invalid JSON Pointer: must start with "/".');
  return pointer.substring(1).split('/').map(segment => segment.replace(/~1/g, '/').replace(/~0/g, '~'));
}

function jsonPatch(doc, patches) {
  const newDoc = _.cloneDeep(doc);
  for (const patch of (patches || [])) {
    const { op, path, value } = patch;
    const fromPath = patch.from != null ? pointerToPath(patch.from) : undefined;
    const lodashPath = pointerToPath(path);
    switch (op) {
      case 'set': case 'assign': case 'add': case 'replace': {
        // RFC 6902 "-" appends to the end of an array.
        if (lodashPath[lodashPath.length - 1] === '-') {
          const parent = _.get(newDoc, lodashPath.slice(0, -1));
          if (Array.isArray(parent)) parent.push(value);
          else if (typeof toastr !== 'undefined') toastr.error(`Cannot push to a non-array value at path: ${lodashPath.slice(0, -1).join('.')}`, 'JSON Patch');
        } else {
          _.set(newDoc, lodashPath, value);
        }
        break;
      }
      case 'remove': {
        if (!_.unset(newDoc, lodashPath) && typeof toastr !== 'undefined') {
          toastr.warn(`Path "${path}" could not be removed.`, 'JSON Patch');
        }
        break;
      }
      case 'move': {
        const moved = _.get(newDoc, fromPath);
        if (moved === undefined) break;
        _.unset(newDoc, fromPath);
        _.set(newDoc, lodashPath, moved);
        break;
      }
      case 'copy': {
        const copied = _.get(newDoc, fromPath);
        if (copied === undefined) break;
        _.set(newDoc, lodashPath, copied);
        break;
      }
      case 'test': {
        if (!_.isEqual(_.get(newDoc, lodashPath), value)) return doc;
        break;
      }
      default: break;
    }
  }
  return newDoc;
}

// ── Chat message reads (ST-Prompt-Template chat.ts parity) ───────────────
// The request carries the visible chat floors with ST's message shape
// (id/is_user/is_system/name/mes); ST additionally re-applies regex and
// macros here, which Tellev already applied upstream. The functions are
// bound per call inside __tellevTemplate (they close over request.chat).
function roleMatches(message, role) {
  return !role
    || (role === 'user' && message.is_user)
    || (role === 'system' && message.is_system)
    || (role === 'assistant' && !message.is_user && !message.is_system);
}

window.__tellevTemplateDeactivate = function (count) {
  // ST clears the activation registry at the end of each generation pass
  // (handler.ts:401); the per-build deactivate hook gives the same lifetime —
  // last build's registrations are gone before this build renders.
  activatedWorldEntries.clear();
  return deactivatePrompts(count || 1);
};
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
  const request_chat = request.chat || [];
  // Per-floor render fields (ST handler.ts:43): set for chat floors, unset
  // for the system prompt's generate-before environment.
  const mc = request.messageContext || {};
  const cache = Object.assign({}, global, local, message);
  const stack = [];
  // getChatMessage(s) / matchChatMessages (ST chat.ts): role-filtered reads
  // over the visible chat floors.
  const getChatMessage = (idx, role) => {
    const list = request_chat.filter(m => roleMatches(m, role));
    const message = list[idx > -1 ? idx : list.length + idx];
    return message ? (message.mes ?? '') : '';
  };
  const getChatMessages = (startOrCount = undefined, endOrRole, role) => {
    const filtered = request_chat.filter(m => roleMatches(m, endOrRole || role));
    if (endOrRole == null || typeof endOrRole === 'string') {
      if (startOrCount == null) return filtered.map(m => m.mes ?? '');
      return (startOrCount > 0
        ? filtered.slice(0, startOrCount)
        : startOrCount < 0 ? filtered.slice(startOrCount) : [])
        .map(m => m.mes ?? '');
    }
    return (startOrCount > 0
      ? filtered.slice(startOrCount, endOrRole)
      : startOrCount < 0 ? filtered.slice(startOrCount, endOrRole) : [])
      .map(m => m.mes ?? '');
  };
  const matchChatMessages = (pattern, options = {}) => {
    const start = options.start ?? -2;
    const messages = getChatMessages(start, options.end, options.role);
    const patterns = Array.isArray(pattern) ? pattern : [pattern];
    return messages.some(m => options.and
      ? patterns.every(p => m.match(p))
      : patterns.some(p => m.match(p)));
  };
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
  const patchVariables = (key, change, options = {}) => {
    const doc = getvar(key, options);
    const patched = jsonPatch(doc, typeof change === 'string' ? parseJSON(change) : change);
    return setvar(key, patched, options);
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
    variables: cache,
    // ST render-phase fields: undefined on the system prompt (no
    // messageContext), floor-scoped on chat floors.
    message_id: mc.message_id,
    swipe_id: mc.swipe_id,
    is_last: mc.is_last,
    is_user: mc.is_user,
    is_system: mc.is_system,
    name: mc.name,
    getvar, setvar,
    // ST exposes SillyTavern.getContext() to templates; Tellev has no ST
    // internals, so stub it to a permissive empty context — bare references
    // and getContext() survive, deeper property access yields undefined.
    SillyTavern: { getContext: () => ({}) },
    // ST execute() runs STscript; Tellev has no STscript engine, so calls
    // resolve to an empty pipe instead of a ReferenceError.
    execute: async () => '',
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
    parseJSON, jsonPatch, patchVariables,
    getChatMessage, getChatMessages, matchChatMessages,
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

  // ── Character data (ST-Prompt-Template characters.ts parity) ───────────
  // The render request carries only the active character; ST reads the whole
  // roster, so lookups for other characters return null instead of throwing.
  const characters = request.character ? [request.character] : [];
  const getCharacterData = (name) => {
    if (name == null || name === '') name = 0;
    return characters[name]
      ?? characters.find(c => c.name === name || (name instanceof RegExp && c.name && c.name.match(name)))
      ?? null;
  };
  const getCharacterDefine = (name) => {
    const char = getCharacterData(name);
    if (!char) return null;
    const data = char.data || {};
    let example = String(char.mes_example ?? '').trim();
    if (example.startsWith('<START>')) example = example.slice(7).trim();
    example = example.replace('<START>', '```\n```');
    if (example && example.includes('```')) example += '\n```';
    return {
      name: char.name,
      description: char.description,
      personality: char.personality,
      scenario: char.scenario,
      first_message: char.first_mes,
      message_example: example,
      creator_notes: data.creator_notes,
      creatorcomment: char.creatorcomment,
      system_prompt: data.system_prompt,
      post_history_instructions: data.post_history_instructions,
      alternate_greetings: data.alternate_greetings,
      depth_prompt: data.depth_prompt,
      creator: data.creator,
    };
  };
  // getchr/getchar/getChara are boundedCharDef (render the character define
  // through an EJS template), NOT data accessors — the data accessors are
  // getCharaData/getCharData (ejs.ts SHARE_CONTEXT).
  const getchr = async (name, template = DEFAULT_CHAR_DEFINE, data = {}) => {
    const defs = getCharacterDefine(name);
    if (!defs) { console.warn(`[Prompt Template] character ${name} not found`); return ''; }
    const rendered = await render(template, Object.assign({}, data, defs, { chara_name: defs.name }));
    return substituteCharMacros(rendered, defs.name, env.user);
  };
  const getCharaData = (name) => getCharacterData(name);
  // ST binds BOTH spellings to the data accessor in the template namespace
  // (ejs.ts SHARE_CONTEXT); TavernHelper.getCharData(charId, field) is a
  // different function in a different namespace.
  const getCharData = (name) => getCharacterData(name);

  // Preset prompts / quick replies have no Tellev pipeline counterpart in the
  // template path. ST returns '' for a missing name; Tellev has no source at
  // all, so the honest result is the same '' with a one-time warning instead
  // of a ReferenceError.
  const warnMissingSource = (label) => {
    if (!warnMissingSource.warned) {
      warnMissingSource.warned = true;
      console.warn(`[tellev] ${label}: no matching data source in template rendering, returning empty`);
    }
  };
  const getprp = async (name) => { warnMissingSource('getprp/getpreset'); return ''; };
  const getqr = async (name, label) => { warnMissingSource('getqr/getQuickReply'); return ''; };

  // Nested template evaluation (ejs.ts boundedEvalTemplate): merges data into
  // the live env — later scriptlets see it — and re-substitutes char/user.
  const evalTemplate = async (content, data = {}) => {
    if (typeof content !== 'string') return content;
    if (!content.includes('<%')) return content;
    return substituteCharMacros(await render(content, data), env.char, env.user);
  };

  // Schema-annotated YAML dump (variables.ts dumpYamlWithSchema): the schema
  // tree seeds structure, the variable values fill it in.
  const applyVarYamlAnnotate = (key = null, schema = '') => {
    const value = getvar(key);
    if (!schema) return YAML.stringify(value ?? null);
    const tree = YAML.parseDocument(String(schema));
    const deepSet = (path, update) => {
      if (update === null || update === undefined) return;
      if (_.isPlainObject(update)) {
        for (const [k, v] of Object.entries(update)) deepSet(path.concat(k), v);
      } else if (!path.length) {
        try { tree.contents = update; } catch (_) { /* keep the schema tree */ }
      } else {
        tree.setIn(path, update);
      }
    };
    deepSet([], value ?? {});
    return tree.toString();
  };
  const setVariableSchema = (schema) => {
    warnMissingSource('setVariableSchema');
    return undefined;
  };
  // Historical floors carry no per-floor variables in the render request, so
  // the backward search has nothing to find — an honest empty result.
  const findVariables = () => ({});

  Object.assign(env, {
    characters,
    getCharacterData, getCharaData, getCharData,
    getCharacterDefine,
    getchr, getchar: getchr, getChara: getchr,
    getprp, getpreset: getprp, getPresetPrompt: getprp,
    getqr, getQuickReply: getqr,
    getQuickReplyData: () => { warnMissingSource('getQuickReplyData'); return null; },
    getUserAvatarURL: () => '',
    // ST spells it "Avater"; keep the typo for source compatibility.
    getCharacterAvaterURL: () => '',
    evalTemplate,
    applyVarYamlAnnotate,
    setVariableSchema,
    findVariables,
  });

  // ── World-info entry/activation family (worldinfo.ts parity) ───────────
  // Catalog entries carry Tellev's id/comment/title/content plus the card's
  // raw entry fields (uid, key, keysecondary, constant, disable, ...). ST
  // shapes expose uid as a number, decorators stripped from content, and the
  // owning book as `world`.
  const wiBookOf = e => e.bookName || e.bookId || '';
  const wiShape = entry => {
    const raw = entry.raw || {};
    const [decorators, content] = parseDecorators(entry.content ?? raw.content ?? '');
    return Object.assign({}, raw, entry, {
      uid: Number(raw.uid ?? entry.id ?? 0),
      comment: entry.comment ?? raw.comment ?? '',
      decorators,
      content,
      world: wiBookOf(entry),
    });
  };
  const resolveBook = name => name || env.charLoreBook || env.userLoreBook
    || env.chatLoreBook || request.currentWorldBookId || null;
  const byOrder = (a, b) => (a.order ?? 100) - (b.order ?? 100);
  const getWorldInfoEntries = async (name) => {
    const book = resolveBook(name);
    if (!book) return [];
    const catalog = request.worldCatalog || [];
    return catalog
      .filter(e => e.bookId === book || e.bookName === book || wiBookOf(e) === book)
      .map(wiShape)
      .sort(byOrder);
  };
  const getWorldInfoComments = async (name) =>
    (await getWorldInfoEntries(name)).map(e => e.comment);
  const getWorldInfoEntry = async (name, title) => {
    let book;
    let key;
    if (title == null) { key = name; }
    else { book = name; key = title; }
    const entries = await getWorldInfoEntries(book);
    for (const data of entries) {
      if (data.comment === key || data.uid === key
        || (data.comment != null && data.comment.match(key))) return data;
    }
    console.warn(`[Prompt Template] entry not found: ${title ?? name}`);
    return null;
  };
  const getWorldInfoEntryContent = async (name, title) =>
    (await getWorldInfoEntry(name, title))?.content ?? null;

  // ST getEnabledLoreBooks: char book / global / persona / extra / chat
  // sources. Tellev cannot distinguish the non-char sources — the render
  // catalog's distinct books are exactly the books the scan enabled. Books
  // are deduped by id (Tellev's charLoreBook is a book id while the catalog
  // exposes both id and name for the same book).
  const getEnabledLoreBooks = (chara = true, global = true, persona = true, charaExtra = true, chat = true) => {
    const results = [];
    const seen = new Set();
    const catalog = request.worldCatalog || [];
    const pushEntry = e => {
      const key = e.bookId || e.bookName;
      if (key && !seen.has(key)) { seen.add(key); results.push(e.bookName || e.bookId); }
    };
    if (chara && env.charLoreBook) {
      const charEntry = catalog.find(e => e.bookId === env.charLoreBook || e.bookName === env.charLoreBook);
      if (charEntry) pushEntry(charEntry);
      else if (!seen.has(env.charLoreBook)) { seen.add(env.charLoreBook); results.push(env.charLoreBook); }
    }
    if (global || persona || charaExtra || chat) {
      for (const e of catalog) pushEntry(e);
    }
    return results;
  };
  const getEnabledWorldInfoEntries = async (chara = true, global = true, persona = true, charaExtra = true, chat = true) => {
    const results = [];
    for (const book of getEnabledLoreBooks(chara, global, persona, charaExtra, chat)) {
      const entries = await getWorldInfoEntries(book);
      if (entries.length > 0) results.push(...entries);
    }
    return results.sort(byOrder);
  };

  // Keyword matching (worldinfo.ts:482): `/re/flags` keys are regex, otherwise
  // whole-word or substring match, case-folded unless the entry says otherwise.
  const matchKeys = (haystack, needle, entry) => {
    const keyRegex = parseRegexFromString(needle);
    if (keyRegex) return keyRegex.test(haystack);
    const transform = str => (entry?.caseSensitive ? str : String(str).toLowerCase());
    const h = transform(haystack);
    const n = transform(needle);
    if (entry?.matchWholeWords ?? false) {
      const words = n.split(/\s+/);
      if (words.length > 1) return h.includes(n);
      return new RegExp(`(?:^|\\W)(${_.escapeRegExp(n)})(?:$|\\W)`).test(h);
    }
    return h.includes(n);
  };
  const getScore = (haystack, entry) => {
    let numberOfPrimaryKeys = 0, numberOfSecondaryKeys = 0, primaryScore = 0, secondaryScore = 0;
    if (Array.isArray(entry.key)) {
      numberOfPrimaryKeys = entry.key.length;
      for (const key of entry.key) if (matchKeys(haystack, key, entry)) primaryScore++;
    }
    if (Array.isArray(entry.keysecondary)) {
      numberOfSecondaryKeys = entry.keysecondary.length;
      for (const key of entry.keysecondary) if (matchKeys(haystack, key, entry)) secondaryScore++;
    }
    if (!numberOfPrimaryKeys) return 0;
    if (numberOfSecondaryKeys > 0) {
      switch (entry.selectiveLogic) {
        case 0: return primaryScore + secondaryScore; // AND_ANY
        case 3: return secondaryScore === numberOfSecondaryKeys
          ? primaryScore + secondaryScore : primaryScore; // AND_ALL
      }
    }
    return primaryScore;
  };
  const WI_LOGIC = { AND_ANY: 0, NOT_ALL: 1, NOT_ANY: 2, AND_ALL: 3 };
  const selectActivatedEntries = (entries, keywords, condition = {}) => {
    const activated = new Set();
    const trigger = (Array.isArray(keywords) ? keywords : [keywords]).join('\n\n');
    for (const data of entries) {
      if (condition.constant != null && data.constant !== condition.constant) continue;
      if (condition.disabled != null && data.disable !== condition.disabled) continue;
      if (condition.vectorized != null && data.vectorized !== condition.vectorized) continue;
      if (data.useProbability && data.probability < 1 + Math.floor(Math.random() * 100)) continue;
      if (data.constant) { activated.add(data); continue; }
      if (data.decorators?.includes('@@activate')) { activated.add(data); continue; }
      if (data.decorators?.includes('@@dont_activate')) continue;
      if (data.decorators?.includes('@@only_preload')) continue;
      const matchedKey = (data.key || []).find(k => matchKeys(trigger, k, data));
      if (!matchedKey) continue;
      const hasSecondaryKey = data.selective && Array.isArray(data.keysecondary) && data.keysecondary.length > 0;
      if (!hasSecondaryKey) { activated.add(data); continue; }
      const selectiveLogic = data.selectiveLogic ?? WI_LOGIC.AND_ANY;
      let hasAnyMatch = false;
      let hasAllMatch = true;
      for (const secondary of data.keysecondary) {
        const hasSecondaryMatch = secondary && matchKeys(trigger, String(secondary).trim(), data);
        if (hasSecondaryMatch) hasAnyMatch = true;
        if (!hasSecondaryMatch) hasAllMatch = false;
        if (selectiveLogic === WI_LOGIC.AND_ANY && hasSecondaryMatch) { activated.add(data); break; }
        if (selectiveLogic === WI_LOGIC.NOT_ALL && !hasSecondaryMatch) { activated.add(data); break; }
      }
      if (selectiveLogic === WI_LOGIC.NOT_ANY && !hasAnyMatch) { activated.add(data); continue; }
      if (selectiveLogic === WI_LOGIC.AND_ALL && hasAllMatch) { activated.add(data); continue; }
    }
    if (activated.size <= 0) return [];
    const grouped = {};
    for (const data of activated) { const g = data.group || ''; (grouped[g] = grouped[g] || []).push(data); }
    const ungrouped = grouped[''] || [];
    if (ungrouped.length > 0 && Object.keys(grouped).length <= 1) return ungrouped.slice().sort(byOrder);
    const matched = [];
    for (const [group, datas] of Object.entries(grouped)) {
      if (group === '') continue;
      if (datas.length === 1) { matched.push(datas[0]); continue; }
      const usePrioritize = datas.filter(d => d.groupOverride);
      if (usePrioritize.length > 0) {
        const orders = datas.map(d => d.order);
        const top = Math.min(...orders);
        if (top) { matched.push(datas[Math.max(orders.findIndex(o => o <= top), 0)]); continue; }
      }
      const useScores = datas.filter(d => d.useGroupScoring ?? false);
      if (useScores.length > 0) {
        const scores = datas.map(d => getScore(trigger, d));
        const top = Math.max(...scores);
        if (top) { matched.push(datas[Math.max(scores.findIndex(s => s >= top), 0)]); continue; }
      }
      const useWeights = datas.filter(d => !d.groupOverride && !d.useGroupScoring);
      if (useWeights.length > 0) {
        const weights = datas.map(d => d.groupWeight ?? 100);
        const totalWeight = weights.reduce((a, b) => a + b, 0);
        let rollValue = 1 + Math.floor(Math.random() * totalWeight);
        const winner = weights.findIndex(w => (rollValue -= w) <= 0);
        if (winner >= 0) matched.push(datas[winner]);
      }
    }
    return ungrouped.concat(matched).sort(byOrder);
  };
  const getWorldInfoActivatedEntries = async (name, keywords, condition = {}) => {
    const entries = await getWorldInfoEntries(name);
    if (!entries) return [];
    return selectActivatedEntries(entries, keywords, condition);
  };
  const activateWorldInfo = async (world, uid, force) => {
    if (typeof uid === 'boolean') { force = uid; uid = undefined; }
    const entry = await getWorldInfoEntry(world, uid);
    if (entry) {
      activatedWorldEntries.set(`${world}.${uid}`, Object.assign({}, entry, {
        disable: false,
        constant: force ? true : entry.constant,
        cooldown: force ? 0 : entry.cooldown,
        delay: force ? 0 : entry.delay,
        vectorized: force ? false : entry.vectorized,
        delayUntilRecursion: force ? false : entry.delayUntilRecursion,
        triggers: force ? [] : entry.triggers,
        hash: force ? Math.random() + 1 : undefined,
        content: force ? String(entry.content).replace('@@dont_activate', '') : entry.content,
        ignoreBudget: force || entry.ignoreBudget,
        group: force ? '' : entry.group,
      }));
    }
    return entry;
  };
  const activateWorldInfoByKeywords = async (keywords, condition = {}) => {
    const entries = await getEnabledWorldInfoEntries();
    const activated = selectActivatedEntries(entries, keywords, condition);
    await Promise.all(activated.map(x => activateWorldInfo(x.world, x.uid, condition.force)));
    return activated;
  };
  const applyActivateWorldInfo = () => {
    if (!applyActivateWorldInfo.warned) {
      applyActivateWorldInfo.warned = true;
      console.warn('[tellev] applyActivateWorldInfo: mid-build world re-scan is not supported, registrations stay readable via getActivatedWIEntries');
    }
    return '';
  };

  Object.assign(env, {
    // Authoritative ejs.ts mounts:
    getWorldInfoData: getWorldInfoEntries,
    getWorldInfoActivatedData: getWorldInfoActivatedEntries,
    getEnabledWorldInfoEntries,
    selectActivatedEntries,
    activewi: activateWorldInfo,
    activateWorldInfo,
    activateWorldInfoByKeywords,
    getEnabledLoreBooks,
    // Documented reference names (reference_cn.md) — a safe superset: in ST
    // they resolve the same helpers through module exports.
    getWorldInfoEntries,
    getWorldInfoEntry,
    getWorldInfoEntryContent,
    getWorldInfoComments,
    getActivatedWIEntries: () => Array.from(activatedWorldEntries.values()),
    deactivateActivateWorldInfo: () => { activatedWorldEntries.clear(); return ''; },
    applyActivateWorldInfo,
  });
  const doRender = async () => {
    const content = await render(request.template);
    return {content, local, global, message, definitions};
  };
  // Isolated renders (historical floors) must not leave side effects behind:
  // restore the injection registry afterwards; variable scopes are discarded
  // by the caller (DefaultPromptTemplateProcessor renders them on a snapshot).
  // A floor that injects AND collects within itself must still see its own
  // content: resolve its placeholders before the rollback, matching ST where
  // registrations stay alive until the end-of-pass scan.
  if (request.isolated) {
    const snapshot = new Map();
    const activationSnapshot = new Map(activatedWorldEntries);
    promptInjected.forEach((inner, key) => snapshot.set(key, new Map(inner)));
    try {
      const result = await doRender();
      result.content = applyOutletPrompts(result.content);
      return result;
    }
    finally {
      promptInjected.clear(); snapshot.forEach((inner, key) => promptInjected.set(key, inner));
      activatedWorldEntries.clear(); activationSnapshot.forEach((entry, key) => activatedWorldEntries.set(key, entry));
    }
  }
  return doRender();
};
