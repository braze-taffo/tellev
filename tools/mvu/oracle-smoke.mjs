// Uses a clean headless browser and the unmodified upstream distributions.
import { chromium } from 'playwright-core';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
const root=new URL('../../',import.meta.url);
const browser=await chromium.launch({channel:'msedge',headless:true});
const errors=[];
try {
  const page=await browser.newPage();
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:18181/',{waitUntil:'load',timeout:120000});
  await page.waitForFunction(()=>window.TavernHelper && window.EjsTemplate,{},{timeout:60000});
  const result=await page.evaluate(()=>({
    helper:typeof TavernHelper.getVariables,
    template:typeof EjsTemplate.evalTemplate,
    eventSource:typeof SillyTavern.getContext().eventSource.emit,
    extensions:[...document.scripts].filter(s=>s.src.includes('/third-party/')).map(s=>new URL(s.src).pathname),
  }));
  const report={...result,errors,provenance:JSON.parse(await readFile(new URL('build/mvu-oracle/provenance.json',root),'utf8'))};
  await mkdir(new URL('build/mvu-oracle/results/',root),{recursive:true});
  await writeFile(new URL('build/mvu-oracle/results/smoke.json',root),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
  if(result.helper!=='function'||result.eventSource!=='function'||errors.length)process.exitCode=1;
} finally { await browser.close(); }
