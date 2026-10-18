package io.github.colino17.jellyshim

import java.net.URI

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/** Turns "192.168.1.5:8096/" into "http://192.168.1.5:8096". */
fun normalizeUrl(raw: String): String {
    var u = raw.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    return u.trimEnd('/')
}

/**
 * True for addresses that normally stay on a local network or a private VPN: loopback, RFC 1918,
 * link-local, carrier-grade NAT (Tailscale and similar), "localhost", single-word host names and
 * common LAN suffixes. Public host names and addresses are false.
 */
internal fun isPrivateHost(host: String?): Boolean {
    if (host == null) return true // can't tell; the login itself will fail on a broken URL
    val h = host.lowercase()
    if (h.startsWith("[")) { // IPv6 literal
        return h.startsWith("[::1]") || h.startsWith("[fe80") || h.startsWith("[fc") || h.startsWith("[fd")
    }
    if (h == "localhost" || !h.contains('.') ||
        listOf(".local", ".lan", ".home", ".internal").any { h.endsWith(it) }
    ) return true
    val m = IPV4.matchEntire(h) ?: return false
    val a = m.groupValues[1].toInt()
    val b = m.groupValues[2].toInt()
    return a == 10 || a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

/** True for plain-http URLs that point somewhere other than a local network. */
internal fun isInsecurePublic(url: String): Boolean {
    if (!url.startsWith("http://")) return false
    val host = try { URI(url).host } catch (e: Exception) { null }
    return !isPrivateHost(host)
}
