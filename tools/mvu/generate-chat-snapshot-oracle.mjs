// Execute the current reference repositories' functions, not Tellev bridge code.
import fs from 'node:fs';
import vm from 'node:vm';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {transformSync} from 'esbuild';
import lodash from 'lodash';
import YAML from 'yaml';
const workspace=new URL('../../../',import.meta.url);
const repository=name=>fileURLToPath(new URL(name+'/',workspace));
const read=(repo,path)=>fs.readFileSync(repository(repo)+path,'utf8');
const revision=repo=>execFileSync('git',['rev-parse','HEAD'],{cwd:repository(repo),encoding:'utf8'}).trim();
const coreSource=read('SillyTavern','public/scripts/macros/definitions/chat-macros.js').replace(/^import .*;\r?$/gm,'').replace(/^export /gm,'');
const helperSource=read('js-slash-runner','src/function/variables.ts').replace(/^import .*;\r?$/gm,'');
const macroSource=read('js-slash-runner','src/function/macro_like.ts').replace(/^import .*;\r?$/gm,'');
const ejsSource=read('ST-Prompt-Template','src/function/ejs.ts');
const templateKeys=['lastUserMessage','lastUserMessageId','lastCharMessage','lastCharMessageId','lastMessageId'];
const getters=templateKeys.map(name=>{
  const match=ejsSource.match(new RegExp('get '+name+'\\(\\) \\{([\\s\\S]*?)\\n        \\}'));
  if(!match)throw new Error('Upstream getter not found: '+name);
  return 'get '+name+'(){'+match[1]+'}';
}).join(',');
const floor=(role,content,hidden=false,variables=[])=>({role,content,hidden,variables,swipeIndex:0});
const cases=[
 {name:'empty',floors:[]},
 {name:'first-input',floors:[floor('User','first')]},
 {name:'correction',floors:[floor('User','old'),floor('Character','reply'),floor('User','corrected')]},
 {name:'regeneration-snapshot',floors:[floor('User','old'),floor('Character','reply'),floor('User','corrected')]},
 {name:'hidden-interleaved',floors:[floor('User','old'),floor('User','hidden input',true),floor('Character','reply'),floor('User','corrected'),floor('Character','hidden reply',true)]},
 {name:'hidden-user-tail',floors:[floor('User','visible'),floor('Character','reply'),floor('User','hidden input',true)]},
 {name:'hidden-variable-floor',floors:[floor('Character','reply',false,[{stat_data:'earlier'}]),floor('User','current'),floor('Character','hidden',true,[{stat_data:'latest'}])]},
 {name:'empty-current-variable-object',floors:[floor('Character','reply',false,[{stat_data:'earlier'}]),floor('User','current',false,[{}])]},
 {name:'selected-swipe',floors:[{...floor('Character','active reply',false,[{stat_data:'old'},{stat_data:'active'}]),swipeIndex:1},floor('User','current')]},
 {name:'visible-narrator',floors:[floor('User','current'),floor('System','narrator')]},
 {name:'hook-appended-character',floors:[floor('User','accepted'),floor('Character','hook floor')]},
 {name:'hook-appended-user',floors:[floor('User','accepted'),floor('User','hook input')]},
];
for(const test of cases){
  const chat=test.floors.map(f=>({is_user:f.role==='User',is_system:f.hidden,mes:f.content,variables:f.variables,swipe_id:f.swipeIndex}));
  const handlers=new Map();
  const context=vm.createContext({chat,_:lodash,YAML,module:{exports:{}},exports:{},MacroRegistry:{registerMacro:(name,options)=>handlers.set(name,options.handler)},MacroCategory:{CHAT:'chat'},MacroValueType:{INTEGER:'integer'},
    // These fixtures contain no dollar-prefixed keys; omitted-key behavior is outside this oracle.
    omitDeepBy:value=>value});
  vm.runInContext(coreSource+'\nregisterChatMacros();',context);
  const compile=source=>transformSync(source,{loader:'ts',format:'cjs'}).code;
  vm.runInContext(compile(helperSource),context);
  context.get_variables_without_clone=context.module.exports.get_variables_without_clone;
  context.module={exports:{}};
  vm.runInContext(compile(macroSource),context);
  const format=context.module.exports.macros.find(m=>m.regex.source.includes('format_(')&&!m.regex.source.includes('quoted'));
  const text='{{format_message_variable::stat_data}}';
  let variables,variableError;
  try { variables=text.replace(format.regex,(substring,...args)=>format.replace({},substring,...args)); }
  catch(error) { variableError=error.message; }
  const template=vm.runInContext('({'+getters+'})',context);
  test.expected={core:Object.fromEntries(['lastMessage','lastMessageId','lastUserMessage','lastCharMessage'].map(key=>[key,handlers.get(key)()])),template:Object.fromEntries(templateKeys.map(key=>[key,template[key]])),formattedState:variables,formattedStateError:variableError};
}
const report={scope:'Exact current upstream macro functions and EJS getter bodies; synthetic saved-chat snapshots; no model calls',upstream:Object.fromEntries(['SillyTavern','js-slash-runner','ST-Prompt-Template'].map(repo=>[repo,revision(repo)])),cases};
const output=new URL('../../app/src/test/resources/fixtures/upstream-chat-snapshot.json',import.meta.url);
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({upstream:report.upstream,cases:cases.length,output:fileURLToPath(output)},null,2));
