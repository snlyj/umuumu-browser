# 唔姆唔姆 (UmuUmu, formerly 萌萌浏览器) — project notes

Cute/girly Android browser for 由佳. Kotlin + Jetpack Compose + Android System WebView.

## Build
```
export JAVA_HOME=/workspace/tools/jdk17 ANDROID_HOME=/workspace/tools/android PATH=$JAVA_HOME/bin:$PATH
cd /workspace/cute-browser && ./gradlew assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```
- AGP 8.7.3, Gradle 8.10.2 (wrapper), Kotlin 2.0.21 + Compose compiler plugin, Compose BOM 2024.12.01
- compileSdk/targetSdk 35, minSdk 24, applicationId `com.yuka.cutebrowser`
- SDK at /workspace/tools/android (platforms 34/35, build-tools 34/35, emulator + android-34 google_apis x86_64 image if downloaded)
- Debug builds use the default debug keystore; release builds are signed with the project keystore (see v1.1 → Signing).
- Release artifacts: /workspace/cute-browser-release/{UmuUmu-release.apk (install this), UmuUmu-debug.apk, cute-browser-source.zip, screens/}

## Code map (app/src/main/java/com/yuka/cutebrowser)
- `MainActivity.kt` — creates repo + BrowserController, permission/file-chooser launchers, handles VIEW / WEB_SEARCH intents (app registers as a browser).
- `browser/BrowserController.kt` — tabs (`TabState` = Compose-observable state + its WebView), navigation, WebViewClient/ChromeClient, DownloadManager downloads, external app links (only on user gesture), share, clear cache.
- `browser/UrlUtils.kt` — URL vs search-term detection.
- `data/CuteRepository.kt` — DataStore Preferences: theme, search engine, bookmarks + history (JSON strings, history capped at 500).
- `ui/theme/Theme.kt` — 3 palettes: 草莓牛奶 / 樱花 / 星空梦境 (`LocalCutePalette`).
- `ui/BrowserScreen.kt` — pill address bar, bunny progress bar, WebView host, bottom jelly-button bar, hearts overlay.
- `ui/HomePage.kt` — new-tab page: greeting, Canvas bunny mascot, sticker grid (6 sites), 每日一句 (local list, rotates by day), recent bookmarks.
- `ui/Sheets.kt` — bottom sheets: 菜单, 标签页, 收藏, 历史, 设置.
- `ui/Components.kt` — JellyButton, bouncyClick, BunnyProgressBar, FloatingHearts, drag handle.
- `ui/CuteIcons.kt` — self-drawn stroke icons (heart, star, bow, flower, home-with-heart…).
- Launcher icon: `res/drawable/ic_launcher_{background,foreground}.xml` + adaptive icon in mipmap-anydpi-v26.

## Design decisions
- Activity handles config changes itself (manifest `configChanges`) so WebViews survive rotation; WebViews live in the controller, not a ViewModel.
- Home page is a Compose screen shown when `tab.isHome`; the WebView keeps its page underneath, so Back/Forward from home returns to the page.
- System back: WebView history → home page → exit.

## Ideas / v2 backlog
- 无痕模式, 广告拦截, 夜间模式 for web content (WebSettingsCompat algorithmic darkening), gestures (swipe between tabs)
- Persist open tabs across app restarts; tab thumbnails
- In-app downloads list (currently opens system Downloads UI)
- Pull-to-refresh with a bouncing cloud
- Find-in-page, desktop-site toggle, text size
- Release signing + R8 minify

## Verification (2026-11-25)
- `./gradlew clean assembleDebug testDebugUnitTest` passes (1 compiler-clean build, 3 tests).
- Screenshots: `./gradlew testDebugUnitTest -Proborazzi.test.record=true` (Robolectric NATIVE graphics + Roborazzi) writes PNGs to /workspace/cute-browser-release/screens. WebView content can't render under Robolectric, so shots cover the Compose UI only.
- The Android emulator (AVD `cute`, android-34 x86_64) was installed but hangs before VM start in this box (nested KVM), so no on-device run yet.

## v1.1 — rename + performance pass
- App renamed 唔姆唔姆 (label, home title, menu, settings footer, download description). Package id still `com.yuka.cutebrowser` (changing it would break upgrade-in-place for no gain).
- **Release build**: R8 minify + resource shrink + zh/en resources only. `./gradlew assembleRelease` -> app/build/outputs/apk/release/app-release.apk.
- **Signing**: keystore at `/workspace/cute-browser/keystore/umu-release.jks`; alias + passwords in `keystore/keystore.properties` (chmod 600, git-ignored, excluded from the source zip). BACK THIS UP: losing it means users can't upgrade in place. build.gradle.kts reads it automatically; without it release is unsigned.
- **Baseline profile**: hand-written `app/src/main/baseline-prof.txt` (wildcards over com.yuka.cutebrowser.**) + `androidx.profileinstaller`; AGP packs assets/dexopt/baseline.prof (verified in APK). NOT generated from a real device run, so it is a coarse "compile all our code AOT" profile. A real Macrobenchmark-generated profile would be better when a device is available.
- **Startup**: SplashScreen API (cream bg + icon), theme read async from DataStore and UI shown only once known (splash holds meanwhile), no blocking work in onCreate, WebViews no longer created at launch.
- **WebView**: created lazily when a tab first shows a page; one spare WebView pre-warmed in a main-looper idle handler after first frame + DNS warm-up for the 6 sticker hosts (IO thread); max 3 live WebViews (LRU, oldest background tabs destroyed and reloaded by URL on demand — their back/forward history is lost); onTrimMemory (RUNNING_LOW/CRITICAL/BACKGROUND+) drops background tabs + spare; detached WebViews are onPause()d, pauseTimers/resumeTimers when the app is backgrounded; renderer priority IMPORTANT (waived when invisible); hardware layer; cache mode LOAD_DEFAULT; hardwareAccelerated in manifest.
- **Ad/tracker block**: `browser/AdBlocker.kt`, ~60 hosts, subresources only, hash lookup in shouldInterceptRequest; toggle in Settings (default ON), stored in DataStore key `adblock`.
- **Compose**: progress bar takes lambdas and reads progress/hop only in layout/draw (no whole-screen recomposition while loading); floating hearts animate in graphicsLayer (12 hearts, not 16); isBookmarked via derivedStateOf; greeting/quote remembered; DataStore flows compare raw JSON before parsing and parse on Dispatchers.Default; history cap 300. Cute look unchanged.
- Sizes (bytes): before v1.0 debug 10,272,033; v1.0 unminified release 7,068,294; v1.1 release 1,235,397; v1.1 debug 9,917,146. DEX method refs: 99,339 (v1.0) -> 15,215 (v1.1 release).
- No runtime speed was measured (no device/emulator in this box). Everything above is static/structural.
- Release build is untested on a device; R8 could in theory strip something needed (check first launch, downloads, file upload).

