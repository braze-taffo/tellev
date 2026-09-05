import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const directory=new URL('../../build/mvu-oracle/SillyTavern/',import.meta.url);
// Prevent an archived upstream server from mistaking the enclosing tellev Git repo for itself.
const child=spawn(process.execPath,['server.js'],{cwd:directory,stdio:'inherit',env:{...process.env,
  GIT_CEILING_DIRECTORIES:fileURLToPath(new URL('../',directory))}});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>child.kill(signal));
child.on('exit',code=>{process.exitCode=code??1;});
