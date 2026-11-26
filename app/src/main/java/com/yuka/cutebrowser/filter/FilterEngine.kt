package com.yuka.cutebrowser.filter

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 资源类型（位掩码）。UNKNOWN 表示猜不出来：v1.7 起只限定了类型的规则（$script、$image…）不再套到猜不出类型的请求上
 * （以前会套，结果比如 `$image` 规则能把同地址的 XHR/脚本也拦掉）；只排除类型的（`$~image`）和 `$all` 仍然生效。
 */
object ResType {
    const val UNKNOWN = 0
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLESHEET = 4
    const val OBJECT = 8
    const val XHR = 16
    const val SUBDOC = 32
    const val PING = 64
    const val MEDIA = 128
    const val FONT = 256
    const val WEBSOCKET = 512
    const val OTHER = 1024
    const val ALL = 2047

    private val IMG = hashSetOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val MED = hashSetOf("mp4", "webm", "m3u8", "mp3", "m4a", "ts", "ogg", "wav", "flv", "m4s")
    private val FNT = hashSetOf("woff", "woff2", "ttf", "otf", "eot")

    /** 从 URL 后缀和 Accept 头猜资源类型（shouldInterceptRequest 里拿不到真正的类型） */
    fun infer(url: String, accept: String?): Int {
        var end = url.length
        val q = url.indexOf('?'); if (q >= 0) end = q
        val h = url.indexOf('#'); if (h in 0 until end) end = h
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot > slash && dot > 0) {
            when (val ext = url.substring(dot + 1, end).lowercase()) {
                "js", "mjs" -> return SCRIPT
                "css" -> return STYLESHEET
                in IMG -> return IMAGE
                in FNT -> return FONT
                in MED -> return MEDIA
                else -> if (ext == "html" || ext == "htm") return SUBDOC
            }
        }
        if (accept != null) {
            if (accept.startsWith("text/css")) return STYLESHEET
            if (accept.startsWith("image/")) return IMAGE
            if (accept.startsWith("text/html")) return SUBDOC
        }
        if (url.startsWith("ws:") || url.startsWith("wss:")) return WEBSOCKET
        return UNKNOWN
    }
}

/** 一次请求的上下文（host 和 url 都已经是小写） */
class FilterRequest(
    val url: String,
    val host: String,
    val pageHost: String?,
    val type: Int,
    /** 原始大小写的网址（只有 $match-case 规则会用） */
    val rawUrl: String = url,
) {
    /** 只有带 third-party / first-party 选项的规则才会问，所以按需再算（每个请求省掉两次 substring） */
    val thirdParty: Boolean
        get() {
            var t = tp
            if (t < 0) {
                t = if (pageHost == null || Domains.base(host) != Domains.base(pageHost)) 1 else 0
                tp = t
            }
            return t == 1
        }
    private var tp = -1
}

