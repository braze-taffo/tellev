import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../../app/src/main/java/app/tellev/feature/chat/ChatWebViewPanel.kt', import.meta.url), 'utf8');
const script = source.split('internal fun tavernResizeScript()')[1].split('"""')[1];

function measure(height, viewport, children = []) {
  const posted = [];
  const body = { offsetHeight: height, scrollHeight: Math.max(height, viewport), children,
    getBoundingClientRect: () => ({ top: 0, height }) };
  const document = { body, documentElement: { offsetHeight: viewport, scrollHeight: viewport }, addEventListener() {} };
  const window = { addEventListener() {}, getComputedStyle: child => child.style,
    TellevBridge: { resize: h => posted.push(h) } };
  vm.runInNewContext(script, { window, document, setTimeout() {}, clearTimeout() {} });
  return posted[0];
}

test('expanded viewport does not become the minimum collapsed height', () => {
  assert.equal(measure(864, 500), 864);
  assert.equal(measure(64, 500), 64);
  assert.equal(measure(864, 64), 864);
});

test('hidden or fixed decorations do not enlarge the content panel', () => {
  const decoration = { style: { display: 'block', position: 'fixed' }, getBoundingClientRect: () => ({ bottom: 900 }) };
  assert.equal(measure(64, 500, [decoration]), 64);
});

const installTemplate = source.split('internal fun tavernPanelInstallScript(')[1].split('"""')[1];
function install(window, document, height = 500) {
  const code = installTemplate.replaceAll('$quotedId', JSON.stringify('current'))
    .replaceAll('$viewportHeight', String(height))
    .replace('$script', 'window.installCount = (window.installCount || 0) + 1;');
  vm.runInNewContext(code, { window, document });
}

test('layout installation waits for the current DOM and runs once per viewport without waiting for remote resources', () => {
  const window = { __tellevDocumentId: 'old' };
  const document = { readyState: 'complete' };
  install(window, document);
  assert.equal(window.installCount, undefined);
  window.__tellevDocumentId = 'current';
  document.readyState = 'loading';
  install(window, document);
  assert.equal(window.installCount, undefined);
  document.readyState = 'interactive';
  install(window, document);
  document.readyState = 'complete';
  install(window, document);
  assert.equal(window.installCount, 1);
  install(window, document, 700);
  assert.equal(window.installCount, 2);
});
