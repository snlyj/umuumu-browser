package com.yuka.cutebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把未捕获的异常写到应用私有目录，方便用户在设置里复制出来发给我们。
 * 只存在本机，不上网、不上传。
 */
object CrashLog {
    const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 64 * 1024

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    fun install(app: Context) {
        val ctx = app.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(ctx, thread, error) }
            prev?.uncaughtException(thread, error)
        }
    }

    fun write(ctx: Context, thread: Thread, error: Throwable) {
        val pm = ctx.applicationContext.packageManager
        val pkg = ctx.applicationContext.packageName
        val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val version = info?.versionName.orEmpty()
        val code = if (info != null && Build.VERSION.SDK_INT >= 28) info.longVersionCode else info?.versionCode?.toLong() ?: 0L
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("唔姆唔姆 crash log (local only)")
            appendLine("time=$now")
            appendLine("version=$version ($code)")
            appendLine("sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread=${thread.name}")
            appendLine()
            append(stack)
        }.let { if (it.length <= MAX_CHARS) it else it.substring(0, MAX_CHARS) }
        val f = file(ctx)
        f.writeText(text)
    }

    fun read(ctx: Context): String? = runCatching {
        val f = file(ctx)
        if (!f.isFile || f.length() == 0L) null else f.readText()
    }.getOrNull()

    /** 把上次崩溃日志写进系统剪贴板。没有日志时返回 false。 */
    fun copyToClipboard(ctx: Context): Boolean = runCatching {
        val text = read(ctx) ?: return@runCatching false
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("唔姆唔姆崩溃日志", text))
        true
    }.getOrDefault(false)
}
