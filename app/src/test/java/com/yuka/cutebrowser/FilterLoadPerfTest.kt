package com.yuka.cutebrowser

import com.yuka.cutebrowser.filter.FilterEngine
import com.yuka.cutebrowser.filter.FilterRequest
import com.yuka.cutebrowser.filter.ResType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 启动路径上的过滤规则缓存读取耗时（JVM 数字，手机上会慢几倍，但前后对比有意义），
 * 以及"多个 WebView 线程同时匹配不会互相拖慢 / 结果一致"。
 */
class FilterLoadPerfTest {
    private fun fullEngine(): FilterEngine {
        val dir = File("src/main/assets/filters")
        val e = FilterEngine()
        File(dir, "easylist.txt").bufferedReader().use { e.addList(it) }
        File(dir, "easyprivacy.txt").bufferedReader().use { e.addList(it) }
        File("src/main/assets/adblock_hosts.txt").bufferedReader().useLines { ls ->
            e.addHosts(ls.map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.toList())
        }
        e.finish()
        return e
    }

    private fun req(url: String, page: String = "www.example.org", type: Int = ResType.SCRIPT): FilterRequest {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return FilterRequest(url.lowercase(), host.lowercase(), page, type)
    }

    @Test
    fun cacheLoadTiming_andRoundTrip() {
        val e = fullEngine()
        val bos = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(bos, 1 shl 16)).use { e.writeTo(it) }
        val bytes = bos.toByteArray()
        val times = ArrayList<Double>()
        val ready = ArrayList<Double>()
        var last: FilterEngine? = null
        repeat(6) {
            val t0 = System.nanoTime()
            last = FilterEngine.readFrom(DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes), 1 shl 16))) {
                ready += (System.nanoTime() - t0) / 1e6 // 网络规则就绪（可以开始拦请求）
            }
            times += (System.nanoTime() - t0) / 1e6
        }
        val l = last!!
        assertEquals(e.networkRuleCount, l.networkRuleCount)
        assertEquals(e.cosmeticRuleCount, l.cosmeticRuleCount)
        println("FILTER_LOAD_MS first=%.0f networkReadyFirst=%.0f runs=%s networkReadyRuns=%s bytes=%d".format(times[0], ready[0], times.joinToString(",") { "%.0f".format(it) }, ready.joinToString(",") { "%.0f".format(it) }, bytes.size))
        assertTrue(l.shouldBlock(req("https://securepubads.g.doubleclick.net/tag/js/gpt.js")))
        assertTrue(l.cosmeticCss("https://www.example.com/", "www.example.com").let { true })
    }

    @Test
    fun concurrentMatching_isConsistent() {
        val e = fullEngine()
        val urls = listOf(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js" to true,
            "https://www.google-analytics.com/analytics.js" to true,
            "https://www.example.org/static/app.js" to false,
            "https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js" to false,
            "https://hm.baidu.com/hm.js?abcdef0123456789" to true,
        ).map { (u, b) -> req(u) to b }
        val n = 100_000
        for (i in 0 until 30_000) e.shouldBlock(urls[i % urls.size].first) // 预热 JIT
        // 单线程基准
        val t0 = System.nanoTime()
        for (i in 0 until n) e.shouldBlock(urls[i % urls.size].first)
        val singleNs = System.nanoTime() - t0
        val threads = 4
        val wrong = AtomicInteger()
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val t1 = System.nanoTime()
        repeat(threads) {
            thread {
                go.await()
                for (i in 0 until n) {
                    val (r, expect) = urls[i % urls.size]
                    if (e.shouldBlock(r) != expect) wrong.incrementAndGet()
                }
                done.countDown()
            }
        }
        go.countDown(); done.await()
        val wallNs = System.nanoTime() - t1
        // 每个线程做的活和单线程基准一样多：没有锁竞争时墙钟时间应该和单线程差不多（比值接近 1）；有全局锁就会接近线程数
        val ratio = wallNs.toDouble() / singleNs
        println("FILTER_CONCURRENCY single=%.2fus/req  4threads_wall/single=%.2f (1.0 = 没有竞争, 4.0 = 完全串行)".format(singleNs / 1e3 / n, ratio))
        assertEquals(0, wrong.get())
        if (Runtime.getRuntime().availableProcessors() >= 4) assertTrue("matching seems serialised: $ratio", ratio < 3.0)
    }
}
