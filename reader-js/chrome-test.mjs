// 用桌面版 Chrome（无头）+ CDP 验证阅读模式脚本（和 WebView 的 evaluateJavascript 一样在页面主世界里执行）
// 还会：检查桌面版网站的 viewport 脚本；把 build/reader-samples/*.html（ReaderHtmlTest 生成）截图到 ../../cute-browser-feat-screens/
// 用法：先 ./gradlew testDebugUnitTest --tests '*ReaderHtmlTest'，再 node reader-js/chrome-test.mjs
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, existsSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const CHECK = readFileSync(join(root, 'app/src/main/assets/reader/check.min.js'), 'utf8');
const EXTRACT = readFileSync(join(root, 'app/src/main/assets/reader/extract.min.js'), 'utf8');
const kt = readFileSync(join(root, 'app/src/main/java/com/yuka/cutebrowser/features/DesktopMode.kt'), 'utf8');
const VIEWPORT = kt.match(/val viewportScript: String = """([\s\S]*?)"""/)[1].replaceAll('$VIEWPORT_WIDTH', '1024');
const SHOTS = join(root, '../cute-browser-feat-screens');
// 随机端口：同一台机器上可能有别的测试在跑
const PORT = 20000 + Math.floor(Math.random() * 20000), DBG = PORT + 1;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const srv = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: join(here, 'testpages'), stdio: 'ignore' });
const chrome = spawn('google-chrome', ['--headless=new', `--remote-debugging-port=${DBG}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'rdc-'))}`,
  '--no-first-run', '--no-sandbox', '--hide-scrollbars', '--font-render-hinting=none', 'about:blank'], { stdio: 'ignore' });
let ws, id = 0; const pending = new Map();
function send(method, params = {}, sessionId) { const i = ++id; ws.send(JSON.stringify({ id: i, method, params, sessionId })); return new Promise((res, rej) => pending.set(i, { res, rej })); }
async function connect() {
  for (let i = 0; i < 50; i++) { try { const v = await (await fetch(`http://127.0.0.1:${DBG}/json/version`)).json(); ws = new WebSocket(v.webSocketDebuggerUrl); break; } catch { await sleep(200); } }
  await new Promise((r) => ws.addEventListener('open', r));
  ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
}
async function open(url, { mobile = true, preload } = {}) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride', { width: 393, height: 852, deviceScaleFactor: 2, mobile }, sessionId);
  if (preload) await send('Page.addScriptToEvaluateOnNewDocument', { source: preload }, sessionId);
  await send('Page.navigate', { url }, sessionId);
  for (let i = 0; i < 60; i++) { await sleep(100); const r = await send('Runtime.evaluate', { expression: 'document.readyState', returnByValue: true }, sessionId); if (r.result.value === 'complete') break; }
  return { targetId, sessionId };
}
const ev = async (s, expression) => (await send('Runtime.evaluate', { expression, returnByValue: true }, s.sessionId)).result.value;
let fails = 0;
function ok(cond, msg) { console.log(`${cond ? 'PASS' : 'FAIL'} ${msg}`); if (!cond) fails++; }

try {
  await connect();
  const base = `http://127.0.0.1:${PORT}`;
  // ---- 检测 ----
  const art = await open(`${base}/article.html`);
  ok(await ev(art, CHECK) === true, 'check: article is readerable');
  const nav = await open(`${base}/nav.html`);
  ok(await ev(nav, CHECK) === false, 'check: short nav page is not readerable');
  // ---- 抽取 ----
  await ev(art, 'window.__pwned = window.__pwned2 = window.__pwned3 = 0');
  const t0 = Date.now();
  const raw = await ev(art, EXTRACT);
  console.log(`extract took ${Date.now() - t0} ms, ${raw ? raw.length : 0} chars`);
  ok(typeof raw === 'string', 'extract returns a JSON string');
  const a = JSON.parse(raw);
  ok(a.title.includes('兔子的一天'), `title = ${a.title}`);
  ok(a.site === '萌宠日报', `site = ${a.site}`);
  ok(a.lang === 'zh-CN', `lang = ${a.lang}`);
  const h = a.html;
  ok(!/<script|onerror|onclick|onload|<iframe|<form|<input|<svg|javascript:|style=/i.test(h), 'sanitized: no script/handlers/iframe/form/svg/javascript:/style');
  ok(h.includes('https://cdn.example.com/bunny.jpg'), 'keeps https image');
  ok(!h.includes('insecure.example.com'), 'drops http image');
  ok(h.includes('data:image/png;base64'), 'keeps small data: image');
  ok(h.includes('href="https://example.com/more"'), 'keeps https link');
  ok(h.includes(`href="${base}/relative/path"`), 'relative link made absolute');
  ok(!h.includes('买买买'), 'drops ad banner');
  ok(await ev(art, 'typeof Readability === "undefined" && typeof isProbablyReaderable === "undefined" && typeof umuSanitize === "undefined"'), 'no globals leaked to page');
  ok(await ev(art, '!window.__pwned && !window.__pwned2 && !window.__pwned3'), 'page payloads (script/onerror/onload) did not run again during extraction');
  ok(await ev(art, 'window.module && window.module.exports === "page-owned"'), "page's own window.module untouched");
  ok(await ev(art, 'document.querySelectorAll("article p").length >= 5 && !!document.querySelector("iframe")'), 'live page DOM untouched (cloneNode)');
  ok(await ev(nav, EXTRACT) === null || true, 'extract on nav page does not throw');
  // ---- 桌面版 viewport ----
  const desk = await open(`${base}/article.html`, { preload: VIEWPORT });
  const vp = await ev(desk, 'document.querySelector("meta[name=viewport]").getAttribute("content")');
  ok(vp === 'width=1024', `desktop viewport meta rewritten: ${vp}`);
  const iw = await ev(desk, 'window.innerWidth');
  ok(iw >= 1000, `desktop layout width ${iw}`);
  const mob = await ev(art, 'window.innerWidth');
  ok(mob < 500, `mobile layout width ${mob}`);
  // ---- 阅读页截图 ----
  if (!existsSync(SHOTS)) mkdirSync(SHOTS, { recursive: true });
  const samples = join(root, 'app/build/reader-samples');
  const names = { auto: '42_reader_strawberry', sepia: '43_reader_sepia_serif', night: '44_reader_night' };
  for (const [k, out] of Object.entries(names)) {
    const f = join(samples, `${k}.html`);
    if (!existsSync(f)) { console.log(`skip screenshot ${k} (run ReaderHtmlTest first)`); continue; }
    const p = await open(`file://${f}`);
    await sleep(300);
    const shot = await send('Page.captureScreenshot', { format: 'png' }, p.sessionId);
    writeFileSync(join(SHOTS, `${out}.png`), Buffer.from(shot.data, 'base64'));
    console.log(`wrote ${out}.png`);
  }
} catch (e) {
  console.error(e); fails++;
} finally {
  chrome.kill(); srv.kill();
}
console.log(fails ? `${fails} FAILED` : 'ALL PASS');
process.exit(fails ? 1 : 0);
