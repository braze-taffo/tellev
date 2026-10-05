import { chromium } from 'playwright-core';
import { readFile } from 'node:fs/promises';
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';

const repo = new URL('../../', import.meta.url);
const panel = await readFile(new URL('app/src/main/java/app/tellev/feature/chat/ChatWebViewPanel.kt', repo), 'utf8');
const compat = await readFile(new URL('app/src/main/java/app/tellev/feature/chat/TavernMessageCompat.kt', repo), 'utf8');
const hostStyle = panel.match(/<style id="tellev-host-style">[\s\S]*?<\/style>/)[0]
  .replace('$themeVariables', '--SmartThemeBodyColor: #222;').replace('$dialogueQuoteCss', '').replace('$fontSizeCss', '');
const layout = compat.split('internal fun tavernMessageLayoutScript')[1].split('"""')[1]
  .replace('${nativeViewportHeight.coerceAtLeast(0)}', '420');
const resize = panel.split('internal fun tavernResizeScript()')[1].split('"""')[1];
let browser;
before(async () => { browser = await chromium.launch({ channel: process.env.TELLEV_BROWSER_CHANNEL || 'msedge', headless: true }); });
after(async () => { await browser?.close(); });

async function open(html, density = 1) {
  const page = await browser.newPage({ viewport: { width: 384, height: 420 }, deviceScaleFactor: density });
  // Authored fonts/assets are irrelevant to geometry; keep this check offline.
  await page.route('https://**', route => route.abort());
  await page.setContent(html.replace(/<head>/i, `<head>${hostStyle}<meta name="viewport" content="width=device-width,initial-scale=1">`));
  await page.evaluate(() => {
    window.boundary = []; window.owners = [];
    window.TellevBridge = {
      resize(h) { window.measured = h; },
      setNestedScrollGesture(value) { window.owners.push(value); },
      forwardBoundaryDrag(value) { window.boundary.push(value); },
      forwardBoundaryFling() {},
    };
  });
  return page;
}

async function geometry(page, selector = '#card') {
  return page.evaluate(selector => {
    const card = document.querySelector(selector), box = card.getBoundingClientRect();
    const style = getComputedStyle(document.body);
    return { top: box.top, left: box.left, height: box.height, bottom: box.bottom,
      align: style.alignItems, justify: style.justifyContent, bodyHeight: document.body.clientHeight,
      bodyRectHeight: document.body.getBoundingClientRect().height,
      bodyScrollHeight: document.body.scrollHeight, measured: window.measured };
  }, selector);
}

test('oversized row and column cards start at the top while horizontal centering is retained', async () => {
  for (const direction of ['row', 'row-reverse', 'column', 'column-reverse']) {
    const html = `<!doctype html><html><head><style>
      body { display:flex; flex-direction:${direction}; align-items:center; justify-content:center; height:100vh; padding:10px; }
      #card { width:280px; min-height:900px; flex-shrink:0; }
      </style></head><body><main id="card">Long content</main></body></html>`;
    const page = await open(html);
    try {
      assert.ok((await geometry(page)).top < 0, `${direction}: fixture must reproduce clipping`);
      await page.evaluate(layout + ';' + resize);
      const result = await geometry(page);
      assert.ok(result.top >= 0, JSON.stringify({ direction, result }));
      assert.ok(Math.abs(result.left - 52) < 1, `${direction}: horizontal alignment changed`);
      assert.ok(result.measured >= 900);
    } finally { await page.close(); }
  }
});

test('a short centered card keeps its authored alignment', async () => {
  const page = await open(`<html><head><style>body { display:flex; align-items:center; justify-content:center; min-height:100vh; }
    #card { width:200px; height:80px; }</style></head><body><div id="card">Short</div></body></html>`);
  try {
    const before = await geometry(page);
    await page.evaluate(layout + ';' + resize);
    const result = await geometry(page);
    assert.equal(result.top, before.top);
    assert.equal(result.left, before.left);
    assert.equal(result.align, 'center');
    assert.equal(result.justify, 'center');
  } finally { await page.close(); }
});

test('a quirks document collapsed to padding gets a usable viewport for its long card', async () => {
  const page = await open(`<html><head><style>
    html { height:20px; } body { display:flex; align-items:center; justify-content:center; padding:10px; min-height:0; }
    #card { width:280px; min-height:900px; flex-shrink:0; }
    </style></head><body><div id="card">Long</div></body></html>`);
  try {
    assert.ok((await geometry(page)).bodyRectHeight <= 20);
    await page.evaluate(layout + ';' + resize);
    const result = await geometry(page);
    assert.equal(result.bodyHeight, 420);
    assert.ok(result.top >= 0);
    assert.ok(result.bodyScrollHeight >= 900);
  } finally { await page.close(); }
});

