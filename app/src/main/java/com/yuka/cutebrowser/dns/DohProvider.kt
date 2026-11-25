package com.yuka.cutebrowser.dns

import java.net.InetAddress

/**
 * DoH 服务商。bootstrap IP 写死在这里：连 DoH 服务器本身不需要先查 DNS（否则又走回系统 DNS 了）。
 * 证书仍然按域名（url 里的 host）校验，IP 只用来建 TCP 连接。
 */
class DohProvider(
    val id: String,
    val label: String,
    val url: String,
    val bootstrap: List<String>,
    val note: String = "",
) {
    val host: String get() = java.net.URI(url).host

    /** bootstrap 地址：IPv4 在前（几乎所有网络都通），再 IPv6 */
    fun bootstrapAddresses(): List<InetAddress> = bootstrap.map { InetAddress.getByAddress(host, IpLiteral.parse(it)!!) }

    companion object {
        val CLOUDFLARE = DohProvider(
            "cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"), "全球最快之一",
        )
        val GOOGLE = DohProvider(
            "google", "Google", "https://dns.google/dns-query",
            listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
        )
        val QUAD9 = DohProvider(
            "quad9", "Quad9", "https://dns.quad9.net/dns-query",
            listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"), "会拦截恶意网站",
        )
        val ALIDNS = DohProvider(
            "alidns", "阿里 DNS", "https://dns.alidns.com/dns-query",
            listOf("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"), "国内网络推荐",
        )
        val DNSPOD = DohProvider(
            "dnspod", "腾讯 DNSPod", "https://doh.pub/dns-query",
            listOf("1.12.12.12", "120.53.53.53"), "国内网络推荐",
        )
        val BUILT_IN = listOf(CLOUDFLARE, GOOGLE, QUAD9, ALIDNS, DNSPOD)
        const val CUSTOM_ID = "custom"

        fun byId(id: String): DohProvider = BUILT_IN.firstOrNull { it.id == id } ?: CLOUDFLARE

        /** 自定义地址：必须是 https://，host 不能为空。bootstrap 为空 → 用系统 DNS 查一次服务器域名（IP 写法的 url 不用查） */
        fun custom(url: String): DohProvider? {
            val u = url.trim()
            val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrEmpty()) return null
            val ipHost = uri.host.trim('[', ']')
            return DohProvider(CUSTOM_ID, "自定义", u, if (IpLiteral.parse(ipHost) != null) listOf(ipHost) else emptyList())
        }
    }
}

/** 解析 IP 字面量（不触发 DNS）。不是 IP 返回 null */
object IpLiteral {
    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun parse(s: String): ByteArray? {
        val h = s.trim('[', ']')
        V4.matchEntire(h)?.let { m ->
            val parts = m.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return ByteArray(4) { parts[it].toByte() }
        }
        if (':' !in h) return null
        // IPv6：交给 InetAddress，但先确认字符集，避免把域名当 IP 去查
        if (h.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%') }) return null
        return runCatching { InetAddress.getByName(h).address }.getOrNull()
    }

    fun toAddress(s: String): InetAddress? = parse(s)?.let { InetAddress.getByAddress(s.trim('[', ']'), it) }
}
