package com.yuka.cutebrowser.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.data.LocalDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DlStatus { PENDING, RUNNING, PAUSED, DONE, FAILED }

@Immutable
data class DownloadItem(
    val key: String,
    val dmId: Long,          // DownloadManager 的 id；本地保存的文件为 -1
    val localUri: String?,
    val title: String,
    val mime: String?,
    val status: DlStatus,
    val soFar: Long,
    val total: Long,
    val time: Long,
) {
    val active: Boolean get() = status == DlStatus.RUNNING || status == DlStatus.PENDING || status == DlStatus.PAUSED
}

/** 下载管理：查询 DownloadManager（系统只会返回本 App 发起的下载）+ 本地保存的 blob/data 文件 */
class Downloads(private val context: Context, private val repo: CuteRepository) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    suspend fun list(): List<DownloadItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<DownloadItem>()
        runCatching {
            dm.query(DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iSoFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iTime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                val iMime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val status = when (c.getInt(iStatus)) {
                        DownloadManager.STATUS_PENDING -> DlStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> DlStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> DlStatus.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DlStatus.DONE
                        else -> DlStatus.FAILED
                    }
                    out += DownloadItem(
                        key = "dm:$id", dmId = id, localUri = null,
                        title = c.getString(iTitle).orEmpty().ifBlank { "未命名文件" },
                        mime = c.getString(iMime), status = status,
                        soFar = c.getLong(iSoFar), total = c.getLong(iTotal), time = c.getLong(iTime),
                    )
                }
            }
        }
        repo.localDownloads.first().forEach {
            out += DownloadItem("l:${it.uri}", -1, it.uri, it.title, it.mime, DlStatus.DONE, it.size, it.size, it.time)
        }
        out.sortedByDescending { it.time }
    }

    /** 打开已完成的文件，返回是否成功 */
    fun open(item: DownloadItem): Boolean {
        val uri = runCatching {
            if (item.dmId >= 0) dm.getUriForDownloadedFile(item.dmId) else Uri.parse(item.localUri)
        }.getOrNull() ?: return false
        val mime = item.mime?.takeIf { it.isNotBlank() } ?: context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** 取消进行中的下载，或删除已下载的文件 */
    suspend fun remove(item: DownloadItem) = withContext(Dispatchers.IO) {
        if (item.dmId >= 0) {
            runCatching { dm.remove(item.dmId) }
        } else {
            val u = item.localUri ?: return@withContext
            runCatching {
                val uri = Uri.parse(u)
                if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
            }
            repo.removeLocalDownload(u)
        }
    }

    /** 把字节保存到公共"下载"目录：Q+ 用 MediaStore（不需要权限），更早的系统写文件后登记到 DownloadManager */
    @Suppress("DEPRECATION")
    suspend fun saveBytes(name: String, mime: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                repo.addLocalDownload(LocalDownload(uri.toString(), name, mime, bytes.size.toLong(), System.currentTimeMillis()))
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = uniqueFile(dir, name)
                file.writeBytes(bytes)
                dm.addCompletedDownload(file.name, "唔姆唔姆", true, mime, file.absolutePath, file.length(), true)
            }
            true
        }.getOrDefault(false)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base($i)$ext").also { i++ }
        return f
    }

    companion object {
        /** blob 最大 50MB（要经过 JS 桥传 base64，太大会占很多内存） */
        const val MAX_BLOB_BYTES = 50L * 1024 * 1024

        /** 桥上收到的消息最长多少字符：base64 膨胀 4/3，再留点 JSON 和 data: 头的余量；超过的一律丢弃 */
        const val MAX_BLOB_JSON_CHARS = (MAX_BLOB_BYTES * 4 / 3 + 4096).toInt()

        /**
         * 文件名消毒：网页（Content-Disposition / URL / blob 的 download 属性）说了算的名字不能带路径、控制符、
         * 反向书写符（把 gpj.exe 显示成 exe.jpg 骗人），不能以点开头（隐藏文件 / ".."），长度有限。
         */
        fun sanitizeFileName(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (c in raw) {
                when {
                    c < ' ' || c == '\u007f' -> sb.append('_')
                    c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|' -> sb.append('_')
                    c in '\u200e'..'\u200f' || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' || c == '\u061c' -> Unit
                    else -> sb.append(c)
                }
            }
            var n = sb.toString().trim().trimStart('.', ' ').trimEnd('.', ' ')
            if (n.length > 120) {
                val dot = n.lastIndexOf('.')
                val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
                n = n.substring(0, 120 - ext.length) + ext
            }
            return n.ifEmpty { "download" }
        }

        private val COMMON_EXT = mapOf(
            "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
            "image/svg+xml" to "svg", "application/pdf" to "pdf", "text/plain" to "txt", "text/csv" to "csv",
            "application/json" to "json", "application/zip" to "zip", "video/mp4" to "mp4", "audio/mpeg" to "mp3",
            "text/html" to "html",
        )

        fun fileName(url: String, contentDisposition: String?, mime: String?): String =
            sanitizeFileName(rawFileName(url, contentDisposition, mime))

        private fun rawFileName(url: String, contentDisposition: String?, mime: String?): String {
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (scheme == "data" || scheme == "blob") {
                if (!contentDisposition.isNullOrBlank() && contentDisposition.contains("filename", true)) {
                    return URLUtil.guessFileName("https://local/file", contentDisposition, mime)
                }
                val m = mime?.substringBefore(';')?.trim()?.lowercase()
                val ext = m?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) ?: COMMON_EXT[it] } ?: "bin"
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                return "umu_$stamp.$ext"
            }
            return URLUtil.guessFileName(url, contentDisposition, mime)
        }

        /** 解析 data: URL，返回 (mime, 内容)；格式不对返回 null */
        fun decodeDataUrl(url: String): Pair<String, ByteArray>? = runCatching {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val isB64 = meta.endsWith(";base64", ignoreCase = true)
            val mime = meta.substringBefore(';').trim().ifBlank { "text/plain" }
            val bytes = if (isB64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            mime to bytes
        }.getOrNull()
    }
}

/**
 * blob: 下载的 JS 桥。只有我们自己在下载回调里生成的一次性 token 才会被接受，
 * 网页没法自己调用它来往手机里塞文件。
 */
@Keep
class BlobBridge(private val onMessage: (json: String, origin: String?) -> Unit) {
    /** 旧 WebView 没有 WebMessageListener 时的退路，JS 里同样是 UmuBlobBridge.postMessage(...) */
    @Keep
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json, null)

    companion object {
        const val NAME = "UmuBlobBridge"

        fun script(token: String, blobUrl: String, maxBytes: Long): String {
            val t = org.json.JSONObject.quote(token)
            val u = org.json.JSONObject.quote(blobUrl)
            return """(function(){var t=$t;var B=window.$NAME;if(!B)return;
function S(o){o.t=t;B.postMessage(JSON.stringify(o));}
fetch($u).then(function(r){return r.blob();}).then(function(b){
if(b.size>$maxBytes){S({e:'too_large'});return;}
var fr=new FileReader();fr.onloadend=function(){if(fr.result)S({d:String(fr.result),m:b.type||''});else S({e:'read'});};
fr.onerror=function(){S({e:'read'});};fr.readAsDataURL(b);
}).catch(function(e){S({e:String(e)});});})();"""
        }
    }
}
