import { build, transform } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
const outdir = path.resolve(root, '../../app/src/main/assets/compat');
await mkdir(outdir, { recursive: true });
const httpsNpm = {
  name: 'pinned-npm', setup(b) {
    b.onResolve({ filter: /^https:\/\/testingcf\.jsdelivr\.net\/npm\// }, async ({ path: url }) => {
      const spec = url.split('/npm/')[1].replace(/\/\+esm$/, '').replace(/(?<!^)@[\d][^/]*/, '');
      return b.resolve(spec, { resolveDir: root, kind: 'import-statement' });
    });
  },
};
for (const [entry, name, format] of [
  ['globals.js', 'globals.js', 'iife'],
  ['vendor/mvu.js', 'mvu.js', 'esm'],
  ['vendor/mvu-zod.js', 'mvu-zod.js', 'esm'],
]) {
  await build({ absWorkingDir: root, entryPoints: [entry], outfile: path.join(outdir, name),
    bundle: true, minify: true, legalComments: 'linked', format, target: 'chrome100',
    plugins: [httpsNpm], define: { 'process.env.NODE_ENV': '"production"' } });
}
const files = {};
const chatSource = await readFile(path.join(root,'vendor/chat_message.ts'),'utf8');
const chatFunctions = chatSource.slice(chatSource.indexOf('// TODO: 移入'),chatSource.indexOf('type SetChatMessagesOption'))
  .replaceAll('export function','function');
const chatAdapter = `window.__tellevGetChatMessages=function(chat,range,options){
  const substituteParamsExtended=String, system_message_types={NARRATOR:'narrator'};
  const klona=value=>JSON.parse(JSON.stringify(value));
  ${chatFunctions}
  return getChatMessages(range ?? ('0-'+(chat.length-1)),options);
};`;
await writeFile(path.join(outdir,'chat.js'),(await transform(chatAdapter,{loader:'ts',target:'chrome100'})).code);
for (const name of ['globals.js', 'mvu.js', 'mvu-zod.js','chat.js','host.js','template.js','message.js']) {
  files[name] = createHash('sha256').update(await readFile(path.join(outdir, name))).digest('hex');
}
await writeFile(path.join(outdir, 'manifest.json'), JSON.stringify({
  upstream: { mvu: '61010dab47bc3a08a1b626320bf7fc8c9573eca4',
    tavern_resource: '4b3ce6130ebb039f6675583dce46a46d3f9cc4f1',
    tavern_helper: 'ef0468636011e810efd12ab9286b4d74cc656aa8',
    sillytavern: '51ad27fb86d39a3daca3adaa970375c9670c12df',
    prompt_template: 'f9a07da0fbe25cd310eee746c2f5af24ed61f62b' }, files,
}, null, 2) + '\n');