object Domains {
    /** 常见的二级公共后缀（防指纹脚本也用这一份，见 FpSeed） */
    val SECOND_LEVEL: Set<String> = hashSetOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk",
        "com.tw", "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr", "com.au", "net.au", "org.au",
        "com.br", "com.sg", "com.my", "co.in", "co.nz", "com.mx", "com.tr", "co.za", "co.id", "com.vn",
        "github.io", "blogspot.com", "appspot.com", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev",
        // 更多国家的二级后缀：缺了它们，不同的网站会被当成"同一个网站"（防指纹的"这个网站例外"、每站种子、第三方判断都按它算）
        "com.ar", "com.co", "com.ph", "com.pk", "com.ng", "com.ua", "com.pl", "com.eg", "com.sa", "com.pe", "com.ve",
        "co.th", "co.il", "co.ke", "co.kr", "or.id", "ac.id", "web.id", "go.id", "co.ve", "edu.au", "gov.au", "edu.hk",
        "org.hk", "org.tw", "edu.tw", "gov.tw", "org.br", "net.br", "gov.br", "org.in", "net.in", "gov.in", "ac.in",
        "net.my", "org.my", "org.sg", "net.sg", "edu.sg", "net.nz", "org.nz", "org.za", "ne.kr", "go.kr", "go.jp", "gr.jp",
        // 共享托管：每个子域都是不同的主人
        "web.app", "firebaseapp.com", "workers.dev", "gitlab.io", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
        "myshopify.com", "wordpress.com", "weebly.com", "wixsite.com", "glitch.me", "onrender.com", "fly.dev", "surge.sh",
        "neocities.org", "duckdns.org", "ngrok.io", "ngrok-free.app", "sslip.io", "nip.io", "xip.io", "repl.co", "replit.app", "github.dev", "codeberg.page",
        "bitbucket.io", "tumblr.com", "substack.com", "notion.site", "framer.app", "webflow.io", "carrd.co", "lofter.com",
    )

    /** 近似的"可注册域名"（eTLD+1）。没有完整的公共后缀表，只覆盖常见的二级后缀 */
    fun base(host: String): String {
        if (host.isEmpty() || host[0].isDigit() && host.all { it.isDigit() || it == '.' }) return host
        val last = host.lastIndexOf('.')
        if (last <= 0) return host
        val second = host.lastIndexOf('.', last - 1)
        if (second < 0) return host
        val tail2 = host.substring(second + 1)
        if (tail2 in SECOND_LEVEL) {
            val third = host.lastIndexOf('.', second - 1)
            return if (third < 0) host else host.substring(third + 1)
        }
        return tail2
    }

    fun matchesAny(host: String, list: Array<String>): Boolean {
        for (d in list) {
            if (host == d) return true
            if (host.length > d.length && host.endsWith(d) && host[host.length - d.length - 1] == '.') return true
        }
        return false
    }
}

/** 一条网络规则。pattern 已小写，不含 || | 锚点；flags 见 [FilterEngine] 的常量 */
class NetRule(
    @JvmField val pattern: String,
    @JvmField val flags: Int,
    @JvmField val types: Int,
    @JvmField val include: Array<String>?,
    @JvmField val exclude: Array<String>?,
)

/** 一组规则（拦截规则或例外规则），按 host / token 建索引 */
class RuleSet {
    val plainHosts = HashSet<String>()                  // ||host^ 且没有任何选项（最常见，最快）
    val hostRules = HashMap<String, ArrayList<NetRule>>() // ||host^$选项
    val tokenRules = HashMap<String, ArrayList<NetRule>>()
    val noToken = ArrayList<NetRule>()
    var size = 0

    fun find(req: FilterRequest, tokens: List<String>): Boolean {
        var h = req.host
        while (true) {
            if (h in plainHosts) return true
            hostRules[h]?.let { list -> for (r in list) if (FilterEngine.optionsOk(r, req)) return true }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (t in tokens) {
            val list = tokenRules[t] ?: continue
            for (r in list) if (FilterEngine.ruleMatches(r, req)) return true
        }
        for (r in noToken) if (FilterEngine.ruleMatches(r, req)) return true
        return false
    }
}

/**
 * 支持 Adblock Plus / uBlock Origin 语法的一个常用子集：
 * - 网络规则：`||host^`、`|` 开头/结尾锚点、`*` 通配、`^` 分隔符、`@@` 例外
 * - 选项：third-party/3p、first-party/1p、domain=a.com|~b.com、important、资源类型（script/image/...，可取反）、
 *   例外里的 $document（整个网站放行）和 $elemhide（不隐藏元素）
 * - 元素隐藏：`a.com,b.com##选择器` 与 `#@#` 例外（只处理指定了网站的规则；通用 `##` 规则数量太大，注入会拖慢页面）
 * 不支持（整条规则跳过）：正则规则、redirect/csp/removeparam 等修改类选项、程序化选择器（:has-text 等）、scriptlet。
 */
class FilterEngine {
    val block = RuleSet()
    val allow = RuleSet()
    private val important = RuleSet()
    private val docAllow = RuleSet()     // @@...$document：整个页面不拦截
    private val elemhideAllow = RuleSet() // @@...$elemhide
    private val hide = HashMap<String, ArrayList<String>>()
    private val hideExcept = HashMap<String, HashSet<String>>()

    val networkRuleCount: Int get() = block.size + allow.size + important.size + docAllow.size + elemhideAllow.size
    val cosmeticRuleCount: Int get() = hide.values.sumOf { it.size }

