# v1.4 防指纹 (anti-fingerprinting) — branch feature/antifp

(To be folded into NOTES.md when merging.)

Brave-style "farbling": small per-site deterministic noise + normalising high-entropy values, injected with
`WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))` (all origins, all frames, before page scripts) — the same
mechanism as the v1.3 WebRTC/GPC script, but a separate ScriptHandler so it can be swapped without rebuilding WebViews.

## Files
- `fp-js/fingerprint.js` — readable source. `fp-js/build.sh` → terser → `app/src/main/assets/fingerprint.min.js` (≈9.3 KB, committed). Re-run build.sh after editing the source.
- `privacy/fingerprint/FpSeed.kt` — eTLD+1 (`site()`, same approximate suffix list as `filter/Domains.SECOND_LEVEL`, now public), FNV-1a + fmix32 seed, `newKey()` (SecureRandom 128-bit hex).
- `privacy/fingerprint/FingerprintShield.kt` — loads asset (background preload), builds config JSON, attaches per WebView, swaps scripts on setting changes (`update()` → `ScriptHandler.remove()` + re-add on live WebViews only), incognito key, fallback for old WebViews.
- Touch points: `BrowserController` (field, `attach` in setupWebView, `update` in applySecurity, `setFpExceptions`, `setSiteFingerprint`, fallback in onPageStarted, `endIncognito`), `CuteRepository` (`SecurityPrefs.antiFp/fpStrict` = JSON keys `fp`/`fps`; DataStore key `fp_off` = exempt eTLD+1 set), `MainActivity` (load `fp_off` before first page + collect), `SecurityUi` (toggle + 标准/严格 picker in 安全与隐私; shield dialog line + 「在这个网站防指纹」), `FilterEngine` (`SECOND_LEVEL` made public).

