import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

// Browser check for the actual Android host template plus authored adapters.
// Run after the JVM test exports app/build/compat-host.html.
const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const [template, globals, chat, host] = await Promise.all([
  read('../../app/build/compat-host.html'),
  read('../../app/src/main/assets/compat/globals.js'),
  read('../../app/src/main/assets/compat/chat.js'),
  read('../../app/src/main/assets/compat/host.js'),
]);
assert.match(template, /__HOST_ADAPTER__/);
const scripts = [
  {
    id: 'storage', name: 'Storage',
    content: `localStorage.setItem('card.sound', 'on');
      sessionStorage.setItem('card.session', 'ok');
      parent.document.body.insertAdjacentHTML('beforeend', '<main id="card-ui">visible</main>');
      window.probe = { id: getScriptId(), name: getScriptName(), frameId: frameElement.id,
        parentIsSeparate: parent !== window, sound: localStorage.getItem('card.sound'),
        parentRegistrations: $('#tavern_helper').find('div[data-script-id]').length };`,
  },
  {
    id: 'listener', name: 'Listener',
    content: `window.probe = { id: getScriptId(), frameId: frameElement.id };
      eventOn('app_ready', () => { parent.document.querySelector('#card-ui').dataset.ready = 'true'; });`,
  },
];
const module = `<script type="module">await window.__tellevLoadScripts(${JSON.stringify(scripts)});
  window.__tellevReady().then(() => tellevNative.extensionReady())
    .catch(error => tellevNative.extensionFailed(String(error.stack || error)));
  </script>`;
const html = (template.includes("frame-src 'self' blob:;") ? template :
  template.replace("font-src https: data:;", "font-src https: data:; frame-src 'self' blob:;"))
  .replaceAll('__EXTENSION_ID__', 'character-iframe-probe')
  .replaceAll('__TOKEN__', 'test-token')
  .replaceAll('__EJS_SETTINGS_JSON__', '{}')
  .replaceAll('__TAVERN_HELPER_SETTINGS_JSON__', '{}')
  .replace('__SHOWDOWN_SOURCE__', '')
  .replace('__HOST_ADAPTER__', () => `${chat}\n${host}`)
  .replace('__EXTENSION_SCRIPT__', () => module);
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  const context = await browser.newContext();
  await context.addInitScript(() => {
    window.__testReady = false;
    window.__testFailure = null;
    window.tellevNative = new Proxy({
      getSettings: () => '{}',
      stGetContext: () => JSON.stringify({ chat: [], chatId: 'probe' }),
      stGetVariablesForScope: () => '{}',
      stGetAllVariables: () => '{}',
      stGetMessageVariables: () => '{}',
      log: () => {},
      extensionReady: () => { window.__testReady = true; },
      extensionFailed: message => { window.__testFailure = message; },
    }, { get: (target, name) => target[name] ?? (() => {}) });
  });
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/compat/globals.js') {
      return route.fulfill({ status: 200, contentType: 'application/javascript', body: globals,
        headers: { 'access-control-allow-origin': '*' } });
    }
    if (url.pathname === '/') return route.fulfill({ status: 200, contentType: 'text/html', body: html });
    if (url.pathname === '/isolation') return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>isolation</title>' });
    // Served without CORS headers so its top-level throw reaches the host
    // document's window.onerror as an opaque cross-origin "Script error.".
    if (url.hostname === 'remote-fixtures.invalid' && url.pathname === '/throwing.js') {
      return route.fulfill({ status: 200, contentType: 'application/javascript', body: "throw new Error('remote bootstrap failure');" });
    }
    return route.abort();
  });
  const page = await context.newPage();
  const browserErrors = [];
  page.on('pageerror', error => browserErrors.push(String(error)));
  await page.goto('https://e-0000000000000000000000000000000000000001.extensions.tellev.local/');
  await page.waitForFunction(() => window.__testReady || window.__testFailure, null, { timeout: 15000 });
  const result = await page.evaluate(() => ({
    failed: window.__testFailure,
    ui: document.querySelector('#card-ui')?.textContent,
    frames: [...document.querySelectorAll('#tavern_helper iframe')].map(frame => frame.contentWindow.probe),
    registrations: [...document.querySelectorAll('#tavern_helper div[data-script-id]')]
      .map(entry => entry.dataset.scriptId),
    childSession: document.querySelector('#TH-script--Listener--listener')?.contentWindow.sessionStorage.getItem('card.session'),
    sound: localStorage.getItem('card.sound'),
  }));
  assert.equal(result.failed, null, JSON.stringify({ result, browserErrors }));
  assert.equal(result.ui, 'visible');
  assert.deepEqual(result.frames, [
    { id: 'storage', name: 'Storage', frameId: 'TH-script--Storage--storage', parentIsSeparate: true,
      sound: 'on', parentRegistrations: 2 },
    { id: 'listener', frameId: 'TH-script--Listener--listener' },
  ]);
  assert.equal(result.sound, 'on');
  assert.deepEqual(result.registrations.slice(1), ['storage', 'listener']);
  assert.equal(result.childSession, 'ok');
  const failure = await page.evaluate(async () => {
    try {
      await window.__tellevLoadScripts([{ id: 'broken', name: 'Broken card script',
        content: "throw new Error('fixture module failure')" }]);
    } catch (error) {
      return { reported: window.__testFailure, rejected: String(error) };
    }
  });
  assert.match(failure.reported, /Broken card script:.*fixture module failure/);
  assert.match(failure.rejected, /Broken card script:.*fixture module failure/);
  // A remote script a card injects SPreset-style must not poison the load
  // when it throws: cross-origin errors reach window.onerror as an opaque
  // "Script error." and are downgraded to a log entry.
  await page.evaluate(() => { window.__testFailure = null; });
  const remote = await page.evaluate(async () => {
    await window.__tellevLoadScripts([{ id: 'spreset-like', name: 'Remote bootstrap',
      content: "const s=document.createElement('script');s.src='https://remote-fixtures.invalid/throwing.js';document.body.appendChild(s);" }]);
    return 'loaded';
  });
  assert.equal(remote, 'loaded');
  await page.waitForTimeout(800);
  assert.equal(await page.evaluate(() => window.__testFailure), null);
  const sameOrigin = await context.newPage();
  await sameOrigin.goto('https://e-0000000000000000000000000000000000000001.extensions.tellev.local/isolation');
  assert.equal(await sameOrigin.evaluate(() => localStorage.getItem('card.sound')), 'on');
  const second = await context.newPage();
  await second.goto('https://e-0000000000000000000000000000000000000002.extensions.tellev.local/isolation');
  assert.equal(await second.evaluate(() => localStorage.getItem('card.sound')), null);
  console.log('Character iframe and Web Storage browser probe passed');
} finally {
  await browser.close();
}
