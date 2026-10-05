(function () {
  const snapshot = () => JSON.parse(TellevMessage.getContext());
  const th = window.TavernHelper;
  const expose = (name, fn) => { th[name] = window[name] = fn; };
  const request = window.__tellevRequest;
  const compact = payload => {
    if (typeof TellevMessage.readVariables !== 'function') return null;
    const result = JSON.parse(TellevMessage.readVariables(JSON.stringify(payload)));
    if (result === null) return null;
    if (!result.ok) throw new Error(result.error);
    return result;
  };
  expose('getVariables', (option = {type:'chat'}) => {
    const result = compact({kind:'scope',options:option});
    if (result) return result.value;
    const c = snapshot(), type = option.type || 'chat';
    if (type !== 'message') return c.variableScopes?.[type] || {};
    let id = option.message_id;
    if (id === undefined || id === 'latest') id = c.chat.findLastIndex(m => !m.is_system);
    else if (id < 0) id += c.chat.length;
    const m = c.chat[id];
    if (!m) throw new Error(`Invalid message_id: ${id}`);
    return m.variables?.[m.swipe_id || 0] || {};
  });
  expose('getAllVariables', () => {
    const cached = compact({kind:'all'});
    if (cached) return cached.value;
    const c = snapshot(), result = Object.assign({}, c.variableScopes?.global,
      c.variableScopes?.character, c.variableScopes?.chat);
    for (const m of c.chat.slice(0, getCurrentMessageId()+1)) Object.assign(result,m.variables?.[m.swipe_id || 0]);
    return result;
  });
  expose('getLastMessageId', () => {
    const cached = compact({kind:'last'});
    return cached ? cached.value : snapshot().chat.length-1;
  });
  expose('getChatMessages', (range,options) => window.__tellevGetChatMessages(snapshot().chat,range,options));
  expose('replaceVariables', (variables, options) => request('replaceVariables',{variables,options}));
  expose('updateVariablesWith', async (updater,options) => {
    const variables=await updater(getVariables(options)); await replaceVariables(variables,options); return variables;
  });
  expose('setChatMessages',(messages,options)=>request('setChatMessages',{messages,options}));
  const storage = (operation,payload) => request('compatibilityStorage',{operation,payload});
  const current = name => { if (name !== 'current') throw new Error('Only current context is supported'); };
  expose('getCharWorldbookNames',(name='current')=> {
    current(name); const c=snapshot(); return c.characterWorldbookBindings || {primary:null,additional:[]};
  });
  expose('rebindCharWorldbooks',(name,binding)=>storage('rebindCharWorldbooks',{name,binding}));
  expose('getChatWorldbookName',(chat='current')=> { current(chat); return snapshot().chat_metadata?.world_info || null; });
  expose('rebindChatWorldbook',(chat,name)=>storage('rebindChatWorldbook',{chat,name}));
  expose('getOrCreateChatWorldbook',async(chat,name)=>(await storage('getOrCreateChatWorldbook',{chat,name})).name);
  const listeners=new Map();
  expose('eventOn',(event,callback)=> {
    if(!listeners.has(event))listeners.set(event,new Set()); listeners.get(event).add(callback);
    return {stop:()=>listeners.get(event)?.delete(callback)};
  });
  expose('eventRemoveListener',(event,callback)=>listeners.get(event)?.delete(callback));
  expose('eventOnce',(event,callback)=> { const sub=eventOn(event,(...args)=>{sub.stop();return callback(...args)}); return sub; });
  expose('eventEmit',async(event,...args)=>{for(const callback of listeners.get(event)||[])await callback(...args)});
  let lastVariables;
  window.__tellevStateChanged=async()=>{
    const next=getVariables({type:'message',message_id:getCurrentMessageId()});
    if(JSON.stringify(next)===JSON.stringify(lastVariables))return;
    const before=lastVariables; lastVariables=next;
    if(window.Mvu?.events?.VARIABLE_UPDATE_ENDED)
      await eventEmit(window.Mvu.events.VARIABLE_UPDATE_ENDED,next,before);
  };
  window.SillyTavern.getContext=snapshot;
  // Read calls stay synchronous. Computation is delegated to the actual loaded MVU runtime.
  const call=(method,args)=>request('mvuCall',{method,args});
  window.Mvu={events:{},getMvuData:getVariables,replaceMvuData:replaceVariables,
    parseMessage:(...args)=>call('parseMessage',args),parseMessages:(...args)=>call('parseMessages',args)};
  expose('waitGlobalInitialized',async name=>{
    if(name==='Mvu')window.Mvu.events=await request('mvuReady',{});
    if(window[name]===undefined)throw new Error(`Global not initialized: ${name}`);
    return window[name];
  });
})();
