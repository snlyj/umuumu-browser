// 用桌面版 Chrome（无头）+ CDP 验证防指纹脚本：Page.addScriptToEvaluateOnNewDocument 和 WebView 的
// addDocumentStartJavaScript 一样，在每个 frame 的网页脚本之前执行。
// 用法：node fp-js/chrome-test.mjs   （需要 /usr/bin/google-chrome 和 python3）
import { spawn } from 'node:child_process';
import { readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const MIN = readFileSync(join(here, '../app/src/main/assets/fingerprint.min.js'), 'utf8');
const SL = ['co.uk', 'com.cn'];
const PORT = 8765, DBG = 9339;
const site = here; // 测试页 fp-js/fp-testpage.html（/workspace/umu-testsite/fp-testpage.html 是同一份，给模拟器/手机用）
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const srv = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: site, stdio: 'ignore' });
const chrome = spawn('google-chrome', ['--headless=new', `--remote-debugging-port=${DBG}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'fpc-'))}`,
  '--no-first-run', '--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--host-resolver-rules=MAP *.test 127.0.0.1, MAP *.co.uk 127.0.0.1', 'about:blank'], { stdio: 'ignore' });
let ws, id = 0; const pending = new Map();
function send(method, params = {}, sessionId) { const i = ++id; ws.send(JSON.stringify({ id: i, method, params, sessionId })); return new Promise((res, rej) => pending.set(i, { res, rej })); }
async function connect() {
  for (let i = 0; i < 50; i++) { try { const v = await (await fetch(`http://127.0.0.1:${DBG}/json/version`)).json(); ws = new WebSocket(v.webSocketDebuggerUrl); break; } catch { await sleep(200); } }
  await new Promise((r) => ws.addEventListener('open', r));
  ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
}
async function measure(url, cfg) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  if (cfg) await send('Page.addScriptToEvaluateOnNewDocument', { source: MIN.replace('__UMU_FP_CONFIG__', JSON.stringify(cfg)) }, sessionId);
  await send('Page.navigate', { url }, sessionId);
  let r;
  for (let i = 0; i < 100; i++) {
    await sleep(150);
    const v = await send('Runtime.evaluate', { expression: 'window.__fp && JSON.stringify(window.__fp)', returnByValue: true }, sessionId);
    if (v.result && v.result.value) { r = JSON.parse(v.result.value); break; }
  }
  await send('Target.closeTarget', { targetId });
  return r;
}
let fails = 0;
function check(name, ok, extra = '') { console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`); if (!ok) fails++; }
try {
  await connect(); await sleep(300);
  const A = `http://www.site-one.test:${PORT}/fp-testpage.html`, A2 = `http://img.site-one.test:${PORT}/fp-testpage.html`, B = `http://shop.other.co.uk:${PORT}/fp-testpage.html`;
  const base = await measure(A, null);
  const k1 = { k: 'aaaa1111bbbb2222', l: 1, x: [], p: SL };
  const a1 = await measure(A, k1), a1b = await measure(A, k1), a2 = await measure(A2, k1), b1 = await measure(B, k1);
  const a1k2 = await measure(A, { ...k1, k: 'cccc3333dddd4444' });
  const ex = await measure(A, { ...k1, x: ['site-one.test'] });
  const st = await measure(A, { ...k1, l: 2 });
  console.log('baseline', JSON.stringify(base));
  console.log('standard', JSON.stringify(a1));
  console.log('strict  ', JSON.stringify(st));
  for (const k of ['canvasDataURL', 'canvasImageData', 'audio', 'glReadPixels', 'measureText']) {
    check(`${k}: differs from no-protection`, a1[k] !== base[k], `${base[k]} -> ${a1[k]}`);
    check(`${k}: stable on reload (same site)`, a1[k] === a1b[k]);
    check(`${k}: same for subdomain of same eTLD+1`, a1[k] === a2[k]);
    check(`${k}: differs on other site`, a1[k] !== b1[k]);
    check(`${k}: differs with new session key`, a1[k] !== a1k2[k]);
    check(`${k}: exception site = unprotected`, ex[k] === base[k]);
  }
  check('toDataURL twice equal', a1.canvasDataURL === a1.canvasDataURL2);
  check('hidden iframe canvas = top canvas', a1.iframeCanvasSame === true);
  check('new iframe canvas = top canvas', a1.newIframeCanvasSame === true);
  check('new iframe navigator patched', a1.iframePlatform === 'Linux armv8l', String(a1.iframePlatform));
  check('WebGL vendor generic', a1.glVendor === 'WebKit' && a1.glRenderer === 'WebKit WebGL', `${base.glVendor} / ${base.glRenderer}`);
  check('hardwareConcurrency 8', a1.hardwareConcurrency === 8);
  
  check('platform', a1.platform === 'Linux armv8l');
  check('languages zh-CN first', JSON.parse(a1.languages)[0] === 'zh-CN' && a1.language === 'zh-CN');
  check('screen bucketed', /^\d+x\d+ avail \d+x\d+ depth 24$/.test(a1.screen), a1.screen + ' (real ' + base.screen + ')');
  check('dpr bucketed', a1.dpr * 2 === Math.round(a1.dpr * 2), String(a1.dpr));
  check('toString looks native', a1.toStringNative === 'true,true,true,true', a1.toStringText);
  check('toString has no extra (symbol) keys, key not leaked', a1.toStringKeys === base.toStringKeys && !a1.toStringKeys.includes('aaaa1111'), a1.toStringKeys);
  check('function name/length/no prototype', a1.fnName === base.fnName, a1.fnName);
  check('gamepads 4 nulls', a1.gamepads === '[null,null,null,null]');
  check('usb/bluetooth/hid/serial/keyboard hidden', !a1.usb && !a1.bluetooth && !a1.hid && !a1.serial && !a1.keyboard);
  // 安全上下文才有的接口（deviceMemory / getBattery）：用 localhost 测
  const L = await measure(`http://localhost:${PORT}/fp-testpage.html`, k1), Lb = await measure(`http://localhost:${PORT}/fp-testpage.html`, null);
  check('secure ctx: deviceMemory 4', L.secure && L.deviceMemory === 4, `${Lb.deviceMemory} -> ${L.deviceMemory}`);
  check('secure ctx: battery constant', L.battery === '1 charging true', `${Lb.battery} -> ${L.battery}`);
  check('secure ctx: usb/hid/serial/bluetooth/keyboard hidden', !L.usb && !L.hid && !L.serial && !L.bluetooth && !L.keyboard, `real: usb ${Lb.usb} hid ${Lb.hid} serial ${Lb.serial} bt ${Lb.bluetooth} kb ${Lb.keyboard}`);
  // 脚本本身的开销（一个新页面里执行一次）
  {
    const { targetId } = await send('Target.createTarget', { url: `http://localhost:${PORT}/fp-testpage.html` });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    await sleep(500);
    const src = MIN.replace('__UMU_FP_CONFIG__', JSON.stringify({ ...k1, k: 'perf0perf0perf0' }));
    const v = await send('Runtime.evaluate', { expression: `(()=>{const t=performance.now();${src};return performance.now()-t})()`, returnByValue: true }, sessionId);
    console.log(`script cost (desktop, one frame, first run incl. parse): ${v.result.value.toFixed(2)} ms`);
    await send('Target.closeTarget', { targetId });
  }
  check('standard keeps timezone', a1.timezone === base.timezone);
  check('strict timezone UTC', /^UTC offset 0$/.test(st.timezone) && /GMT\+0000/.test(st.dateString), st.timezone + ' | ' + st.dateString);
  check('strict screen fixed', st.screen.startsWith('360x800') || st.screen.startsWith('800x360') || st.screen.startsWith('800x1280') || st.screen.startsWith('1280x800'), st.screen);
  check('strict perf.now >= 1ms step', st.perfNowStep >= 1, String(st.perfNowStep));
  check('strict dpr 2', st.dpr === 2);
} catch (e) { console.error(e); fails++; }
finally { chrome.kill('SIGKILL'); srv.kill(); }
console.log(fails ? `${fails} FAILED` : 'ALL PASS');
process.exit(fails ? 1 : 0);
