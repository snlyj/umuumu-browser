// 页面加载完后判断「是不是适合阅读模式的文章」（只读 DOM，不改网页）
// 中日韩文字信息密度高，一段 60 个字就算正经段落；其他语言沿用 Readability 默认的 140 个字符
try {
  if (!document.body || location.protocol.indexOf('http') !== 0) return false;
  var cjk = /^(zh|ja|ko)/i.test(document.documentElement.lang || '') || /[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]/.test(document.title || '');
  return isProbablyReaderable(document, { minContentLength: cjk ? 60 : 140, minScore: 20 });
} catch (e) {
  return false;
}
