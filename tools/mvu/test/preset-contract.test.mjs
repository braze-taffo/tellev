import test from 'node:test';
import assert from 'node:assert/strict';
import {createHost} from './host-fixture.mjs';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';

test('MVU extra-model consumer sees synchronous preset names and public settings/prompts', async()=> {
  const h=await createHost({chat:[]});
  try {
    const names=h.w.getPresetNames();assert.ok(Array.isArray(names));assert.ok(names.includes('A'));
    assert.equal(h.w.getLoadedPresetName(),'A');
    const preset=h.w.getPreset('A');assert.ok(Array.isArray(preset.prompts));
    assert.equal(preset.prompts[0].id,'main');assert.equal(preset.settings.temperature,0.8);
    assert.equal(preset.settings.max_completion_tokens,200);
  }finally{h.close();}
});
test('async updater preserves unknown raw settings and maps public absolute prompt positions',async()=> {
  const h=await createHost({chat:[]});
  try {
    await h.w.updatePresetWith('A',async p=> {
      await Promise.resolve();p.settings.temperature=0.25;p.settings.max_completion_tokens=512;
      p.prompts.push({id:'depth',name:'Depth',enabled:true,position:{type:'in_chat',depth:2,order:70},role:'assistant',content:'injected'});
      return p;
    });
    const raw=h.presetsStore.get('A');assert.equal(raw.temperature,0.25);assert.equal(raw.openai_max_tokens,512);
    assert.equal(raw.reasoning_effort,'high');assert.deepEqual(raw.custom_unknown,{keep:true});
    const prompt=raw.prompts.find(p=>p.identifier==='depth');
    assert.equal(prompt.injection_position,1);assert.equal(prompt.injection_depth,2);assert.equal(prompt.injection_order,70);
    assert.equal(h.w.getPreset('in_use').settings.temperature,0.8);
  }finally{h.close();}
});
test('failed preset load emits no success event; failed writes reject',async()=> {
  const h=await createHost({chat:[],failApi:(method,path)=>path.endsWith('/replace')?{status:500,error:'disk failed'}:null});
  try {
    let changed=0;h.w.eventOn(h.w.tavern_events.PRESET_CHANGED,()=>changed++);
    assert.equal(h.w.loadPreset('missing'),false);assert.equal(changed,0);
    await assert.rejects(h.w.updatePresetWith('A',async p=>p),/disk failed/);
    assert.equal(h.presetsStore.get('A').temperature,0.8);
  }finally{h.close();}
});
test('preset variables survive switching A to B to A without cross-preset collision',async()=> {
  const make=variables=>({prompts:[],extensions:{tavern_helper:{variables}}});
  const h=await createHost({chat:[],card:{presets:{A:make({a:1}),B:make({b:2})}}});
  try {
    h.w.replaceVariables({a:3},{type:'preset'});assert.equal(h.w.loadPreset('B'),true);
    assert.equal(h.w.getVariables({type:'preset'}).b,2);assert.equal(h.w.getVariables({type:'preset'}).a,undefined);
    h.w.replaceVariables({b:4},{type:'preset'});h.w.loadPreset('A');assert.equal(h.w.getVariables({type:'preset'}).a,3);
    assert.equal(h.w.getVariables({type:'preset'}).b,undefined);
    h.w.replaceVariables({character:5},{type:'character'});assert.equal(h.canonicalVariables.character.character,5);
  }finally{h.close();}
});
test('character and chat worldbook bindings change and getOrCreate is idempotent',async()=> {
  const h=await createHost({chat:[]});
  try {
    await h.w.rebindCharWorldbooks('current',{primary:null,additional:['fixture']});
    assert.equal(h.w.getCharWorldbookNames('current').primary,null);
    assert.equal(h.w.getCharWorldbookNames('current').additional[0],'fixture');
    assert.equal(h.w.getChatWorldbookName('current'),null);
    assert.equal(await h.w.getOrCreateChatWorldbook('current','new book'),'new book');
    assert.equal(h.w.getChatWorldbookName('current'),'new book');
    assert.equal(await h.w.getOrCreateChatWorldbook('current','ignored'),'new book');
    await h.w.rebindChatWorldbook('current','fixture');assert.equal(h.w.getChatWorldbookName('current'),'fixture');
    await assert.rejects(h.w.rebindChatWorldbook('other','fixture'),/Only current/);
  }finally{h.close();}
});
test('generate and generateRaw return strings and retain their distinct request mode',async()=> {
  const calls=[];const h=await createHost({chat:[],failApi:(m,p,b)=>{if(p==='/api/backends/chat-completions/generate')calls.push(b);return null;}});
  try {
    assert.equal(await h.w.generate({user_input:'a'}),'fixture generation');
    assert.equal(await h.w.generateRaw({ordered_prompts:[{role:'user',content:'b'}]}),'fixture generation');
    assert.equal(calls[0].__tellev_use_preset,true);assert.equal(calls[1].__tellev_use_preset,false);
    assert.equal(calls[1].ordered_prompts[0].content,'b');
  }finally{h.close();}
});

// Exact consumer from MagicalAstrogy/MagVarUpdate 61010dab47bc3a08a1b626320bf7fc8c9573eca4.
// Only localization is replaced; contract calls and validation execute unchanged.
test('locked upstream MVU preset consumer reads the repaired bridge',async()=> {
  const h=await createHost({chat:[]});
  try {
    const source=await readFile(new URL('../vendor/extra_model_preset.ts',import.meta.url),'utf8');
    const compiled=await transform(source.replace("import { tr } from '@/i18n';","const tr=(key:string)=>key;"),
      {loader:'ts',format:'iife',globalName:'UpstreamPreset'});
    h.w.eval(compiled.code);
    assert.deepEqual(Array.from(h.w.UpstreamPreset.getAvailableExtraModelPresetNames()),['A']);
    const preset=h.w.UpstreamPreset.getExtraModelPreset('A');assert.equal(preset.settings.temperature,0.8);
    assert.equal(preset.prompts[0].id,'main');
  }finally{h.close();}
});

test('public position changes replace old native aliases and in_use reads canonical file settings',async()=> {
  const h=await createHost({chat:[],card:{presets:{A:{temperature:0.7,temp_openai:0.2,prompts:[{identifier:'depth',name:'Depth',role:'system',content:'x',relative:false,depth:4}],extensions:{}}}}});
  try {
    assert.equal(h.w.getPreset('in_use').settings.temperature,0.7);
    await h.w.updatePresetWith('A',p=> {p.prompts[0].position={type:'in_chat',depth:8,order:60};return p;});
    assert.equal(h.presetsStore.get('A').prompts[0].relative,true);
    assert.equal(h.presetsStore.get('A').prompts[0].depth,8);
    assert.equal(h.w.getPreset('A').prompts[0].position.type,'in_chat');
    await h.w.updatePresetWith('A',p=> {p.prompts[0].position={type:'relative'};return p;});
    assert.equal(h.presetsStore.get('A').prompts[0].relative,false);
    assert.equal(h.presetsStore.get('A').prompts[0].injection_position,0);
  }finally{h.close();}
});
