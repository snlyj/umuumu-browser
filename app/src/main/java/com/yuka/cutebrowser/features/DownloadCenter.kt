package com.yuka.cutebrowser.features

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import androidx.compose.runtime.Immutable
import com.yuka.cutebrowser.browser.DlStatus
import com.yuka.cutebrowser.browser.DownloadItem
import com.yuka.cutebrowser.browser.Downloads
import com.yuka.cutebrowser.browser.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 我们自己记下的一次下载（DownloadManager 的记录会被系统清掉，也不带原始网址，重试要靠它） */
@Immutable
data class DownloadRecord(
    val dmId: Long,
    val url: String,
    val title: String,
    val mime: String?,
    val userAgent: String?,
    val time: Long,
)

/** 下载管理页的一行：系统里还有的下载（item）+ 我们的记录（record），两者至少有一个 */
@Immutable
data class DownloadRow(val item: DownloadItem?, val record: DownloadRecord?) {
    val key: String get() = item?.key ?: "r:${record?.dmId}:${record?.time}"
    val title: String get() = item?.title ?: record?.title ?: "未命名文件"
    val mime: String? get() = item?.mime ?: record?.mime
    val time: Long get() = item?.time?.takeIf { it > 0 } ?: record?.time ?: 0L
    /** 系统里已经没有这条下载了（被清理 / 在系统下载里删掉了），只剩我们的记录 */
    val missing: Boolean get() = item == null
    val done: Boolean get() = item?.status == DlStatus.DONE
    val active: Boolean get() = item?.active == true
    val failed: Boolean get() = item?.status == DlStatus.FAILED
    val canRetry: Boolean get() = record != null && UrlUtils.isWebUrl(record.url) && (missing || failed)
    val canShare: Boolean get() = done
}

/** 下载记录：filesDir/downloads/records.json，最多 [MAX] 条；只记普通标签的下载（隐身下载不留网址） */
class DownloadRecords(private val dir: File) {
    private val file get() = File(dir, "records.json")
    private val lock = Mutex()
    private var cache: List<DownloadRecord>? = null

    suspend fun all(): List<DownloadRecord> = lock.withLock { load() }

    suspend fun add(r: DownloadRecord) = lock.withLock {
        save(listOf(r) + load().filterNot { it.dmId == r.dmId })
    }

    suspend fun remove(dmId: Long) = lock.withLock {
        val cur = load()
        if (cur.any { it.dmId == dmId }) save(cur.filterNot { it.dmId == dmId })
    }

    suspend fun replace(oldId: Long, r: DownloadRecord) = lock.withLock {
        save(listOf(r) + load().filterNot { it.dmId == oldId || it.dmId == r.dmId })
    }

    suspend fun clear() = lock.withLock { save(emptyList()) }

    private suspend fun load(): List<DownloadRecord> = cache ?: withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) decode(file.readText()) else emptyList() }.getOrDefault(emptyList())
    }.also { cache = it }

    private suspend fun save(list: List<DownloadRecord>) {
        val trimmed = list.take(MAX)
        cache = trimmed
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "records.json.tmp")
                tmp.writeText(encode(trimmed))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    companion object {
        const val MAX = 200

        fun encode(list: List<DownloadRecord>): String = JSONArray().apply {
            list.forEach { r ->
                put(JSONObject().put("id", r.dmId).put("url", r.url).put("title", r.title).put("mime", r.mime ?: "")
                    .put("ua", r.userAgent ?: "").put("time", r.time))
            }
        }.toString()

        /** 坏掉的条目跳过，不会因为一条坏数据把整个列表丢掉 */
        fun decode(s: String): List<DownloadRecord> {
            val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    DownloadRecord(
                        dmId = o.getLong("id"),
                        url = o.getString("url"),
                        title = o.optString("title").ifBlank { "未命名文件" },
                        mime = o.optString("mime").ifBlank { null },
                        userAgent = o.optString("ua").ifBlank { null },
                        time = o.optLong("time"),
                    )
                }.getOrNull()
            }
        }

        /** 系统里的下载 + 我们的记录，合并成下载页的行（按时间倒序） */
        fun merge(items: List<DownloadItem>, records: List<DownloadRecord>): List<DownloadRow> {
            val byId = records.associateBy { it.dmId }
            val dmIds = items.mapNotNullTo(HashSet()) { it.dmId.takeIf { id -> id >= 0 } }
            val rows = items.map { DownloadRow(it, if (it.dmId >= 0) byId[it.dmId] else null) } +
                records.filter { it.dmId !in dmIds }.map { DownloadRow(null, it) }
            return rows.sortedByDescending { it.time }
        }
    }
}

/** 下载管理页背后的逻辑：列表、分享、重试、删除 */
class DownloadCenter(private val context: Context, private val downloads: Downloads) {
    val records = DownloadRecords(File(context.filesDir, "downloads"))
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    suspend fun rows(): List<DownloadRow> = DownloadRecords.merge(downloads.list(), records.all())

    /** BrowserController 每发起一个 http(s) 下载调用一次（隐身标签不调用） */
    suspend fun record(dmId: Long, url: String, title: String, mime: String?, userAgent: String?) {
        records.add(DownloadRecord(dmId, url, title, mime, userAgent, System.currentTimeMillis()))
    }

    fun open(row: DownloadRow): Boolean = row.item?.let { downloads.open(it) } ?: false

    /** 分享已下载的文件（content:// + 临时读权限） */
    fun share(activityContext: Context, row: DownloadRow): Boolean {
        val item = row.item ?: return false
        if (item.status != DlStatus.DONE) return false
        val uri = runCatching {
            if (item.dmId >= 0) dm.getUriForDownloadedFile(item.dmId) else Uri.parse(item.localUri)
        }.getOrNull() ?: return false
        val mime = item.mime?.takeIf { it.isNotBlank() } ?: runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(item.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            activityContext.startActivity(Intent.createChooser(send, "分享文件 💌").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.isSuccess
    }

    /** 重新下载：用记下的网址/UA 重新排队（Cookie 取普通标签现在的），旧的失败条目删掉 */
    suspend fun retry(row: DownloadRow): Boolean = withContext(Dispatchers.IO) {
        val r = row.record ?: return@withContext false
        if (!UrlUtils.isWebUrl(r.url)) return@withContext false
        runCatching {
            val name = Downloads.sanitizeFileName(r.title)
            val req = DownloadManager.Request(Uri.parse(r.url)).apply {
                r.mime?.let { setMimeType(it) }
                r.userAgent?.let { addRequestHeader("User-Agent", it) }
                runCatching { CookieManager.getInstance().getCookie(r.url) }.getOrNull()?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setDescription("唔姆唔姆正在重新下载～")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val id = dm.enqueue(req)
            row.item?.let { if (it.status == DlStatus.FAILED && it.dmId >= 0) runCatching { dm.remove(it.dmId) } }
            records.replace(r.dmId, r.copy(dmId = id, title = name, time = System.currentTimeMillis()))
            true
        }.getOrDefault(false)
    }

    /** 取消进行中的 / 删除已下载的文件，连同我们的记录 */
    suspend fun delete(row: DownloadRow) {
        row.item?.let { downloads.remove(it) }
        row.record?.let { records.remove(it.dmId) }
    }

    /** 只清掉「文件已经不在了」的记录 */
    suspend fun clearMissing(rows: List<DownloadRow>) {
        rows.filter { it.missing }.forEach { r -> r.record?.let { records.remove(it.dmId) } }
    }
}