test('ordinary body layout and authored large minimum heights are retained', async () => {
  for (const style of ['display:block', 'display:flex;align-items:flex-start;justify-content:center;min-height:600px']) {
    const page = await open(`<html><head><style>body { ${style} } #card { width:280px; height:900px; }</style></head>
      <body><div id="card">Content</div></body></html>`);
    try {
      const before = await geometry(page);
      await page.evaluate(layout + ';' + resize);
      const result = await geometry(page);
      assert.equal(result.top, before.top);
      if (style.includes('600px')) assert.ok(result.bodyRectHeight >= 600);
      assert.equal(await page.evaluate(() => document.documentElement.style.height), '');
    } finally { await page.close(); }
  }
});

test('collapsible flex cards still measure their content after repeated expand and collapse', async () => {
  const page = await open(`<!doctype html><html><head><style>
    body { display:flex;align-items:flex-start;justify-content:center; }
    #card { width:100%; overflow:hidden; } #header { height:64px; }
    #content { display:grid;grid-template-rows:0fr;transition:grid-template-rows .2s; }
    #card.expanded #content { grid-template-rows:1fr; }
    #clip { overflow:hidden;min-height:0; } #text { height:800px; }
    </style></head><body><div id="card"><div id="header">Toggle</div>
    <div id="content"><div id="clip"><div id="text">Content</div></div></div></div></body></html>`);
  try {
    await page.evaluate(layout + ';' + resize);
    await page.waitForFunction(() => window.measured >= 62 && window.measured <= 68);
    for (let i = 0; i < 3; i++) {
      await page.evaluate(() => document.querySelector('#card').classList.add('expanded'));
      await page.waitForFunction(() => window.measured >= 860);
      await page.evaluate(() => document.querySelector('#card').classList.remove('expanded'));
      await page.waitForFunction(() => window.measured >= 62 && window.measured <= 68);
    }
  } finally { await page.close(); }
});

test('bounded active screens retain their own scroll container', async () => {
  const page = await open(`<html><head><style>body { display:flex; align-items:center; justify-content:center; }
    .screen { width:100%; overflow:auto; } #card { height:900px; }</style></head><body>
    <main class="screen active"><div id="card">Page</div></main></body></html>`);
  try {
    await page.evaluate(layout + ';' + resize);
    const result = await page.evaluate(() => ({ body: document.body.offsetHeight,
      screen: document.querySelector('.screen').clientHeight,
      align: getComputedStyle(document.body).alignItems,
      overflow: getComputedStyle(document.querySelector('.screen')).overflowY }));
    assert.equal(result.body, 420);
    assert.ok(result.screen <= 404);
    assert.equal(result.align, 'center');
    assert.equal(result.overflow, 'auto');
  } finally { await page.close(); }
});

test('supplied Daoyuan greeting is fully reachable and its body forwards only boundary drags',
  { skip: !process.env.TELLEV_CARD_FIXTURE }, async () => {
    const raw = JSON.parse(await readFile(process.env.TELLEV_CARD_FIXTURE, 'utf8'));
    const card = raw.data ?? raw;
    assert.match(card.name, /道渊/);
    const html = card.first_mes.replace(/^\s*```html\s*/, '').replace(/\s*```\s*$/, '');
    const page = await open(html, 3);
    try {
      const before = await geometry(page, '.info-card');
      assert.ok(before.top < 0, 'The original card must reproduce clipping');
      await page.evaluate(layout + ';' + resize);
      const result = await geometry(page, '.info-card');
      assert.ok(result.top >= 0 && result.top < 24, JSON.stringify(result));
      assert.ok(result.measured >= result.height);
      assert.equal(result.justify, 'center');
      assert.ok(result.bodyScrollHeight > result.bodyHeight);
      await page.mouse.move(180, 200);
      await page.mouse.wheel(0, 600);
      await page.waitForFunction(() => document.body.scrollTop > 100);
      await page.evaluate(() => {
        const emit = (name, y) => {
          const event = new Event(name, { bubbles: true, cancelable: true });
          Object.defineProperty(event, 'touches', { value: [{ screenY: y }] });
          document.querySelector('.info-card').dispatchEvent(event);
        };
        document.body.scrollTop = 100;
        emit('touchstart', 100); emit('touchmove', 80);
        window.middleBoundary = window.boundary.slice();
        document.body.scrollTop = document.body.scrollHeight;
        emit('touchmove', 60); emit('touchend', 60);
      });
      const gestures = await page.evaluate(() => ({ owners: window.owners, middle: window.middleBoundary, end: window.boundary }));
      assert.deepEqual(gestures.owners, [true]);
      assert.deepEqual(gestures.middle, []);
      assert.deepEqual(gestures.end, [60]);
    } finally { await page.close(); }
  });
