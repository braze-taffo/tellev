import { chromium } from 'playwright-core';
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const fixture = { name:'Fixture', mes:'one', is_user:false, is_system:false, send_date:'1970-01-01T00:00:00.000Z',
  swipe_id:0, swipes:['one','two'], variables:[{n:1},null], swipe_info:[{a:1},{b:2}], extra:{a:1}, unknown:{keep:[1,2]} };
const cases = [
  ['data_ignores_swipe_id', [{message_id:0, swipe_id:1, data:{n:2}}]],
  ['data_and_message_ignore_swipe_arrays', [{message_id:0, message:'edited', data:{n:3}, swipes:['ignored'], swipes_data:[{ignored:true}]}]],
  ['select_and_pad', [{message_id:0, swipe_id:99, swipes:['new'], swipes_data:[{n:5},{n:6},{n:7}]}]],
  ['swipe_only', [{message_id:-1, swipe_id:1}]],
  ['extra_without_message_or_data', [{message_id:0, extra:{ignored:true}}]],
  ['extra_on_selected_resets_info', [{message_id:0, data:{}, extra:{changed:true}}]],
  ['duplicate_last_field_wins', [{message_id:0, data:{n:4}},{message_id:-1, data:{n:8}, name:'Renamed'}]],
  ['narrator_is_not_hidden', [{message_id:0, role:'system', is_hidden:false}]],
  ['hidden_is_not_narrator', [{message_id:0, role:'assistant', is_hidden:true}]],
  ['empty_swipes', [{message_id:0, swipes:[]}]],
];
const browser = await chromium.launch({channel:'msedge',headless:true});
try {
  const page = await browser.newPage();
  await page.goto('http://127.0.0.1:18181/');
  await page.waitForFunction(()=>window.TavernHelper && window.EjsTemplate);
  const runs=[];
  for(let repeat=0; repeat<3; repeat++) {
    const results=[];
    for(const [name, updates] of cases) {
      const output = await page.evaluate(async ({fixture, updates})=>{
        const c=SillyTavern.getContext(); c.chat.splice(0,c.chat.length,structuredClone(fixture));
        await TavernHelper.setChatMessages(updates,{refresh:'none'});
        return JSON.parse(JSON.stringify(c.chat));
      }, {fixture, updates});
      results.push({name,input:[fixture],updates,output});
    }
    runs.push(results);
  }
  assert.deepEqual(runs[0],runs[1]); assert.deepEqual(runs[1],runs[2]);
  const provenance=JSON.parse(await readFile(new URL('build/mvu-oracle/provenance.json',root),'utf8'));
  const report={provenance,repeats:3,normalizations:[],cases:runs[0]};
  await writeFile(new URL('app/src/test/resources/fixtures/upstream-message-mutations.json',root),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({cases:cases.length,repeats:3,stable:true,emptyCase:runs[0].at(-1).output}));
} finally { await browser.close(); }