    /** 是否拦截这个子资源请求 */
    fun shouldBlock(req: FilterRequest): Boolean {
        val tokens = tokenize(req.url)
        if (important.find(req, tokens)) return true
        if (!block.find(req, tokens)) return false
        return !allow.find(req, tokens)
    }

    /** 页面本身是否被 $document 例外放行（每次换页算一次就够） */
    fun isPageAllowlisted(pageUrl: String, pageHost: String): Boolean {
        val req = FilterRequest(pageUrl, pageHost, pageHost, ResType.UNKNOWN)
        return docAllow.find(req, tokenize(pageUrl))
    }

    /** 给某个网站的元素隐藏 CSS（没有就返回 null） */
    fun cosmeticCss(pageUrl: String, pageHost: String): String? {
        if (hide.isEmpty()) return null
        val req = FilterRequest(pageUrl, pageHost, pageHost, ResType.UNKNOWN)
        if (elemhideAllow.find(req, tokenize(pageUrl))) return null
        val sels = LinkedHashSet<String>()
        val except = HashSet<String>()
        var h = pageHost
        while (true) {
            hide[h]?.let { sels.addAll(it) }
            hideExcept[h]?.let { except.addAll(it) }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        sels.removeAll(except)
        if (sels.isEmpty()) return null
        val sb = StringBuilder(sels.size * 40)
        // 每个选择器单独一条规则：一个写错的选择器不会让整组失效
        for (s in sels) sb.append(s).append("{display:none!important}\n")
        return sb.toString()
    }

    // ---------------- 编译 ----------------

    private val pendingTokenRules = ArrayList<Pair<RuleSet, NetRule>>()

    /** 读入一个 ABP 格式的规则列表 */
    fun addList(reader: BufferedReader) {
        reader.forEachLine { addLine(it) }
    }

    /** hosts 文件风格的纯域名列表（每行一个域名） */
    fun addHosts(hosts: Iterable<String>) {
        for (h in hosts) if (block.plainHosts.add(h)) block.size++
    }

    fun addLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        val cos = cosmeticSeparator(line)
        if (cos != null) { addCosmetic(line, cos.first, cos.second); return }
        if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("#@?#") ||
            line.contains("#@$#") || line.contains("#@%#")
        ) return
        addNetwork(line)
    }

    /** $badfilter 要作废的规则（[ruleKey]） */
    private val badKeys = HashSet<String>()

    /** 两条规则"除了 badfilter 一模一样"的比较键：选项顺序、域名顺序不影响 */
    private fun ruleKey(r: NetRule): String = buildString {
        append(r.flags and KEY_FLAGS).append('|').append(r.types).append('|').append(r.pattern.removeSuffix("^").let { if (r.flags and HOST != 0) it else r.pattern })
        append('|'); r.include?.sorted()?.joinTo(this, ",")
        append('|'); r.exclude?.sorted()?.joinTo(this, ",")
    }

