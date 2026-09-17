import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// Execute the shipped JS, with layout/touch inputs controlled independently.
const source = readFileSync(new URL('../../app/src/main/java/app/tellev/feature/chat/TavernMessageCompat.kt', import.meta.url), 'utf8');
const script = source.split('internal fun tavernMessageLayoutScript')[1].split('"""')[1]
  .replace('${nativeViewportHeight.coerceAtLeast(0)}', '400');

function fixture(density = 1) {
  const listeners = {}, deltas = [], flings = [];
  const style = { setProperty() {} };
  const root = { style, scrollTop: 0, clientHeight: 400, scrollHeight: 400 };
  const body = { ...root, parentElement: root, children: [], querySelector: () => null };
  const owner = { nodeType: 1, style, parentElement: body, scrollTop: 100, clientHeight: 100, scrollHeight: 200 };
  const document = {
    body, documentElement: root, scrollingElement: root,
    addEventListener: (name, callback) => { listeners[name] = callback; },
  };
  const window = {
    devicePixelRatio: density, innerHeight: 400,
    getComputedStyle: () => ({ overflowY: 'auto', display: 'block' }),
    requestAnimationFrame() {}, scrollTo() {},
  };
  vm.runInNewContext(script, {
    window, document, setTimeout() {},
    TellevBridge: { setNestedScrollGesture() {}, forwardBoundaryDrag: d => deltas.push(d), forwardBoundaryFling: v => flings.push(v) },
  });
  let time = 0;
  const touch = (name, screenY, clientY = screenY, elapsed = 16) => {
    time += elapsed;
    let prevented = false;
    listeners[name]({ type: name, timeStamp: time, target: owner, touches: [{ screenY, clientY }], cancelable: true,
      preventDefault() { prevented = true; } });
    return prevented;
  };
  return { owner, body, root, deltas, flings, touch };
}

test('edge drag has the same physical distance at every screen density', () => {
  for (const density of [1, 2, 3]) {
    const f = fixture(density);
    f.touch('touchstart', 100);
    assert.equal(f.touch('touchmove', 100 - 30 / density), true);
    assert.ok(Math.abs(f.deltas[0] - 30) < 0.0001);
  }
});

test('moving WebView does not amplify the next touch delta', () => {
  const f = fixture(3);
  f.touch('touchstart', 100, 100);
  f.touch('touchmove', 90, 90);
  f.touch('touchmove', 80, 110); // view moved up while finger kept moving up
  assert.deepEqual(f.deltas, [30, 30]);
});

test('inner scrolling and scrollable ancestors retain the gesture', () => {
  const f = fixture();
  f.owner.scrollTop = 50;
  f.touch('touchstart', 100);
  assert.equal(f.touch('touchmove', 90), false);
  f.owner.scrollTop = 100;
  f.root.scrollHeight = 800;
  assert.equal(f.touch('touchmove', 80), false);
  assert.deepEqual(f.deltas, []);
  f.root.scrollHeight = 400;
  const parent = { ...f.owner, scrollTop: 50, parentElement: f.body };
  f.owner.parentElement = parent;
  assert.equal(f.touch('touchmove', 70), false);
  assert.deepEqual(f.deltas, []);
});

test('top edge forwards downward motion and ended touches stop forwarding', () => {
  const f = fixture(2);
  f.owner.scrollTop = 0;
  f.touch('touchstart', 100);
  f.touch('touchmove', 115);
  f.touch('touchend', 115);
  f.touch('touchmove', 130);
  assert.deepEqual(f.deltas, [-30]);
});

test('edge release transfers physical velocity but cancel and a held finger do not fling', () => {
  const f = fixture(3);
  f.touch('touchstart', 100);
  f.touch('touchmove', 80);
  f.touch('touchmove', 60);
  f.touch('touchend', 60);
  assert.equal(f.flings.length, 1);
  assert.equal(f.flings[0], 2500);
  for (const [ending, elapsed] of [['touchcancel', 16], ['touchend', 200]]) {
    f.touch('touchstart', 100);
    f.touch('touchmove', 60);
    f.touch(ending, 60, 60, elapsed);
    assert.equal(f.flings.length, 1);
  }
});

test('release inside scrollable content leaves inertia to WebView', () => {
  const f = fixture();
  f.owner.scrollTop = 20;
  f.touch('touchstart', 100);
  f.touch('touchmove', 60);
  f.touch('touchend', 60);
  assert.deepEqual(f.flings, []);
});