## v1.2 — fixing the v1/v1.1 limitations
- **Web dark mode**: when theme = 星空梦境 and Settings「网页跟随主题变暗」is on (default on), WebViews are created with a dark ContextThemeWrapper (`Theme.CuteBrowser.WebDark`, isLightTheme=false on API 29+) so sites get `prefers-color-scheme: dark` and use their own dark CSS; sites without one are algorithmically darkened via `WebSettingsCompat.setAlgorithmicDarkeningAllowed` (androidx.webkit 1.12.1, needs a recent System WebView; silently skipped if unsupported). Toggling theme/setting saves every live WebView's state, destroys and recreates them (`webGeneration` key), restoring back/forward history.
- **Session restore**: `browser/SessionStore.kt` writes `filesDir/session/session.json` (tabs: id/url/title/isHome + active index) and `<id>.state` (WebView.saveState Bundle, Parcel-marshalled) on every Activity onPause (IO thread, atomic tmp+rename). Startup reads only the JSON (splash held until done); no WebView is created at startup — a tab's .state file is read and `restoreState` is called only when that tab is first shown. Falls back to loading the URL if the state can't be restored (e.g. after a WebView update).
- **Evicted tabs keep history**: LRU eviction (max 3 live WebViews) and onTrimMemory now `saveState` before destroy and `restoreState` on next show.
- **下载管理 sheet**: menu → 下载. Lists DownloadManager rows (system only returns this app's) + files saved from blob:/data:. Live progress (polls 0.7 s while something is active, 2.5 s otherwise, only while the sheet is open), tap to open (content URI + grant), button to cancel/delete, link to system Downloads.
- **data:/blob: downloads**: data: decoded off-main and saved; blob: → one-shot UUID token + `evaluateJavascript` that fetches the blob in-page, FileReader→base64 → `window.UmuBlobBridge.onData(token,…)`. Bridge ignores any call without a live token we issued from the DownloadListener (pages can't push files on their own), max 3 pending, 50 MB cap. Saved via MediaStore.Downloads on Q+ (no permission), file + DownloadManager.addCompletedDownload on 7–9 (uses existing WRITE_EXTERNAL_STORAGE ≤28 prompt).
- **Blocklist**: `app/src/main/assets/adblock_hosts.txt` = AdAway default hosts (https://adaway.org/hosts.txt, fetched 2026-11-25), **CC BY 3.0**, attribution in file header + Settings footer. ~6.5k hosts + built-ins, loaded on an IO thread at startup into a HashSet (built-in small list is used until loaded); suffix matching; subresources only. Checked: none of the sticker sites or their main domains is listed.
- **History**: back to 500 entries, moved to its own DataStore file (`cute_history`) so settings writes don't rewrite history and vice versa; old v1.x history migrated once.
- **R8**: explicit keep rules for `@JavascriptInterface` methods and `BlobBridge` in proguard-rules.pro; BlobBridge is also `@Keep`.
- **WebViewFeature** calls wrapped in runCatching (it throws when no WebView provider, e.g. Robolectric).
- `profiling` build type (release minus minify/obfuscation, non-debuggable, debug-signed) exists only to capture a real ART profile on an emulator.
- **Startup**: EmojiCompat's androidx.startup initializer removed in the manifest (it queries the GMS downloadable-font provider at launch; we use system emoji fonts and avoid emoji newer than Unicode 11). ProfileInstaller initializer kept.

### v1.2 verification status (honest)
- Unit/Robolectric tests: 7 pass (URL parsing, ad-block suffix matching + bundled list ≥5000 hosts, data: URL decode + filenames, session JSON + state Bundle round trip, Compose screenshots incl. 下载管理 empty state).
- Release APK: signed (CN=UmuUmu), label 唔姆唔姆, v1.2 (code 3), contains assets/dexopt/baseline.prof and adblock_hosts.txt; `onData`/`onError`/`UmuBlobBridge` names present in release dex (R8 keep rules work).
- **Emulator**: KVM is unusable in this box (VM never starts); a software-CPU (TCG, `ANDROID_I_WANT_MY_TCG=yes -accel off`) Android 14 emulator did boot. The v1.2 release APK installed and launched and rendered the home page correctly (screens/10_emulator_release_home.png), and its WebView fetched a local test page — but the emulated system_server repeatedly ANR'd/died under TCG, so downloads, dark web pages, session restore, tab eviction were NOT exercised at runtime. Test page kept at /workspace/umu-testsite (server.py; open http://10.0.2.2:8000/ from an emulator) for a future device run.
- **Baseline profile**: still the hand-written wildcard profile (all com.yuka.cutebrowser code + libraries' own bundled profiles). A real one needs a stable device/emulator to run the app and `adb shell pm dump-profiles` (use the `profiling` build type) or Macrobenchmark BaselineProfileRule — not possible here.

## v1.3 — security hardening ("IronFox-like", plan A: System WebView kept, hardened at app level)
New code: `filter/FilterEngine.kt` (ABP/uBO subset engine), `security/SecurityUtils.kt` (HttpsUpgrade, UserAgents), `security/WebHardening.kt` (WebView lockdown, document-start script, UA metadata, profiles, blob bridge), `ui/SecurityUi.kt` (lock screen, HTTPS/cert warning page, permission dialog, shield chip/dialog, incognito home, 安全与隐私 + 网站权限 sheets). `SecurityPrefs` is stored as JSON in DataStore key `security`. Also new keys: `site_perms`, `http_allowed`, `adblock_off`.

### What each item does
1. **HTTPS-only** (default on): `HttpsUpgrade.upgrade()` rewrites http→https for typed/loaded URLs, for main-frame link navigations and redirects (shouldOverrideUrlLoading), and as a fallback in onPageStarted (back/forward/restored entries). localhost/127.*/::1 are exempt. When the upgraded https load fails (onReceivedError main frame, except DNS failure) or the cert is bad on an upgraded URL, a cute interstitial is shown with 「返回」 / 「仍然用 http 打开」. Choosing http remembers the host (DataStore; only for the session in incognito); 「重新对所有网站使用 HTTPS」 in Security resets it. A bad cert on a real https site shows the warning with **no** continue button (handler.cancel() always). Mixed content: `MIXED_CONTENT_NEVER_ALLOW`. `usesCleartextTraffic` stays true only so the explicit "continue with http" choice can work.
2. **Third-party cookies blocked** (default on) via `CookieManager.setAcceptThirdPartyCookies(view, false)` per WebView (and per incognito profile's CookieManager). Third-party *storage* partitioning is not controllable from the WebView API; recent Chromium WebView partitions third-party storage by itself, but we can't force or verify it per version.
3. **Filter engine** (`FilterEngine`): supports `||host^`, `|start`, `end|`, `*`, `^`, `@@` exceptions, `$important`, `$third-party`/`~third-party`/`1p`/`3p`, `$domain=a|~b`, resource types (`script,image,stylesheet,xmlhttprequest,subdocument,media,font,object,ping,websocket,other` + `~` negation), `@@…$document` (whole-page allow), `@@…$elemhide` and domain-specific `example.com##sel` / `#@#` cosmetic rules (injected as one `<style>` on commit/finish). Skipped on purpose: regex rules, `$redirect/$csp/$removeparam/$replace/…`, popup/generichide-type rules, scriptlets (`##+js`), procedural cosmetics (`:has-text` etc.), and *generic* `##` rules (they would mean injecting ~10k selectors into every page — slow). Request type is inferred from URL extension + Accept header (WebView doesn't tell us the type); unknown type ⇒ type-restricted rules still apply. "Third-party" is computed against the top-level page host with an approximate eTLD+1 (common 2nd-level suffixes like co.uk/com.cn; no full Public Suffix List).
   - Index: plain `||host^` rules in a HashSet (suffix walk), host-anchored rules with options keyed by host, everything else keyed by its rarest token; tiny no-token list.
   - Loading: `AdBlocker.load()` runs on a background-priority thread at startup. First run compiles the bundled lists (EasyList + EasyPrivacy + AdAway hosts + built-ins) and writes `filesDir/filters/compiled-<versionCode>-<lastUpdateTime>.bin` (binary, ~3.4 MB); later runs just read the cache. Until ready, the old small built-in host list is used, so startup never waits.
   - Shield: per-tab blocked counter (AtomicInteger, coalesced main-thread post) shown in the address bar (🛡️N, ⚠️ on http). Tap → dialog with https status, count, and a per-site 「在这个网站拦截广告」 toggle (stored in `adblock_off`).
4. **DNS over HTTPS — not implemented in-app (decision).** The only WebView hook is `ProxyController` + a local proxy that would resolve via DoH; that adds a hop for every request, breaks HTTP/3/QUIC, needs a robust CONNECT/HTTP proxy in-process and is a new failure point — contrary to "快、流畅". Instead the Security sheet shows the system **Private DNS (DoT)** status (`LinkProperties.isPrivateDnsActive/privateDnsServerName`, API 28+) and a button to open system settings (`android.settings.PRIVATE_DNS_SETTINGS`, falling back to wireless/settings), with suggested hostnames.
5. **WebRTC IP leak protection** (default on): a document-start script (`WebViewCompat.addDocumentStartJavaScript`, all origins, all frames) replaces `RTCPeerConnection` & friends with non-configurable `undefined`. Fallback for old WebViews without DOCUMENT_START_SCRIPT: evaluateJavascript at onPageStarted (can race with page scripts). Camera/mic are also deny-by-default (item 8). Note: Chromium already hides local IPs behind mDNS for non-media WebRTC; the script additionally stops STUN-based public-IP discovery. Web video calls won't work while this is on (setting explains it).
6. **Incognito** (menu → 隐身标签, or the mask button in 标签页): separate tabs flagged `incognito`, never written to session, never added to history, permission choices not remembered, http exceptions session-only, no spare WebView reuse. With `WebViewFeature.MULTI_PROFILE` each incognito session gets its own profile `incognito-<ts>` (`WebViewCompat.setProfile` immediately after construction) ⇒ separate cookies, cache, localStorage/IndexedDB, service workers; when the last incognito tab closes the profile is deleted (`ProfileStore.deleteProfile`, after WebView teardown); leftover profiles (e.g. process killed) are deleted at next idle prewarm. **Fallback** (WebView too old): `LOAD_NO_CACHE` only — cookies/storage are shared with normal tabs; the incognito home page says so honestly. Look: dark-lavender palette (`IncognitoPalette`), WebViews get dark theme, masked bunny mascot, 「悄悄模式」 home, FLAG_SECURE always on.
7. **Generic UA** (default on): strips `; wv`, `Version/4.0`, model/build (`(Linux; Android 10; K)`) and reduces Chrome to `major.0.0.0` (same shape as Chrome's reduced UA). With USER_AGENT_METADATA, client hints get model "" , platformVersion "10.0.0" and the "Android WebView" brand removed. **DNT/GPC** (default on): `navigator.globalPrivacyControl=true`, `navigator.doNotTrack="1"` via the document-start script, and `DNT: 1` + `Sec-GPC: 1` headers on loads we start (`loadUrl` with headers). WebView gives no way to add headers to link clicks or subresources (only by re-fetching every request ourselves, which is slow and breaks things), so those requests don't carry the headers.
8. **Site permissions**: geolocation, camera, mic, protected media (DRM), MIDI → cute dialog every time unless remembered; dismiss = deny; default deny. Allowed ⇒ then the Android runtime permission is requested (CAMERA/RECORD_AUDIO/location added to manifest, uses-feature required=false). Geolocation callback uses `retain=false` (we store choices, not WebView). Manage in 安全与隐私 → 网站权限 (forget one / reset all). **Web Notifications are not supported by Android WebView at all** (Notification API unavailable), so there is nothing to prompt for.
9. **WebView lockdown**: allowFileAccess/allowContentAccess/file-URL access off; Safe Browsing on (manifest meta-data + `WebSettingsCompat.setSafeBrowsingEnabled`); WebView metrics opt-out; attribution reporting disabled; saveFormData/savePassword off; autofill excluded (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, API 26+) unless enabled in settings; `setSupportMultipleWindows(true)` + `javaScriptCanOpenWindowsAutomatically=false` + `onCreateWindow` that only opens a new tab when `isUserGesture` (popups without a click are dropped); render-process-gone handled (tab WebView rebuilt instead of app crash); `allowBackup=false`. **Only JS exposure**: the blob-download bridge `UmuBlobBridge.postMessage(json)`. Preferred path is `WebViewCompat.addWebMessageListener` (we get `sourceOrigin` + `isMainFrame`; messages from iframes are ignored and the origin must equal the page origin recorded when the download started, plus the one-time UUID token). Old WebViews fall back to `addJavascriptInterface` with the same JS API (token check only — origin can't be verified there).
10. **App protection**: FLAG_SECURE always for incognito, optional for normal tabs, and while locked. **App lock** (androidx.biometric 1.1.0, BIOMETRIC_WEAK | DEVICE_CREDENTIAL; MainActivity is now a FragmentActivity): locks at cold start and after >15 s in background; turning it off requires authentication; refuses to enable if the device has no screen lock; on API 33+ recents screenshots are disabled while the lock is enabled. **Clear data on exit**: when on, tabs are never written to disk; on finishing the activity cookies, web storage, HTTP cache and history are cleared (bookmarks kept); a marker file makes the next cold start finish the job if the process was killed (swiped away) before clearing (cache dir deleted before WebView loads).
11. **Settings → 安全与隐私**: all toggles above, DNS section, always-on protections list, filter rule count + load time, WebView package/version and whether incognito isolation is available.

### Licences
- **EasyList / EasyPrivacy** (https://easylist.to, The EasyList authors) are dual-licensed **GPLv3 or CC BY-SA 3.0** (https://easylist.to/pages/licence.html). We use them under **CC BY-SA 3.0**: attribution is in the asset headers and in Settings; we state they were modified (comments and generic element-hiding rules removed). ShareAlike applies to the *lists*: if the modified list files (or the compiled cache derived from them) are redistributed, they must stay under CC BY-SA 3.0 — the app code itself is not affected. Choosing GPLv3 instead would arguably make the APK a GPL distribution, so CC BY-SA is the safer choice for a closed-source app. Concern: some lawyers consider bundling a CC BY-SA database inside an APK to create an "adaptation" question; mere aggregation is the common reading (uBlock Origin, Brave, AdGuard do the same).
- **AdAway hosts**: CC BY 3.0, attribution in file header + Settings.
- androidx.biometric / fragment: Apache 2.0.

### Measured (JVM unit test `FilterEngineTest`, this box, not a phone)
- 106,589 network + 16,657 cosmetic rules. Full compile ≈ 220–390 ms; binary cache 3.38 MB; cache load ≈ 40–70 ms; **≈ 2.8–4.3 µs per request** match (200k requests over a realistic URL mix, after JIT warm-up); engine heap ≈ 14.5 MB. Phones are slower than this box (expect several × for compile/load), but it runs on a background thread and only once per app version.
- **Not measured**: on-device startup time, page-load time, memory on ART, battery. No on-device numbers are claimed.

### Sizes
- Release APK: 1,310,568 (v1.2) → **2,961,276** bytes (v1.3). +1.1 MB compressed filter lists (easylist 663 KB + easyprivacy 444 KB compressed), ≈ +250 KB dex (androidx.biometric pulls in fragment + parts of appcompat; plus our new code). DEX method refs 15,675 → 19,645.
- Debug APK: 10,066,257 → 12,620,587 bytes.

### Not possible / limited with System WebView (honest list)
- No real DoH inside WebView without a local proxy (see 4); no per-request DNS control.
- No WebRTC IP-handling policy API; JS removal is the only lever (old WebViews: racy fallback).
- No way to add DNT/Sec-GPC headers to navigations/subresources started by the page; no header stripping (Referer, etc.) without re-implementing networking.
- Request resource type isn't exposed in shouldInterceptRequest (inferred); shouldInterceptRequest isn't called for some requests (e.g. service-worker-handled requests unless a ServiceWorkerClient is set — not done; WebSocket upgrades), so blocking isn't 100%.
- No fingerprinting resistance at engine level (canvas/WebGL/fonts/screen) like Firefox's RFP; only UA/client hints are generalised. Chromium version is still visible.
- Third-party storage partitioning and bounce-tracking mitigation depend on the installed WebView version; not configurable.
- Incognito isolation requires a WebView with MULTI_PROFILE (Chromium ~M123+); older ones share cookies.
- Safe Browsing depends on Google Play Services / WebView support; can't be verified offline.
- Web Notifications/push don't exist in WebView.
- Security patches for the engine come from the System WebView updates (Play Store), not from us.

### v1.3 verification status (honest)
- Unit/Robolectric tests: **15 pass** — FilterEngineTest (5: anchors/separators, exceptions/important/$domain/types, unsupported-rule skipping, cosmetic + $document, full-list compile/cache/speed), SecurityUtilsTest (2: http→https upgrade incl. :80 / localhost / allowlist; generic UA), StorageTest (incl. full engine load via Android assets + cache file round trip), plus the previous ones and 3 screenshot tests.
- Screenshots (Compose UI only): screens/11_security.png, 12_security_more.png, 13_incognito_home.png, 14_tabs_incognito.png, 15_https_warning.png, 16_permission_dialog.png. (Robolectric has no WebView provider, so 13 shows the "not isolated" fallback text.)
- Release APK: signed with the project key (CN=UmuUmu), versionCode 4 / 1.3; `BlobBridge.postMessage` survives R8.
- Runtime (TCG software emulator, Android 14): the v1.3 release APK installed (`Success`) and launched from a VIEW intent for `http://10.0.2.2:8000/`. One screenshot (screens/17_emulator_v13_release_loading.png) shows the app running with the green 🛡️ chip, which means the tab's URL was already https (so the http→https upgrade ran), with the page still loading. After that the emulator's keyguard came up and stayed stuck despite `locksettings set-disabled`, wakeup and dismiss-keyguard, so every later screencap was black. The HTTPS-failed interstitial, filter blocking/counter, incognito profile isolation, permission dialogs, app lock/biometric, FLAG_SECURE, clear-on-exit and blob downloads were **not** exercised on a device. Treat them as compile-checked + unit/screenshot-tested only.

### Progress log (v1.3)
- 2026-11-26 01:19 (UTC+8): all 11 items implemented; release + debug built and copied; source zip refreshed.
- 2026-11-26 01:35 (UTC+8): fixed test isolation (AdBlocker.resetForTest); all 15 tests pass in one run; final release/debug rebuilt; zip refreshed (48 files, no keystore); emulator attempt done (see verification). v1.3 complete.

### Progress log (v1.4)
- 2026-11-26 02:15 (UTC+8): main was clean vs v1.3-baseline (9afbb38). Merged feature/doh then feature/antifp — git auto-merged with **no textual conflicts**; semantic check done by reading the merged diff. Bumped to 1.4 / code 5, added okhttp dontwarn rules, fixed stale DNS card wording. Next: tests, builds, screenshots, notes fold-in, smoke test, tag.


## v1.4 — built-in DoH + anti-fingerprinting (merged from feature/doh and feature/antifp)
Merge: feature/doh and feature/antifp were merged into main from baseline 9afbb38 (tag v1.3-baseline) with no textual conflicts. They touch the same files in disjoint places (MainActivity: DoH start/awaitInitial + fpOff collector; BrowserController: prewarm via DohManager + FingerprintShield hooks; SecurityPrefs: `fp`/`fps` keys; SecurityUi: shield dialog toggle + DoH section + level picker; build.gradle: okhttp). The v1.3 "Private DNS" card text was updated since it claimed App-level forwarding was a bad idea (DoH is now an option, default off). R8: okhttp 4.12 ships consumer rules; extra `-dontwarn` for bouncycastle/conscrypt/openjsse/okhttp platform added. Anti-fp needs no keep rules (asset script, no reflection). Screenshot tests now write to cute-browser-release/screens (21–27).

## 内置加密 DNS（DoH）— feature/doh

Branch `feature/doh` off `v1.3-baseline`. Everything lives in `app/src/main/java/com/yuka/cutebrowser/dns/` + `ui/DohSettings.kt`; touch points elsewhere are 1–3 lines each (see "Touch points").

### Design
- **LocalProxy** (`dns/LocalProxy.kt`): HTTP proxy on `127.0.0.1:<random>` (ServerSocket, backlog 128).
  - `CONNECT host:port` → resolve via DoH → connect → `200 Connection Established` → raw byte relay (TLS stays end-to-end; WebView still validates certs).
  - Plain `GET http://host/…` → request line rewritten to origin-form, `Proxy-Connection`/`Connection`/`Keep-Alive`/`Proxy-Authorization` dropped, `Connection: close` added (so a client connection is never reused for another host).
  - Errors: unresolvable / connect failure → `502` (WebView shows ERR_TUNNEL_CONNECTION_FAILED, not ERR_NAME_NOT_RESOLVED); proxy-to-itself → `403`; garbage → `400`.
  - Threads: 2 blocking relay threads per tunnel (128 KB stacks, daemon, cached pool), TCP_NODELAY, half-close propagated (shutdownOutput), both sockets closed when both directions finish. Chromium opens ≤32 sockets per proxy, so ≤~64 relay threads.
  - Happy eyeballs (RFC 8305): addresses IPv6/IPv4 interleaved, non-blocking connects started 250 ms apart (or immediately after a failure) on one Selector, first success wins. If IPv6 loses/fails, IPv4 goes first for 10 minutes.
- **DohResolver** (`dns/DohResolver.kt`): A + AAAA in parallel; returns once A is in plus ≤50 ms for AAAA (RFC 8305 resolution delay). Coalesces concurrent identical queries. IP literals / localhost skip DNS.
  - NXDOMAIN / NODATA on both → `UnknownHostException`, **never** falls back to system DNS.
  - Transport errors/timeouts (5 s) / SERVFAIL: **fail-open (default)** → `InetAddress.getAllByName` + toast (max once/60 s) "加密 DNS 暂时连不上，先用系统 DNS 啦 🐰"; **strict mode** → fail.
- **Transport** (`OkHttpDohTransport`): RFC 8484 GET `?dns=<base64url>` with `Accept: application/dns-message`, OkHttp 4.12 (HTTP/2 multiplexing, pool of 2 idle conns / 5 min). Connects to hard-coded bootstrap IPs (custom Dns), certificate checked against the provider hostname. Custom URL with a hostname → that one hostname is looked up via system DNS (IP-literal URLs need no lookup).
- **DnsMessage** (`dns/DnsMessage.kt`): own wire-format encoder/decoder: EDNS0 OPT + RFC 8467 padding to 128-byte blocks, compression pointers with loop guard, TTL high-bit = 0, negative TTL from SOA (RFC 2308), answer-for-wrong-question rejected.
- **DnsCache** (`dns/DnsCache.kt`): LRU 512 entries, TTL clamped 30 s–6 h, negative 15–300 s, serve-stale up to 5 min while refreshing in background (positive answers only). In-memory only (never on disk).
- **DohManager** (`dns/DohManager.kt`): own DataStore file `doh_prefs` (enabled=false, provider=cloudflare, customUrl, strict=false). Applies prefs: start proxy (IO) → `ProxyController.setProxyOverride("127.0.0.1:port")` and waits for the listener (≤2 s) → pre-warms the DoH connection. Disable → `clearProxyOverride` → stop accepting (live tunnels finish naturally) → old resolver closed after 10 s. Provider switch swaps the resolver in place (no proxy restart). `PROXY_OVERRIDE` unsupported → settings show 不支持 card.
- Providers (bootstrap IPs hard-coded): Cloudflare `cloudflare-dns.com` (1.1.1.1, 1.0.0.1, 2606:4700:4700::1111/1001), Google `dns.google` (8.8.8.8, 8.8.4.4, 2001:4860:4860::8888/8844), Quad9 `dns.quad9.net` (9.9.9.9, 149.112.112.112, 2620:fe::fe, 2620:fe::9), 阿里 `dns.alidns.com` (223.5.5.5, 223.6.6.6, 2400:3200::1, 2400:3200:baba::1), 腾讯 DNSPod `doh.pub` (1.12.12.12, 120.53.53.53), 自定义 https URL.
- **Off by default = zero cost**: when off nothing from OkHttp/dns is class-loaded except reading one tiny DataStore file in parallel with the security prefs; `awaitInitial()` before session restore returns as soon as that read completes.

### Touch points outside dns/
- `MainActivity.kt`: import; `DohManager.notice = controller::toast`; `lifecycleScope.launch { DohManager.run(applicationContext) }`; `DohManager.awaitInitial()` right before `controller.restore(saved)` (so the first page never bypasses DoH when it's on).
- `BrowserController.prewarm()`: `InetAddress` warm-up replaced by `DohManager.prewarm(PREWARM_HOSTS)` (DoH when on, system DNS when off); unused `InetAddress` import removed.
- `ui/SecurityUi.kt`: `Gap(); DohSettingsSection()` after the existing 系统私人 DNS card (card/shortcut unchanged).
- `app/build.gradle.kts`: `implementation("com.squareup.okhttp3:okhttp:4.12.0")`.

### Settings UI (安全与隐私 → 加密 DNS)
Toggle「App 内加密 DNS（DoH）」, provider chips (+ custom URL field with https validation), 「测一测 ⏱️」(cold = new TLS connection + query, warm = reused connection) and 「全部测一测」, 严格模式 toggle, status card (运行中/统计: lookups, cache-hit %, avg DoH ms, live tunnels, fallback count) + notes (downloads use system DNS, system proxy ignored). Screens: /workspace/cute-browser-doh-screens/21_doh_settings.png, 22_doh_settings_starry.png, 23_doh_unsupported.png.

### Tests
`app/src/test/java/com/yuka/cutebrowser/dns/`: DnsMessageTest (10), DnsCacheTest (4), DohResolverTest (7: interleave+cache, literals, NXDOMAIN no fallback, fail-open fallback+notice, strict fail-closed, slow AAAA not blocking, coalescing), LocalProxyTest (5: CONNECT echo 200 KB + half-close, HTTP rewrite, 502/403/400, happy-eyeballs past blackhole+refused, host:port parsing), DohScreenshotTest (3), DohLiveTest (2, skipped unless `UMU_LIVE_DOH=1`).
Build in the worktree with its own project cache: `./gradlew --project-cache-dir /workspace/cute-browser-doh/.gradle-doh assembleDebug testDebugUnitTest`.

### Measured (box, US network, JVM — NOT a phone)
Live run (`UMU_LIVE_DOH=1`), OkHttp transport, A query:
- Cloudflare cold ≈150 ms (incl. TLS; first run also JVM class loading) / warm median ≈12–20 ms; Quad9 ≈90 / ≈9 ms; Google ≈130 / ≈70 ms; 阿里 ≈0.9–1.3 s / ≈220 ms; DNSPod ≈1.1–1.3 s / ≈190 ms (CN providers are far from this US box; in mainland China expect the opposite).
- Full HTTPS GET with new connection each time (keep-alive off), median of 5: example.com proxy 97 ms vs direct 93 ms; cloudflare.com 107 vs 107; github.com 138 vs 131. Cached resolve: ~0.02 ms. Caveat: the box's system DNS returns fake 198.18.x IPs through a sandbox egress gateway, so "direct" isn't a clean baseline.
- Unit-test timings: 200 KB echo through a tunnel, happy-eyeballs fallback past a blackhole address < 2 s (one 250 ms step).
- Not measured: anything on Android (no stable device/emulator), memory, battery, release APK size. Debug APK 12,620,587 (v1.3) → 13,169,058 bytes (+OkHttp/okio + dns code, unminified).

### Limitations
- DownloadManager downloads don't use the WebView proxy → their hostnames go to system DNS (blob:/data: downloads are in-page, unaffected).
- Proxy override is app-wide (all WebViews, incl. incognito profiles per docs); the system/Wi-Fi HTTP proxy is ignored while on (VPNs unaffected).
- With a proxy Chromium doesn't do its own DNS: no HTTPS/SVCB records → no ECH, and no DNS-based HTTP/3 hints; QUIC/HTTP3 is not used through an HTTP proxy at all (TCP+TLS only). That's the main speed cost besides the first DoH lookup.
- DNS failures show Chromium's tunnel/proxy error page rather than "address not found".
- Any app on the device can connect to the 127.0.0.1 proxy port while it's on (no auth); it only gives them DNS-over-HTTPS forwarding they could do themselves. Requests to the proxy itself are refused.
- Existing direct keep-alive connections opened before turning it on may be reused briefly.
- The DoH cache is shared by normal and incognito tabs (memory only, cleared on process death).
- ProxyController behaviour, the strict-mode error pages, toast, and real-world speed are compile/unit-tested only — not run on a device.

## v1.4 防指纹 (anti-fingerprinting) — branch feature/antifp

(To be folded into NOTES.md when merging.)

Brave-style "farbling": small per-site deterministic noise + normalising high-entropy values, injected with
`WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))` (all origins, all frames, before page scripts) — the same
mechanism as the v1.3 WebRTC/GPC script, but a separate ScriptHandler so it can be swapped without rebuilding WebViews.

### Files
- `fp-js/fingerprint.js` — readable source. `fp-js/build.sh` → terser → `app/src/main/assets/fingerprint.min.js` (≈9.3 KB, committed). Re-run build.sh after editing the source.
- `privacy/fingerprint/FpSeed.kt` — eTLD+1 (`site()`, same approximate suffix list as `filter/Domains.SECOND_LEVEL`, now public), FNV-1a + fmix32 seed, `newKey()` (SecureRandom 128-bit hex).
- `privacy/fingerprint/FingerprintShield.kt` — loads asset (background preload), builds config JSON, attaches per WebView, swaps scripts on setting changes (`update()` → `ScriptHandler.remove()` + re-add on live WebViews only), incognito key, fallback for old WebViews.
- Touch points: `BrowserController` (field, `attach` in setupWebView, `update` in applySecurity, `setFpExceptions`, `setSiteFingerprint`, fallback in onPageStarted, `endIncognito`), `CuteRepository` (`SecurityPrefs.antiFp/fpStrict` = JSON keys `fp`/`fps`; DataStore key `fp_off` = exempt eTLD+1 set), `MainActivity` (load `fp_off` before first page + collect), `SecurityUi` (toggle + 标准/严格 picker in 安全与隐私; shield dialog line + 「在这个网站防指纹」), `FilterEngine` (`SECOND_LEVEL` made public).

### Seeds
- Session key: random per process start (= app restart). Incognito: own key, created with the first incognito WebView, dropped when the incognito session ends.
- Site = approx eTLD+1 of the **top-level** page (iframes: `top.location` if same-origin, else `location.ancestorOrigins` last entry). Seed = fmix32(FNV-1a(key + "|" + site)). Kotlin and JS implementations are cross-checked (`FpSeedTest` vectors == `node fp-js/seed-vectors.mjs --check`).
- So: same site → same values all session (sites don't break on reload), different sites → different values, restart → new values.

### What is covered (config `l`: 1 standard, 2 strict)
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

### Verification (2026-11-26, this box)
- `fp-js/chrome-test.mjs`: headless desktop Chrome 156 + CDP `Page.addScriptToEvaluateOnNewDocument` (same timing semantics as WebView document-start scripts) against `fp-js/fp-testpage.html` (copy of /workspace/umu-testsite/fp.html) on fake hosts mapped to 127.0.0.1: **all checks pass** — canvas/imageData/WebGL readPixels/audio/measureText change vs unprotected, stable on reload, equal across subdomains of one eTLD+1, differ across sites and across session keys, exception site = unprotected; iframe (incl. dynamically created about:blank) patched; toString native; strict TZ/screen/DPR/timer.
- Script cost: ≈0.45 ms per frame warm, ≈0.8 ms first run incl. parse (desktop x86; expect 2–4× on a phone).
- No new JS exceptions on bing.com, baidu.com, bilibili.com, Google Maps, map.baidu.com (standard and strict) in desktop Chrome.
- Unit tests: `FpSeedTest` (eTLD+1, seed vectors vs JS, per-site/per-key differences, script assembly + incognito key rotation + exceptions, `node --check` of the assembled script), `FpScreenshotTest` (UI shots to app/build/fp-screens with -Proborazzi.test.record=true). Full suite: 21 tests pass. `assembleDebug` OK.
- **Not run on Android WebView / a device.** Desktop Chrome ≠ Android WebView (e.g. WebView feature set, whether document-start scripts run in about:blank frames).

### Limitations (honest)
- Chromium engine & version remain visible (UA/Client Hints major version, JS/CSS feature set, Android WebView quirks). We look like "a Chromium on Android with fingerprint protection", not like everyone else.
- Spoofing is detectable by dedicated tools (CreepJS-style "lie" detection: stack traces through our wrappers, cross-realm/worker comparisons, Proxy behaviour, inconsistencies like screen vs CSS media queries or timezone vs Date constructor). Typical commercial tracker scripts are defeated (their hash changes per site/session); targeted detection isn't.
- Not covered: Web Workers / SharedWorkers / ServiceWorkers (document-start scripts don't run there → real hardwareConcurrency, OffscreenCanvas in workers un-noised); CSS media queries (`device-width`, `resolution`), `window.outerWidth/outerHeight`; DOM-based font enumeration via element offsetWidth; `document.fonts.check`; `Intl` locale/time-zone outside DateTimeFormat; `new Date(y,m,d…)`/`Date.parse` still local in strict; HTTP headers (Accept-Language is the device locale); WebGL prototype methods captured by a page *before* it ever calls getContext('webgl'); `window[0]`/`frames[0]` access to a fresh about:blank iframe bypasses the contentWindow hook.
- Old WebViews without DOCUMENT_START_SCRIPT: fallback evaluateJavascript in onPageStarted, main frame only, can run after page scripts.
- Breakage risks: strict timezone shows wrong local times (by design); blocked device sensors break tilt/360° content and some games; generic WebGL renderer may make a few WebGL apps pick conservative paths; canvas noise could affect pixel-exact canvas apps (CAPTCHA/image hashing) — per-site exception exists; languages forced zh-CN even if device locale differs.

### v1.4 final status
- Tests (all in one run, `testDebugUnitTest -Proborazzi.test.record=true`): 52 run, 50 pass, 2 skipped (DohLiveTest — needs network). Sizes: release 3,122,982 B (v1.3 2,961,276), debug 12,966,703 B (v1.3 12,620,587); DEX method refs 20,889 (v1.3 19,645).
- Screenshots: screens/21–23 (DoH), 24–26 (anti-fp), 27 (merged security sheet).
- Release APK signed CN=UmuUmu, versionCode 5 / 1.4. Release mapping confirms okhttp3, DohManager, LocalProxy, FingerprintShield retained.
- Emulator (TCG, Android 14) smoke test of v1.4: boot OK (~4 min), release APK installed, VIEW intent for http://10.0.2.2:8000/fp.html sent; the emulated keyguard stayed up (`isKeyguardShowing=true`) and system_server became unstable (ActivityManager binder failures in the app's crash log), so no usable screenshot/UI dump. **Not runtime-verified on a device/emulator:** anti-fp on fp.html, DoH toggle/proxy/ProxyController, and all v1.3 security features. Anti-fp JS was exercised only in desktop Chrome (fp-js/chrome-test.mjs, per NOTES above); DoH resolver/proxy only in JVM unit tests (+ skipped live tests).

## v1.5 — 伪造时区和语言 (versionCode 6)
Settings → 安全与隐私 → 「时区与语言」; per-site via the shield chip (「这个网站的时区和语言」). Independent of 防指纹 (separate master toggle `SecurityPrefs.tzOn`; when anti-fp is off the same script is still injected with `l=0`, only the tz/locale block runs).

### Choices / UI
- Timezone: any IANA zone (searchable by id, Chinese name or `UTC+9`; ~19 common zones first: Asia/Shanghai, Tokyo, Los_Angeles, New_York, London, UTC…), 「跟随系统」(""), 「每个网站随机」("*": per-eTLD+1 pick from a 22-zone pool, stable for the app session via the same seed as anti-fp). Site overrides additionally have 「跟随全局」(no override) and 「用系统真实值」("-").
- Language: zh-CN, zh-TW, en-US, en-GB, ja-JP, ko-KR, fr-FR, de-DE, es-ES, ru-RU + custom BCP-47 tag (validated/normalised `ja_jp`→`ja-JP`).
- Presets (一键搭配): 美国西部(LA+en-US), 日本(Tokyo+ja-JP), 英国, 韩国, 德国. IP/VPN reminder is in the UI.
- Strict anti-fp defaults the zone to UTC (unchanged) but an explicit zone overrides it. Anti-fp on + no language chosen → everything (JS, Intl, header) normalised to zh-CN (v1.4 behaviour, now also for the header); explicit language wins, also when anti-fp is off. Site anti-fp exemption → real language unless the user chose one.
- UI text style is pinned to `zh-CN` locale list (default `Locale` changes with the spoof; otherwise Compose would pick JP/KR glyph variants for Chinese text).
- Storage: `SecurityPrefs.tzOn/tz/lang` (JSON keys tzon/tz/lang), per-site map in DataStore key `tzlang_sites` (JSON `{site:{z,g}}`).

### Code map
- `fp-js/fingerprint.js` (→ `assets/fingerprint.min.js` via `fp-js/build.sh`): config fields `z` global zone, `g` global lang, `o` per-site `{z,g}`, `rz` random pool (plus old `k l x p`).
- `privacy/locale/TzLang.kt`: `SiteTzLang`, `SpoofConfig`, `TzLang` (lists, search, normalise, Accept-Language string), `ProcessLocale`, `LangPolicy`.
- `FingerprintShield`: builds config, `active` = anti-fp on OR spoof has effect. `BrowserController`: process locale, per-site headers, WebView rebuild on locale change. `dns/LocalProxy` + `DohManager.acceptLanguageFor`: plain-HTTP header rewrite. `ui/TzLangUi.kt`: section, pickers, site dialog.

### What the script covers (all driven by one zone/locale, DST-correct)
- Offset model = `Intl.DateTimeFormat(…,{timeZone}).formatToParts` in the target zone (15-minute bucket cache), `toUtc` implements ECMAScript gap (pre-transition offset) / overlap (earlier instant) rules.
- `Date`: constructor from components and local strings, `Date.parse`/`Date(…)` of local-time strings, all local getters/setters (`getHours`, `setMonth`, …), `getTimezoneOffset`, `toString/toDateString/toTimeString` (with the localised zone name, e.g. 「GMT+0900 (日本标准时间)」), `toLocale{,Date,Time}String`; Proxy-replaced with `[native code]` toString and native name/length.
- `Intl`: `DateTimeFormat` (default timeZone and locale, `resolvedOptions().timeZone/locale`, format/formatToParts/formatRange), `NumberFormat`, `Collator`, `PluralRules`, `RelativeTimeFormat`, `ListFormat`, `DisplayNames`, `Segmenter`, `DurationFormat` (default locale); `Number/BigInt.prototype.toLocaleString`, `String.prototype.localeCompare`.
- `Temporal` (only if the WebView has it; patched when present, exercised only where desktop Chrome 156 has it): `Temporal.Now.timeZoneId()` and the default zone of `Now.zonedDateTimeISO/plainDateTimeISO/plainDateISO/plainTimeISO`; Temporal `toLocaleString` default locale.
- `navigator.language`, `navigator.languages` (`[ja-JP, ja]` / `[fr]`).
- iframes/about:blank/srcdoc patched through the same contentWindow hook as anti-fp.
- Accept-Language header (see next section).

### Accept-Language — how and limits
WebView has no per-WebView Accept-Language API. Chromium builds the header from the **process-wide** `LocaleList.getDefault()`, read when an `AwContents` is created and on attach / configuration change. So:
1. Global language: `ProcessLocale.apply()` (`LocaleList.setDefault`) is called in `BrowserController.init` before any WebView exists, and whenever the global language / anti-fp changes (then all WebViews are rebuilt, history kept). This changes every request (main frame, subresources, XHR/fetch, iframes, https included) natively. Side effect: default `Locale` of the app process is the spoofed one (UI is hard-coded Chinese and text style is pinned to zh-CN).
2. Per-site overrides: WebView can't change the header per site, so for **main-frame** navigations to a site whose effective language differs from the process language the controller re-issues the load with `loadUrl(url, mapOf("Accept-Language" to …))` (`shouldOverrideUrlLoading` for GET, `reloadWithLang` for reload). Subresources/XHR of such a page still carry the process-wide header.
3. Plain `http://` requests while the DoH proxy is active: `LocalProxy` rewrites the Accept-Language line per Host (this covers subresources of http pages too). `https` is end-to-end TLS through the proxy tunnel, so it can't be rewritten.
Limits: POST main-frame loads, sub-frames and fetch/XHR of a *per-site-override* page keep the process language header; `navigator.language` is correct for them regardless (script). Web Workers/SharedWorkers/ServiceWorkers are not covered (document-start scripts don't run there): `Intl`/`Date` inside a worker reports the real zone/locale; their `navigator.language` is derived from the process locale so it matches the header only for the global language. `Intl.supportedValuesOf`, CSS `:lang()` UA defaults, `<input type=date>` UI locale, and the browser's own error pages follow the real system. IP geolocation is not changed (suggest VPN). Android 13+ per-app language is not used.

### Verification
- **Desktop Chrome differential test** `node fp-js/chrome-locale-test.mjs [zone…]`: the oracle is the same Chrome with CDP `Emulation.setTimezoneOverride` / `setLocaleOverride` (no script); the subject is Chrome on real TZ=Asia/Shanghai with the injected script configured for the target. `fp-js/tzlang-testpage.html` runs hundreds of calls per page (DST boundaries Mar/Nov, nonexistent and repeated local times, many date-string formats, setters across DST, Intl formatters/parts/range, Temporal, iframe + srcdoc, strict/no-anti-fp variants). Result: **89 checks passed / 0 failed** across 16 zone×locale pairs plus extras (16 zone×locale pairs: America/New_York [July −240 / January −300, `new Date(2026,6,1).getTimezoneOffset()`, EDT/EST names], Europe/London, Asia/Tokyo, America/Los_Angeles, UTC, Asia/Kolkata +5:30, Australia/Lord_Howe (30-min DST), Pacific/Apia, Asia/Kathmandu, America/St_Johns, Europe/Berlin, Africa/Casablanca (inverted Ramadan DST), America/Sao_Paulo, Europe/Moscow, Asia/Shanghai, Pacific/Auckland; plus language-only, zone-only, strict default UTC+zh-CN, explicit zone beating strict, per-site real/override, anti-fp exception site, subdomain → eTLD+1, random zone). `fp-js/chrome-test.mjs` (v1.4 anti-fp) still ALL PASS. Overhead ≈ 3 ms install, ~0.9 µs per `new Date().getHours()`, ~9 µs `toString` (desktop).
- Known, normalised differences vs native Chrome (documented in the harness): `Collator/PluralRules.resolvedOptions().locale` ("en" vs "en-US"), pre-1970 / LMT zone-name quirks, tz names ICU doesn't localise (Casablanca-style), CDP canonicalisation (Katmandu/Calcutta), Temporal length checks.
- Kotlin: `TzLangTest` (8: zone lists/DST offsets/search, tag normalisation, Accept-Language strings, `LangPolicy` matrix, `SpoofConfig`, `FingerprintShield` config incl. anti-fp-off independence and random pool, repository + per-site JSON round-trip incl. corrupt data), `TzLangScreenshotTest` (screens 28–33). Full suite: 62 run, 60 pass, 2 skipped (DohLiveTest). Test notes: DataStore is shared between tests in a JVM → TzLangTest resets first; BasicTextField inside a Robolectric `Dialog` never reports idle, so picker shots only advance the clock.
- Bug found by the screenshot test (not by compile): `BrowserController.tzSites` was declared after `init {}` → NPE at app start; fixed (declared right after `sec`).
- **Not verified on Android WebView / device**: the emulator (TCG) was running; the probe app (`/workspace/wvprobe`, modes A = `LocaleList.setDefault` before WebView, B = createConfigurationContext, C = setDefault after load) installed but the WebView never completed a load (system_server unstable, load avg ≈ 25), so **whether WebView 113+ really takes the header from `LocaleList.getDefault()` as designed, whether loadUrl extra headers carry Accept-Language, and the whole app UI at runtime are unconfirmed**. The design follows Chromium's `AwContents`/`AwSettings` behaviour (Accept-Language from `LocaleUtils`/default locale list) but treat it as unverified. Run `adb shell am start -n probe.wv/probe.P --es mode A --es lang ja-JP --es url http://<host>:8001/` against `wvprobe/server.py` on a real device to confirm.
