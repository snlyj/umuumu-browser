package com.yuka.cutebrowser.data

import com.yuka.cutebrowser.privacy.locale.SiteTzLang
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** 设置 + 书签 + 本地保存的下载（小数据） */
private val Context.cuteDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cute_prefs",
    // 文件损坏（断电/写一半）时当作空设置重新开始，而不是让每个收集设置的协程崩溃、App 一启动就闪退
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 历史记录单独一个文件：改设置时不会连带重写几百条历史，写历史也不会重写设置 */
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cute_history",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Immutable
data class PageEntry(val url: String, val title: String, val time: Long)

/** 通过 blob:/data: 保存到"下载"目录的文件（DownloadManager 不知道它们） */
@Immutable
data class LocalDownload(val uri: String, val title: String, val mime: String, val size: Long, val time: Long)

/** 安全/隐私设置（v1.3）。默认值偏安全；除了"应用锁""退出时清除"这类会打扰使用的，默认都开 */
@Immutable
data class SecurityPrefs(
    val httpsOnly: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val webrtcProtect: Boolean = true,
    val genericUa: Boolean = true,
    val dntGpc: Boolean = true,
    val autofill: Boolean = false,
    val secureScreen: Boolean = false,      // 普通标签防截屏（隐身标签总是开）
    val appLock: Boolean = false,
    val clearOnExit: Boolean = false,
    val antiFp: Boolean = true,            // 防指纹（v1.4）
    val fpStrict: Boolean = false,         // 防指纹严格级别：另外把时区设成 UTC、屏幕固定等
    val tzOn: Boolean = false,             // 伪造时区和语言（v1.5，独立于防指纹）
    val tz: String = "",                   // "" 跟随系统 / "*" 每个网站随机 / IANA 时区名
    val lang: String = "",                 // "" 跟随系统 / BCP-47 标签
)

/** 网站权限种类 */
enum class SitePerm(val label: String, val emoji: String) {
    LOCATION("位置", "📍"), CAMERA("相机", "📷"), MIC("麦克风", "🎤"), DRM("受保护内容(DRM)", "🎬"), MIDI("MIDI 设备", "🎹")
}

/** 记住的网站权限：origin + 种类 -> 允许/拒绝 */
@Immutable
data class SitePermEntry(val origin: String, val perm: SitePerm, val allow: Boolean)

enum class SearchEngine(val label: String, private val template: String) {
    BING("必应", "https://cn.bing.com/search?q=%s"),
    BAIDU("百度", "https://www.baidu.com/s?wd=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s");

    fun searchUrl(query: String): String =
        template.format(java.net.URLEncoder.encode(query, "UTF-8"))
}

class CuteRepository(context: Context) {
    private val ds = context.applicationContext.cuteDataStore
    private val hs = context.applicationContext.historyDataStore

    // 读盘失败（IO 错误）时当作"还没有数据"，而不是把异常抛给 UI 里的 collect
    private val dsData: Flow<Preferences> = safe(ds.data)
    private val hsData: Flow<Preferences> = safe(hs.data)

