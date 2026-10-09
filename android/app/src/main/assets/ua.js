/* FullStay User-Agent Switcher: make JavaScript agree with the spoofed user-agent. */
(function () {
  'use strict';
  var ua = __UA__;
  var desktop = __DESKTOP__;

  if (ua) {
    var N = Navigator.prototype;
    var def = function (p, v) {
      try { Object.defineProperty(N, p, { configurable: true, enumerable: true, get: function () { return v; } }); } catch (e) {}
    };
    var has = function (re) { return re.test(ua); };
    var isFx = has(/Firefox\//);
    var isIOS = has(/iPhone|iPad|iPod/);
    var chromium = !isFx && has(/Chrome\/|CriOS\//);
    var safari = !isFx && !chromium && has(/Safari\//);

    var platform = has(/Windows/) ? 'Win32'
      : has(/iPhone/) ? 'iPhone' : has(/iPad/) ? 'iPad' : has(/iPod/) ? 'iPod'
      : has(/Macintosh|Mac OS X/) ? 'MacIntel'
      : has(/Android/) ? 'Linux armv81'
      : has(/X11|Linux|CrOS/) ? 'Linux x86_64' : '';
    var vendor = isFx ? '' : (isIOS || safari) ? 'Apple Computer, Inc.' : chromium ? 'Google Inc.' : '';

    def('userAgent', ua);
    def('appVersion', ua.replace(/^Mozilla\//, ''));
    if (platform) def('platform', platform);
    def('vendor', vendor);

    var m = ua.match(/Chrome\/(\d+)/);
    if (chromium && !isIOS && m) {
      var major = m[1];
      var brand = has(/Edg(A|iOS)?\//) ? 'Microsoft Edge' : has(/OPR\//) ? 'Opera'
        : has(/SamsungBrowser\//) ? 'Samsung Internet' : 'Google Chrome';
      var own = ua.match(/(?:OPR|SamsungBrowser)\/(\d+)/);   // Opera/Samsung report their own version
      var brands = [{ brand: 'Chromium', version: major }, { brand: brand, version: own ? own[1] : major },
                    { brand: 'Not)A;Brand', version: '24' }];
      var mobile = has(/Mobile/);
      var plat = has(/Windows/) ? 'Windows' : has(/Android/) ? 'Android' : has(/CrOS/) ? 'Chrome OS'
        : has(/Macintosh/) ? 'macOS' : 'Linux';
      var low = { brands: brands, mobile: mobile, platform: plat };
      def('userAgentData', {
        brands: brands, mobile: mobile, platform: plat,
        getHighEntropyValues: function () {
          return Promise.resolve({
            brands: brands, mobile: mobile, platform: plat,
            platformVersion: plat === 'Windows' ? '19.0.0' : plat === 'macOS' ? '15.0.0' : plat === 'Android' ? '15.0.0' : '',
            architecture: plat === 'Android' ? 'arm' : 'x86', bitness: '64', model: '', wow64: false,
            uaFullVersion: major + '.0.0.0',
            fullVersionList: brands.map(function (b) { return { brand: b.brand, version: b.version + '.0.0.0' }; })
          });
        },
        toJSON: function () { return low; }
      });
    } else {
      // Firefox, Safari and others don't have navigator.userAgentData.
      try { delete N.userAgentData; } catch (e) {}
    }
  }

  // Desktop layout: ignore the page's "mobile" viewport so it renders like on a computer.
  if (desktop && window === window.top) {
    var fix = function () {
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      for (var i = 0; i < metas.length; i++) metas[i].setAttribute('content', 'width=1280');
    };
    document.addEventListener('DOMContentLoaded', fix, true);
    try {
      new MutationObserver(function (list, obs) {
        fix();
        if (document.readyState !== 'loading') obs.disconnect();
      }).observe(document, { childList: true, subtree: true });
    } catch (e) {}
  }
})();
