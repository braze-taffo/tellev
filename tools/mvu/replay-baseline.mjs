import { chromium } from 'playwright-core';
import { JSDOM, VirtualConsole } from 'jsdom';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';
const root=new URL('../../',import.meta.url);
const read=p=>readFile(new URL(p,root),'utf8');
const probe=await read('tools/mvu/protocol-probe.js');
const fixture=()=>[
  {name:'Test',mes:'first',is_user:false,is_system:false,swipe_id:1,swipes:['a','first'],variables:[{n:1},{n:2}],swipe_info:[{extra:{a:1}},{extra:{b:2}}],extra:{}},
  {name:'User',mes:'second',is_user:true,is_system:false,extra:{}},
  {name:'Hidden',mes:'third',is_user:false,is_system:true,swipe_id:0,variables:[{n:3}],extra:{}},
];
async function upstream(browser) {
  const page=await browser.newPage();
  try {
    await page.goto('http://127.0.0.1:18181/');
    await page.waitForFunction(()=>window.TavernHelper && window.EjsTemplate);
    await page.evaluate(chat=>{
      const c=SillyTavern.getContext();c.chat.splice(0,c.chat.length,...chat);
    },fixture());
    await page.addScriptTag({content:probe});
    return await page.evaluate(()=>runProtocolProbe({helper:TavernHelper,context:SillyTavern.getContext(),template:EjsTemplate}));
  } finally { await page.close(); }
}
async function tellev() {
  const chat=fixture(),scopes={local:{},global:{}};
  const dom=new JSDOM('<html data-extension-id="probe"><body/></html>',{
    runScripts:'dangerously',url:'https://extensions.tellev.local/probe/',virtualConsole:new VirtualConsole(),
  });
  const w=dom.window;
  try {
    w.fetch=async()=>{throw Error('Protocol probes must not use network');};
    const native={
      stGetContext:()=>JSON.stringify({chat,chatId:'probe'}),getSettings:()=> '{}',log:()=>{},
      stGetVariablesForScope:s=>JSON.stringify(scopes[s]||{}),
      stSetVariablesForScope:(s,v)=>{scopes[s]=JSON.parse(v);},
      stGetMessageVariables:i=>JSON.stringify(chat.at(i)?.variables?.[chat.at(i).swipe_id??0]||{}),
      stSetMessageVariables:(i,v)=>{(chat.at(i).variables??=[])[chat.at(i).swipe_id??0]=JSON.parse(v);},
      emitFromEventSource:()=>{},
    };
    w.tellevNative=new Proxy(native,{get:(o,k)=>o[k]||(()=>{throw Error(`Unexpected native call: ${String(k)}`);})});
    w.eval(await read('app/src/main/assets/compat/globals.js'));
    const html=await read('app/build/compat-host.html');
    w.eval(html.slice(html.indexOf('__SHOWDOWN_SOURCE__'),html.indexOf('</script><script>__HOST_ADAPTER__'))
      .replace('__SHOWDOWN_SOURCE__','').replaceAll('__EXTENSION_ID__','probe').replaceAll('__TOKEN__','probe')
      .replaceAll('__EJS_SETTINGS_JSON__','{}').replaceAll('__TAVERN_HELPER_SETTINGS_JSON__','{}'));
    for(const file of ['chat.js','host.js'])w.eval(await read(`app/src/main/assets/compat/${file}`));
    w.eval(probe);
    return JSON.parse(JSON.stringify(await w.runProtocolProbe({helper:w.TavernHelper,context:{chat,eventSource:w.eventSource},template:w.EjsTemplate})));
  } finally {w.close();}
}
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
  const repeats=[];
  for(let i=0;i<3;i++) {
    const oracle=await upstream(browser),actual=await tellev();
    const differences=Object.keys(oracle).filter(key=>!isDeepStrictEqual(oracle[key],actual[key]));
    repeats.push({oracle,actual,differences});
  }
  const stable=repeats.every(r=>isDeepStrictEqual(r,repeats[0]));
  const report={formatVersion:1,environment:'full-upstream-vs-jsdom-tellev-host',normalizations:[],repeats,stable};
  await mkdir(new URL('build/mvu-oracle/results/',root),{recursive:true});
  await writeFile(new URL('build/mvu-oracle/results/protocol-baseline.json',root),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({stable,repeats:repeats.length,differences:repeats[0].differences},null,2));
  if(!stable || repeats[0].differences.length)process.exitCode=1;
} finally {await browser.close();}