    private fun safe(f: Flow<Preferences>): Flow<Preferences> = f
        .retryWhen { e, attempt -> if (e is IOException && attempt < 2) { delay(150); true } else false }
        .catch { emit(emptyPreferences()) }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val ENGINE = stringPreferencesKey("engine")
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val HISTORY = stringPreferencesKey("history") // v1.0/1.1 放在主文件里，迁移用
        val ADBLOCK = booleanPreferencesKey("adblock")
        val WEB_DARK = booleanPreferencesKey("web_dark")
        val LOCAL_DL = stringPreferencesKey("local_downloads")
        val H = stringPreferencesKey("h")
        val SEC = stringPreferencesKey("security")
        val SITE_PERMS = stringPreferencesKey("site_perms")
        val HTTP_OK = stringPreferencesKey("http_allowed")
        val AD_OFF = stringPreferencesKey("adblock_off")
        val FP_OFF = stringPreferencesKey("fp_off")
        val TZ_SITES = stringPreferencesKey("tzlang_sites")
    }

    val security: Flow<SecurityPrefs> = dsData.map { it[Keys.SEC] }.distinctUntilChanged().map { decodeSec(it) }

    val sitePerms: Flow<List<SitePermEntry>> = dsData.map { it[Keys.SITE_PERMS] }.distinctUntilChanged()
        .map { decodePerms(it) }.flowOn(Dispatchers.Default)

    /** 用户点过"仍然用 http 打开"的网站 */
    val httpAllowed: Flow<Set<String>> = dsData.map { it[Keys.HTTP_OK] }.distinctUntilChanged().map { decodeSet(it) }

    /** 用户关掉了拦截的网站 */
    val adBlockOff: Flow<Set<String>> = dsData.map { it[Keys.AD_OFF] }.distinctUntilChanged().map { decodeSet(it) }

    /** 用户关掉防指纹的网站（eTLD+1） */
    val fpOff: Flow<Set<String>> = dsData.map { it[Keys.FP_OFF] }.distinctUntilChanged().map { decodeSet(it) }

    /** 单个网站的时区/语言覆盖（按 eTLD+1） */
    val tzLangSites: Flow<Map<String, SiteTzLang>> = dsData.map { it[Keys.TZ_SITES] }.distinctUntilChanged().map { decodeSites(it) }

    suspend fun setSiteTzLang(site: String, v: SiteTzLang?) {
        ds.edit { p ->
            val m = decodeSites(p[Keys.TZ_SITES]).toMutableMap()
            if (v == null || v.isEmpty) m.remove(site) else m[site] = v
            p[Keys.TZ_SITES] = encodeSites(m)
        }
    }

    suspend fun clearSiteTzLang() { ds.edit { it.remove(Keys.TZ_SITES) } }

    internal fun encodeSites(m: Map<String, SiteTzLang>): String = JSONObject().apply {
        m.forEach { (k, v) -> put(k, JSONObject().apply { v.tz?.let { put("z", it) }; v.lang?.let { put("g", it) } }) }
    }.toString()

    internal fun decodeSites(s: String?): Map<String, SiteTzLang> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, SiteTzLang>()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                val v = SiteTzLang(if (e.has("z")) e.optString("z") else null, if (e.has("g")) e.optString("g") else null)
                if (!v.isEmpty) out[k] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    suspend fun updateSecurity(f: (SecurityPrefs) -> SecurityPrefs) {
        ds.edit { p -> p[Keys.SEC] = encodeSec(f(decodeSec(p[Keys.SEC]))) }
    }

    suspend fun setSitePerm(origin: String, perm: SitePerm, allow: Boolean?) {
        ds.edit { p ->
            val list = decodePerms(p[Keys.SITE_PERMS]).filterNot { it.origin == origin && it.perm == perm }.toMutableList()
            if (allow != null) list.add(0, SitePermEntry(origin, perm, allow))
            p[Keys.SITE_PERMS] = encodePerms(list)
        }
    }

    suspend fun clearSitePerms() { ds.edit { it.remove(Keys.SITE_PERMS) } }

    suspend fun setHttpAllowed(host: String, allowed: Boolean) = editSet(Keys.HTTP_OK, host, allowed)
    suspend fun setAdBlockOff(host: String, off: Boolean) = editSet(Keys.AD_OFF, host, off)
    suspend fun setFpOff(site: String, off: Boolean) = editSet(Keys.FP_OFF, site, off)

    private suspend fun editSet(key: Preferences.Key<String>, v: String, add: Boolean) {
        ds.edit { p ->
            val set = decodeSet(p[key]).toMutableSet()
            if (add) set.add(v) else set.remove(v)
            p[key] = JSONArray(set.toList()).toString()
        }
    }

    internal fun decodeSet(s: String?): Set<String> {
        if (s.isNullOrBlank()) return emptySet()
        return runCatching { val a = JSONArray(s); (0 until a.length()).mapNotNullTo(HashSet()) { a.optString(it).takeIf { v -> v.isNotEmpty() } } }.getOrDefault(emptySet())
    }

    internal fun encodeSec(x: SecurityPrefs): String = JSONObject()
        .put("https", x.httpsOnly).put("3pc", x.blockThirdPartyCookies).put("rtc", x.webrtcProtect).put("ua", x.genericUa)
        .put("gpc", x.dntGpc).put("autofill", x.autofill).put("secure", x.secureScreen).put("lock", x.appLock)
        .put("clear", x.clearOnExit).put("fp", x.antiFp).put("fps", x.fpStrict)
        .put("tzon", x.tzOn).put("tz", x.tz).put("lang", x.lang).toString()

    internal fun decodeSec(s: String?): SecurityPrefs {
        val d = SecurityPrefs()
        if (s.isNullOrBlank()) return d
        return runCatching {
            val o = JSONObject(s)
            SecurityPrefs(
                o.optBoolean("https", d.httpsOnly), o.optBoolean("3pc", d.blockThirdPartyCookies), o.optBoolean("rtc", d.webrtcProtect),
                o.optBoolean("ua", d.genericUa), o.optBoolean("gpc", d.dntGpc), o.optBoolean("autofill", d.autofill),
                o.optBoolean("secure", d.secureScreen), o.optBoolean("lock", d.appLock), o.optBoolean("clear", d.clearOnExit),
                o.optBoolean("fp", d.antiFp), o.optBoolean("fps", d.fpStrict),
                o.optBoolean("tzon", d.tzOn), o.optString("tz", d.tz), o.optString("lang", d.lang),
            )
        }.getOrDefault(d)
    }

    private fun encodePerms(list: List<SitePermEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("o", it.origin).put("p", it.perm.name).put("a", it.allow)) }
    }.toString()

    internal fun decodePerms(s: String?): List<SitePermEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val perm = SitePerm.entries.firstOrNull { it.name == o.optString("p") } ?: return@mapNotNull null
                SitePermEntry(o.optString("o"), perm, o.optBoolean("a"))
            }
        }.getOrDefault(emptyList())
    }

    val theme: Flow<CuteThemeId> = dsData.map { p ->
        CuteThemeId.entries.firstOrNull { it.name == p[Keys.THEME] } ?: CuteThemeId.STRAWBERRY
    }.distinctUntilChanged()

    val engine: Flow<SearchEngine> = dsData.map { p ->
        SearchEngine.entries.firstOrNull { it.name == p[Keys.ENGINE] } ?: SearchEngine.BING
    }.distinctUntilChanged()

    val adBlock: Flow<Boolean> = dsData.map { it[Keys.ADBLOCK] ?: true }.distinctUntilChanged()

    /** 网页跟随主题变暗（只在深色主题下生效），默认开 */
    val webDark: Flow<Boolean> = dsData.map { it[Keys.WEB_DARK] ?: true }.distinctUntilChanged()

    // 先比较原始字符串，没变就不重新解析 JSON；解析放在后台线程
    val bookmarks: Flow<List<PageEntry>> = dsData.map { it[Keys.BOOKMARKS] }.distinctUntilChanged()
        .map { decode(it) }.flowOn(Dispatchers.Default)
    val history: Flow<List<PageEntry>> = hsData.map { it[Keys.H] }.distinctUntilChanged()
        .map { decode(it) }.flowOn(Dispatchers.Default)
    val localDownloads: Flow<List<LocalDownload>> = dsData.map { it[Keys.LOCAL_DL] }.distinctUntilChanged()
        .map { decodeLocal(it) }.flowOn(Dispatchers.Default)

    suspend fun setTheme(id: CuteThemeId) { ds.edit { it[Keys.THEME] = id.name } }
    suspend fun setEngine(e: SearchEngine) { ds.edit { it[Keys.ENGINE] = e.name } }
    suspend fun setAdBlock(on: Boolean) { ds.edit { it[Keys.ADBLOCK] = on } }
    suspend fun setWebDark(on: Boolean) { ds.edit { it[Keys.WEB_DARK] = on } }

    /** 把旧版本放在主文件里的历史搬到单独的历史文件（只做一次，很快） */
    suspend fun migrateIfNeeded() {
        val old = dsData.first()[Keys.HISTORY] ?: return
        hs.edit { if (it[Keys.H].isNullOrBlank()) it[Keys.H] = old }
        ds.edit { it.remove(Keys.HISTORY) }
    }

    /** 切换收藏状态，返回 true 表示新加入了收藏 */
    suspend fun toggleBookmark(url: String, title: String): Boolean {
        var added = false
        ds.edit { p ->
            val list = decode(p[Keys.BOOKMARKS]).toMutableList()
            val idx = list.indexOfFirst { it.url == url }
            if (idx >= 0) list.removeAt(idx) else {
                list.add(0, PageEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
                added = true
            }
            p[Keys.BOOKMARKS] = encode(list)
        }
        return added
    }

    suspend fun removeBookmark(url: String) {
        ds.edit { p -> p[Keys.BOOKMARKS] = encode(decode(p[Keys.BOOKMARKS]).filterNot { it.url == url }) }
    }

    suspend fun addHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
        hs.edit { p ->
            val list = decode(p[Keys.H]).toMutableList()
            if (list.firstOrNull()?.url == url) list.removeAt(0)
            list.add(0, PageEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
            p[Keys.H] = encode(if (list.size > MAX_HISTORY) list.subList(0, MAX_HISTORY) else list)
        }
    }

    suspend fun removeHistory(entry: PageEntry) {
        hs.edit { p -> p[Keys.H] = encode(decode(p[Keys.H]).filterNot { it == entry }) }
    }

    suspend fun clearHistory() { hs.edit { it.remove(Keys.H) } }

    suspend fun addLocalDownload(d: LocalDownload) {
        ds.edit { p -> p[Keys.LOCAL_DL] = encodeLocal((listOf(d) + decodeLocal(p[Keys.LOCAL_DL])).take(200)) }
    }

    suspend fun removeLocalDownload(uri: String) {
        ds.edit { p -> p[Keys.LOCAL_DL] = encodeLocal(decodeLocal(p[Keys.LOCAL_DL]).filterNot { it.uri == uri }) }
    }

    private fun encode(list: List<PageEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
    }.toString()

    internal fun decode(s: String?): List<PageEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            // 单条损坏只丢掉那一条，不连累整个列表
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("u")
                if (u.isEmpty()) null else PageEntry(u, o.optString("t"), o.optLong("d"))
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeLocal(list: List<LocalDownload>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("u", it.uri).put("t", it.title).put("m", it.mime).put("s", it.size).put("d", it.time))
        }
    }.toString()

    internal fun decodeLocal(s: String?): List<LocalDownload> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("u")
                if (u.isEmpty()) null else LocalDownload(u, o.optString("t"), o.optString("m"), o.optLong("s"), o.optLong("d"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 「清除本站数据」：去掉这个网站（eTLD+1）相关的记住的权限、拦截/防指纹/时区例外、允许 http 的记录。
     * [inSite] 判断一个主机名是否属于这个网站。
     */
    suspend fun clearSiteSettings(site: String, inSite: (String) -> Boolean) {
        ds.edit { p ->
            fun dropHosts(key: Preferences.Key<String>) {
                val set = decodeSet(p[key])
                val kept = set.filterNot { inSite(it) }
                if (kept.size != set.size) p[key] = JSONArray(kept).toString()
            }
            dropHosts(Keys.AD_OFF)
            dropHosts(Keys.HTTP_OK)
            dropHosts(Keys.FP_OFF)
            val sites = decodeSites(p[Keys.TZ_SITES])
            if (sites.keys.any { inSite(it) }) p[Keys.TZ_SITES] = encodeSites(sites.filterKeys { !inSite(it) })
            val perms = decodePerms(p[Keys.SITE_PERMS])
            val keptPerms = perms.filterNot { inSite(com.yuka.cutebrowser.security.HttpsUpgrade.hostOf(it.origin)) }
            if (keptPerms.size != perms.size) p[Keys.SITE_PERMS] = encodePerms(keptPerms)
        }
    }

    // ---------------- 备份 / 恢复（v1.7，见 data/Backup.kt） ----------------

    /** 读出要备份的东西（历史只有 [withHistory] 时才读） */
    suspend fun backupSnapshot(withHistory: Boolean): Backup.Content {
        val p = dsData.first()
        val settings = Backup.Settings(
            theme = p[Keys.THEME],
            engine = p[Keys.ENGINE],
            adBlock = p[Keys.ADBLOCK],
            webDark = p[Keys.WEB_DARK],
            security = p[Keys.SEC]?.let { runCatching { JSONObject(it) }.getOrNull() },
            adBlockOff = decodeSet(p[Keys.AD_OFF]),
            fpOff = decodeSet(p[Keys.FP_OFF]),
            httpAllowed = decodeSet(p[Keys.HTTP_OK]),
            tzSites = decodeSites(p[Keys.TZ_SITES]),
            sitePerms = decodePerms(p[Keys.SITE_PERMS]),
        )
        val history = if (withHistory) decode(hsData.first()[Keys.H]) else null
        return Backup.Content(decode(p[Keys.BOOKMARKS]), history, settings)
    }

    /** 导入：书签/历史合并去重；设置覆盖（网站名单合并）。返回新加了几个书签、几条历史 */
    suspend fun applyBackup(c: Backup.Content, bookmarks: Boolean, settings: Boolean, history: Boolean): Pair<Int, Int> {
        var addedB = 0
        var addedH = 0
        ds.edit { p ->
            if (bookmarks) {
                val old = decode(p[Keys.BOOKMARKS])
                val merged = Backup.mergeBookmarks(old, c.bookmarks)
                addedB = merged.size - old.size
                p[Keys.BOOKMARKS] = encode(merged)
            }
            val s = c.settings
            if (settings && s != null) {
                s.theme?.let { t -> if (CuteThemeId.entries.any { it.name == t }) p[Keys.THEME] = t }
                s.engine?.let { e -> if (SearchEngine.entries.any { it.name == e }) p[Keys.ENGINE] = e }
                s.adBlock?.let { p[Keys.ADBLOCK] = it }
                s.webDark?.let { p[Keys.WEB_DARK] = it }
                s.security?.let { p[Keys.SEC] = encodeSec(decodeSec(it.toString())) } // 过一遍解析：只留认识的字段
                if (s.adBlockOff.isNotEmpty()) p[Keys.AD_OFF] = JSONArray((decodeSet(p[Keys.AD_OFF]) + s.adBlockOff).toList()).toString()
                if (s.fpOff.isNotEmpty()) p[Keys.FP_OFF] = JSONArray((decodeSet(p[Keys.FP_OFF]) + s.fpOff).toList()).toString()
                if (s.httpAllowed.isNotEmpty()) p[Keys.HTTP_OK] = JSONArray((decodeSet(p[Keys.HTTP_OK]) + s.httpAllowed).toList()).toString()
                if (s.tzSites.isNotEmpty()) p[Keys.TZ_SITES] = encodeSites(decodeSites(p[Keys.TZ_SITES]) + s.tzSites)
                if (s.sitePerms.isNotEmpty()) {
                    val cur = decodePerms(p[Keys.SITE_PERMS])
                    val incoming = s.sitePerms.filter { com.yuka.cutebrowser.browser.SitePermPolicy.canRemember(it.origin, false) }
                    val keys = incoming.mapTo(HashSet()) { it.origin to it.perm }
                    p[Keys.SITE_PERMS] = encodePerms(incoming + cur.filterNot { (it.origin to it.perm) in keys })
                }
            }
        }
        val h = c.history
        if (history && h != null) {
            hs.edit { p ->
                val old = decode(p[Keys.H])
                val merged = Backup.mergeHistory(old, h, MAX_HISTORY)
                addedH = merged.count { it !in old }
                p[Keys.H] = encode(merged)
            }
        }
        return addedB to addedH
    }

    companion object { const val MAX_HISTORY = 500 }
}
