package space.nami.browser

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A tiny HTTP proxy inside Nami (127.0.0.1:<random port>). The WebView is pointed at it with ProxyController.
 * For each connection it:
 *  - sends registered sites (e.g. iwara.tv) through the user's SOCKS5 proxy (AdGuard VPN's SOCKS5 mode),
 *  - looks other hosts up with DNS-over-HTTPS (Cloudflare 1.1.1.1 / Google 8.8.8.8),
 *  - optionally splits the TLS ClientHello into two TLS records so ISP SNI filtering can't read the site name
 *    (the same idea as Unicorn HTTPS / GoodbyeDPI).
 * Only Nami's traffic goes through it; no VPN, other apps are untouched.
 */
object LocalProxy {
    @Volatile private var server: ServerSocket? = null
    @Volatile var port = 0
        private set
    @Volatile private var prefs: Prefs? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nami-proxy").apply { isDaemon = true } }
    private val dnsCache = ConcurrentHashMap<String, Pair<InetAddress, Long>>()

    val running get() = server != null

    @Synchronized
    fun start(p: Prefs): Int {
        prefs = p
        server?.let { return port }
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss; port = ss.localPort
        pool.execute {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(c) }.onFailure { runCatching { c.close() } } }
            }
        }
        return port
    }

    @Synchronized
    fun stop() { runCatching { server?.close() }; server = null; port = 0 }

    /** For Nami's own downloads / GM_xmlhttpRequest, so they follow the same routes. */
    fun javaProxy(): Proxy = if (running) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)) else Proxy.NO_PROXY

    fun clearDns() = dnsCache.clear()

    // ---------- routing ----------

    private fun matches(host: String, list: Set<String>): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in list) return true
            val i = h.indexOf('.'); if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    private fun viaSocks(host: String): Boolean {
        val p = prefs ?: return false
        return p.socksHost.isNotBlank() && p.socksPort > 0 && matches(host, p.proxySites)
    }

    private fun open(host: String, port: Int): Socket =
        if (viaSocks(host)) socksConnect(host, port)
        else Socket().apply { connect(InetSocketAddress(resolve(host), port), 12_000); tcpNoDelay = true }

    // ---------- DNS over HTTPS ----------

    private val ipLiteral = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun resolve(host: String): InetAddress {
        if (ipLiteral.matches(host) || host.contains(':')) return InetAddress.getByName(host)
        val mode = prefs?.dnsMode ?: DnsMode.OFF
        if (mode == DnsMode.OFF) return InetAddress.getByName(host)
        dnsCache[host]?.let { (a, exp) -> if (exp > System.currentTimeMillis()) return a }
        val got = runCatching { doh(host, mode, "A") ?: doh(host, mode, "AAAA") }.getOrNull()
        if (got != null) { dnsCache[host] = got; return got.first }
        return InetAddress.getByName(host)  // DoH failed → phone DNS
    }

    private fun doh(host: String, mode: DnsMode, type: String): Pair<InetAddress, Long>? {
        val q = URLEncoder.encode(host, "UTF-8")
        val url = when (mode) {
            DnsMode.GOOGLE -> "https://8.8.8.8/resolve?name=$q&type=$type"
            else -> "https://1.1.1.1/dns-query?name=$q&type=$type"
        }
        val c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.setRequestProperty("Accept", "application/dns-json")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val ans = o.optJSONArray("Answer") ?: return null
            val want = if (type == "A") 1 else 28
            for (i in 0 until ans.length()) {
                val a = ans.getJSONObject(i)
                if (a.optInt("type") == want) {
                    val ttl = a.optLong("TTL", 300).coerceIn(60, 3600)
                    return InetAddress.getByName(a.getString("data")) to (System.currentTimeMillis() + ttl * 1000)
                }
            }
            return null
        } finally { c.disconnect() }
    }

    // ---------- SOCKS5 client (RFC 1928 + user/pass RFC 1929) ----------

    fun socksConnect(host: String, port: Int): Socket {
        val p = prefs ?: throw IOException("설정 없음")
        val s = Socket()
        s.connect(InetSocketAddress(p.socksHost.trim(), p.socksPort), 10_000)
        s.tcpNoDelay = true; s.soTimeout = 15_000
        val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
        val auth = p.socksUser.isNotEmpty()
        o.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); o.flush()
        i.readUnsignedByte()
        when (i.readUnsignedByte()) {
            0 -> {}
            2 -> {
                val u = p.socksUser.toByteArray(); val pw = p.socksPass.toByteArray()
                o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw); o.flush()
                i.readUnsignedByte()
                if (i.readUnsignedByte() != 0) { s.close(); throw IOException("SOCKS 아이디/비밀번호가 틀렸어요") }
            }
            else -> { s.close(); throw IOException("SOCKS 인증 방식이 맞지 않아요") }
        }
        val h = host.toByteArray()
        o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
        i.readUnsignedByte(); val rep = i.readUnsignedByte(); i.readUnsignedByte()
        when (i.readUnsignedByte()) { 1 -> i.skipBytes(4); 3 -> i.skipBytes(i.readUnsignedByte()); 4 -> i.skipBytes(16) }
        i.skipBytes(2)
        if (rep != 0) { s.close(); throw IOException("SOCKS 서버가 연결을 거부했어요 ($rep)") }
        s.soTimeout = 0
        return s
    }

    /** Settings "연결 확인": asks ipinfo.io through the SOCKS proxy and returns the IP / country it sees. */
    fun testSocks(p: Prefs): String = runCatching {
        prefs = prefs ?: p
        val raw = socksConnect("ipinfo.io", 443)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "ipinfo.io", 443, true) as SSLSocket
        ssl.soTimeout = 10_000
        ssl.startHandshake()
        ssl.outputStream.write("GET /json HTTP/1.1\r\nHost: ipinfo.io\r\nUser-Agent: Nami\r\nAccept: application/json\r\nConnection: close\r\n\r\n".toByteArray())
        ssl.outputStream.flush()
        val text = ssl.inputStream.bufferedReader().use { it.readText() }
        ssl.close()
        val body = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
        val o = JSONObject(body)
        "연결됨 · 사이트가 보는 IP ${o.optString("ip")} (${o.optString("country")})"
    }.getOrElse { "연결 실패: ${it.message ?: it.javaClass.simpleName}" }

    // ---------- connection handling ----------

    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 65536) {
            val b = inp.read(); if (b < 0) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return sb.toString()
        }
        return null
    }

    private fun handle(c: Socket) {
        c.tcpNoDelay = true
        val inp = BufferedInputStream(c.getInputStream(), 32 * 1024)
        val out = c.getOutputStream()
        val head = readHead(inp) ?: run { c.close(); return }
        val lines = head.split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) { c.close(); return }
        val method = first[0]; val target = first[1]
        if (method.equals("CONNECT", true)) {
            val host = target.substringBeforeLast(':').trim('[', ']')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val up = try { open(host, port) } catch (e: Exception) {
                runCatching { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush() }
                c.close(); return
            }
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); out.flush()
            val p = prefs
            if (port == 443 && p != null && p.dpiBypass && !viaSocks(host) && !matches(host, p.bypassOff)) {
                runCatching { sendSplitHello(inp, up.getOutputStream(), host) }.onFailure { up.close(); c.close(); return }
            }
            pipe(c, inp, up)
        } else {
            // plain http:// request in absolute form
            val u = runCatching { URL(target) }.getOrNull() ?: run { c.close(); return }
            val port = if (u.port > 0) u.port else 80
            val up = try { open(u.host, port) } catch (e: Exception) {
                runCatching { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush() }
                c.close(); return
            }
            val sb = StringBuilder()
            sb.append(method).append(' ').append(u.file.ifEmpty { "/" }).append(' ').append(first.getOrElse(2) { "HTTP/1.1" }).append("\r\n")
            for (l in lines.drop(1)) {
                if (l.isEmpty()) continue
                val k = l.substringBefore(':').trim().lowercase()
                if (k == "proxy-connection" || k == "connection" || k == "keep-alive" || k == "proxy-authorization") continue
                sb.append(l).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            up.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1)); up.getOutputStream().flush()
            pipe(c, inp, up)
        }
    }

    /** Reads the first TLS record (ClientHello) and sends it as two records, split inside the site name. */
    private fun sendSplitHello(inp: InputStream, up: OutputStream, host: String) {
        val buf = ByteArray(17 * 1024)
        var n = inp.read(buf)
        if (n <= 0) return
        if (n < 5 || buf[0] != 0x16.toByte()) { up.write(buf, 0, n); up.flush(); return }
        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
        while (n < 5 + recLen && n < buf.size) { val r = inp.read(buf, n, minOf(buf.size - n, 5 + recLen - n)); if (r < 0) break; n += r }
        if (n < 5 + recLen) { up.write(buf, 0, n); up.flush(); return }
        val payload = buf.copyOfRange(5, 5 + recLen)
        val hb = host.toByteArray()
        var at = -1
        outer@ for (i in 0..payload.size - hb.size) { for (j in hb.indices) if (payload[i + j] != hb[j]) continue@outer; at = i; break }
        val cut = if (at >= 0) at + maxOf(1, hb.size / 2) else maxOf(1, payload.size / 2)
        fun rec(part: ByteArray) = byteArrayOf(0x16, buf[1], buf[2], (part.size shr 8).toByte(), part.size.toByte()) + part
        up.write(rec(payload.copyOfRange(0, cut))); up.flush()
        Thread.sleep(3)
        up.write(rec(payload.copyOfRange(cut, payload.size)))
        if (n > 5 + recLen) up.write(buf, 5 + recLen, n - 5 - recLen)
        up.flush()
    }

    private fun pipe(c: Socket, cin: InputStream, up: Socket) {
        val uin = up.getInputStream(); val uout = up.getOutputStream(); val cout = c.getOutputStream()
        pool.execute {
            copy(uin, cout)
            runCatching { c.shutdownOutput() }
            runCatching { c.close() }; runCatching { up.close() }
        }
        copy(cin, uout)
        runCatching { up.shutdownOutput() }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val b = ByteArray(32 * 1024)
        try {
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); o.flush() }
        } catch (_: IOException) {}
    }
}
