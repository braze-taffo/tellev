import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startReplayService } from '../replay-service.mjs';
test('replay preserves prompt bytes and emits deterministic normal and Unicode SSE replies',async()=>{
  const service=await startReplayService({responses:['普通回复','中文🦊\n结束']});
  try {
    const raw='{ "model":"mvu-replay", "messages":[{"role":"user","content":"  正文\\n"}] }';
    const normal=await fetch(service.url+'/chat/completions',{method:'POST',body:raw});
    assert.equal((await normal.json()).choices[0].message.content,'普通回复');
    assert.equal(service.requests[0].raw,raw);
    const stream=await fetch(service.url+'/chat/completions',{method:'POST',body:JSON.stringify({messages:[],stream:true})});
    const text=await stream.text();
    const events=text.split('\n\n').filter(Boolean).map(s=>s.slice(6));
    assert.equal(events.pop(),'[DONE]');
    assert.equal(events.map(s=>JSON.parse(s).choices[0].delta.content||'').join(''),'中文🦊\n结束');
    const exhausted=await fetch(service.url+'/chat/completions',{method:'POST',body:'{}'});
    assert.equal(exhausted.status,409);
  } finally {await service.close();}
});
