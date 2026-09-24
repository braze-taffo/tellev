import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const file = process.argv[2];
const options = process.argv.slice(3);
const selectedName = options.find(option => !option.startsWith('--'));
const expectMvu = options.includes('--expect-mvu');
const expectedUi = options.find(option => option.startsWith('--expect-ui='))?.slice('--expect-ui='.length);
if (!file) {
  console.error('Usage: node tools/mvu/verify-card-load.mjs <extracted-card.json> [script-name] [--expect-mvu] [--expect-ui=id]');
  process.exit(2);
}
const card = JSON.parse(await readFile(file, 'utf8')).data;
const scripts = card.extensions?.tavern_helper?.scripts
  ?.filter(script => script.enabled && (!selectedName || script.name === selectedName))
  .map(({ id, name, content }) => ({ id, name, content })) ?? [];
if (!scripts.length) throw Error('No matching enabled scripts');
const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const [template, globals, chat, host, mvu, zod] = await Promise.all([
  read('../../app/build/compat-host.html'),
  read('../../app/src/main/assets/compat/globals.js'),
  read('../../app/src/main/assets/compat/chat.js'),
  read('../../app/src/main/assets/compat/host.js'),
  read('../../app/src/main/assets/compat/mvu.js'),
  read('../../app/src/main/assets/compat/mvu-zod.js'),
]);
const safeScripts = JSON.stringify(scripts).replace(/<\/script/gi, '<\\/script');
const module = `<script type="module">await window.__tellevLoadScripts(${safeScripts});
  window.__tellevReady().then(() => tellevNative.extensionReady())
    .catch(error => tellevNative.extensionFailed(String(error.stack || error)));
  </script>`;
const html = (template.includes("frame-src 'self' blob:;") ? template :
  template.replace("font-src https: data:;", "font-src https: data:; frame-src 'self' blob:;"))
  .replaceAll('__EXTENSION_ID__', 'character-card-browser-replay')
  .replaceAll('__TOKEN__', 'test-token')
  .replaceAll('__EJS_SETTINGS_JSON__', '{}')
  .replaceAll('__TAVERN_HELPER_SETTINGS_JSON__', '{}')
  .replace('__SHOWDOWN_SOURCE__', '')
  .replace('__HOST_ADAPTER__', () => `${chat}\n${host}`)
  .replace('__EXTENSION_SCRIPT__', () => module);

const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  const context = await browser.newContext();
  await context.addInitScript(character => {
    const first = character.first_mes ?? '';
    const chat = [{ name: character.name, mes: first, is_user: false, is_system: false,
      swipe_id: 0, swipes: [first], variables: [] }];
    window.__testReady = false;
    window.__testFailure = null;
    window.__nativeErrors = [];
    window.tellevNative = new Proxy({
      getSettings: () => '{}',
      stGetContext: () => JSON.stringify({ chat, chatId: 'browser-replay', name2: character.name,
        name1: 'User', characterId: 'browser-replay' }),
      stGetVariablesForScope: () => '{}', stGetAllVariables: () => '{}',
      stGetMessageVariables: () => '{}',
      apiCall: requestId => queueMicrotask(() => window.Tellev?.onApiResponse(
        requestId, 200, JSON.stringify({ pkgVersion: '1.18.0' }))),
      log: (level, message) => { if (level === 'error') window.__nativeErrors.push(String(message)); },
      extensionReady: () => { window.__testReady = true; },
      extensionFailed: message => { window.__testFailure = message; },
    }, { get: (target, name) => target[name] ?? (() => {}) });
  }, { name: card.name, first_mes: card.first_mes });
  await context.route('**/*', route => {
    const url = route.request().url();
    if (url === 'https://extensions.tellev.local/compat/globals.js') {
      return route.fulfill({ status: 200, contentType: 'application/javascript', body: globals,
        headers: { 'access-control-allow-origin': '*' } });
    }
    if (url.includes('/MagicalAstrogy/MagVarUpdate/artifact/bundle.js')) {
      return route.fulfill({ status: 200, contentType: 'application/javascript', body: mvu,
        headers: { 'access-control-allow-origin': '*' } });
    }
    if (url.includes('/StageDog/tavern_resource/dist/util/mvu_zod.js')) {
      return route.fulfill({ status: 200, contentType: 'application/javascript', body: zod,
        headers: { 'access-control-allow-origin': '*' } });
    }
    if (url.startsWith('https://e-') && url.endsWith('.extensions.tellev.local/')) {
      return route.fulfill({ status: 200, contentType: 'text/html', body: html });
    }
    if (new URL(url).hostname.endsWith('jsdelivr.net')) return route.continue();
    return route.abort();
  });
  const page = await context.newPage();
  const browserErrors = [];
  page.on('pageerror', error => browserErrors.push(error.stack ?? String(error)));
  await page.goto('https://e-0000000000000000000000000000000000000003.extensions.tellev.local/');
  await page.waitForFunction(() => window.__testReady || window.__testFailure, null, { timeout: 60000 });
  if (!selectedName) {
    await page.evaluate(async () => {
      await window.__tellevDispatch('app_initialized', '{}');
      await window.__tellevDispatch('app_ready', '{}');
    });
    await page.waitForTimeout(500);
  }
  const result = await page.evaluate(() => ({
    ready: window.__testReady, failure: window.__testFailure,
    frames: [...document.querySelectorAll('#tavern_helper iframe')].map(frame => frame.id),
    storageAvailable: Boolean(window.localStorage),
    mvuReady: Boolean(window.Mvu?.events),
    cardUi: [...document.body.children].filter(element => element.tagName !== 'SCRIPT' && element.id !== 'tavern_helper')
      .map(element => ({ tag: element.tagName, id: element.id, childFrames: element.querySelectorAll('iframe').length })),
    nativeErrors: window.__nativeErrors.slice(0, 10),
  }));
  console.log(JSON.stringify({ card: card.name, selectedName: selectedName ?? 'all', result,
    browserErrors: browserErrors.slice(0, 10) }, null, 2));
  if (!result.ready || result.failure || result.nativeErrors.length || browserErrors.length ||
      (expectMvu && !result.mvuReady) ||
      (expectedUi && !result.cardUi.some(element => element.id === expectedUi))) process.exitCode = 1;
} finally {
  await browser.close();
}
