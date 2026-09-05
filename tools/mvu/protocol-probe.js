// Shared *inputs and observations* only. Neither implementation is imported here.
// Every output property is semantic; the comparator must not normalize any of it.
window.runProtocolProbe = async function ({ helper, context, template, frame }) {
  const out={};
  const capture=async (name,fn)=>{try{out[name]={value:await fn()};}catch(e){out[name]={error:String(e.message)};}};
  helper.replaceVariables({nested:{n:1},array:[1,2]}, {type:'chat'});
  const copy=helper.getVariables(); copy.nested.n=99;
  out.clone=helper.getVariables();
  out.syncUpdater=helper.updateVariablesWith(v=>({...v,sync:true}));
  out.asyncUpdater=await helper.updateVariablesWith(async v=>({...v,async:true}));
  out.assign=helper.insertOrAssignVariables({array:[9],nested:{b:2}});
  out.insert=helper.insertVariables({array:[7,8,9],nested:{n:8,c:3}});
  out.deleteMissing=helper.deleteVariable('absent.path');
  out.deleteExisting=helper.deleteVariable('nested.n');
  for (const [key,option] of Object.entries({
    latest:{type:'message',message_id:'latest'},
    negative:{type:'message',message_id:-1},
    emptyFloor:{type:'message',message_id:1},
    invalid:{type:'message',message_id:99},
  }))await capture(key,()=>helper.getVariables(option));
  await capture('replaceLatest',()=>{
    const option={type:'message',message_id:'latest'};
    const value={written:true}; helper.replaceVariables(value,option); value.after=1;
    return {option,messages:context.chat.map(m=>m.variables)};
  });
  await capture('messages',()=>helper.getChatMessages('0-2'));
  await capture('swipes',()=>helper.getChatMessages('-3--1',{include_swipes:true}));
  await capture('hidden',()=>helper.getChatMessages('0-2',{hide_state:'hidden'}));
  await capture('template',()=>template.evalTemplate('<% const a=await Promise.resolve([1,2]); %><%= a.join("/") %> <b>正文</b>',{},{}));
  const source=context.eventSource;
  const trace=[]; const data={count:0};
  const first=async value=>{trace.push('first:start'); await Promise.resolve(); value.count++; trace.push('first:end');};
  const second=value=>trace.push(`second:${value.count}`);
  source.on('mvu_probe',first); source.on('mvu_probe',second);
  try {await source.emit('mvu_probe',data);out.events={trace,data};}
  finally {source.removeListener('mvu_probe',first);source.removeListener('mvu_probe',second);}
  return JSON.parse(JSON.stringify(out));
};
