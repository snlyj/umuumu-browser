// 时区/语言伪造的"对照测试"：
//   标准答案 = 真·Chrome 用 CDP 把时区(Emulation.setTimezoneOverride)、语言(setLocaleOverride + UA 的 acceptLanguage)切成目标值，不注入脚本；
//   被测   = Chrome 保持真实时区(TZ=Asia/Shanghai)，注入 fingerprint.min.js，配置里写目标时区/语言。
// 两边在 tzlang-testpage.html 里跑几百个 API 调用（含夏令时边界、不存在/重复的钟点、各种日期字符串、setter、Intl、Temporal、iframe），逐项比较。
// 用法：node fp-js/chrome-locale-test.mjs [zone ...]   需要 /usr/bin/google-chrome 和 python3
import { spawn } from 'node:child_process';
import { readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const MIN = readFileSync(join(here, '../app/src/main/assets/fingerprint.min.js'), 'utf8');
const PORT = 8766, DBG = 9345, REAL_TZ = 'Asia/Shanghai';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const srv = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: here, stdio: 'ignore' });
const chrome = spawn('google-chrome', ['--headless=new', `--remote-debugging-port=${DBG}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'fpl-'))}`,
  '--no-first-run', '--no-sandbox', '--host-resolver-rules=MAP *.test 127.0.0.1', 'about:blank'], { stdio: 'ignore', env: { ...process.env, TZ: REAL_TZ } });
