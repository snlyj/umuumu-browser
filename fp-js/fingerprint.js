/*
 * 唔姆唔姆 防指纹脚本（Brave 式"每站随机但稳定"）。
 * 由 WebViewCompat.addDocumentStartJavaScript 在每个页面、每个 frame 的最开始执行（网页自己的脚本之前）。
 * 源码在这里，发布用的是 terser 压缩后的 app/src/main/assets/fingerprint.min.js（见 fp-js/build.sh）。
 *
 * __UMU_FP_CONFIG__ 由 App 替换成 JSON：
 *   k  本次会话的随机密钥（App 每次启动换一个；隐身会话单独一个）
 *   l  1 = 标准，2 = 严格（另外：时区 UTC、固定屏幕、1 ms 计时、清空语音列表、隐藏网络类型）
 *   x  用户关掉防指纹的网站（eTLD+1）
 *   z  全局时区："" 跟随系统 / IANA 名 / "*" 每个网站随机（严格防指纹且为空时 = UTC）
 *   g  全局语言（BCP-47，如 ja-JP），"" = 跟随系统（开着防指纹时 = zh-CN）
 *   o  站点覆盖 {eTLD+1: {z, g}}，"-" = 这个网站用系统真实值
 *   rz 「每个网站随机」用的时区候选
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
    var FPON = L > 0 && (C.x || []).indexOf(SITE) < 0; // L=0：防指纹关了；x：用户给这个网站开了例外

    var SEED = mix(fnv(C.k + '|' + SITE));
    function sub(tag) { return mix(SEED ^ fnv(tag)); }

    // 时区 / 语言：站点覆盖 > 全局设置 > 防指纹默认（严格 = UTC；开着防指纹 = zh-CN）。'-' = 明确用系统真实值，'*' = 每个网站随机一个时区
    var OV = (C.o && C.o[SITE]) || {};
    var ZS = OV.z !== undefined ? OV.z : C.z;
    if (!ZS) ZS = FPON && STRICT ? 'UTC' : '';
    if (ZS === '*') { var RZ = C.rz || []; ZS = RZ.length ? RZ[sub('tz') % RZ.length] : ''; }
    if (ZS === '-') ZS = '';
    var LANG = OV.g !== undefined ? OV.g : C.g;
    if (!LANG) LANG = FPON ? 'zh-CN' : '';
    if (LANG === '-') LANG = '';
    if (LANG) { try { LANG = W.Intl.getCanonicalLocales(LANG)[0]; } catch (e) { LANG = ''; } }
    // 同一个窗口只打一次补丁（iframe 钩子 + 文档开始脚本可能都会来）。这几份脚本互相只能靠"全局共享的东西"通气：
    // 用一个注册表 Symbol 当暗号——不要把它挂成 toString 的属性（Object.getOwnPropertySymbols 看得见，描述里还曾带着会话密钥，
    // 等于把跨网站的会话标识交给了网页，也让网页一眼看出被打过补丁）。暗号只用来当 toString 的 this：网页拿不到它，
    // 原生的 toString 对 Symbol 会抛 TypeError，打过补丁的会回 'umu'。暗号按网站派生，不同网站互相对不上。
    var MK = Symbol.for('\u2063' + sub('mk'));

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

    function patch(w) {
      var FP = w.Function && w.Function.prototype; if (!FP) return;
      var oTS = FP.toString;
      if (!oTS) return;
      try { if (oTS.call(MK) === 'umu') return; } catch (e) { }
      var NM = new WeakMap(); // 假函数 -> 原函数，toString 时显示原函数的 "[native code]"
      var ts = ({ toString() { if (this === MK) return 'umu'; var o = NM.get(this); return oTS.call(o === undefined ? this : o); } }).toString;
      NM.set(ts, oTS);
      var d0 = gopd(FP, 'toString'); d0.value = ts; defp(FP, 'toString', d0);

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

      if (FPON) try {
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

      } catch (e) { }

      // ---------- 时区 / 语言（不依赖防指纹开关；配置见文件头） ----------
      if (ZS || LANG) try {
        var I = w.Intl, oDate = w.Date, DP = oDate.prototype, AP = Array.prototype;
        var LANGS = null;
        var oVal = DP.valueOf, oSetTime = DP.setTime, oUTC = oDate.UTC, oParse = oDate.parse, oGTZO = DP.getTimezoneOffset;
        function withTz(x) {
          if (!ZS || x === null) return x; // 没伪造时区就原样；null 原生会抛 TypeError
          if (typeof x !== 'object') return { timeZone: ZS };
          return x.timeZone === undefined ? O.create(x, { timeZone: { value: ZS, enumerable: true, writable: true, configurable: true } }) : x;
        }
        function fixL(a) { if (a.length && a[0] !== undefined || !LANG) return a; var b = AP.slice.call(a); b[0] = LANG; return b; }

        var QB = 900000;
        // 时区名字（"China Standard Time"）：Intl 对历史上的怪偏移（LMT 等）只给 "GMT-04:56:02"，这时借用今年 1 月/7 月里偏移最接近的名字
        function mkName(fmt, offOf) {
          var cache = new Map(), mod = null;
          var at = function (u) { var ps = fmt.formatToParts(u), n = ''; for (var i = 0; i < ps.length; i++) if (ps[i].type === 'timeZoneName') n = ps[i].value; return n; };
          return function (u) {
            var b = Math.floor(u / QB), n = cache.get(b);
            if (n === undefined) {
              n = at(b * QB);
              if (!n || /^(GMT|UTC)[+-]/.test(n)) {
                if (!mod) { var y = new oDate().getUTCFullYear(); mod = [Date.UTC(y, 0, 15), Date.UTC(y, 6, 15)].map(function (t) { return [offOf(t), at(t)]; }); }
                var o0 = offOf(b * QB), best = mod[0], d0 = Math.abs(mod[0][0] - o0), d1 = Math.abs(mod[1][0] - o0);
                if (d1 < d0 || (d1 === d0 && mod[1][0] < mod[0][0])) best = mod[1];
                if (best[1]) n = best[1];
              }
              if (cache.size > 400) cache.clear();
              cache.set(b, n);
            }
            return n;
          };
        }

        // ----- 语言：navigator + Intl 默认 locale -----
        if (LANG) {
          var N = P('Navigator'), base = LANG.split('-')[0];
          V(N, 'language', LANG);
          V(N, 'languages', function () { return LANGS || (LANGS = O.freeze(base === LANG ? [LANG] : [LANG, base])); });
          if (I) ['NumberFormat', 'Collator', 'PluralRules', 'RelativeTimeFormat', 'ListFormat', 'DisplayNames', 'Segmenter', 'DurationFormat'].forEach(function (n) {
            var K = I[n]; if (typeof K !== 'function') return;
            var pk = new Proxy(K, {
              construct: function (tg, a, nt) { return Reflect.construct(tg, fixL(a), nt); },
              apply: function (tg, th, a) { return Reflect.apply(tg, th, fixL(a)); }
            });
            NM.set(pk, K);
            defp(I, n, { value: pk, writable: true, configurable: true, enumerable: false });
            var dc = gopd(K.prototype, 'constructor'); if (dc && dc.value === K) { dc.value = pk; defp(K.prototype, 'constructor', dc); }
          });
          M(P('Number'), 'toLocaleString', function (t, o, a) { return o.call(t, a[0] === undefined ? LANG : a[0], a[1]); });
          if (w.BigInt) M(w.BigInt.prototype, 'toLocaleString', function (t, o, a) { return o.call(t, a[0] === undefined ? LANG : a[0], a[1]); });
          M(P('String'), 'localeCompare', function (t, o, a) { return o.call(t, a[0], a[1] === undefined ? LANG : a[1], a[2]); });
          M(P('String'), 'toLocaleUpperCase', function (t, o, a) { return o.call(t, a[0] === undefined ? LANG : a[0]); });
          M(P('String'), 'toLocaleLowerCase', function (t, o, a) { return o.call(t, a[0] === undefined ? LANG : a[0]); });
        }

        // ----- 时区 -----
        var oDTF = I && I.DateTimeFormat, tzf, tzn, ZC = '';
        if (ZS) try {
          tzf = new oDTF('en-US', { timeZone: ZS, hourCycle: 'h23', era: 'short', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' });
          ZC = tzf.resolvedOptions().timeZone;
          tzn = new oDTF(LANG || undefined, { timeZone: ZS, timeZoneName: 'long' });
        } catch (e) { ZS = ''; }
        if (ZS) {
          var TMP = new oDate(0), ISUTC = ZC === 'UTC' || ZC === 'Etc/UTC' || ZC === 'Etc/GMT' || ZC === 'GMT';
          var OFF = new Map(), MAXT = 8.64e15, DAY = 864e5;
          // 某个时刻（UTC 毫秒）在目标时区的偏移（毫秒）。按 15 分钟一档缓存，所以夏令时/历史变动都由 Intl 的时区数据决定
          var off = function (u) {
            if (ISUTC) return 0;
            if (u > MAXT) u = MAXT; else if (u < -MAXT) u = -MAXT;
            var b = Math.floor(u / QB), c = OFF.get(b);
            if (c !== undefined) return c;
            var t0 = b * QB, ps = tzf.formatToParts(t0), v = {}, i, q;
            for (i = 0; i < ps.length; i++) { q = ps[i]; v[q.type] = q.value; }
            var y = +v.year; if (v.era && v.era.charAt(0) === 'B') y = 1 - y;
            TMP.setUTCFullYear(y, v.month - 1, v.day); TMP.setUTCHours(v.hour, v.minute, v.second, 0);
            c = TMP.getTime() - t0;
            if (OFF.size > 4000) OFF.clear();
            OFF.set(b, c); return c;
          };
          // "本地时间"（按目标时区的墙上时间，当成 UTC 毫秒）→ 真正的时刻。重复的钟点取前一次，不存在的钟点按转换前的偏移算（和规范一致）
          var toUtc = function (Lc) {
            if (Lc !== Lc || Lc > MAXT + DAY || Lc < -MAXT - DAY) return NaN;
            var a = off(Lc - DAY), b = off(Lc + DAY), u1 = Lc - a, u2 = Lc - b;
            var ok1 = u1 + off(u1) === Lc, ok2 = u2 + off(u2) === Lc;
            if (ok1 && ok2) return Math.min(u1, u2);
            return ok2 ? u2 : u1;
          };
          var tzName = mkName(tzn, off);
          // ---- getters ----
          ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (n) {
            var ug = DP['getUTC' + n];
            M(DP, 'get' + n, function (t, o, a) { var v = oVal.call(t); if (v !== v) return NaN; oSetTime.call(TMP, v + off(v)); return ug.call(TMP); });
          });
          M(DP, 'getYear', function (t, o, a) { var v = oVal.call(t); if (v !== v) return NaN; oSetTime.call(TMP, v + off(v)); return TMP.getUTCFullYear() - 1900; });
          M(DP, 'getTimezoneOffset', function (t, o, a) { var v = oVal.call(t); if (v !== v) return NaN; return -off(v) / 60000; });
          // ---- setters ----
          ['FullYear', 'Month', 'Date', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (n) {
            var us = DP['setUTC' + n], full = n === 'FullYear';
            M(DP, 'set' + n, function (t, o, a) {
              var v = oVal.call(t), Lc;
              if (v !== v) { if (!full) { oSetTime.call(TMP, NaN); us.apply(TMP, a); return NaN; } Lc = 0; } else Lc = v + off(v);
              oSetTime.call(TMP, Lc);
              var r = us.apply(TMP, a);
              return oSetTime.call(t, r !== r ? NaN : toUtc(r));
            });
          });
          M(DP, 'setYear', function (t, o, a) { var y = +a[0]; if (y === y) { var yi = Math.trunc(y); if (yi >= 0 && yi <= 99) y = 1900 + yi; } return DP.setFullYear.call(t, y); });
          // ---- 文字形式 ----
          var DN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'], MN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
          var pad = function (n, l) { var s = String(n); while (s.length < l) s = '0' + s; return s; };
          var dateStr = function () { var y = TMP.getUTCFullYear(); return DN[TMP.getUTCDay()] + ' ' + MN[TMP.getUTCMonth()] + ' ' + pad(TMP.getUTCDate(), 2) + ' ' + (y < 0 ? '-' + pad(-y, 4) : pad(y, 4)); };
          var timeStr = function (nm, of) {
            var m = Math.floor(Math.abs(of) / 60000);
            return pad(TMP.getUTCHours(), 2) + ':' + pad(TMP.getUTCMinutes(), 2) + ':' + pad(TMP.getUTCSeconds(), 2) + ' GMT' + (of < 0 ? '-' : '+') + pad(Math.floor(m / 60), 2) + pad(m % 60, 2) + (nm ? ' (' + nm + ')' : '');
          };
          var fmtD = function (t, mode) {
            var v = oVal.call(t); if (v !== v) return 'Invalid Date';
            var of = off(v), nm = mode === 1 ? '' : tzName(v); // 名字要先算：它内部会用到 TMP
            oSetTime.call(TMP, v + of);
            return mode === 1 ? dateStr() : mode === 2 ? timeStr(nm, of) : dateStr() + ' ' + timeStr(nm, of);
          };
          var pToString = M(DP, 'toString', function (t) { return fmtD(t, 0); });
          M(DP, 'toDateString', function (t) { return fmtD(t, 1); });
          M(DP, 'toTimeString', function (t) { return fmtD(t, 2); });
          // ---- 把字符串当"本地时间"解析（没写时区的才算；ISO 纯日期、带 Z/±hh:mm/GMT/UTC/EST… 的是绝对时间，不动）----
          var ISODATE = /^[+-]?\d{4,6}(-\d\d(-\d\d)?)?$/, ISODT = /^[+-]?\d{4,6}-\d\d-\d\dT\d\d:\d\d(:\d\d(\.\d+)?)?$/;
          var HASZ = /(?:\d|\s)(?:z|gmt|utc|ut|[ecmp][sd]t)(?![a-z])|\d\s*[+-]\d\d:?\d\d(?!\d)|gmt\s*[+-]\d/i;
          var parseStr = function (s) {
            s = String(s);
            var p = oParse(s); if (p !== p || ISODATE.test(s) || HASZ.test(s)) return p;
            var Lc = ISODT.test(s) ? oParse(s + 'Z') : oParse(s + ' UTC');
            if (Lc !== Lc) Lc = p - oGTZO.call(new oDate(p)) * 60000;
            return toUtc(Lc);
          };
          M(oDate, 'parse', function (t, o, a) { return parseStr(a[0]); });
          var prim = function (x) {
            var tp = x[Symbol.toPrimitive];
            if (tp !== undefined && tp !== null) return tp.call(x, 'default');
            var v = x.valueOf(); return v !== null && typeof v !== 'object' && typeof v !== 'function' ? v : x.toString();
          };
          var pDate = new Proxy(oDate, {
            construct: function (tg, a, nt) {
              var n = a.length;
              if (n === 0) return Reflect.construct(tg, a, nt);
              if (n === 1) {
                var x = a[0];
                if (x !== null && typeof x === 'object') { try { oVal.call(x); return Reflect.construct(tg, a, nt); } catch (e) { } x = prim(x); }
                return Reflect.construct(tg, [typeof x === 'string' ? parseStr(x) : x], nt);
              }
              var nums = []; for (var i = 0; i < n && i < 7; i++) nums.push(+a[i]);
              return Reflect.construct(tg, [toUtc(oUTC.apply(null, nums))], nt);
            },
            apply: function () { return pToString.call(new oDate()); }
          });
          NM.set(pDate, oDate);
          defp(w, 'Date', { value: pDate, writable: true, configurable: true, enumerable: false });
          defp(DP, 'constructor', { value: pDate, writable: true, configurable: true, enumerable: false });
          // ---- Temporal（新版 WebView 才有）----
          var TP = w.Temporal;
          if (TP && TP.Now) {
            ['zonedDateTimeISO', 'plainDateTimeISO', 'plainDateISO', 'plainTimeISO'].forEach(function (n) {
              M(TP.Now, n, function (t, o, a) { return o.call(t, a[0] === undefined ? ZS : a[0]); });
            });
            M(TP.Now, 'timeZoneId', function (t, o, a) { return ZS; });
          }
        }
        // 只伪造语言、没伪造时区：Date 的文字形式末尾括号里的时区名（"中国标准时间"）是跟着语言走的，不换掉就会泄露真实语言
        if (LANG && !ZS && oDTF) try {
          var tzn2 = new oDTF(LANG, { timeZoneName: 'long' });
          var nm2 = mkName(tzn2, function (u) { return -oGTZO.call(new oDate(u)) * 60000; });
          var renameTz = function (t, o, a) {
            var s = o.apply(t, a), v = oVal.call(t); if (v !== v) return s;
            var n = nm2(v);
            return n ? s.replace(/\([^)]*\)\s*$/, '(' + n + ')') : s;
          };
          M(DP, 'toString', renameTz); M(DP, 'toTimeString', renameTz);
        } catch (e) { }
        // ----- Date.toLocale*String 和 Intl.DateTimeFormat：默认语言 / 默认时区 -----
        ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (n) {
          M(DP, n, function (t, o, a) { return o.call(t, a[0] === undefined && LANG ? LANG : a[0], withTz(a[1])); });
        });
        if (oDTF) {
          var fixD = function (a) { return [a[0] === undefined && LANG ? LANG : a[0], withTz(a[1])]; };
          var pD = new Proxy(oDTF, {
            construct: function (tg, a, nt) { return Reflect.construct(tg, fixD(a), nt); },
            apply: function (tg, th, a) { return Reflect.apply(tg, th, fixD(a)); }
          });
          NM.set(pD, oDTF);
          defp(I, 'DateTimeFormat', { value: pD, writable: true, configurable: true, enumerable: false });
          var dcd = gopd(oDTF.prototype, 'constructor'); if (dcd) { dcd.value = pD; defp(oDTF.prototype, 'constructor', dcd); }
        }
        // Temporal 的 toLocaleString：默认 locale（Instant 还要默认时区）
        var TP2 = w.Temporal;
        if (TP2) ['Instant', 'PlainDate', 'PlainTime', 'PlainDateTime', 'PlainYearMonth', 'PlainMonthDay', 'ZonedDateTime', 'Duration'].forEach(function (n) {
          var K = TP2[n]; if (!K) return;
          M(K.prototype, 'toLocaleString', function (t, o, a) {
            return o.call(t, a[0] === undefined && LANG ? LANG : a[0], n === 'Instant' && ZS ? withTz(a[1]) : a[1]);
          });
        });
      } catch (e) { }

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
