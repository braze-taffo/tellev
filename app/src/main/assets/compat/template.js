// EJS compilation options and unescaped output follow ST-Prompt-Template/ejs.ts.
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
