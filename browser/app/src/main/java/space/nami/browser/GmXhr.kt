package space.nami.browser

import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** GM_xmlhttpRequest done natively (cross-origin allowed, like Tampermonkey). Returns the response object as JSON. */
object GmXhr {
    fun run(o: JSONObject, ua: String): JSONObject = try {
        val url = o.getString("url")
        val c = URL(url).openConnection(LocalProxy.javaProxy()) as HttpURLConnection
        try {
            val method = o.optString("method", "GET").uppercase()
            runCatching { c.requestMethod = method }.onFailure {
                c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", method)
            }
            val timeout = o.optInt("timeout", 0)
            c.connectTimeout = if (timeout > 0) timeout else 20_000
            c.readTimeout = if (timeout > 0) timeout else 60_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            if (!o.optBoolean("anonymous")) CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> c.setRequestProperty(k, h.optString(k)) } }
            if (!o.isNull("data") && method != "GET" && method != "HEAD") {
                c.doOutput = true
                c.outputStream.use { it.write(o.getString("data").toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val ctype = c.contentType ?: ""
            val cs = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(ctype)?.groupValues?.get(1)
            val text = runCatching { String(bytes, java.nio.charset.Charset.forName(cs ?: "UTF-8")) }.getOrElse { String(bytes, Charsets.UTF_8) }
            val headers = StringBuilder()
            c.headerFields.forEach { (k, v) -> if (k != null) v.forEach { headers.append(k.lowercase()).append(": ").append(it).append("\r\n") } }
            // keep cookies the server set, like a browser would
            c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
            JSONObject().put("status", code).put("statusText", c.responseMessage ?: "").put("finalUrl", c.url.toString())
                .put("responseHeaders", headers.toString()).put("contentType", ctype)
                .put("responseText", if (o.optBoolean("binary")) "" else text)
                .apply { if (o.optBoolean("binary")) put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
        } finally { c.disconnect() }
    } catch (e: Throwable) {
        JSONObject().put("error", true).put("status", 0).put("statusText", e.message ?: e.javaClass.simpleName)
            .put("timeout", e is SocketTimeoutException).put("responseText", "")
    }
}
