import {readFile, writeFile, mkdir, access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {JSDOM} from 'jsdom';

const root = new URL('../../', import.meta.url);
const asset = name => readFile(new URL(`app/src/main/assets/compat/${name}`, root), 'utf8');
const html = await readFile(new URL('app/build/compat-host.html', root), 'utf8');
const dom = new JSDOM('<html data-extension-id="audit"><body></body></html>', {runScripts:'dangerously',url:'https://extensions.tellev.local/audit/'});
try {
  const w=dom.window;
  w.fetch=async()=>{throw Error('API enumeration must not access the network');};
  const before=new Set(Object.getOwnPropertyNames(w));
  w.tellevNative=new Proxy({getSettings:()=> '{}',log:()=>{},
    stGetContext:()=>JSON.stringify({chat:[],chatId:'audit'})}, {
    get(target,key) { return target[key] || (()=>{throw Error(`Unexpected native call while enumerating: ${String(key)}`)}); }
  });
  w.eval(await asset('globals.js'));
  const libraries=new Set(Object.getOwnPropertyNames(w).filter(k=>!before.has(k)));
  w.eval(html.slice(html.indexOf('__SHOWDOWN_SOURCE__'),html.indexOf('</script><script>__HOST_ADAPTER__'))
    .replace('__SHOWDOWN_SOURCE__','').replaceAll('__EXTENSION_ID__','audit').replaceAll('__TOKEN__','audit')
    .replaceAll('__EJS_SETTINGS_JSON__','{}').replaceAll('__TAVERN_HELPER_SETTINGS_JSON__','{}'));
  w.eval(await asset('chat.js')); w.eval(await asset('host.js'));
  const entries=[];
  function collect(object,prefix,depth=0) {
    for(const [name,d] of Object.entries(Object.getOwnPropertyDescriptors(object))) {
      const value='value' in d ? d.value : d.get?.call(object), path=`${prefix}.${name}`;
      if(typeof value==='function') {
        const source=Function.prototype.toString.call(value);
        entries.push({path,arity:value.length,sourceSha256:createHash('sha256').update(source).digest('hex'),
          placeholderCandidate:/not implemented|function\s*\([^)]*\)\s*\{\s*(?:return\s+(?:undefined|null|''|""|\{\}|\[\]|Promise\.resolve\([^)]*\))\s*;?)?\s*\}/.test(source),
          contractStatus:'pending',test:null});
      } else if(value && typeof value==='object' && depth<2 && ['_bind','builtin','_th_impl'].includes(name))collect(value,path,depth+1);
    }
  }
  for(const name of ['TavernHelper','SillyTavern','EjsTemplate'])collect(w[name],name);
  for(const name of Object.getOwnPropertyNames(w)) {
    if(before.has(name)||libraries.has(name)||name.startsWith('_'))continue;
    const d=Object.getOwnPropertyDescriptor(w,name);
    if(typeof d.value==='function')collect({[name]:d.value},'window');
  }
  entries.sort((a,b)=>a.path.localeCompare(b.path));
  const destination=new URL('tools/mvu/contracts/',root);await mkdir(destination,{recursive:true});
  const baseline=new URL('api-baseline.json',destination);
  const exists=await access(baseline).then(()=>true,()=>false);
  const file=exists ? new URL('build/mvu-api-current.json',root) : baseline;
  await writeFile(file,JSON.stringify({formatVersion:1,entries},null,2)+'\n');
  console.log(`${exists?'Audited':'Frozen'} ${entries.length} API entries; ${entries.filter(e=>e.placeholderCandidate).length} placeholder candidates require review.`);
} finally {dom.window.close();}
