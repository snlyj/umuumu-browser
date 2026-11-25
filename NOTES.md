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
