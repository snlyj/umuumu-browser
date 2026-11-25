// 从 fingerprint.js 源码里原样取出 fnv / mix / site 三个函数，算出对照向量；
// FpSeedTest.kt 里写死的是同一组数字，两边一致就说明 Kotlin 和 JS 的种子算法相同。
// 用法：node fp-js/seed-vectors.mjs   （加 --check 时和 FpSeedTest.kt 里的数字比对）
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, 'fingerprint.js'), 'utf8');
const grab = (name) => { const m = src.match(new RegExp(`function ${name}\\(.*`)); if (!m) throw new Error(name); return m[0]; };
// site() 跨多行：取到它后面那个 hostOf 之前
const siteSrc = src.slice(src.indexOf('function site('), src.indexOf('function hostOf('));
const SL = {}; ['co.uk', 'com.cn', 'github.io'].forEach((s) => SL[s] = 1);
const fns = new Function('SL', `${grab('fnv')}\n${grab('mix')}\n${siteSrc}\nreturn {fnv, mix, site};`)(SL);
const hosts = ['www.example.com', 'a.b.example.co.uk', 'news.sina.com.cn', 'Example.COM.', 'yuka.github.io', '192.168.1.10', '[::1]', 'localhost', ''];
const keys = ['aaaa1111bbbb2222', '唔姆'];
const out = [];
for (const h of hosts) out.push(['site', h, fns.site(h)]);
for (const k of keys) for (const s of ['example.com', 'example.co.uk', '']) out.push(['seed', k, s, fns.mix(fns.fnv(k + '|' + s))]);
console.log(JSON.stringify(out, null, 1));
if (process.argv.includes('--check')) {
  const kt = readFileSync(join(here, '../app/src/test/java/com/yuka/cutebrowser/FpSeedTest.kt'), 'utf8');
  let bad = 0;
  for (const v of out) { const needle = v[0] === 'site' ? `"${v[2]}"` : `${v[3]}L`; if (!kt.includes(needle)) { console.log('MISSING in FpSeedTest.kt:', v); bad++; } }
  console.log(bad ? `${bad} mismatches` : 'Kotlin test vectors match JS');
  process.exit(bad ? 1 : 0);
}
