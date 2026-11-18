package space.nami.tweaks

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** English -> Korean for the app descriptions (Google's free endpoint, same as Nami). Cached per package. */
object Ko {
    private fun cache(c: Context) = c.getSharedPreferences("ko", Context.MODE_PRIVATE)

    /** Hand-written Korean descriptions (assets/ko.json, pkg -> text). They win over machine translation. */
    @Volatile private var curated: Map<String, String>? = null

    private fun curated(c: Context): Map<String, String> = curated ?: synchronized(this) {
        curated ?: runCatching {
            val o = org.json.JSONObject(c.assets.open("ko.json").bufferedReader().readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap()).also { curated = it }
    }

    fun cached(c: Context, key: String): String? = curated(c)[key] ?: cache(c).getString(key, null)

    /** null when it couldn't be translated (offline etc.). */
    suspend fun translate(c: Context, key: String, text: String): String? {
        cached(c, key)?.let { return it }
        val out = withContext(Dispatchers.IO) { runCatching { google(text) }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: return null
        cache(c).edit().putString(key, out).apply()
        return out
    }

    private fun google(text: String): String {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ko&dt=t")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36")
            c.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val parts = JSONArray(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) sb.append(p.optString(0))
            }
            return sb.toString()
        } finally { c.disconnect() }
    }
}
