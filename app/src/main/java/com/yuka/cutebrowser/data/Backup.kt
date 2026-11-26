package com.yuka.cutebrowser.data

import com.yuka.cutebrowser.privacy.locale.SiteTzLang
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 书签 + 设置的备份 / 恢复（v1.7）。纯 JVM 逻辑，界面在 ui/BackupUi.kt。
 *
 * 文件格式：
 *  - JSON：`{"format":"umu-backup","v":1,"bookmarks":[…],"settings":{…},"history":[…]}`。
 *    历史只有用户在导出时勾了才会写进去；Cookie 永远不导出（WebView 也没法把 Cookie 列出来）。
 *  - 加密 JSON：`{"format":"umu-backup-encrypted","v":1,"kdf":"PBKDF2-HMAC-SHA256","iter":…,"salt":…,"iv":…,"ct":…}`，
 *    AES-256-GCM，密钥由密码经 PBKDF2-HMAC-SHA256 派生（自己实现 PBKDF2，因为 Android 7 的 JCE 没有 SHA256 版本）。
 *  - Netscape 书签 HTML（Chrome / Firefox / Edge 的「导出书签」格式）：只有书签，和其它浏览器互通。
 */
object Backup {
    const val FORMAT = "umu-backup"
    const val FORMAT_ENC = "umu-backup-encrypted"
    const val VERSION = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val DEFAULT_ITER = 310_000
    private const val MAX_ITER = 5_000_000
    private const val AAD = "umu-backup-v1"
    /** 导入文件大小上限（字节）：书签+设置不可能这么大，防止误选大文件把内存撑爆 */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    class Settings(
        val theme: String? = null,
        val engine: String? = null,
        val adBlock: Boolean? = null,
        val webDark: Boolean? = null,
        val security: JSONObject? = null,
        val adBlockOff: Set<String> = emptySet(),
        val fpOff: Set<String> = emptySet(),
        val httpAllowed: Set<String> = emptySet(),
        val tzSites: Map<String, SiteTzLang> = emptyMap(),
        val sitePerms: List<SitePermEntry> = emptyList(),
    )

    class Content(val bookmarks: List<PageEntry>, val history: List<PageEntry>?, val settings: Settings?)

    /** 导出时的选择。历史默认不导出 */
    data class Options(val bookmarks: Boolean = true, val settings: Boolean = true, val siteLists: Boolean = true, val history: Boolean = false)

    // ---------------- JSON ----------------

    fun toJson(c: Content, o: Options, now: Long = System.currentTimeMillis()): String {
        val root = JSONObject().put("format", FORMAT).put("v", VERSION).put("app", "唔姆唔姆").put("exportedAt", now)
        if (o.bookmarks) root.put("bookmarks", entries(c.bookmarks))
        val s = c.settings
        if (o.settings && s != null) {
            val js = JSONObject()
            s.theme?.let { js.put("theme", it) }
            s.engine?.let { js.put("engine", it) }
            s.adBlock?.let { js.put("adBlock", it) }
            s.webDark?.let { js.put("webDark", it) }
            s.security?.let { js.put("security", it) }
            if (o.siteLists) {
                js.put("adBlockOff", JSONArray(s.adBlockOff.sorted()))
                js.put("fpOff", JSONArray(s.fpOff.sorted()))
                js.put("httpAllowed", JSONArray(s.httpAllowed.sorted()))
                js.put("tzSites", JSONObject().apply {
                    s.tzSites.toSortedMap().forEach { (k, v) -> put(k, JSONObject().apply { v.tz?.let { put("z", it) }; v.lang?.let { put("g", it) } }) }
                })
                js.put("sitePerms", JSONArray().apply {
                    s.sitePerms.forEach { put(JSONObject().put("o", it.origin).put("p", it.perm.name).put("a", it.allow)) }
                })
            }
            root.put("settings", js)
        }
        val h = c.history
        if (o.history && h != null) root.put("history", entries(h))
        return root.toString(1)
    }

    private fun entries(l: List<PageEntry>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
    }

    class BadBackup(msg: String) : Exception(msg)