## Seeds
- Session key: random per process start (= app restart). Incognito: own key, created with the first incognito WebView, dropped when the incognito session ends.
- Site = approx eTLD+1 of the **top-level** page (iframes: `top.location` if same-origin, else `location.ancestorOrigins` last entry). Seed = fmix32(FNV-1a(key + "|" + site)). Kotlin and JS implementations are cross-checked (`FpSeedTest` vectors == `node fp-js/seed-vectors.mjs --check`).
- So: same site → same values all session (sites don't break on reload), different sites → different values, restart → new values.

## What is covered (config `l`: 1 standard, 2 strict)
- Canvas: `toDataURL`/`toBlob` (copy → noise → encode; original canvas untouched), `getImageData` (2D + OffscreenCanvas 2D), `OffscreenCanvas.convertToBlob`. Noise = flip LSB of one colour channel on a sparse, seed+size-determined set of fully opaque pixels (~1 in 24). Skipped above 4 Mpx.
- `measureText().width` × per-font factor (±1e-5, sub-pixel).
- WebGL/WebGL2 (patched lazily at the first `getContext('webgl*')` — touching WebGL interfaces early costs ~1 ms): `UNMASKED_VENDOR/RENDERER` → "WebKit"/"WebKit WebGL"; caps clamped down to common values (vertex attribs 16, uniform vectors 256/224, varyings 15, texture units 32/16/16; strict also MAX_TEXTURE/RENDERBUFFER/CUBE_MAP 4096, viewport 4096); `readPixels` RGBA/UNSIGNED_BYTE noise.
- Audio: `AudioBuffer.getChannelData` (once per channel, buffers ≤ 262k samples) and `copyFromChannel`, `AnalyserNode.getFloat{Frequency,TimeDomain}Data` × (1 − ~1e-5·k) — inaudible.
- navigator: hardwareConcurrency 8, deviceMemory 4, platform "Linux armv8l", maxTouchPoints 5, language "zh-CN", languages ["zh-CN","zh"]; getGamepads → 4 nulls; usb/bluetooth/hid/serial/keyboard/xr removed; NetworkInformation rtt 100 / downlink 10 / effectiveType 4g (strict: type "unknown"); BatteryManager → charging, level 1; `enumerateDevices` → one device per kind unless labels are present (i.e. camera/mic was granted); strict: `speechSynthesis.getVoices()` → [].
- plugins/mimeTypes: Android Chrome/WebView already returns empty arrays → left native (spoofing would be more detectable).
- screen: width/height/avail* snapped to common phone CSS sizes (360…430 × 640…932; tablets to 64-px steps), colorDepth/pixelDepth 24; devicePixelRatio rounded to 0.5 steps. Strict: 360×800 (tablet 800×1280), DPR 2.
- performance.now(): floored to 0.1 ms (standard; removes the 5 µs precision of cross-origin-isolated pages) / 1 ms (strict).
- Sensors: `devicemotion`/`deviceorientation(absolute)` listeners on window and the `on…` handlers are swallowed; Generic Sensor constructors removed.
- Strict only — timezone UTC: getTimezoneOffset 0, local Date getters/setters mapped to UTC ones, toString/toDateString/toTimeString as GMT+0000 (协调世界时), toLocale*String and `Intl.DateTimeFormat` default `timeZone: 'UTC'` (Proxy).
- Native look: patched functions keep name/length, have no `prototype`, and `Function.prototype.toString` (itself patched) returns the original's "function x() { [native code] }".
- iframes: `HTMLIFrameElement/HTMLFrameElement.contentWindow/contentDocument` getters patch same-origin child windows (e.g. about:blank) before the page can use them; a per-window marker (Symbol.for(secret)) prevents double patching.
- Per-site exception (shield chip → 「在这个网站防指纹」) keyed by eTLD+1 → the script returns immediately for that site. Toggle reloads the page.

## Verification (2026-11-26, this box)
- `fp-js/chrome-test.mjs`: headless desktop Chrome 156 + CDP `Page.addScriptToEvaluateOnNewDocument` (same timing semantics as WebView document-start scripts) against `fp-js/fp-testpage.html` (copy of /workspace/umu-testsite/fp.html) on fake hosts mapped to 127.0.0.1: **all checks pass** — canvas/imageData/WebGL readPixels/audio/measureText change vs unprotected, stable on reload, equal across subdomains of one eTLD+1, differ across sites and across session keys, exception site = unprotected; iframe (incl. dynamically created about:blank) patched; toString native; strict TZ/screen/DPR/timer.
- Script cost: ≈0.45 ms per frame warm, ≈0.8 ms first run incl. parse (desktop x86; expect 2–4× on a phone).
- No new JS exceptions on bing.com, baidu.com, bilibili.com, Google Maps, map.baidu.com (standard and strict) in desktop Chrome.
- Unit tests: `FpSeedTest` (eTLD+1, seed vectors vs JS, per-site/per-key differences, script assembly + incognito key rotation + exceptions, `node --check` of the assembled script), `FpScreenshotTest` (UI shots to app/build/fp-screens with -Proborazzi.test.record=true). Full suite: 21 tests pass. `assembleDebug` OK.
- **Not run on Android WebView / a device.** Desktop Chrome ≠ Android WebView (e.g. WebView feature set, whether document-start scripts run in about:blank frames).

## Limitations (honest)
- Chromium engine & version remain visible (UA/Client Hints major version, JS/CSS feature set, Android WebView quirks). We look like "a Chromium on Android with fingerprint protection", not like everyone else.
- Spoofing is detectable by dedicated tools (CreepJS-style "lie" detection: stack traces through our wrappers, cross-realm/worker comparisons, Proxy behaviour, inconsistencies like screen vs CSS media queries or timezone vs Date constructor). Typical commercial tracker scripts are defeated (their hash changes per site/session); targeted detection isn't.
- Not covered: Web Workers / SharedWorkers / ServiceWorkers (document-start scripts don't run there → real hardwareConcurrency, OffscreenCanvas in workers un-noised); CSS media queries (`device-width`, `resolution`), `window.outerWidth/outerHeight`; DOM-based font enumeration via element offsetWidth; `document.fonts.check`; `Intl` locale/time-zone outside DateTimeFormat; `new Date(y,m,d…)`/`Date.parse` still local in strict; HTTP headers (Accept-Language is the device locale); WebGL prototype methods captured by a page *before* it ever calls getContext('webgl'); `window[0]`/`frames[0]` access to a fresh about:blank iframe bypasses the contentWindow hook.
- Old WebViews without DOCUMENT_START_SCRIPT: fallback evaluateJavascript in onPageStarted, main frame only, can run after page scripts.
- Breakage risks: strict timezone shows wrong local times (by design); blocked device sensors break tilt/360° content and some games; generic WebGL renderer may make a few WebGL apps pick conservative paths; canvas noise could affect pixel-exact canvas apps (CAPTCHA/image hashing) — per-site exception exists; languages forced zh-CN even if device locale differs.
