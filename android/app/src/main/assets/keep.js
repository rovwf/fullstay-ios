/* FullStay: make the page believe the app never went to the background. */
(function () {
  'use strict';
  if (window.__fsKeep) return;
  try { Object.defineProperty(window, '__fsKeep', { value: true }); } catch (e) { window.__fsKeep = true; }

  var D = Document.prototype;
  var hd = Object.getOwnPropertyDescriptor(D, 'hidden');
  var hiddenGet = hd && hd.get;
  function realHidden() { try { return hiddenGet ? hiddenGet.call(document) : false; } catch (e) { return false; } }

  var lastHide = 0, lastShow = 0, wanted = null, pending = false, busy = false;
  function nearSwitch() {
    var n = Date.now();
    return realHidden() || n - lastHide < 3000 || n - lastShow < 3000;
  }
  function stop(e) { e.stopImmediatePropagation(); }

  // 1) Swallow every "you went away" signal (this listener runs before any page script).
  window.addEventListener('visibilitychange', function (e) {
    if (realHidden()) lastHide = Date.now(); else lastShow = Date.now();
    e.stopImmediatePropagation();
  }, true);
  window.addEventListener('webkitvisibilitychange', stop, true);
  window.addEventListener('freeze', stop, true);
  window.addEventListener('resume', stop, true);
  window.addEventListener('blur', function (e) {
    if (e.target === window || e.target === document) e.stopImmediatePropagation();
  }, true);

  // 2) Always report "visible" and "focused".
  function getter(obj, prop, fn) {
    try { Object.defineProperty(obj, prop, { configurable: true, enumerable: true, get: fn }); } catch (e) {}
  }
  getter(D, 'hidden', function () { return false; });
  getter(D, 'visibilityState', function () { return 'visible'; });
  if ('webkitHidden' in document) getter(D, 'webkitHidden', function () { return false; });
  if ('webkitVisibilityState' in document) getter(D, 'webkitVisibilityState', function () { return 'visible'; });
  try { D.hasFocus = function () { return true; }; } catch (e) {}

  // 3) Don't let the page leave fullscreen by itself around an app switch.
  ['exitFullscreen', 'webkitExitFullscreen'].forEach(function (name) {
    var real = D[name];
    if (typeof real !== 'function') return;
    try {
      D[name] = function () {
        if (nearSwitch()) return name === 'exitFullscreen' ? Promise.resolve() : undefined;
        return real.apply(this, arguments);
      };
    } catch (e) {}
  });

  // 4) Backup: if the system still ended fullscreen, restore it on the first tap.
  document.addEventListener('fullscreenchange', function () {
    var el = document.fullscreenElement;
    if (el) { wanted = el; pending = false; return; }
    if (!wanted) return;
    setTimeout(function () {
      if (document.fullscreenElement) return;
      if (nearSwitch()) pending = true; else wanted = null;
    }, 800);
  }, true);

  function restore(e) {
    if (window.__fsNoRestore || !pending || busy || document.fullscreenElement || realHidden()) return;
    if (e.type === 'keydown' && e.key === 'Escape') return;
    var t = wanted && wanted.isConnected ? wanted : document.documentElement;
    if (!t || typeof t.requestFullscreen !== 'function') return;
    busy = true;
    setTimeout(function () { busy = false; }, 1000);
    try {
      var p = t.requestFullscreen({ navigationUI: 'hide' });
      if (p && p.then) p.then(function () { pending = false; }, function () { busy = false; });
    } catch (x) { busy = false; }
  }
  ['pointerup', 'touchend', 'click', 'keydown'].forEach(function (t) {
    window.addEventListener(t, restore, true);
  });

  // Called by the app when it goes to the background / comes back.
  window.__fsKeepSetBg = function (bg) { if (bg) lastHide = Date.now(); else lastShow = Date.now(); };
})();
