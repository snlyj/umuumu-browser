// Worker 覆盖测试：页面里的防护脚本是否也进到了 blob:/data:/module/嵌套/Shared Worker 里，
// 以及 App 在网络层给 http(s) Worker 脚本拼上的前缀（这里用预先拼好的 worker-http-shielded.js 模拟）是否生效。
// 用法：node fp-js/chrome-worker-test.mjs （需要 google-chrome 和 python3）
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const MIN = readFileSync(join(here, '../app/src/main/assets/fingerprint.min.js'), 'utf8');
const PORT = 8767, DBG = 9341;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// 测试用的 http Worker 脚本
const MEASURE_SRC = readFileSync(join(here, 'worker-testpage.html'), 'utf8').match(/const MEASURE = `([\s\S]*?)`;/)[1];
const WBODY = MEASURE_SRC + "\nvar strictMode = (function(){ return this === undefined; })();\nvar m = measure(); m.strict = strictMode; postMessage(m);\n";
const cfg = { k: 'wkwkwkwk11112222', l: 1, x: [], p: ['co.uk'], z: 'Asia/Tokyo', g: 'ja-JP' };
// App 在网络层做的事（WorkerScripts.prefix）：同一份脚本 + ws=顶层网站
const prefixed = MIN.replace('__UMU_FP_CONFIG__', JSON.stringify({ ...cfg, ws: 'site-one.test' })) + '\n' + WBODY;
writeFileSync(join(here, 'worker-http.js'), WBODY);
writeFileSync(join(here, 'worker-http-shielded.js'), prefixed);

const srv = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: here, stdio: 'ignore' });
const chrome = spawn('google-chrome', ['--headless=new', `--remote-debugging-port=${DBG}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'fpw-'))}`,
  '--no-first-run', '--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--host-resolver-rules=MAP *.test 127.0.0.1', 'about:blank'], { stdio: 'ignore' });
let ws, id = 0; const pending = new Map();
function send(method, params = {}, sessionId) { const i = ++id; ws.send(JSON.stringify({ id: i, method, params, sessionId })); return new Promise((res, rej) => pending.set(i, { res, rej })); }
async function connect() {
  for (let i = 0; i < 50; i++) { try { const v = await (await fetch(`http://127.0.0.1:${DBG}/json/version`)).json(); ws = new WebSocket(v.webSocketDebuggerUrl); break; } catch { await sleep(200); } }
  await new Promise((r) => ws.addEventListener('open', r));
  ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
}
async function measure(url, c) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  if (c) await send('Page.addScriptToEvaluateOnNewDocument', { source: MIN.replace('__UMU_FP_CONFIG__', JSON.stringify(c)) }, sessionId);
  await send('Page.navigate', { url }, sessionId);
  let r;
  for (let i = 0; i < 150; i++) {
    await sleep(150);
    const v = await send('Runtime.evaluate', { expression: 'window.__wk && JSON.stringify(window.__wk)', returnByValue: true }, sessionId);
    if (v.result && v.result.value) { r = JSON.parse(v.result.value); break; }
  }
  await send('Target.closeTarget', { targetId });
  return r;
}
let fails = 0;
function check(name, ok, extra = '') { console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`); if (!ok) fails++; }
const same = (a, b) => a && b && a.platform === b.platform && a.hc === b.hc && a.lang === b.lang && a.tz === b.tz && a.tzo === b.tzo && a.canvas === b.canvas;
const show = (o) => o ? `${o.platform}/${o.hc}/${o.lang}/${o.tz}/${o.tzo}/${o.canvas}${o.error ? ' ' + o.error : ''}` : 'undefined';
try {
  await connect(); await sleep(300);
  const A = `http://www.site-one.test:${PORT}/worker-testpage.html`, B = `http://shop.other.test:${PORT}/worker-testpage.html`;
  const base = await measure(A, null);
  const a = await measure(A, cfg);
  const b = await measure(B, cfg);
  const ex = await measure(A, { ...cfg, x: ['site-one.test'], z: '', g: '' });
  console.log('baseline page  ', show(base.page), ' blob', show(base.blob));
  console.log('shielded page  ', show(a.page));
  check('baseline: workers see real values (control)', same(base.page, base.blob) && base.page.platform !== 'Linux armv8l');
  check('page shielded', a.page.platform === 'Linux armv8l' && a.page.lang === 'ja-JP' && a.page.tz === 'Asia/Tokyo' && a.page.tzo === -540 && a.page.canvas !== base.page.canvas, show(a.page));
  for (const k of ['blob', 'blobStrict', 'data', 'module', 'nested']) {
    check(`${k} worker = page (platform/hc/lang/tz/canvas noise)`, same(a[k], a.page), show(a[k]));
    check(`${k} worker differs from unprotected`, a[k] && a[k].canvas !== base[k].canvas && a[k].platform !== base[k].platform);
  }
  check('blob worker revoked right after new Worker() still runs', !a.blob.error);
  check("'use strict' directive kept in blob worker", a.blobStrict.strict === true && base.blobStrict.strict === true);
  check('sloppy worker stays sloppy', a.blob.strict === false);
  check('nested worker sees Worker ctor', a.nested && a.nested.workerType === 'undefined' || a.nested.workerType === 'function');
  console.log('baseline shared', show(base.shared));
  if (a.shared) {
    // SharedWorker 里画出来的文字本来就和页面里不一样（control: baseline 也不同），所以画布只比"和未保护时不同"
    check('SharedWorker = page (platform/hc/lang/tz)', same({ ...a.shared, canvas: 0 }, { ...a.page, canvas: 0 }), show(a.shared));
    check('SharedWorker canvas noised', a.shared.canvas !== base.shared.canvas);
    check('SharedWorker: 2nd connection reaches same instance', a.sharedSecondCount === 2, String(a.sharedSecondCount));
    check('SharedWorker baseline also shared (control)', base.sharedSecondCount === 2);
  } else console.log('SKIP  SharedWorker not available');
  check('http worker without network prefix stays real (expected: App handles it at network layer)', a.http.platform === base.http.platform);
  check('http worker with App prefix (ws=site-one.test) = page', same(a.httpShielded, a.page), show(a.httpShielded));
  check('other site: worker canvas differs from site-one', b.blob.canvas !== a.blob.canvas && same(b.blob, b.page));
  check('exception site: worker unprotected', ex.blob.platform === base.blob.platform && ex.blob.canvas === base.blob.canvas, show(ex.blob));
  check('Worker.toString looks native', a.workerCtorToString === base.workerCtorToString, a.workerCtorToString);
  check('Worker() without new still throws TypeError', a.callWithoutNew === 'TypeError' && base.callWithoutNew === 'TypeError', a.callWithoutNew);
  check('new Worker instanceof / prototype unchanged', a.instanceOk === true);
} catch (e) { console.error(e); fails++; }
finally { chrome.kill('SIGKILL'); srv.kill(); for (const f of ['worker-http.js', 'worker-http-shielded.js']) rmSync(join(here, f), { force: true }); }
console.log(fails ? `${fails} FAILED` : 'ALL PASS');
process.exit(fails ? 1 : 0);
