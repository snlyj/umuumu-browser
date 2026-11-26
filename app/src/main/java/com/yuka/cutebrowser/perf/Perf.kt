package com.yuka.cutebrowser.perf

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log

/**
 * 性能埋点（几乎零开销）：
 * - [section]：在 Perfetto / Android Studio profiler 里能看到一段命名的耗时（android.os.Trace，不增加依赖）
 * - [StartupMarks]：记录冷启动各阶段离进程创建过了多少毫秒，写进 logcat（tag UmuStartup），也方便 Macrobenchmark 对照
 */
inline fun <T> section(name: String, block: () -> T): T {
    Trace.beginSection(name.take(100))
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

object StartupMarks {
    private const val TAG = "UmuStartup"
    private val lock = Any()
    private val marks = LinkedHashMap<String, Long>()

    /** 进程创建时刻（API 24+ 有；拿不到就用第一次 mark 的时刻） */
    private val processStart: Long by lazy {
        if (Build.VERSION.SDK_INT >= 24) Process.getStartElapsedRealtime() else SystemClock.elapsedRealtime()
    }

    /** 同名只记第一次（Activity 重建时不覆盖冷启动的数据） */
    fun mark(name: String, now: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            if (name in marks) return
            marks[name] = now
        }
        runCatching { Log.i(TAG, "$name +${now - processStart}ms") }
    }

    /** 各阶段相对进程创建的毫秒数（按记录顺序） */
    fun snapshot(): List<Pair<String, Long>> = synchronized(lock) { marks.map { it.key to it.value - processStart } }

    fun summary(): String = snapshot().joinToString(" · ") { "${it.first} ${it.second}ms" }

    internal fun resetForTest() = synchronized(lock) { marks.clear() }
}
