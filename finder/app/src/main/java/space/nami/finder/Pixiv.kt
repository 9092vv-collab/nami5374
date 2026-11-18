package space.nami.finder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * pixiv background: a random work from the ranking (top N) or a tag search, like a normal visitor would see it.
 * One request chain per picture, at most every N minutes — no bulk downloading.
 * Login (needed for R-18) happens in [PixivLogin]; the session cookie stays in this app's WebView cookie store.
 */
object Pixiv {
    const val UA = "Mozilla/5.0 (Linux; Android 16; SM-F9X0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    fun mode(c: Context) = Look.prefs(c).getString("pixivMode", "ranking") ?: "ranking"      // ranking | tag
    fun period(c: Context) = Look.prefs(c).getString("pixivPeriod", "daily") ?: "daily"     // daily | weekly | monthly
    fun r18(c: Context) = Look.prefs(c).getBoolean("pixivR18", false)
    fun top(c: Context) = Look.prefs(c).getInt("pixivTop", 100).coerceIn(1, 500)
    fun tags(c: Context) = Look.prefs(c).getString("pixivTags", "") ?: ""

    /** Main thread only. */
    fun cookie(): String = runCatching { CookieManager.getInstance().getCookie("https://www.pixiv.net") }.getOrNull() ?: ""
    fun loggedIn(cookie: String = cookie()) = Regex("PHPSESSID=\\d+_").containsMatchIn(cookie)
    fun logout() = runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }

    /** Picks a work and downloads its image bytes. Returns (bytes, page url) or throws with a Korean message. */
    fun pick(c: Context, cookie: String): Pair<ByteArray, String> {
        val id = if (mode(c) == "tag") fromTag(c, cookie) else fromRanking(c, cookie)
        val info = json("https://www.pixiv.net/ajax/illust/$id", cookie)
        if (info.optBoolean("error")) throw Msg("작품 정보를 못 받았어요")
        val urls = info.getJSONObject("body").getJSONObject("urls")
        val bytes = runCatching { get(urls.getString("original"), cookie) }
            .getOrElse { get(urls.getString("regular"), cookie) }
        return bytes to "https://www.pixiv.net/artworks/$id"
    }

    private fun fromRanking(c: Context, cookie: String): String {
        val r18 = r18(c)
        if (r18 && !loggedIn(cookie)) throw Msg("R-18 랭킹은 픽시브 로그인이 필요해요")
        val p = period(c).let { if (r18 && it == "monthly") "weekly" else it }   // there's no monthly R-18 ranking
        val mode = if (r18) "${p}_r18" else p
        val rank = (1..top(c)).random()
        val page = (rank - 1) / 50 + 1
        val o = json("https://www.pixiv.net/ranking.php?mode=$mode&content=illust&format=json&p=$page", cookie)
        val list = o.optJSONArray("contents") ?: throw Msg(if (r18) "R-18 랭킹을 못 봤어요 (픽시브 설정에서 R-18 보기를 켰는지 확인)" else "랭킹을 못 받았어요")
        val items = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("illust_type") != "2" }
        if (items.isEmpty()) throw Msg("랭킹이 비어 있어요")
        val want = items.firstOrNull { it.optInt("rank") == rank } ?: items.random()
        return want.get("illust_id").toString()
    }

    private fun fromTag(c: Context, cookie: String): String {
        val word = tags(c).trim()
        if (word.isEmpty()) throw Msg("태그를 적어 주세요")
        val r18 = r18(c)
        if (r18 && !loggedIn(cookie)) throw Msg("R-18 검색은 픽시브 로그인이 필요해요")
        val enc = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val page = (1..3).random()
        val o = json("https://www.pixiv.net/ajax/search/illustrations/$enc?word=$enc&order=date_d&mode=${if (r18) "r18" else "safe"}&p=$page&s_mode=s_tag&type=illust", cookie)
        if (o.optBoolean("error")) throw Msg("검색을 못 했어요")
        val data: JSONArray = o.getJSONObject("body").getJSONObject("illust").getJSONArray("data")
        val items = (0 until data.length()).map { data.getJSONObject(it) }
            .filter { it.has("id") && it.optInt("illustType") != 2 }
        if (items.isEmpty()) throw Msg("조건에 맞는 그림이 없어요")
        return items.random().get("id").toString()
    }

    private fun json(url: String, cookie: String) = JSONObject(String(get(url, cookie)))

    private fun get(url: String, cookie: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://www.pixiv.net/")
        if (cookie.isNotEmpty() && url.startsWith("https://www.pixiv.net")) conn.setRequestProperty("Cookie", cookie)
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    class Msg(m: String) : Exception(m)
}

/** pixiv login page in a WebView; closes itself once logged in. */
class PixivLogin : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("https://www.pixiv.net") && Pixiv.loggedIn()) {
                    CookieManager.getInstance().flush()
                    finish()
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F&source=touch")
    }

    @Deprecated("back")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
