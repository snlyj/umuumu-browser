/*
 * 唔姆唔姆 防指纹脚本（Brave 式"每站随机但稳定"）。
 * 由 WebViewCompat.addDocumentStartJavaScript 在每个页面、每个 frame 的最开始执行（网页自己的脚本之前）。
 * 源码在这里，发布用的是 terser 压缩后的 app/src/main/assets/fingerprint.min.js（见 fp-js/build.sh）。
 *
 * __UMU_FP_CONFIG__ 由 App 替换成 JSON：
 *   k  本次会话的随机密钥（App 每次启动换一个；隐身会话单独一个）
 *   l  1 = 标准，2 = 严格（另外：时区 UTC、固定屏幕、1 ms 计时、清空语音列表、隐藏网络类型）
 *   x  用户关掉防指纹的网站（eTLD+1）
 *   p  二级公共后缀（co.uk、com.cn…），和 Kotlin 的 Domains 用同一份
 * 每个网站（按顶层页面的 eTLD+1）的种子 = fmix32(FNV-1a(k + "|" + site))，Kotlin 的 FpSeed 有同样的实现（有单元测试对照）。
 */
(function (C) {
  'use strict';
  try {
    var W = window, L = C.l | 0, STRICT = L >= 2;
    var O = Object, gopd = O.getOwnPropertyDescriptor, defp = O.defineProperty;

    // ---------- 哈希 / 伪随机 ----------
    function fnv(s) { var h = 0x811c9dc5; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); } return h >>> 0; }
    function mix(h) { h ^= h >>> 16; h = Math.imul(h, 0x85ebca6b); h ^= h >>> 13; h = Math.imul(h, 0xc2b2ae35); h ^= h >>> 16; return h >>> 0; }
    function rng(a) { return function () { a = (a + 0x6D2B79F5) | 0; var t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return (t ^ (t >>> 14)) >>> 0; }; }

    // ---------- 顶层网站（eTLD+1） ----------
    var SL = {}; (C.p || []).forEach(function (s) { SL[s] = 1; });
    function site(h) {
      h = String(h || '').toLowerCase(); if (h.charAt(h.length - 1) === '.') h = h.slice(0, -1);
      if (!h || h.indexOf(':') >= 0 || /^[0-9.]+$/.test(h)) return h;
      var p = h.split('.'); if (p.length <= 2) return h;
      var t2 = p.slice(-2).join('.');
      return SL[t2] ? p.slice(-3).join('.') : t2;
    }
    function hostOf(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }
    var topHost = '';
    try { topHost = W.top.location.hostname; } catch (e) { }
    if (!topHost) { try { var ao = W.location.ancestorOrigins; if (ao && ao.length) topHost = hostOf(ao[ao.length - 1]); } catch (e) { } }
    if (!topHost) { try { topHost = W.location.hostname; } catch (e) { } }
    var SITE = site(topHost);
    if ((C.x || []).indexOf(SITE) >= 0) return; // 用户给这个网站开了例外

    var SEED = mix(fnv(C.k + '|' + SITE));
    function sub(tag) { return mix(SEED ^ fnv(tag)); }
    var MK = Symbol.for('\u2063' + C.k); // 同一个窗口只打一次补丁（iframe 钩子 + 文档开始脚本可能都会来）

    // 画布噪声：按像素位置决定改哪个像素的哪个颜色通道的最低位，只改不透明像素。
    // 同一网站、同样大小的画布 → 同样的噪声（结果稳定）；不同网站 → 不同。
    var MAXPX = 1 << 22;
    function noiseImg(d, w, h) {
      w = w | 0; h = h | 0; var n = w * h;
      if (!d || n <= 0 || n > MAXPX || d.length < n * 4) return;
      var r = rng(SEED ^ Math.imul(w, 0x10001) ^ h ^ 0x9e3779b9);
      for (var i = r() % 23; i < n; i += 1 + (r() % 47)) {
        var j = i << 2;
        if (d[j + 3] !== 255) continue;
        d[j + (r() % 3)] ^= 1;
      }
    }
    // 音频：整体乘一个很接近 1 的系数（约 -0.0001 dB，听不出来）
    var AF = 1 - (1 + (sub('audio') % 1000) / 1000) * 1e-5;
    var AMAX = 1 << 18;
    // 文字宽度：每种字体一个极小的系数（亚像素级）
    function textFactor(font) { return 1 + (((mix(SEED ^ fnv(String(font))) % 2001) - 1000) * 1e-8); }

    // 屏幕分档
    var BW = [360, 375, 384, 390, 393, 412, 414, 430], BH = [640, 667, 720, 736, 760, 780, 800, 844, 851, 854, 873, 892, 896, 915, 926, 932];
    function near(a, v) { var b = a[0]; for (var i = 1; i < a.length; i++) if (Math.abs(a[i] - v) < Math.abs(b - v)) b = a[i]; return b; }
    var scr = null;
    function screenDims(sw, sh) {
      if (scr) return scr;
      var land = sw > sh, s = land ? sh : sw, l = land ? sw : sh, bs, bl;
      if (STRICT) { bs = s > 480 ? 800 : 360; bl = s > 480 ? 1280 : 800; }
      else if (s <= 480) { bs = near(BW, s); bl = near(BH, l); }
      else { bs = Math.max(512, Math.round(s / 64) * 64); bl = Math.max(bs, Math.round(l / 64) * 64); }
      return (scr = land ? [bl, bs] : [bs, bl]);
    }
    var LANGS = null;

    function patch(w) {
      var FP = w.Function && w.Function.prototype; if (!FP) return;
      var oTS = FP.toString;
      if (!oTS || oTS[MK]) return;
      var NM = new WeakMap(); // 假函数 -> 原函数，toString 时显示原函数的 "[native code]"
      var ts = ({ toString() { var o = NM.get(this); return oTS.call(o === undefined ? this : o); } }).toString;
      NM.set(ts, oTS);
      var d0 = gopd(FP, 'toString'); d0.value = ts; defp(FP, 'toString', d0);
      defp(ts, MK, { value: 1 });

      function P(name) { var c = w[name]; return c && c.prototype; }
      // 包装方法：impl(this, 原函数, arguments)
      function M(proto, name, impl) {
        if (!proto) return;
        var d = gopd(proto, name); if (!d || typeof d.value !== 'function') return;
        var o = d.value;
        var f = ({ [name]() { return impl(this, o, arguments); } })[name];
        defp(f, 'length', { value: o.length });
        NM.set(f, o); d.value = f; defp(proto, name, d);
        return f;
      }
      // 包装 getter：impl(this, 原 getter)
      function G(obj, name, impl) {
        if (!obj) return;
        var d = gopd(obj, name); if (!d || !d.get) return;
        var o = d.get;
        var g = gopd({ get [name]() { return impl(this, o); } }, name).get;
        NM.set(g, o); d.get = g; defp(obj, name, d);
      }
      // 固定值的 getter（先调一次原 getter，保持非法调用时同样报错）
      function V(obj, name, val) { G(obj, name, function (t, o) { var r = o.call(t); return r === undefined || r === null ? r : (typeof val === 'function' ? val(r, t) : val); }); }
      function del(obj, name) { try { if (obj && name in obj) delete obj[name]; } catch (e) { } }

      // ---------- Canvas ----------
      var C2D = P('CanvasRenderingContext2D'), OC2D = P('OffscreenCanvasRenderingContext2D');
      var oGID = C2D && C2D.getImageData, oPID = C2D && C2D.putImageData, oDraw = C2D && C2D.drawImage;
      var oGetCtx = P('HTMLCanvasElement') && P('HTMLCanvasElement').getContext;
      var oCreate = P('Document') && P('Document').createElement;
      function farbled(cv) {
        var cw = cv.width, ch = cv.height;
        if (!cw || !ch || cw * ch > MAXPX || !oCreate) return null;
        var c2 = oCreate.call(cv.ownerDocument || w.document, 'canvas');
        c2.width = cw; c2.height = ch;
        var x = oGetCtx.call(c2, '2d'); if (!x) return null;
        oDraw.call(x, cv, 0, 0);
        var id = oGID.call(x, 0, 0, cw, ch);
        noiseImg(id.data, cw, ch);
        oPID.call(x, id, 0, 0);
        return c2;
      }
      var HC = P('HTMLCanvasElement');
      ['toDataURL', 'toBlob'].forEach(function (n) {
        M(HC, n, function (t, o, a) { var c2 = null; try { c2 = farbled(t); } catch (e) { } return o.apply(c2 || t, a); });
      });
      var OCv = P('OffscreenCanvas');
      M(OCv, 'convertToBlob', function (t, o, a) {
        var src = t;
        try {
          var cw = t.width, ch = t.height;
          if (cw && ch && cw * ch <= MAXPX) {
            var c2 = new w.OffscreenCanvas(cw, ch), x = c2.getContext('2d');
            oDraw.call(x, t, 0, 0); var id = oGID.call(x, 0, 0, cw, ch); noiseImg(id.data, cw, ch); oPID.call(x, id, 0, 0); src = c2;
          }
        } catch (e) { src = t; }
        return o.apply(src, a);
      });
      [C2D, OC2D].forEach(function (pr) {
        M(pr, 'getImageData', function (t, o, a) { var d = o.apply(t, a); try { noiseImg(d.data, d.width, d.height); } catch (e) { } return d; });
      });
      // measureText：宽度乘一个每字体固定的极小系数
      var TMF = new WeakMap();
      [C2D, OC2D].forEach(function (pr) {
        M(pr, 'measureText', function (t, o, a) { var m = o.apply(t, a); try { TMF.set(m, textFactor(t.font)); } catch (e) { } return m; });
      });
      G(P('TextMetrics'), 'width', function (t, o) { var v = o.call(t), f = TMF.get(t); return f ? v * f : v; });

      // ---------- WebGL ----------
      var CLAMP = { 0x8869: 16, 0x8DFB: 256, 0x8DFD: 224, 0x8DFC: 15, 0x8B4D: 32, 0x8872: 16, 0x8B4C: 16 };
      if (STRICT) { CLAMP[0x0D33] = 4096; CLAMP[0x84E8] = 4096; CLAMP[0x851C] = 4096; }
      // 第一次真正创建 WebGL 上下文时才去打补丁：在这之前碰 WebGLRenderingContext 会让内核初始化一大张常量表（桌面上约 1 ms）
      var glDone = false;
      function patchGL() {
        if (glDone) return; glDone = true;
        ['WebGLRenderingContext', 'WebGL2RenderingContext'].forEach(function (n) {
          var pr = P(n); if (!pr) return;
          M(pr, 'getParameter', function (t, o, a) {
            var v = o.apply(t, a), p = a[0];
            if (v === null || v === undefined) return v;
            if (p === 0x9245) return 'WebKit';        // UNMASKED_VENDOR_WEBGL
            if (p === 0x9246) return 'WebKit WebGL';  // UNMASKED_RENDERER_WEBGL
            if (typeof v === 'number' && CLAMP[p] && v > CLAMP[p]) return CLAMP[p];
            if (STRICT && p === 0x0D3A && v && v.length === 2) { var r = new w.Int32Array(2); r[0] = Math.min(v[0], 4096); r[1] = Math.min(v[1], 4096); return r; } // MAX_VIEWPORT_DIMS
            return v;
          });
          M(pr, 'readPixels', function (t, o, a) {
            var r = o.apply(t, a);
            try { var px = a[6]; if (a.length >= 7 && px && px.BYTES_PER_ELEMENT === 1 && a[4] === 0x1908 && a[5] === 0x1401) noiseImg(px, a[2], a[3]); } catch (e) { }
            return r;
          });
        });
      }
      function hookGetContext(pr) {
        M(pr, 'getContext', function (t, o, a) { var ty = a[0]; if (!glDone && typeof ty === 'string' && ty.indexOf('webgl') >= 0) { try { patchGL(); } catch (e) { } } return o.apply(t, a); });
      }
      hookGetContext(HC); hookGetContext(OCv);

      // ---------- Audio ----------
      var AB = P('AudioBuffer'), ABD = new WeakMap(), fakeGCD = null;
      fakeGCD = M(AB, 'getChannelData', function (t, o, a) {
        var arr = o.apply(t, a);
        try {
          var ch = a[0] | 0, done = ABD.get(t) || 0;
          if (ch < 31 && !(done & (1 << ch)) && arr.length <= AMAX) {
            ABD.set(t, done | (1 << ch));
            for (var i = 0; i < arr.length; i++) arr[i] *= AF;
          }
        } catch (e) { }
        return arr;
      });
      M(AB, 'copyFromChannel', function (t, o, a) { try { if (fakeGCD) fakeGCD.call(t, a[1] | 0); } catch (e) { } return o.apply(t, a); });
      var AN = P('AnalyserNode');
      ['getFloatFrequencyData', 'getFloatTimeDomainData'].forEach(function (n) {
        M(AN, n, function (t, o, a) { var r = o.apply(t, a); try { var x = a[0]; for (var i = 0; i < x.length; i++) x[i] *= AF; } catch (e) { } return r; });
      });

      // ---------- navigator ----------
      var N = P('Navigator');
      V(N, 'hardwareConcurrency', 8);
      V(N, 'deviceMemory', 4);
      V(N, 'platform', 'Linux armv8l');
      V(N, 'maxTouchPoints', 5);
      V(N, 'language', 'zh-CN');
      V(N, 'languages', function () { return LANGS || (LANGS = O.freeze(['zh-CN', 'zh'])); });
      M(N, 'getGamepads', function (t, o, a) { o.apply(t, a); return [null, null, null, null]; });
      ['usb', 'bluetooth', 'hid', 'serial', 'keyboard', 'xr'].forEach(function (n) { del(N, n); });
      var NI = P('NetworkInformation');
      V(NI, 'rtt', 100); V(NI, 'downlink', 10); V(NI, 'effectiveType', '4g');
      if (STRICT) V(NI, 'type', 'unknown');
      // 电池：永远是"充满、在充电"
      var BM = P('BatteryManager');
      V(BM, 'charging', true); V(BM, 'level', 1); V(BM, 'chargingTime', 0); V(BM, 'dischargingTime', Infinity);
      // 媒体设备：没给过相机/麦克风权限时，每种设备只报一个（不暴露设备数量）
      M(P('MediaDevices'), 'enumerateDevices', function (t, o, a) {
        return o.apply(t, a).then(function (list) {
          for (var i = 0; i < list.length; i++) if (list[i].label) return list;
          var seen = {}; return list.filter(function (d) { return seen[d.kind] ? false : (seen[d.kind] = 1); });
        });
      });
      if (STRICT && w.speechSynthesis) M(P('SpeechSynthesis'), 'getVoices', function (t, o, a) { o.apply(t, a); return []; });

      // ---------- screen / 像素比 ----------
      var SC = P('Screen');
      function sd(t) { var s = w.screen; return screenDims(gS.w.call(s), gS.h.call(s)); }
      var gS = { w: SC && gopd(SC, 'width') && gopd(SC, 'width').get, h: SC && gopd(SC, 'height') && gopd(SC, 'height').get };
      if (gS.w && gS.h) {
        G(SC, 'width', function (t, o) { o.call(t); return sd(t)[0]; });
        G(SC, 'availWidth', function (t, o) { o.call(t); return sd(t)[0]; });
        G(SC, 'height', function (t, o) { o.call(t); return sd(t)[1]; });
        G(SC, 'availHeight', function (t, o) { o.call(t); return sd(t)[1]; });
      }
      V(SC, 'colorDepth', 24); V(SC, 'pixelDepth', 24);
      G(w, 'devicePixelRatio', function (t, o) { var v = o.call(t); return STRICT ? 2 : Math.max(1, Math.round(v * 2) / 2); });

      // ---------- 计时精度 ----------
      var Q = STRICT ? 1 : 0.1;
      M(P('Performance'), 'now', function (t, o, a) { var v = o.apply(t, a); return Math.floor(v / Q) * Q; });

      // ---------- 传感器 ----------
      var SENS = { devicemotion: 1, deviceorientation: 1, deviceorientationabsolute: 1 };
      M(P('EventTarget'), 'addEventListener', function (t, o, a) { if (t === w && SENS[a[0]]) return; return o.apply(t, a); });
      ['ondevicemotion', 'ondeviceorientation', 'ondeviceorientationabsolute'].forEach(function (n) {
        var d = gopd(w, n); if (!d || !d.set) return;
        var store = null, os = d.set, og = d.get;
        var acc = gopd({ get [n]() { og.call(this); return store; }, set [n](v) { store = (typeof v === 'function' || typeof v === 'object') ? v : null; } }, n);
        NM.set(acc.get, og); NM.set(acc.set, os);
        d.get = acc.get; d.set = acc.set; defp(w, n, d);
      });
      ['Accelerometer', 'LinearAccelerationSensor', 'GravitySensor', 'Gyroscope', 'Magnetometer', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor', 'AmbientLightSensor'].forEach(function (n) { del(w, n); });

      // ---------- 时区（严格模式） ----------
      if (STRICT) {
        var DP = P('Date'), I = w.Intl;
        M(DP, 'getTimezoneOffset', function (t, o, a) { o.apply(t, a); return 0; });
        ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (n) {
          var ug = DP['getUTC' + n], us = DP['setUTC' + n];
          M(DP, 'get' + n, function (t, o, a) { return ug.apply(t, a); });
          if (us) M(DP, 'set' + n, function (t, o, a) { return us.apply(t, a); });
        });
        var oUTC = DP.toUTCString, oVal = DP.valueOf;
        function parts(t) { var s = oUTC.call(t); if (isNaN(oVal.call(t))) return null; var p = s.split(' '); return p; } // "Wed, 25 Nov 2026 17:56:00 GMT"
        var TZS = 'GMT+0000 (协调世界时)';
        M(DP, 'toDateString', function (t, o, a) { var p = parts(t); return p ? p[0].slice(0, 3) + ' ' + p[2] + ' ' + p[1] + ' ' + p[3] : o.apply(t, a); });
        M(DP, 'toTimeString', function (t, o, a) { var p = parts(t); return p ? p[4] + ' ' + TZS : o.apply(t, a); });
        M(DP, 'toString', function (t, o, a) { var p = parts(t); return p ? p[0].slice(0, 3) + ' ' + p[2] + ' ' + p[1] + ' ' + p[3] + ' ' + p[4] + ' ' + TZS : o.apply(t, a); });
        function utcOpt(x) { if (x === undefined) return { timeZone: 'UTC' }; if (x && typeof x === 'object' && x.timeZone === undefined) return O.assign({}, x, { timeZone: 'UTC' }); return x; }
        ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (n) {
          M(DP, n, function (t, o, a) { return o.call(t, a[0], utcOpt(a[1])); });
        });
        if (I && I.DateTimeFormat) {
          var DTF = I.DateTimeFormat;
          var px = new Proxy(DTF, {
            construct: function (tg, a, nt) { return Reflect.construct(tg, [a[0], utcOpt(a[1])], nt); },
            apply: function (tg, th, a) { return tg.call(th, a[0], utcOpt(a[1])); }
          });
          NM.set(px, DTF);
          defp(I, 'DateTimeFormat', { value: px, writable: true, configurable: true, enumerable: false });
          var dc = gopd(DTF.prototype, 'constructor'); if (dc) { dc.value = px; defp(DTF.prototype, 'constructor', dc); }
        }
      }

      // ---------- iframe：同源子窗口在网页拿到之前先打补丁 ----------
      function sub_(cw) { try { if (cw && cw !== w) patch(cw); } catch (e) { } return cw; }
      var HIF = P('HTMLIFrameElement');
      G(HIF, 'contentWindow', function (t, o) { return sub_(o.call(t)); });
      G(HIF, 'contentDocument', function (t, o) { var d = o.call(t); if (d) sub_(d.defaultView); return d; });
      var HFR = P('HTMLFrameElement');
      G(HFR, 'contentWindow', function (t, o) { return sub_(o.call(t)); });
    }

    patch(W);
  } catch (e) { }
})(__UMU_FP_CONFIG__);
