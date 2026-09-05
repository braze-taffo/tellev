import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM, VirtualConsole } from 'jsdom';
const read = p => readFile(new URL(p, import.meta.url), 'utf8');
const asset = name => read(`../../../app/src/main/assets/compat/${name}`);

test('actual MVU bundle initializes and applies DaoYuan Zod updates', {timeout:20000}, async () => {
  const card = JSON.parse(await read('../../../../card_4.7.json')).data;
  const chat = [{name:card.name, mes:card.first_mes, is_user:false, is_system:false,
    swipe_id:0, swipes:[card.first_mes], variables:[]}];
  const scopes = {local:{}, global:{}};
  const errors = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => errors.push(e.message));
  vc.on('error', (...args) => errors.push(args.join(' ')));
  const dom = new JSDOM('<html data-extension-id="dao"><body></body></html>', {
    url:'https://extensions.tellev.local/dao/', runScripts:'dangerously', pretendToBeVisual:true, virtualConsole:vc,
  });
  const w = dom.window;
  try {
    w.fetch = async () => ({ok:true, json:async()=>({pkgVersion:'1.18.0'}), text:async()=>''});
    w.tellevNative = {
      stGetContext:()=>JSON.stringify({chat, chatId:'replay', name1:'User', name2:card.name,
        characterWorldBooks:['dao'], globalWorldBooks:[], worldBooks:[{name:'dao',entries:card.character_book.entries}]}),
      stGetVariablesForScope:s=>JSON.stringify(scopes[s] || {}),
      stSetVariablesForScope:(s,v)=>{scopes[s]=JSON.parse(v)},
      stGetAllVariables:()=>JSON.stringify({...scopes.global,...scopes.local}),
      stGetMessageVariables:i=>JSON.stringify(chat.at(i)?.variables[chat.at(i).swipe_id] || {}),
      stSetMessageVariables:(i,v)=>{chat.at(i).variables[chat.at(i).swipe_id]=JSON.parse(v)},
      stSetChatMessages:(id,data)=>{
        for(const m of JSON.parse(data)) {
          const dest=chat.at(m.message_id);
          if(m.message!==undefined)dest.mes=m.message;
          if(m.swipes_data)dest.variables=m.swipes_data;
          if(m.data)dest.variables[dest.swipe_id]=m.data;
        }
        queueMicrotask(()=>w.__tellevWriteDone(id,null));
      },
      log:(level,msg)=>{if(level==='error')errors.push(msg)},
      emitFromEventSource:()=>{}, emit:()=>{}, stReplaceVariables:s=>s,
      getSettings:()=> '{}', saveSettings:()=>{}, registerCommand:()=>{}, registerRoute:()=>{},
      extensionReady:()=>{}, extensionFailed:e=>errors.push(e),
      apiCall:(id)=>queueMicrotask(()=>w.Tellev.onApiResult?.(id,200,'{}')),
    };
    w.eval(await asset('globals.js'));
    const html = await read('../../../app/build/compat-host.html');
    const legacy = html.slice(html.indexOf('__SHOWDOWN_SOURCE__'), html.indexOf('</script><script>__HOST_ADAPTER__'))
      .replace('__SHOWDOWN_SOURCE__','').replaceAll('__EXTENSION_ID__','dao')
      .replaceAll('__TOKEN__','token').replaceAll('__EJS_SETTINGS_JSON__','{}').replaceAll('__TAVERN_HELPER_SETTINGS_JSON__','{}');
    w.eval(legacy);
    w.eval(await asset('chat.js'));
    w.eval(await asset('host.js'));
    // Fetching the host's /version goes through a real API in Android; supply the fixture here.
    w.fetch = async () => ({ok:true, json:async()=>({pkgVersion:'1.18.0'}), text:async()=>''});
    w.eval('(function(){'+(await asset('mvu-zod.js')).replace(/export\s*\{[^}]+\};?\s*$/, x => {
      const match=x.match(/(\w+)\s+as\s+registerMvuSchema/); return `window.registerMvuSchema=${match[1]};`;
    })+'})();');
    const schema = card.extensions.tavern_helper.scripts.find(s=>s.name==='ZOD').content
      .replace(/^import[^\n]+\n/, '').replace('export const Schema','const Schema');
    w.eval(schema);
    w.eval('(function(){'+await asset('mvu.js')+'})();');
    await Promise.race([w.__tellevReady(), new Promise((_,reject)=>setTimeout(()=>reject(Error('Initialization timeout: '+errors.join('\n'))),7000))]);
    assert.ok(w.Mvu, 'MVU global initialized: '+errors.join('\n'));
    assert.equal(chat[0].variables[0]?.stat_data?.主角?.生命,100, errors.join('\n'));
    chat.push({name:card.name,is_user:false,is_system:false,swipe_id:0,swipes:[],variables:[],
      mes:'继续前进。<UpdateVariable><JSONPatch>[{"op":"delta","path":"/主角/生命","value":-15}]</JSONPatch></UpdateVariable>'});
    await w.__tellevDispatch('message_received', JSON.stringify({args:[1,'normal']}));
    assert.equal(chat[1].variables[0]?.stat_data?.主角?.生命,85,errors.join('\n'));
    assert.equal(chat[0].variables[0].stat_data.主角.生命,100);
    // Replaying the same output derives from the previous floor, never applies the delta twice.
    await w.__tellevDispatch('message_received', JSON.stringify({args:[1,'normal']}));
    assert.equal(chat[1].variables[0].stat_data.主角.生命,85);
    chat.push({name:card.name,is_user:false,is_system:false,swipe_id:0,swipes:[],variables:[],
      mes:'恢复并清理背包。<UpdateVariable><JSONPatch>'+JSON.stringify([
        {op:'delta',path:'/主角/生命',value:1000},
        {op:'insert',path:'/主角/储物袋/丹药',value:{数量:0,描述:'已用完'}},
        ...Array.from({length:5},(_,i)=>({op:'insert',path:`/机遇/任务${i}`,value:{目标:'测试'}})),
      ])+'</JSONPatch></UpdateVariable>'});
    await w.__tellevDispatch('message_received', JSON.stringify({args:[2,'normal']}));
    assert.equal(chat[2].variables[0].stat_data.主角.生命,100);
    assert.equal(Object.keys(chat[2].variables[0].stat_data.主角.储物袋).length,0);
    assert.equal(Object.keys(chat[2].variables[0].stat_data.机遇).length,3);
    assert.equal(w.getChatMessages(-1)[0].message_id,2);
    const copy=w.getVariables({type:'message',message_id:0});
    copy.stat_data.主角.生命=1;
    assert.equal(chat[0].variables[0].stat_data.主角.生命,100);
    assert.equal(errors.length,0,errors.join('\n'));
  } finally { w.close(); }
});

test('real EJS supports async JavaScript, worldbook includes and variable writes', async () => {
  const dom=new JSDOM('<body/>',{runScripts:'dangerously'}), w=dom.window;
  try {
    w.eval(await asset('globals.js')); w.eval(await asset('template.js'));
    const result=await w.__tellevTemplate({template:'<% const xs=[1,2,3].map(x=>x*2); setvar("n", xs.reduce((a,b)=>a+b,0)); %><%= await include("nested") %>',
      local:{},global:{},worldCatalog:[{comment:'nested',content:'<%= getvar("n") %><b>原样输出</b>'}]});
    assert.equal(result.content,'12<b>原样输出</b>');
    assert.equal(result.local.n,12);
    const wi=await w.__tellevTemplate({template:'<%= await getwi(null,"section") %>',
      currentWorldBookId:'book',worldCatalog:[{bookId:'book',id:'1',comment:'section',content:'<%= 6*7 %>'}]});
    assert.equal(wi.content,'42');
    await assert.rejects(()=>w.__tellevTemplate({template:'<% await include("missing") %>'}),/Unknown worldbook/);
  } finally { w.close(); }
});
