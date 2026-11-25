# 内置加密 DNS（DoH）— feature/doh

Branch `feature/doh` off `v1.3-baseline`. Everything lives in `app/src/main/java/com/yuka/cutebrowser/dns/` + `ui/DohSettings.kt`; touch points elsewhere are 1–3 lines each (see "Touch points").

## Design
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

## Touch points outside dns/
- `MainActivity.kt`: import; `DohManager.notice = controller::toast`; `lifecycleScope.launch { DohManager.run(applicationContext) }`; `DohManager.awaitInitial()` right before `controller.restore(saved)` (so the first page never bypasses DoH when it's on).
- `BrowserController.prewarm()`: `InetAddress` warm-up replaced by `DohManager.prewarm(PREWARM_HOSTS)` (DoH when on, system DNS when off); unused `InetAddress` import removed.
- `ui/SecurityUi.kt`: `Gap(); DohSettingsSection()` after the existing 系统私人 DNS card (card/shortcut unchanged).
- `app/build.gradle.kts`: `implementation("com.squareup.okhttp3:okhttp:4.12.0")`.

## Settings UI (安全与隐私 → 加密 DNS)
Toggle「App 内加密 DNS（DoH）」, provider chips (+ custom URL field with https validation), 「测一测 ⏱️」(cold = new TLS connection + query, warm = reused connection) and 「全部测一测」, 严格模式 toggle, status card (运行中/统计: lookups, cache-hit %, avg DoH ms, live tunnels, fallback count) + notes (downloads use system DNS, system proxy ignored). Screens: /workspace/cute-browser-doh-screens/21_doh_settings.png, 22_doh_settings_starry.png, 23_doh_unsupported.png.

## Tests
`app/src/test/java/com/yuka/cutebrowser/dns/`: DnsMessageTest (10), DnsCacheTest (4), DohResolverTest (7: interleave+cache, literals, NXDOMAIN no fallback, fail-open fallback+notice, strict fail-closed, slow AAAA not blocking, coalescing), LocalProxyTest (5: CONNECT echo 200 KB + half-close, HTTP rewrite, 502/403/400, happy-eyeballs past blackhole+refused, host:port parsing), DohScreenshotTest (3), DohLiveTest (2, skipped unless `UMU_LIVE_DOH=1`).
Build in the worktree with its own project cache: `./gradlew --project-cache-dir /workspace/cute-browser-doh/.gradle-doh assembleDebug testDebugUnitTest`.

## Measured (box, US network, JVM — NOT a phone)
Live run (`UMU_LIVE_DOH=1`), OkHttp transport, A query:
- Cloudflare cold ≈150 ms (incl. TLS; first run also JVM class loading) / warm median ≈12–20 ms; Quad9 ≈90 / ≈9 ms; Google ≈130 / ≈70 ms; 阿里 ≈0.9–1.3 s / ≈220 ms; DNSPod ≈1.1–1.3 s / ≈190 ms (CN providers are far from this US box; in mainland China expect the opposite).
- Full HTTPS GET with new connection each time (keep-alive off), median of 5: example.com proxy 97 ms vs direct 93 ms; cloudflare.com 107 vs 107; github.com 138 vs 131. Cached resolve: ~0.02 ms. Caveat: the box's system DNS returns fake 198.18.x IPs through a sandbox egress gateway, so "direct" isn't a clean baseline.
- Unit-test timings: 200 KB echo through a tunnel, happy-eyeballs fallback past a blackhole address < 2 s (one 250 ms step).
- Not measured: anything on Android (no stable device/emulator), memory, battery, release APK size. Debug APK 12,620,587 (v1.3) → 13,169,058 bytes (+OkHttp/okio + dns code, unminified).

## Limitations
- DownloadManager downloads don't use the WebView proxy → their hostnames go to system DNS (blob:/data: downloads are in-page, unaffected).
- Proxy override is app-wide (all WebViews, incl. incognito profiles per docs); the system/Wi-Fi HTTP proxy is ignored while on (VPNs unaffected).
- With a proxy Chromium doesn't do its own DNS: no HTTPS/SVCB records → no ECH, and no DNS-based HTTP/3 hints; QUIC/HTTP3 is not used through an HTTP proxy at all (TCP+TLS only). That's the main speed cost besides the first DoH lookup.
- DNS failures show Chromium's tunnel/proxy error page rather than "address not found".
- Any app on the device can connect to the 127.0.0.1 proxy port while it's on (no auth); it only gives them DNS-over-HTTPS forwarding they could do themselves. Requests to the proxy itself are refused.
- Existing direct keep-alive connections opened before turning it on may be reused briefly.
- The DoH cache is shared by normal and incognito tabs (memory only, cleared on process death).
- ProxyController behaviour, the strict-mode error pages, toast, and real-world speed are compile/unit-tested only — not run on a device.
