package com.dreemurr.shinsekai.core

// IPv4 字面量（宽松匹配，合法性交给 OkHttp 解析）。
private val IPV4_HOST = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

// 补 scheme 与尾部斜杠供 Retrofit 使用；已带前缀原样保留，裸地址按 host 推断：IP/localhost 补 http://（本地多为明文），其余 https://。
internal fun normalizeBaseUrl(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return url
    val lower = url.lowercase()
    val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) {
        url
    } else {
        // 取首段路径前的 host:port，再去掉端口；带方括号的 IPv6 字面量按本地处理。
        val host = url.substringBefore('/').substringBeforeLast(':').lowercase()
        val local = host == "localhost" || host.startsWith("[") || IPV4_HOST.matches(host)
        (if (local) "http://" else "https://") + url
    }
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}
