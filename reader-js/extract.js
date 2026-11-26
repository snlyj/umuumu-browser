// 唔姆唔姆阅读模式：在网页里跑 Readability，拿到正文后做一遍白名单清洗，再以 JSON 字符串交回 App。
// 构建时被包进 (function(){ var module; <Readability.js> <本文件> })() 里，网页看不到这些函数。
// 输出的 HTML 会在一个「禁用 JS + 严格 CSP」的单独 WebView 里显示，这里的清洗是第二道保险。
var UMU_TAGS = {
  P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1,
  EM: 1, STRONG: 1, B: 1, I: 1, U: 1, S: 1, SMALL: 1, MARK: 1, SUP: 1, SUB: 1, BR: 1, HR: 1, A: 1, IMG: 1,
  FIGURE: 1, FIGCAPTION: 1, TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1, TR: 1, TD: 1, TH: 1, CAPTION: 1,
  DL: 1, DT: 1, DD: 1, SPAN: 1, DIV: 1, SECTION: 1, ARTICLE: 1, ABBR: 1, Q: 1, CITE: 1, TIME: 1, KBD: 1, VAR: 1
};
// 整个连内容一起丢掉的
var UMU_DROP = { SCRIPT: 1, STYLE: 1, IFRAME: 1, FRAME: 1, OBJECT: 1, EMBED: 1, FORM: 1, INPUT: 1, BUTTON: 1,
  SELECT: 1, TEXTAREA: 1, META: 1, LINK: 1, BASE: 1, SVG: 1, MATH: 1, TEMPLATE: 1, NOSCRIPT: 1, CANVAS: 1,
  AUDIO: 1, VIDEO: 1, SOURCE: 1, TRACK: 1, PORTAL: 1, DIALOG: 1 };

function umuSafeUrl(u, img) {
  if (!u) return null;
  u = String(u).trim();
  if (/^https:\/\//i.test(u)) return u;
  if (!img && /^http:\/\//i.test(u)) return u; // 链接点了以后交给 App，照常走「只走 HTTPS」
  if (img && /^data:image\/(png|jpe?g|gif|webp);/i.test(u) && u.length < 200000) return u;
  return null;
}

function umuClean(src, out, doc, depth) {
  for (var n = src.firstChild; n; n = n.nextSibling) {
    if (n.nodeType === 3) { out.appendChild(doc.createTextNode(n.nodeValue)); continue; }
    if (n.nodeType !== 1) continue;
    var tag = n.tagName.toUpperCase();
    if (UMU_DROP[tag]) continue;
    if (!UMU_TAGS[tag] || depth > 60) { umuClean(n, out, doc, depth + 1); continue; } // 不认识的标签：只留里面的内容
    var e = doc.createElement(tag);
    if (tag === 'A') {
      var h = umuSafeUrl(n.getAttribute('href'), false);
      if (h) e.setAttribute('href', h);
    } else if (tag === 'IMG') {
      var s = umuSafeUrl(n.getAttribute('src'), true);
      if (!s) continue;
      e.setAttribute('src', s);
      var alt = n.getAttribute('alt'); if (alt) e.setAttribute('alt', alt.slice(0, 300));
      e.setAttribute('loading', 'lazy');
    } else if (tag === 'TD' || tag === 'TH') {
      ['colspan', 'rowspan'].forEach(function (a) { var v = parseInt(n.getAttribute(a), 10); if (v > 1 && v < 50) e.setAttribute(a, String(v)); });
    } else if (tag === 'OL') {
      var st = parseInt(n.getAttribute('start'), 10); if (st > 1) e.setAttribute('start', String(st));
    }
    umuClean(n, e, doc, depth + 1);
    out.appendChild(e);
  }
}

function umuSanitize(html) {
  // 在一个没有浏览上下文的空文档里解析：不会执行脚本，也不会加载图片
  var doc = document.implementation.createHTMLDocument('');
  var box = doc.createElement('div');
  box.innerHTML = html;
  var out = doc.createElement('div');
  umuClean(box, out, doc, 0);
  return out.innerHTML;
}

try {
  if (!document.body) return null;
  var umuDoc = document.cloneNode(true);
  var umuArt = new Readability(umuDoc, { charThreshold: 200, keepClasses: false, nbTopCandidates: 5 }).parse();
  if (!umuArt || !umuArt.content) return null;
  var umuHtml = umuSanitize(umuArt.content);
  if (!umuHtml || umuHtml.length < 40) return null;
  return JSON.stringify({
    title: String(umuArt.title || document.title || '').slice(0, 300),
    byline: String(umuArt.byline || '').slice(0, 200),
    site: String(umuArt.siteName || '').slice(0, 100),
    lang: String(umuArt.lang || document.documentElement.lang || '').slice(0, 20),
    dir: umuArt.dir === 'rtl' ? 'rtl' : 'ltr',
    length: umuArt.length | 0,
    html: umuHtml
  });
} catch (e) {
  return null;
}
