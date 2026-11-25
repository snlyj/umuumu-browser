package com.yuka.cutebrowser.dns

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** 真联网测速（默认跳过）：UMU_LIVE_DOH=1 ./gradlew testDebugUnitTest --tests '*DohLiveTest*' -i */
class DohLiveTest {
    @Test
    fun measureProviders() {
        assumeTrue(System.getenv("UMU_LIVE_DOH") != null)
        for (p in DohProvider.BUILT_IN) {
            val t = OkHttpDohTransport(p)
            try {
                val t0 = System.nanoTime()
                val r = DnsMessage.parse(t.exchange(DnsMessage.buildQuery("www.bing.com", DnsMessage.TYPE_A)))
                val cold = (System.nanoTime() - t0) / 1e6
                val warm = (1..5).map { i ->
                    val s = System.nanoTime()
                    DnsMessage.parse(t.exchange(DnsMessage.buildQuery("w$i.example.com", DnsMessage.TYPE_A)))
                    (System.nanoTime() - s) / 1e6
                }.sorted()
                println("LIVE ${p.id}: cold=%.1f ms warm median=%.1f ms (min %.1f, max %.1f) rcode=${r.rcode} ips=${r.addresses(1).map { it.hostAddress }}".format(cold, warm[2], warm[0], warm[4]))
            } catch (e: Exception) {
                println("LIVE ${p.id}: FAILED ${e.javaClass.simpleName}: ${e.message}")
            } finally { t.close() }
        }
    }

    @Test
    fun endToEndThroughProxy() {
        assumeTrue(System.getenv("UMU_LIVE_DOH") != null)
        val r = DohResolver(DohProvider.CLOUDFLARE, strict = true)
        val px = LocalProxy(r)
        val port = px.start()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        System.setProperty("http.keepAlive", "false") // 每次都新建连接：比较的是 "DNS + TCP + TLS" 的完整开销
        fun timed(u: String, via: Proxy): Pair<Int, Double> {
            val s = System.nanoTime()
            val c = URL(u).openConnection(via) as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
            c.disconnect()
            return code to (System.nanoTime() - s) / 1e6
        }
        r.prewarm(emptyList()); Thread.sleep(800)
        for (u in listOf("https://www.example.com/", "https://www.cloudflare.com/robots.txt", "https://github.com/robots.txt")) {
            val first = timed(u, proxy) // 第一次：DoH 查询（未缓存）+ 建连
            val viaProxy = (1..5).map { timed(u, proxy).second }.sorted()
            val direct = (1..5).map { timed(u, Proxy.NO_PROXY).second }.sorted()
            println("LIVE e2e $u code=${first.first} proxy-first=%.1f ms proxy-median=%.1f ms direct-median=%.1f ms".format(first.second, viaProxy[2], direct[2]))
        }
        // 只测解析（缓存外 / 缓存内）
        val s1 = System.nanoTime(); r.resolve("www.mozilla.org"); val m1 = (System.nanoTime() - s1) / 1e6
        val s2 = System.nanoTime(); r.resolve("www.mozilla.org"); val m2 = (System.nanoTime() - s2) / 1e6
        println("LIVE resolve www.mozilla.org uncached=%.1f ms cached=%.3f ms".format(m1, m2))
        println("LIVE stats lookups=${r.lookups} hits=${r.cacheHits} doh=${r.dohQueries} errors=${px.errors}")
        px.stop(); r.close()
    }
}
