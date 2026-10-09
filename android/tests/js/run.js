// Tests for app/src/main/assets/keep.js and ua.js in a simulated browser (jsdom).
// Run: NODE_PATH=$(npm root -g) node tests/js/run.js
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const assets = path.join(__dirname, '../../app/src/main/assets');
const KEEP = fs.readFileSync(path.join(assets, 'keep.js'), 'utf8');
const UA_TPL = fs.readFileSync(path.join(assets, 'ua.js'), 'utf8');

let passed = 0, failed = 0;
const tests = [];
function test(name, fn) { tests.push([name, fn]); }
function eq(a, b, msg) { if (a !== b) throw new Error((msg || '') + ` expected ${JSON.stringify(b)} got ${JSON.stringify(a)}`); }
function ok(v, msg) { if (!v) throw new Error(msg || 'expected truthy'); }
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** A page with fake fullscreen support and a controllable clock. */
function page(html) {
  const dom = new JSDOM(html || '<!doctype html><html><head></head><body><div id="game"></div><input id="i"></body></html>',
    { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  const st = { now: 1_000_000, fsEl: null, exitCalls: 0, reqCalls: 0 };
  w.Date.now = () => st.now;
  Object.defineProperty(w.Document.prototype, 'fullscreenElement', { configurable: true, get: () => st.fsEl });
  w.Document.prototype.exitFullscreen = function () { st.exitCalls++; st.fsEl = null; return Promise.resolve(); };
  w.Element.prototype.requestFullscreen = function () { st.reqCalls++; st.fsEl = this; return Promise.resolve(); };
  return { w, d: w.document, st };
}
function uaScript(ua, desktop) {
  return UA_TPL.replace('__UA__', ua == null ? 'null' : JSON.stringify(ua).replace(/\//g, '\\/'))   // like Java's JSONObject.quote
               .replace('__DESKTOP__', desktop ? 'true' : 'false');
}

// ------------------------------------------------------------------ keep.js
test('page never hears it went to the background', () => {
  const { w, d } = page();
  w.eval(KEEP);
  let heard = 0;
  d.addEventListener('visibilitychange', () => heard++);
  w.addEventListener('visibilitychange', () => heard++);
  d.addEventListener('webkitvisibilitychange', () => heard++);
  d.addEventListener('freeze', () => heard++);
  w.addEventListener('blur', () => heard++);
  d.dispatchEvent(new w.Event('visibilitychange', { bubbles: true }));
  d.dispatchEvent(new w.Event('webkitvisibilitychange', { bubbles: true }));
  d.dispatchEvent(new w.Event('freeze', { bubbles: true }));
  w.dispatchEvent(new w.FocusEvent('blur'));
  eq(heard, 0, 'listeners called');
  eq(d.hidden, false); eq(d.visibilityState, 'visible'); eq(d.hasFocus(), true);
});

test('normal input blur still works', () => {
  const { w, d } = page();
  w.eval(KEEP);
  let blurred = 0;
  d.getElementById('i').addEventListener('blur', () => blurred++);
  d.getElementById('i').dispatchEvent(new w.FocusEvent('blur'));
  eq(blurred, 1);
});

test('page cannot leave fullscreen right around an app switch', async () => {
  const { w, d, st } = page();
  w.eval(KEEP);
  st.fsEl = d.getElementById('game');
  w.__fsKeepSetBg(true);                  // app went to background
  const p = d.exitFullscreen();
  ok(p && typeof p.then === 'function', 'returns a promise');
  eq(st.exitCalls, 0, 'real exit blocked');
  st.now += 60_000;                       // back much later...
  w.__fsKeepSetBg(false);                 // ...app returns
  d.exitFullscreen();
  eq(st.exitCalls, 0, 'still blocked right after returning');
  st.now += 5_000;                        // normal use later
  await d.exitFullscreen();
  eq(st.exitCalls, 1, 'your own exit works');
});

test('lost fullscreen is restored on the first tap after a switch', async () => {
  const { w, d, st } = page();
  w.eval(KEEP);
  const game = d.getElementById('game');
  st.fsEl = game; d.dispatchEvent(new w.Event('fullscreenchange'));
  w.__fsKeepSetBg(true);
  st.fsEl = null; d.dispatchEvent(new w.Event('fullscreenchange'));   // system forced it out
  await sleep(900);
  d.body.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  eq(st.reqCalls, 1, 'requested again');
  eq(st.fsEl, game, 'same element');
  await sleep(10);
  d.body.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  eq(st.reqCalls, 1, 'no repeat once restored');
});

test('fullscreen you left on purpose is not restored', async () => {
  const { w, d, st } = page();
  w.eval(KEEP);
  st.fsEl = d.getElementById('game'); d.dispatchEvent(new w.Event('fullscreenchange'));
  st.now += 10_000;
  st.fsEl = null; d.dispatchEvent(new w.Event('fullscreenchange'));
  await sleep(900);
  d.body.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  eq(st.reqCalls, 0);
});

test('exit build: never re-enters fullscreen after a switch', async () => {
  const { w, d, st } = page();
  w.eval('window.__fsNoRestore=true;' + KEEP);
  st.fsEl = d.getElementById('game'); d.dispatchEvent(new w.Event('fullscreenchange'));
  w.__fsKeepSetBg(true);
  st.fsEl = null; d.dispatchEvent(new w.Event('fullscreenchange'));
  await sleep(900);
  d.body.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  eq(st.reqCalls, 0);
});

test('Escape key never re-enters fullscreen', async () => {
  const { w, d, st } = page();
  w.eval(KEEP);
  st.fsEl = d.body; d.dispatchEvent(new w.Event('fullscreenchange'));
  w.__fsKeepSetBg(true);
  st.fsEl = null; d.dispatchEvent(new w.Event('fullscreenchange'));
  await sleep(900);
  w.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'Escape' }));
  eq(st.reqCalls, 0);
});

test('running twice is harmless', () => {
  const { w, d } = page();
  w.eval(KEEP); w.eval(KEEP);
  eq(d.hidden, false);
  eq(typeof w.__fsKeepSetBg, 'function');
});

// ------------------------------------------------------------------ ua.js
const CHROME_WIN = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36';
const EDGE = CHROME_WIN + ' Edg/154.0.0.0';
const FIREFOX = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0';
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1';
const ANDROID = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36';

test('Chrome on Windows', async () => {
  const { w } = page();
  w.eval(uaScript(CHROME_WIN, true));
  const n = w.navigator;
  eq(n.userAgent, CHROME_WIN); eq(n.platform, 'Win32'); eq(n.vendor, 'Google Inc.');
  eq(n.appVersion, CHROME_WIN.slice(8));
  eq(n.userAgentData.mobile, false); eq(n.userAgentData.platform, 'Windows');
  ok(n.userAgentData.brands.some(b => b.brand === 'Google Chrome' && b.version === '154'));
  const hi = await n.userAgentData.getHighEntropyValues(['platformVersion']);
  eq(hi.platformVersion, '19.0.0'); eq(hi.uaFullVersion, '154.0.0.0');
  eq(JSON.parse(JSON.stringify(n.userAgentData)).platform, 'Windows');
});

test('Edge brand', () => {
  const { w } = page(); w.eval(uaScript(EDGE, true));
  ok(w.navigator.userAgentData.brands.some(b => b.brand === 'Microsoft Edge'));
});

test('Firefox has no userAgentData and no vendor', () => {
  const { w } = page();
  Object.defineProperty(w.Navigator.prototype, 'userAgentData', { configurable: true, get: () => ({ brands: [] }) });
  w.eval(uaScript(FIREFOX, true));
  eq(w.navigator.vendor, ''); eq(w.navigator.platform, 'Win32');
  ok(!('userAgentData' in w.navigator), 'userAgentData removed');
});

test('Opera reports its own version', () => {
  const OPERA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 OPR/139.0.0.0';
  const { w } = page(); w.eval(uaScript(OPERA, true));
  const b = w.navigator.userAgentData.brands;
  ok(b.some(x => x.brand === 'Opera' && x.version === '139'), 'Opera 139');
  ok(b.some(x => x.brand === 'Chromium' && x.version === '153'), 'Chromium 153');
});

test('iPhone Safari', () => {
  const { w } = page(); w.eval(uaScript(IPHONE, false));
  eq(w.navigator.platform, 'iPhone'); eq(w.navigator.vendor, 'Apple Computer, Inc.');
});

test('Android Chrome is mobile', () => {
  const { w } = page(); w.eval(uaScript(ANDROID, false));
  eq(w.navigator.userAgentData.mobile, true); eq(w.navigator.userAgentData.platform, 'Android');
  eq(w.navigator.platform, 'Linux armv81');
});

test('desktop layout rewrites the mobile viewport', async () => {
  const { w, d } = page('<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body></body></html>');
  w.eval(uaScript(CHROME_WIN, true));
  d.dispatchEvent(new w.Event('DOMContentLoaded'));
  eq(d.querySelector('meta').getAttribute('content'), 'width=1280');
});

test('layout only (no user-agent) leaves navigator alone', () => {
  const { w, d } = page('<!doctype html><html><head><meta name="VIEWPORT" content="width=device-width"></head><body></body></html>');
  const before = w.navigator.userAgent;
  w.eval(uaScript(null, true));
  d.dispatchEvent(new w.Event('DOMContentLoaded'));
  eq(w.navigator.userAgent, before);
  eq(d.querySelector('meta').getAttribute('content'), 'width=1280', 'case-insensitive name');
});

test('no desktop layout when off', () => {
  const { w, d } = page('<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body></body></html>');
  w.eval(uaScript(CHROME_WIN, false));
  d.dispatchEvent(new w.Event('DOMContentLoaded'));
  eq(d.querySelector('meta').getAttribute('content'), 'width=device-width');
});

test('quotes in a custom user-agent cannot break the script', () => {
  const evil = 'Weird "UA" \' </script> \\ \u2028 end';
  const { w } = page(); w.eval(uaScript(evil, false));
  eq(w.navigator.userAgent, evil);
});

(async () => {
  for (const [name, fn] of tests) {
    try { await fn(); passed++; console.log('  ok   ' + name); }
    catch (e) { failed++; console.log('  FAIL ' + name + ' -> ' + e.message); }
  }
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})();
