import { chromium } from 'playwright-core';
import { readFile,writeFile,mkdir } from 'node:fs/promises';
import { startReplayService } from './replay-service.mjs';
const root=new URL('../../',import.meta.url);
const dao=process.argv.includes('--dao');
const service=await startReplayService({responses:['固定普通回复。','固定流式回复。']});
const browser=await chromium.launch({channel:'msedge',headless:true});
const watchdog=setTimeout(()=>{console.error('Oracle generation exceeded 120 seconds');browser.close();},120000);
const errors=[],events=[];
let monitor;
try {
  const page=await browser.newPage();
  if(dao)await page.route(/https:\/\/(testingcf|cdn|fastly)\.jsdelivr\.net\/gh\/(MagicalAstrogy\/MagVarUpdate\/artifact\/bundle\.js|StageDog\/tavern_resource\/dist\/util\/mvu_zod\.js)$/,async route=>{
    const file=route.request().url().includes('mvu_zod')?'mvu-zod.js':'mvu.js';
    await route.fulfill({status:200,contentType:'application/javascript',headers:{'Access-Control-Allow-Origin':'*'},body:await readFile(new URL(`app/src/main/assets/compat/${file}`,root))});
  });
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.text().startsWith('ORACLE:'))console.log(m.text());});
  let checking=false;
  monitor=setInterval(async()=>{
    if(checking)return;checking=true;
    try {
      for(const dialog of await page.locator('dialog[open], .vfm__content').all()) {
        const text=await dialog.textContent();
        if(/此角色含有内置正则|是否现在就启用它们|Welcome to SillyTavern|欢迎来到 SillyTavern/.test(text)) {
          const button=dialog.locator('.popup-button-ok').or(dialog.getByText(/^(确认|确定|OK)$/)).first();
          if(await button.count())await button.click({timeout:1000});
        }
      }
    } catch {} finally {checking=false;}
  },500);
  await page.goto('http://127.0.0.1:18181/');
  await page.waitForFunction(()=>window.TavernHelper && window.EjsTemplate);
  const card=dao?JSON.parse(await readFile(new URL('../card_4.7.json',root),'utf8')):{spec:'chara_card_v2',spec_version:'2.0',data:{name:'MVU Replay',description:'Deterministic compatibility fixture.',personality:'',scenario:'',first_mes:'固定开局。',mes_example:'',creator_notes:'',system_prompt:'',post_history_instructions:'',alternate_greetings:[],tags:[],creator:'tellev validation',character_version:'1',extensions:{}}};
  const imported=await page.evaluate(async card=>{
    const st=await import('/script.js');
    const form=new FormData();form.append('avatar',new File([JSON.stringify(card)],'mvu-oracle.json',{type:'application/json'}));form.append('file_type','json');form.append('preserved_name','mvu-oracle');
    const headers=st.getRequestHeaders();delete headers['Content-Type'];
    const response=await fetch('/api/characters/import',{method:'POST',headers,body:form});
    if(!response.ok)throw Error('Character import: '+response.status);
    const data=await response.json();await st.getCharacters();
    console.info('ORACLE: character imported');
    if(card.data.character_book) {
      const wi=await import('/scripts/world-info.js');
      await wi.saveWorldInfo(card.data.character_book.name,wi.convertCharacterBook(card.data.character_book),true);
      console.info('ORACLE: embedded worldbook imported');
    }
    const index=st.characters.findIndex(c=>c.avatar===data.file_name||c.avatar===data.file_name+'.png');
    if(index<0)throw Error('Imported character not listed: '+JSON.stringify(data));
    await st.selectCharacterById(index);
    console.info('ORACLE: character selected');
    await st.doNewChat();
    return data;
  },card);
  console.log('Imported card and started isolated chat');
  if(dao) {
    const preset=await readFile(new URL('../3.27【可待】甲戌.json',root),'utf8');
    await page.evaluate(async preset=>{
      $('#main_api').val('openai').trigger('change');
      const manager=(await import('/scripts/preset-manager.js')).getPresetManager('openai');
      await manager.savePreset('MVU Oracle Jiaxu',JSON.parse(preset));
      await TavernHelper.loadPreset('MVU Oracle Jiaxu');
    },preset);
    await page.waitForFunction(()=>window.Mvu && TavernHelper.getVariables({type:'message',message_id:0}).stat_data,null,{timeout:60000});
    console.log('ORACLE: MVU and card initial variables ready');
  }
  await page.evaluate(async url=>{
    const st=await import('/script.js'),openai=await import('/scripts/openai.js');
    $('#main_api').val('openai').trigger('change');
    $('#chat_completion_source').val('custom').trigger('change');
    Object.assign(openai.oai_settings,{custom_url:url,custom_model:'mvu-replay',chat_completion_source:'custom',stream_openai:false});
    $('#custom_api_url_text').val(url).trigger('input');
    $('#custom_model_id').val('mvu-replay').trigger('input');
    window.__oracleTrace=[];
    for(const [key,event] of Object.entries(st.event_types)) {
      if(/GENERATION|MESSAGE_RECEIVED|MESSAGE_SENT|MESSAGE_RENDERED/.test(key))
        st.eventSource.on(event,(...args)=>window.__oracleTrace.push({event,args:JSON.parse(JSON.stringify(args))}));
    }
    $('#api_button_openai').trigger('click');
  },service.url);
  await page.waitForFunction(()=>SillyTavern.getContext().onlineStatus && SillyTavern.getContext().onlineStatus!=='no_connection',null,{timeout:30000});
  for(const stream of [false,true]) {
    await page.evaluate(async stream=>{
      const st=await import('/script.js'),openai=await import('/scripts/openai.js');
      openai.oai_settings.stream_openai=stream;
      $('#send_textarea').val(stream?'流式测试。':'普通测试。');
      await st.Generate('normal');
    },stream);
  }
  const snapshot=await page.evaluate(()=>({chat:SillyTavern.getContext().chat,events:window.__oracleTrace}));
  const report={imported,errors,...snapshot,requests:service.requests};
  await mkdir(new URL('build/mvu-oracle/results/',root),{recursive:true});
  await writeFile(new URL(`build/mvu-oracle/results/generation${dao?'-dao':''}.json`,root),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({requests:service.requests.length,errors,messages:snapshot.chat.length}));
  if(errors.length||service.requests.length!==2)process.exitCode=1;
} finally { clearTimeout(watchdog);clearInterval(monitor);await browser.close();await service.close(); }
