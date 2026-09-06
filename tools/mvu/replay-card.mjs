// User-owned cards stay outside the repository. This checks their actual scripts
// with synthetic neutral replies; native disk persistence has separate JVM tests.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHost, readAsset } from './test/host-fixture.mjs';

const [cardPath, presetPath] = process.argv.slice(2);
if (!cardPath) throw Error('Usage: node replay-card.mjs <extracted-card.json> [preset.json]');
const input = JSON.parse(await readFile(cardPath, 'utf8'));
const card = input.data || input;
const preset = presetPath ? JSON.parse((await readFile(presetPath, 'utf8')).replace(/^\uFEFF/, '')) : {};
function scriptsOf(value) {
  return (value || []).flatMap(s => s.enabled !== true ? [] : s.type === 'folder' ? scriptsOf(s.scripts) : [s]);
}
const scripts = [...scriptsOf(card.extensions?.tavern_helper?.scripts), ...scriptsOf(preset.extensions?.tavern_helper?.scripts)];
const mvuImport = /import\s*['"]https:\/\/(?:testingcf|cdn|fastly)\.jsdelivr\.net\/gh\/MagicalAstrogy\/MagVarUpdate(?:@beta)?\/artifact\/bundle\.js['"];?/g;
const zodImport = /import\s*\{\s*registerMvuSchema\s*\}\s*from\s*['"]https:\/\/(?:testingcf|cdn|fastly)\.jsdelivr\.net\/gh\/StageDog\/tavern_resource\/dist\/util\/mvu_zod\.js['"];?/g;
const reports = [];
for (const [greeting, text] of [card.first_mes, ...(card.alternate_greetings || [])].entries()) {
  const chat = [{ name: card.name, mes: text, is_user: false, is_system: false, swipe_id: 0, swipes: [text], variables: [] }];
  const host = await createHost({ chat, card });
  try {
    const { w } = host;
    w.eval('(function(){' + (await readAsset('mvu-zod.js')).replace(/export\s*\{[^}]+\};?\s*$/, s => {
      const name = s.match(/(\w+)\s+as\s+registerMvuSchema/)[1];
      return `window.registerMvuSchema=${name};`;
    }) + '})();');
    let hasMvu = false;
    for (const script of scripts) {
      const source = script.content.replace(mvuImport, () => { hasMvu = true; return ''; })
        .replace(zodImport, '').replace(/export\s+(?=(?:const|let|function)\s)/g, '');
      if (/\bimport\s*(?:[{'"*]|\w)/.test(source)) throw Error(`Unresolved import in ${script.name}`);
      w.eval('(function(){' + source + '\n})();');
    }
    assert.ok(hasMvu, 'Card must include a recognized MVU module');
    w.eval('(function(){' + await readAsset('mvu.js') + '\n})();');
    await w.__tellevReady();
    // This card defers registration of its maintenance listener by 1500 ms.
    await new Promise(resolve => w.setTimeout(resolve, 2100));
    assert.ok(chat[0].variables[0]?.stat_data, 'Greeting variables initialized');
    assert.ok(chat[0].variables[0]?.schema, 'Greeting schema initialized');
    const initial = JSON.stringify(chat[0].variables);
    for (let turn = 0; turn < 2; turn++) {
      chat.push({ name: 'User', mes: 'Compatibility replay input.', is_user: true, is_system: false,
        swipe_id: 0, swipes: [], variables: [] });
      await w.__tellevDispatch('message_sent', JSON.stringify({ args: [chat.length - 1] }));
      chat.push({ name: card.name, mes: 'Compatibility replay response.', is_user: false, is_system: false,
        swipe_id: 0, swipes: [], variables: [] });
      const id = chat.length - 1;
      await w.__tellevDispatch('message_received', JSON.stringify({ args: [id, 'normal'] }));
      assert.ok(chat[id].variables[0]?.stat_data, `Turn ${turn + 1} committed`);
      assert.ok(chat[id].mes.includes('<StatusPlaceHolderImpl/>'), 'MVU text rewrite completed');
    }
    assert.equal(JSON.stringify(chat[0].variables), initial, 'Previous floor was preserved');
    assert.equal(host.errors.length, 0, host.errors.join('\n'));
    reports.push({ greeting, scripts: scripts.length, turns: 2, result: 'passed' });
  } finally { host.close(); }
}
console.log(JSON.stringify({ card: card.name, presetScripts: scriptsOf(preset.extensions?.tavern_helper?.scripts).length,
  scope: 'actual card scripts, synthetic replies, in-memory native boundary; no WebView or model acceptance', reports }, null, 2));
