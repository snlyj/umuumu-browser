package com.yuka.cutebrowser.browser

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把打开的标签页存到 filesDir/session：
 * - session.json：每个标签的 url / 标题 / 是否主页，以及当前是哪个
 * - <id>.state：WebView.saveState 得到的前进后退记录（Parcel 序列化的 Bundle）
 * 读写都在 IO 线程；单个 .state 文件在真正需要显示那个标签时才读（很小）。
 */
class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "session")

    data class SavedTab(val id: Long, val url: String, val title: String, val isHome: Boolean)
    data class Session(val tabs: List<SavedTab>, val active: Int)

    fun load(): Session? = runCatching {
        val f = File(dir, "session.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("tabs")
        val tabs = List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            SavedTab(t.getLong("id"), t.optString("u"), t.optString("t"), t.optBoolean("h", true))
        }
        Session(tabs, o.optInt("active", 0))
    }.getOrNull()

    fun readState(id: Long): Bundle? = runCatching {
        val f = File(dir, "$id.state")
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val p = Parcel.obtain()
        try {
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            p.readBundle(javaClass.classLoader)
        } finally {
            p.recycle()
        }
    }.getOrNull()

    /** [states]：要写的新状态；[keepIds]：磁盘上已有、这次没变但要保留的状态 */
    @Synchronized
    fun write(session: Session, states: Map<Long, Bundle>, keepIds: Set<Long>) {
        runCatching {
            dir.mkdirs()
            val arr = JSONArray()
            session.tabs.forEach {
                arr.put(JSONObject().put("id", it.id).put("u", it.url).put("t", it.title).put("h", it.isHome))
            }
            val json = JSONObject().put("active", session.active).put("tabs", arr).toString()
            atomicWrite(File(dir, "session.json"), json.toByteArray())
            states.forEach { (id, b) ->
                val p = Parcel.obtain()
                try {
                    p.writeBundle(b)
                    atomicWrite(File(dir, "$id.state"), p.marshall())
                } finally {
                    p.recycle()
                }
            }
            val alive = states.keys + keepIds
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".state") && f.name.removeSuffix(".state").toLongOrNull() !in alive) f.delete()
            }
        }
    }

    /** 删掉保存的标签页（"退出时清除数据"用） */
    @Synchronized
    fun clear() { dir.deleteRecursively() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete(); tmp.renameTo(target)
        }
    }
}