    private fun applyBadfilters() {
        if (badKeys.isEmpty()) return
        for (set in listOf(block, allow, important, docAllow, elemhideAllow)) {
            val f = HOST or when {
                set === allow -> EXC
                set === important -> IMPORTANT
                set === docAllow -> EXC or DOC
                set === elemhideAllow -> EXC or ELEMHIDE
                else -> 0
            }
            val it = set.plainHosts.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if ("$f|0|$h||" in badKeys) { it.remove(); set.size-- }
            }
            val hi = set.hostRules.entries.iterator()
            while (hi.hasNext()) {
                val e = hi.next()
                val before = e.value.size
                e.value.removeAll { ruleKey(it) in badKeys }
                set.size -= before - e.value.size
                if (e.value.isEmpty()) hi.remove()
            }
        }
        val pi = pendingTokenRules.iterator()
        while (pi.hasNext()) {
            val (set, r) = pi.next()
            if (ruleKey(r) in badKeys) { pi.remove(); set.size-- }
        }
        badKeys.clear()
    }

    /** 全部加完之后调用：给每条带 token 的规则挑"最稀有"的 token 建索引 */
    fun finish() {
        applyBadfilters()
        val freq = HashMap<String, Int>()
        val cand = ArrayList<List<String>>(pendingTokenRules.size)
        for ((_, r) in pendingTokenRules) {
            val t = validTokens(r)
            cand.add(t)
            for (x in t) freq[x] = (freq[x] ?: 0) + 1
        }
        for ((i, pair) in pendingTokenRules.withIndex()) {
            val (set, r) = pair
            val best = cand[i].minByOrNull { (freq[it] ?: 0) * 64 - it.length }
            if (best == null) set.noToken.add(r) else set.tokenRules.getOrPut(best) { ArrayList(2) }.add(r)
        }
        pendingTokenRules.clear()
        pendingTokenRules.trimToSize()
    }

    private fun cosmeticSeparator(line: String): Pair<Int, Boolean>? {
        val ex = line.indexOf("#@#")
        if (ex >= 0) return ex to true
        val i = line.indexOf("##")
        if (i >= 0) return i to false
        return null
    }

    private fun addCosmetic(line: String, idx: Int, exception: Boolean) {
        val domains = line.substring(0, idx)
        val sel = line.substring(idx + if (exception) 3 else 2).trim()
        if (domains.isEmpty() || sel.isEmpty()) return // 通用规则：不注入（性能考虑）
        if (sel.startsWith("+js") || PROCEDURAL.any { sel.contains(it) }) return
        for (d0 in domains.split(',')) {
            val neg = d0.startsWith("~")
            val d = d0.removePrefix("~").trim().lowercase()
            if (d.isEmpty() || d.endsWith(".*") || d.contains('/')) continue
            if (exception || neg) hideExcept.getOrPut(d) { HashSet() }.add(sel)
            else hide.getOrPut(d) { ArrayList(2) }.add(sel)
        }
    }

    private fun addNetwork(line: String) {
        var s = line
        var flags = 0
        if (s.startsWith("@@")) { flags = flags or EXC; s = s.substring(2) }
        var opts: String? = null
        val dollar = s.lastIndexOf('$')
        if (dollar >= 0 && dollar < s.length - 1) {
            val tail = s.substring(dollar + 1)
            if (OPTS_RE.matches(tail)) {
                opts = tail
                s = s.substring(0, dollar)
            } else if (OPT_PREFIX_RE.containsMatchIn(tail)) return // 像选项但我们解析不了（比如 csp=… 带空格）：跳过
        }
        if (s.length >= 2 && s[0] == '/' && s[s.length - 1] == '/') return // 正则规则：跳过
        var types = 0
        var negTypes = 0
        var bad = false
        var include: ArrayList<String>? = null
        var exclude: ArrayList<String>? = null
        if (opts != null) {
            for (o0 in opts.split(',')) {
                val o = o0.trim().lowercase()
                if (o.isEmpty()) continue
                val neg = o.startsWith("~")
                val name = o.removePrefix("~")
                val eq = name.indexOf('=')
                val key = if (eq >= 0) name.substring(0, eq) else name
                when (key) {
                    "third-party", "3p" -> flags = flags or if (neg) FIRST else THIRD
                    "first-party", "1p" -> flags = flags or if (neg) THIRD else FIRST
                    "important" -> flags = flags or IMPORTANT
                    "match-case" -> if (!neg) flags = flags or MATCH_CASE
                    "badfilter" -> bad = true
                    "domain", "from" -> {
                        if (eq < 0) return
                        for (d0 in name.substring(eq + 1).split('|')) {
                            val dn = d0.startsWith("~")
                            val d = d0.removePrefix("~")
                            if (d.isEmpty() || d.endsWith(".*") || d.contains('/')) continue
                            if (dn) (exclude ?: ArrayList<String>().also { exclude = it }).add(d)
                            else (include ?: ArrayList<String>().also { include = it }).add(d)
                        }
                        if (include == null && exclude == null) return
                    }
                    "document", "doc" -> if (flags and EXC != 0 && !neg) flags = flags or DOC else return
                    "elemhide", "ehide" -> if (flags and EXC != 0 && !neg) flags = flags or ELEMHIDE else return
                    "all" -> types = types or ResType.ALL
                    else -> {
                        val t = TYPE_NAMES[key] ?: return // 不支持的选项：整条规则跳过，宁可漏拦也不误拦
                        if (neg) negTypes = negTypes or t else types = types or t
                    }
                }
            }
        }
        var mask = if (types != 0) types else if (negTypes != 0) ResType.ALL else 0
        if (negTypes != 0) mask = mask and negTypes.inv()
        // 只排除了某些类型（$~image）或 $all：猜不出类型的请求也算在内
        if ((types == 0 && negTypes != 0) || types and ResType.ALL == ResType.ALL) flags = flags or ANY_TYPE
        if (mask != 0 && mask and ResType.ALL == 0 && flags and (DOC or ELEMHIDE) == 0) return

        if (s.startsWith("||")) { flags = flags or HOST; s = s.substring(2) }
        else if (s.startsWith("|")) { flags = flags or START; s = s.substring(1) }
        if (s.endsWith("|")) { flags = flags or END; s = s.substring(0, s.length - 1) }
        while (s.startsWith("*")) { s = s.substring(1); flags = flags and (HOST or START).inv() }
        while (s.endsWith("*")) { s = s.substring(0, s.length - 1); flags = flags and END.inv() }
        val lower = s.lowercase()
        // $match-case：只在规则里真有大写字母、而且不是纯域名规则时才有意义（域名本来就不分大小写）
        if (flags and MATCH_CASE != 0 && (lower == s || (flags and HOST != 0 && HOST_ONLY_RE.matches(lower)))) flags = flags and MATCH_CASE.inv()
        if (flags and MATCH_CASE == 0) s = lower
        if (s.all { it == '^' || it == '*' } && include == null) return // 空的、或只有 ^ * 的规则几乎匹配一切（`**^**` 会把所有请求都拦掉）：太危险

        val rule = NetRule(s, flags, mask, include?.toTypedArray(), exclude?.toTypedArray())
        if (bad) { badKeys += ruleKey(rule); return } // $badfilter：作废"除了 badfilter 以外一模一样"的那条规则（finish 里统一删）
        val set = when {
            flags and DOC != 0 -> docAllow
            flags and ELEMHIDE != 0 -> elemhideAllow
            flags and EXC != 0 -> allow
            flags and IMPORTANT != 0 -> important
            else -> block
        }
        set.size++
        if (flags and HOST != 0 && flags and END == 0 && HOST_ONLY_RE.matches(s)) {
            val host = s.removeSuffix("^")
            if (mask == 0 && include == null && exclude == null && flags and (THIRD or FIRST) == 0) set.plainHosts.add(host)
            else set.hostRules.getOrPut(host) { ArrayList(1) }.add(rule)
            return
        }
        pendingTokenRules.add(set to rule)
    }

    private fun validTokens(r: NetRule): List<String> {
        val p = if (r.flags and MATCH_CASE != 0) r.pattern.lowercase() else r.pattern
        val out = ArrayList<String>(4)
        var i = 0
        val anchoredStart = r.flags and (HOST or START) != 0
        val anchoredEnd = r.flags and END != 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) { i++; continue }
            val st = i
            while (i < p.length && isTokenChar(p[i])) i++
            val before = if (st == 0) (if (anchoredStart) '/' else '*') else p[st - 1]
            val after = if (i == p.length) (if (anchoredEnd) '/' else '*') else p[i]
            if (before != '*' && after != '*' && i - st >= 2) out.add(p.substring(st, i))
        }
        return out
    }

    // ---------------- 二进制缓存（启动时直接读，不用再解析 13 万行文本） ----------------

    fun writeTo(out: DataOutputStream) {
        out.writeInt(CACHE_MAGIC)
        for (set in listOf(block, allow, important, docAllow, elemhideAllow)) {
            out.writeInt(set.size)
            out.writeInt(set.plainHosts.size); for (h in set.plainHosts) out.writeUTF(h)
            out.writeInt(set.hostRules.size)
            for ((h, list) in set.hostRules) { out.writeUTF(h); out.writeInt(list.size); list.forEach { writeRule(out, it) } }
            out.writeInt(set.tokenRules.size)
            for ((t, list) in set.tokenRules) { out.writeUTF(t); out.writeInt(list.size); list.forEach { writeRule(out, it) } }
            out.writeInt(set.noToken.size); set.noToken.forEach { writeRule(out, it) }
        }
        out.writeInt(hide.size)
        for ((d, l) in hide) { out.writeUTF(d); out.writeInt(l.size); l.forEach { out.writeUTF(it) } }
        out.writeInt(hideExcept.size)
        for ((d, l) in hideExcept) { out.writeUTF(d); out.writeInt(l.size); l.forEach { out.writeUTF(it) } }
    }

    private fun writeRule(out: DataOutputStream, r: NetRule) {
        out.writeUTF(r.pattern); out.writeInt(r.flags); out.writeInt(r.types)
        out.writeShort(r.include?.size ?: -1); r.include?.forEach { out.writeUTF(it) }
        out.writeShort(r.exclude?.size ?: -1); r.exclude?.forEach { out.writeUTF(it) }
    }

    companion object {
        const val EXC = 1
        const val HOST = 2
        const val START = 4
        const val END = 8
        const val THIRD = 16
        const val FIRST = 32
        const val IMPORTANT = 64
        const val DOC = 128
        const val ELEMHIDE = 256
        const val MATCH_CASE = 512
        const val ANY_TYPE = 1024
        /** 比较规则是否相同时看的标志位（锚点、选项；不含内部推导出来的 ANY_TYPE） */
        private const val KEY_FLAGS = EXC or HOST or START or END or THIRD or FIRST or IMPORTANT or DOC or ELEMHIDE or MATCH_CASE
        private const val CACHE_MAGIC = 0x554D5533 // "UMU3"：v1.7 规则语义变了（badfilter / match-case / 未知类型），旧缓存作废
        private const val NON_SUBRESOURCE = 1 shl 20

        private val OPTS_RE = Regex("^[~a-z0-9\\-_,=|.*:/%]+$", RegexOption.IGNORE_CASE)
        private val OPT_PREFIX_RE = Regex("^~?[a-z0-9-]+(=|,|$)", RegexOption.IGNORE_CASE)
        private val HOST_ONLY_RE = Regex("^[a-z0-9.\\-_]+\\^?$")
        private val PROCEDURAL = listOf(
            ":-abp-", ":has-text(", ":xpath(", ":matches-css", ":upward(", ":remove(", ":style(", ":min-text-length(",
            ":watch-attr(", ":matches-path(", ":others(", ":if(", ":if-not(", ":contains(", ":nth-ancestor(",
            ":matches-attr(", ":matches-media(", ":remove-attr(", ":remove-class(",
        )
        private val TYPE_NAMES = mapOf(
            "script" to ResType.SCRIPT, "image" to ResType.IMAGE, "stylesheet" to ResType.STYLESHEET, "css" to ResType.STYLESHEET,
            "object" to ResType.OBJECT, "xmlhttprequest" to ResType.XHR, "xhr" to ResType.XHR,
            "subdocument" to ResType.SUBDOC, "frame" to ResType.SUBDOC, "ping" to ResType.PING, "beacon" to ResType.PING,
            "media" to ResType.MEDIA, "font" to ResType.FONT, "websocket" to ResType.WEBSOCKET, "other" to ResType.OTHER,
            // 这些类型在 WebView 里不会作为子资源出现：映射成 0 位，整条规则会因为 mask 为 0 而被跳过
            "popup" to NON_SUBRESOURCE, "popunder" to NON_SUBRESOURCE, "genericblock" to NON_SUBRESOURCE, "generichide" to NON_SUBRESOURCE,
            "ghide" to NON_SUBRESOURCE, "specifichide" to NON_SUBRESOURCE, "shide" to NON_SUBRESOURCE,
        )

        @JvmStatic fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'
        @JvmStatic fun isSep(c: Char) = !(c in 'a'..'z' || c in '0'..'9' || c in 'A'..'Z' || c == '_' || c == '-' || c == '.' || c == '%')

        /** URL 切成 token（长度 ≥ 2 的 [a-z0-9%] 串），和规则的 token 用同一套切法 */
        fun tokenize(url: String): List<String> {
            val out = ArrayList<String>(16)
            var i = 0
            val n = url.length
            while (i < n) {
                if (!isTokenChar(url[i])) { i++; continue }
                val st = i
                while (i < n && isTokenChar(url[i])) i++
                if (i - st >= 2) out.add(url.substring(st, i))
            }
            return out
        }

        fun optionsOk(r: NetRule, req: FilterRequest): Boolean {
            if (r.types != 0) {
                if (req.type == ResType.UNKNOWN) { if (r.flags and ANY_TYPE == 0) return false }
                else if (r.types and req.type == 0) return false
            }
            if (r.flags and THIRD != 0 && !req.thirdParty) return false
            if (r.flags and FIRST != 0 && req.thirdParty) return false
            val inc = r.include
            if (inc != null && (req.pageHost == null || !Domains.matchesAny(req.pageHost, inc))) return false
            val exc = r.exclude
            if (exc != null && req.pageHost != null && Domains.matchesAny(req.pageHost, exc)) return false
            return true
        }

        fun ruleMatches(r: NetRule, req: FilterRequest): Boolean {
            if (!optionsOk(r, req)) return false
            val u = if (r.flags and MATCH_CASE != 0) req.rawUrl else req.url
            val end = r.flags and END != 0
            return when {
                r.flags and HOST != 0 -> {
                    val se = u.indexOf("://")
                    val hs = if (se >= 0) se + 3 else 0
                    var he = hs
                    while (he < u.length && u[he] != '/' && u[he] != '?' && u[he] != '#' && u[he] != ':') he++
                    if (glob(r.pattern, u, hs, false, end)) return true
                    var k = hs
                    while (k < he) {
                        if (u[k] == '.' && glob(r.pattern, u, k + 1, false, end)) return true
                        k++
                    }
                    false
                }
                r.flags and START != 0 -> glob(r.pattern, u, 0, false, end)
                else -> glob(r.pattern, u, 0, true, end)
            }
        }

        /** 带 * 和 ^ 的通配匹配（贪心 + 回溯星号，线性级别） */
        @JvmStatic
        fun glob(p: String, u: String, start: Int, leadingStar: Boolean, endAnchor: Boolean): Boolean {
            var i = 0
            var j = start
            var starI = if (leadingStar) 0 else -1
            var starJ = start
            val pl = p.length
            val ul = u.length
            while (true) {
                if (i < pl) {
                    val c = p[i]
                    if (c == '*') { i++; starI = i; starJ = j; continue }
                    if (j < ul) {
                        val uc = u[j]
                        if (if (c == '^') isSep(uc) else c == uc) { i++; j++; continue }
                    } else if (c == '^') { i++; continue }
                } else if (!endAnchor || j == ul) return true
                if (starI >= 0 && starJ < ul) { starJ++; j = starJ; i = starI; continue }
                return false
            }
        }

        fun readFrom(inp: DataInputStream): FilterEngine? {
            if (inp.readInt() != CACHE_MAGIC) return null
            val e = FilterEngine()
            for (set in listOf(e.block, e.allow, e.important, e.docAllow, e.elemhideAllow)) {
                set.size = inp.readInt()
                repeat(inp.readInt()) { set.plainHosts.add(inp.readUTF()) }
                repeat(inp.readInt()) {
                    val h = inp.readUTF(); val n = inp.readInt()
                    set.hostRules[h] = ArrayList<NetRule>(n).apply { repeat(n) { add(readRule(inp)) } }
                }
                repeat(inp.readInt()) {
                    val t = inp.readUTF(); val n = inp.readInt()
                    set.tokenRules[t] = ArrayList<NetRule>(n).apply { repeat(n) { add(readRule(inp)) } }
                }
                repeat(inp.readInt()) { set.noToken.add(readRule(inp)) }
            }
            repeat(inp.readInt()) {
                val d = inp.readUTF(); val n = inp.readInt()
                e.hide[d] = ArrayList<String>(n).apply { repeat(n) { add(inp.readUTF()) } }
            }
            repeat(inp.readInt()) {
                val d = inp.readUTF(); val n = inp.readInt()
                e.hideExcept[d] = HashSet<String>(n).apply { repeat(n) { add(inp.readUTF()) } }
            }
            return e
        }

        private fun readRule(inp: DataInputStream): NetRule {
            val p = inp.readUTF(); val f = inp.readInt(); val t = inp.readInt()
            val ni = inp.readShort().toInt(); val inc = if (ni < 0) null else Array(ni) { inp.readUTF() }
            val ne = inp.readShort().toInt(); val exc = if (ne < 0) null else Array(ne) { inp.readUTF() }
            return NetRule(p, f, t, inc, exc)
        }
    }
}