    /** 解析我们自己的（未加密）JSON 备份。坏条目跳过，不认识的字段忽略 */
    fun fromJson(text: String): Content {
        val root = try { JSONObject(text) } catch (e: Exception) { throw BadBackup("不是有效的备份文件") }
        if (root.optString("format") != FORMAT) throw BadBackup("不是唔姆唔姆的备份文件")
        if (root.optInt("v", 0) > VERSION) throw BadBackup("备份来自更新版本的唔姆唔姆，请先更新 App")
        val bookmarks = readEntries(root.optJSONArray("bookmarks"))
        val history = root.optJSONArray("history")?.let { readEntries(it) }
        val settings = root.optJSONObject("settings")?.let { s ->
            Settings(
                theme = s.optString("theme").takeIf { it.isNotEmpty() },
                engine = s.optString("engine").takeIf { it.isNotEmpty() },
                adBlock = if (s.has("adBlock")) s.optBoolean("adBlock") else null,
                webDark = if (s.has("webDark")) s.optBoolean("webDark") else null,
                security = s.optJSONObject("security"),
                adBlockOff = strings(s.optJSONArray("adBlockOff")),
                fpOff = strings(s.optJSONArray("fpOff")),
                httpAllowed = strings(s.optJSONArray("httpAllowed")),
                tzSites = s.optJSONObject("tzSites")?.let { o ->
                    val m = LinkedHashMap<String, SiteTzLang>()
                    for (k in o.keys()) {
                        val e = o.optJSONObject(k) ?: continue
                        val v = SiteTzLang(if (e.has("z")) e.optString("z") else null, if (e.has("g")) e.optString("g") else null)
                        if (!v.isEmpty && k.isNotBlank()) m[k] = v
                    }
                    m
                } ?: emptyMap(),
                sitePerms = s.optJSONArray("sitePerms")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val o = a.optJSONObject(i) ?: return@mapNotNull null
                        val perm = SitePerm.entries.firstOrNull { it.name == o.optString("p") } ?: return@mapNotNull null
                        val origin = o.optString("o")
                        if (origin.isBlank()) null else SitePermEntry(origin, perm, o.optBoolean("a"))
                    }
                } ?: emptyList(),
            )
        }
        return Content(bookmarks, history, settings)
    }

    private fun strings(a: JSONArray?): Set<String> {
        if (a == null) return emptySet()
        return (0 until a.length()).mapNotNullTo(LinkedHashSet()) { a.optString(it).trim().lowercase().takeIf { s -> s.isNotEmpty() && s.length < 256 } }
    }

    private fun readEntries(a: JSONArray?): List<PageEntry> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!importableUrl(url)) null else PageEntry(url, o.optString("title").ifBlank { url }.take(500), o.optLong("time"))
        }
    }

    /** 只收网页地址：javascript:/file:/data: 之类的书签（"书签小工具"）不导入 */
    fun importableUrl(url: String): Boolean {
        if (url.length > 8192) return false
        val u = url.lowercase()
        return (u.startsWith("https://") || u.startsWith("http://")) && u.length > 8
    }

    // ---------------- 合并 ----------------

    /** 比较用的网址：去掉首尾空白、主机名小写、去掉只有 "/" 的路径和末尾的 # */
    fun dedupeKey(url: String): String {
        var u = url.trim().removeSuffix("#")
        val schemeEnd = u.indexOf("://")
        if (schemeEnd > 0) {
            val hostStart = schemeEnd + 3
            val pathStart = u.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) u.length else it }
            u = u.substring(0, pathStart).lowercase() + u.substring(pathStart)
            if (u.length == pathStart + 1 && u.endsWith("/")) u = u.dropLast(1)
        }
        return u
    }

    /** 已有的书签顺序不变；文件里新的（去重后）按时间从新到旧接在后面 */
    fun mergeBookmarks(existing: List<PageEntry>, incoming: List<PageEntry>): List<PageEntry> {
        val seen = existing.mapTo(HashSet()) { dedupeKey(it.url) }
        val add = ArrayList<PageEntry>()
        for (e in incoming) if (seen.add(dedupeKey(e.url))) add += e
        return existing + add.sortedByDescending { it.time }
    }

    /** 历史：同一个网址同一时刻只留一条，按时间从新到旧，最多 [max] 条 */
    fun mergeHistory(existing: List<PageEntry>, incoming: List<PageEntry>, max: Int): List<PageEntry> {
        val seen = HashSet<String>()
        return (existing + incoming)
            .sortedByDescending { it.time }
            .filter { seen.add(dedupeKey(it.url) + "|" + it.time) }
            .take(max)
    }

    // ---------------- Netscape 书签 HTML ----------------

    fun toNetscapeHtml(bookmarks: List<PageEntry>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        append("    <DT><H3>唔姆唔姆</H3>\n    <DL><p>\n")
        for (b in bookmarks) {
            append("        <DT><A HREF=\"").append(esc(b.url)).append("\" ADD_DATE=\"").append(b.time / 1000).append("\">")
            append(esc(b.title.ifBlank { b.url })).append("</A>\n")
        }
        append("    </DL><p>\n</DL><p>\n")
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private val A_TAG = Regex("<a\\s([^>]*)>([\\s\\S]*?)</a\\s*>", RegexOption.IGNORE_CASE)
    private val HREF = Regex("\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("\\badd_date\\s*=\\s*\"?(\\d{1,13})", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);")

    fun looksLikeNetscape(text: String): Boolean {
        val head = text.take(2048).lowercase()
        return head.contains("netscape-bookmark-file") || (head.contains("<dl") && head.contains("<a "))
    }

    /** 读 Chrome/Firefox/Edge/Safari 导出的书签 HTML（文件夹拍平；只要 http(s) 链接） */
    fun fromNetscapeHtml(text: String): List<PageEntry> {
        val out = ArrayList<PageEntry>()
        for (m in A_TAG.findAll(text)) {
            val attrs = m.groupValues[1]
            val h = HREF.find(attrs) ?: continue
            val href = unescape(h.groupValues[2].ifEmpty { h.groupValues[3] }.ifEmpty { h.groupValues[4] }).trim()
            if (!importableUrl(href)) continue
            val secs = ADD_DATE.find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            // 有的浏览器写秒，有的写微秒/毫秒：按位数换算成毫秒
            val ms = when {
                secs > 100_000_000_000_000L -> secs / 1000
                secs > 100_000_000_000L -> secs
                else -> secs * 1000
            }
            val title = unescape(m.groupValues[2].replace(TAGS, "")).trim().ifBlank { href }.take(500)
            out += PageEntry(href, title, ms)
        }
        return out
    }

    private fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
            e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; e == "apos" -> "'"; e == "nbsp" -> " "
            else -> m.value
        }
    }

    // ---------------- 加密 ----------------

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text).optString("format") == FORMAT_ENC }.getOrDefault(false)

    fun encrypt(plain: String, password: CharArray, iter: Int = DEFAULT_ITER, rnd: SecureRandom = SecureRandom()): String {
        require(password.isNotEmpty()) { "empty password" }
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = pbkdf2Sha256(password, salt, iter, 32)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(AAD.toByteArray())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return JSONObject().put("format", FORMAT_ENC).put("v", VERSION).put("kdf", KDF).put("iter", iter)
                .put("salt", salt.toByteString().base64()).put("iv", iv.toByteString().base64()).put("ct", ct.toByteString().base64())
                .toString(1)
        } finally {
            key.fill(0)
        }
    }

    class WrongPassword : Exception("密码不对")

    fun decrypt(text: String, password: CharArray): String {
        val o = try { JSONObject(text) } catch (e: Exception) { throw BadBackup("不是有效的备份文件") }
        if (o.optString("format") != FORMAT_ENC) throw BadBackup("不是加密的备份文件")
        if (o.optInt("v", 0) > VERSION) throw BadBackup("备份来自更新版本的唔姆唔姆，请先更新 App")
        if (o.optString("kdf") != KDF) throw BadBackup("不认识的加密方式")
        val iter = o.optInt("iter", 0)
        if (iter < 1000 || iter > MAX_ITER) throw BadBackup("备份文件已损坏")
        fun b64(k: String): ByteArray = o.optString(k).decodeBase64()?.toByteArray() ?: throw BadBackup("备份文件已损坏")
        val salt = b64("salt")
        val iv = b64("iv")
        val ct = b64("ct")
        if (salt.size < 8 || iv.size != 12 || ct.size < 16) throw BadBackup("备份文件已损坏")
        val key = pbkdf2Sha256(password, salt, iter, 32)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(AAD.toByteArray())
            return String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        } finally {
            key.fill(0)
        }
    }

    /** RFC 8018 PBKDF2，PRF = HMAC-SHA256。密码按 UTF-8 编码 */
    fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iter: Int, len: Int): ByteArray {
        val pw = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC 的 key 不能是空数组（javax 会抛异常）；空密码前面已经拦了，这里再兜底
        mac.init(SecretKeySpec(if (pw.isEmpty()) ByteArray(1) else pw, "HmacSHA256"))
        pw.fill(0)
        val hLen = 32
        val blocks = (len + hLen - 1) / hLen
        val out = ByteArray(len)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (b in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (i in 1 until iter) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (b - 1) * hLen
            System.arraycopy(t, 0, out, off, minOf(hLen, len - off))
        }
        u.fill(0); t.fill(0)
        return out
    }
}