let ws, id = 0; const pending = new Map();
function send(method, params = {}, sessionId) { const i = ++id; ws.send(JSON.stringify({ id: i, method, params, sessionId })); return new Promise((res, rej) => pending.set(i, { res, rej })); }
async function connect() {
  for (let i = 0; i < 50; i++) { try { const v = await (await fetch(`http://127.0.0.1:${DBG}/json/version`)).json(); ws = new WebSocket(v.webSocketDebuggerUrl); break; } catch { await sleep(200); } }
  await new Promise((r) => ws.addEventListener('open', r));
  ws.addEventListener('message', (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
}
const PAGE = (host = 'www.site-one.test') => `http://${host}:${PORT}/tzlang-testpage.html`;
async function run({ url = PAGE(), cfg, tz, locale }) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  if (tz) await send('Emulation.setTimezoneOverride', { timezoneId: tz }, sessionId);
  if (locale) {
    await send('Emulation.setLocaleOverride', { locale }, sessionId);
    await send('Network.setUserAgentOverride', { userAgent: (await send('Browser.getVersion')).userAgent, acceptLanguage: locale + ',' + locale.split('-')[0] }, sessionId);
  }
  if (cfg) await send('Page.addScriptToEvaluateOnNewDocument', { source: MIN.replace('__UMU_FP_CONFIG__', JSON.stringify(cfg)) }, sessionId);
  await send('Page.navigate', { url }, sessionId);
  let r;
  for (let i = 0; i < 100; i++) {
    await sleep(150);
    const v = await send('Runtime.evaluate', { expression: 'document.title==="done" && JSON.stringify(window.__R)', returnByValue: true }, sessionId);
    if (v.result && v.result.value) { r = JSON.parse(v.result.value); break; }
  }
  await send('Target.closeTarget', { targetId });
  return r;
}
let fails = 0, passes = 0;
function check(name, ok, extra = '') { if (ok) passes++; else { fails++; console.log(`FAIL  ${name}  ${extra}`); } }
const SL = ['co.uk', 'com.cn'];
const base = { k: 'localetestkey0001', l: 0, x: [], p: SL, rz: ['Asia/Tokyo', 'America/New_York', 'Europe/Paris'] };
// 页面里和"真实时区"无关、但标准答案那边也会变的项目（now 相关/语言相关的 navigator）单独断言
// y1900.*：V8 在 1970 年以前对美国夏令时用的是 ICU 外推的"夏令时名字"，没法从 Intl 里推出来（已知小差异，见 NOTES）
const SKIP = new Set(['navigator.language', 'navigator.languages', 'iframe.navigator.language', 'y1900.toString', 'y1900.toTimeString', 'y1900.tpl']);
let LOOSE_NAMES = false; // Casablanca 等：Intl 没有时区的本地化名字，括号里的名字只能是 GMT+01:00，原生 toString 有（已知差异）
function compare(label, ref, got, skip = SKIP) {
  const keys = Object.keys(ref);
  let bad = 0;
  for (const k of keys) {
    if (skip.has(k)) continue;
    // 已知小差异：1) PluralRules/Collator 的 resolvedOptions().locale：显式传 ko-KR 解析成 ko，原生"默认 locale"却直接是 ko-KR
    //            2) 1970 年以前（或 LMT 怪偏移）的日期，V8 的 toString 括号里的名字用 ICU 外推的"夏令时/标准时"名字，Intl 给不出来 → 只比括号外面的部分
    const old = LOOSE_NAMES || /^(y1900|y1950|lmt1883|bc|neg|epoch)\.(toString|toTimeString|tpl)$|^set\.setTime$/.test(k);
    const norm = (v) => (k === 'plural' || k === 'collator' ? String(v).replace(/^[^|]*/, '') : old ? String(v).replace(/\([^)]*\)\s*$/, '') : String(v).replace('Katmandu', 'Kathmandu').replace('Calcutta', 'Kolkata'));
    if (norm(ref[k]) !== norm(got[k])) { bad++; if (bad <= 6) console.log(`  DIFF [${label}] ${k}\n     want ${String(ref[k]).slice(0, 160)}\n     got  ${String(got[k]).slice(0, 160)}`); }
  }
  check(`${label}: ${keys.length} API results identical to native Chrome in that zone/locale`, bad === 0, `${bad} differ`);
}
try {
  await connect(); await sleep(300);
  const real = await run({});
  console.log('real zone sanity:', real['dtf.resolved'], real['jul.getTimezoneOffset'], 'Temporal:', real['temporal.exists']);
  const cases = [
    ['America/New_York', 'en-US'], ['Europe/London', 'en-GB'], ['Asia/Tokyo', 'ja-JP'], ['America/Los_Angeles', 'en-US'], ['UTC', 'zh-CN'],
    ['Asia/Kolkata', 'en-GB'], ['Australia/Lord_Howe', 'en-GB'], ['Pacific/Apia', 'fr-FR'], ['Asia/Kathmandu', 'de-DE'], ['America/St_Johns', 'ko-KR'],
    ['Europe/Berlin', 'de-DE'], ['Africa/Casablanca', 'es-ES'], ['America/Sao_Paulo', 'ru-RU'], ['Europe/Moscow', 'zh-TW'], ['Asia/Shanghai', 'zh-CN'], ['Pacific/Auckland', 'en-US'],
  ].filter(([z]) => process.argv.length <= 2 || process.argv.slice(2).includes(z));
  for (const [z, loc] of cases) {
    const ref = await run({ tz: z, locale: loc });
    const got = await run({ cfg: { ...base, z, g: loc } });
    LOOSE_NAMES = z === 'Africa/Casablanca';
    compare(`${z} + ${loc}`, ref, got);
    check(`${z}: navigator.language`, got['navigator.language'] === loc, got['navigator.language']);
    const base2 = loc.split('-')[0];
    check(`${z}: navigator.languages`, got['navigator.languages'] === JSON.stringify([loc, base2]) + '|true|true', got['navigator.languages']);
    check(`${z}: iframe language`, got['iframe.navigator.language'] === loc, got['iframe.navigator.language']);
    if (z === 'America/New_York') {
      check('NY July: new Date(2026,6,1).getTimezoneOffset() == 240 (DST)', got['jul.getTimezoneOffset'] === '240', got['jul.getTimezoneOffset']);
      check('NY Jan offset 300', got['jan.getTimezoneOffset'] === '300', got['jan.getTimezoneOffset']);
      check('NY: new Date(2026,6,1) = 04:00Z', got['new.ymd'] === String(Date.UTC(2026, 6, 1, 4)), got['new.ymd']);
      check('NY: toString shows EDT long name in July', /GMT-0400 \(Eastern Daylight Time\)/.test(got['jul.toString']), got['jul.toString']);
      check('NY: toString shows EST in January', /GMT-0500 \(Eastern Standard Time\)/.test(got['jan.toString']), got['jan.toString']);
    }
  }
  // 语言单独变（时区跟系统）、时区单独变（语言跟系统）
  {
    const ref = await run({ locale: 'ja-JP' });
    const got = await run({ cfg: { ...base, g: 'ja-JP' } });
    compare('language only (ja-JP)', ref, got);
    const ref2 = await run({ tz: 'America/New_York' }), got2 = await run({ cfg: { ...base, z: 'America/New_York' } });
    compare('timezone only (America/New_York)', ref2, got2, new Set([...SKIP, 'nf.resolved']));
    check('tz only: language untouched', got2['navigator.language'] === real['navigator.language'], got2['navigator.language']);
  }
  // 严格防指纹：默认 UTC（l=2），防指纹开着但没选语言 → zh-CN；站点覆盖/例外
  {
    const ref = await run({ tz: 'UTC', locale: 'zh-CN' });
    const got = await run({ cfg: { ...base, l: 2 } });
    compare('strict fingerprint default (UTC + zh-CN)', ref, got);
    const ov = await run({ cfg: { ...base, l: 2, z: 'Asia/Tokyo' } });
    check('strict + explicit zone: explicit wins', ov['dtf.resolved'].includes('Asia/Tokyo'), ov['dtf.resolved']);
    const real2 = await run({ cfg: { ...base, l: 2, o: { 'site-one.test': { z: '-', g: '-' } } } });
    check('per-site "real" override beats strict UTC', real2['dtf.resolved'] === real['dtf.resolved'] && real2['jul.getTimezoneOffset'] === real['jul.getTimezoneOffset'], real2['dtf.resolved']);
    const std = await run({ cfg: { ...base, l: 1 } });
    check('standard fingerprint keeps real timezone', std['jul.getTimezoneOffset'] === real['jul.getTimezoneOffset']);
    check('standard fingerprint language zh-CN', std['navigator.language'] === 'zh-CN');
    const ex = await run({ cfg: { ...base, l: 2, x: ['site-one.test'] } });
    check('fp exception site: strict UTC not applied', ex['jul.getTimezoneOffset'] === real['jul.getTimezoneOffset']);
    const ex2 = await run({ cfg: { ...base, l: 2, x: ['site-one.test'], z: 'Asia/Tokyo', g: 'ja-JP' } });
    check('fp exception site: explicit zone/lang still applied', ex2['dtf.resolved'].startsWith('ja-JP|Asia/Tokyo'), ex2['dtf.resolved']);
  }
  // 站点覆盖：只有 site-one 用东京；site-two 用全局
  {
    const cfg = { ...base, z: 'America/New_York', g: 'en-US', o: { 'site-one.test': { z: 'Asia/Tokyo', g: 'ja-JP' } } };
    const a = await run({ cfg, url: PAGE('www.site-one.test') }), a2 = await run({ cfg, url: PAGE('m.site-one.test') }), b = await run({ cfg, url: PAGE('www.site-two.test') });
    check('override: site-one.test → Tokyo/ja-JP', a['dtf.resolved'].startsWith('ja-JP|Asia/Tokyo'), a['dtf.resolved']);
    check('override: subdomain follows eTLD+1', a2['dtf.resolved'].startsWith('ja-JP|Asia/Tokyo'), a2['dtf.resolved']);
    check('override: site-two.test → global New_York/en-US', b['dtf.resolved'].startsWith('en-US|America/New_York'), b['dtf.resolved']);
    check('override: iframes (srcdoc) follow site', a['iframe.srcdoc'].endsWith('Asia/Tokyo|ja-JP') && b['iframe.srcdoc'].endsWith('America/New_York|en-US'), a['iframe.srcdoc'] + ' / ' + b['iframe.srcdoc']);
  }
  // 每个网站随机时区：同一站点稳定、不同站点（大概率）不同、换会话密钥会变
  {
    const cfg = { ...base, z: '*', g: 'en-US' };
    const get = async (host, c = cfg) => (await run({ cfg: c, url: PAGE(host) }))['dtf.resolved'].split('|')[1];
    const hosts = ['www.a1.test', 'www.a2.test', 'www.a3.test', 'www.a4.test', 'www.a5.test', 'www.a6.test'];
    const zs = []; for (const h of hosts) zs.push(await get(h));
    const again = await get('www.a1.test'), sub = await get('img.a1.test'), rekey = new Set(); for (const k of ['k1', 'k2', 'k3', 'k4']) rekey.add(await get('www.a1.test', { ...cfg, k }));
    check('random zone: all picked from candidates', zs.every((z) => base.rz.includes(z)), zs.join());
    check('random zone: stable for one site (+ its subdomains)', again === zs[0] && sub === zs[0], `${again} ${sub} ${zs[0]}`);
    check('random zone: differs between sites', new Set(zs).size > 1, zs.join());
    check('random zone: changes with the session key', rekey.size > 1, [...rekey].join());
    const r = await run({ cfg, url: PAGE('www.a1.test') });
    const zone = r['dtf.resolved'].split('|')[1];
    const ref = await run({ tz: zone, locale: 'en-US', url: PAGE('www.a1.test') });
    compare(`random zone (${zone}) vs native`, ref, r);
  }
  // 无效配置不应该弄坏页面
  {
    const bad = await run({ cfg: { ...base, z: 'Mars/Olympus', g: 'xx_@@' } });
    check('invalid zone/lang ignored (page works, real values)', bad['dtf.resolved'] === real['dtf.resolved'] && bad['jul.getTimezoneOffset'] === real['jul.getTimezoneOffset'], bad['dtf.resolved']);
  }
  // 开销：脚本在一个新页面里执行一次（含时区+语言补丁）
  {
    const { targetId } = await send('Target.createTarget', { url: PAGE() });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    await sleep(800);
    const src = MIN.replace('__UMU_FP_CONFIG__', JSON.stringify({ ...base, k: 'perf', z: 'America/New_York', g: 'ja-JP' }));
    const v = await send('Runtime.evaluate', { expression: `(()=>{const f=document.createElement('iframe');document.body.appendChild(f);const w=f.contentWindow;const t=performance.now();w.eval(${JSON.stringify(src)});const a=performance.now()-t;
      const d=new w.Date(2026,6,1);const t2=performance.now();let s=0;for(let i=0;i<20000;i++){s+=new w.Date(2026,i%12,1+i%28).getHours()}const b=(performance.now()-t2)/20000*1000;
      const t3=performance.now();for(let i=0;i<2000;i++){new w.Date(1e12+i*3.6e6).toString()}const c=(performance.now()-t3)/2000*1000;
      return {install_ms:+a.toFixed(2), ctor_plus_getHours_us:+b.toFixed(2), toString_us:+c.toFixed(2)}})()`, returnByValue: true }, sessionId);
    console.log('overhead (desktop Chrome, x86):', JSON.stringify(v.result.value));
    await send('Target.closeTarget', { targetId });
  }
} catch (e) { console.error(e); fails++; }
finally { chrome.kill('SIGKILL'); srv.kill(); }
console.log(`${passes} checks passed, ${fails} failed`);
process.exit(fails ? 1 : 0);
