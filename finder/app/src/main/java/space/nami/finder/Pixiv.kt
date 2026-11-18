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

    fun mode(c: Context) = Look.prefs(c).getString("pixivMode", "ranking") ?: "ranking"      // ranking | tag | bookmark
    fun period(c: Context) = Look.prefs(c).getString("pixivPeriod", "daily") ?: "daily"     // daily | weekly | monthly
    fun r18(c: Context) = Look.prefs(c).getBoolean("pixivR18", false)
    fun top(c: Context) = Look.prefs(c).getInt("pixivTop", 100).coerceIn(1, 500)
    fun tags(c: Context) = Look.prefs(c).getString("pixivTags", "") ?: ""
    /** true = the light version (regular, long side 1200px) instead of the original file. */
    fun light(c: Context) = Look.prefs(c).getBoolean("pixivLight", false)

    /** The WebView's own pixiv cookies (login page). Main thread only. */
    fun webCookie(): String = runCatching { CookieManager.getInstance().getCookie("https://www.pixiv.net") }.getOrNull() ?: ""
    fun loggedIn(cookie: String) = Regex("PHPSESSID=\\d+_").containsMatchIn(cookie)
    fun uidOf(cookie: String) = Regex("PHPSESSID=(\\d+)_").find(cookie)?.groupValues?.get(1)

    /** Picks a work and downloads its image bytes. Returns (bytes, page url) or throws with a Korean message. */
    class Picked(val bytes: ByteArray, val page: String, val pages: List<String>)

    /** Picks a work and downloads its first image. [Picked.pages] = every page's image url (multi-page works). */
    fun pick(c: Context, cookie: String): Picked {
        var lastErr: Msg? = null
        repeat(if (mode(c) == "bookmark") 6 else 1) {
            val id = when (mode(c)) { "tag" -> fromTag(c, cookie); "bookmark" -> fromBookmarks(c, cookie); else -> fromRanking(c, cookie) }
            val info = json("https://www.pixiv.net/ajax/illust/$id", cookie)
            if (info.optBoolean("error")) { lastErr = Msg("작품 정보를 못 받았어요"); return@repeat }
            val body = info.getJSONObject("body")
            // bookmarks hold both ages; respect the 전체 연령 / R-18 choice
            if (mode(c) == "bookmark" && !r18(c) && body.optInt("xRestrict") > 0) { lastErr = Msg("전체 연령 북마크를 못 찾았어요"); return@repeat }
            val urls = body.getJSONObject("urls")
            val lite = light(c)
            fun pickUrl(u: JSONObject) = if (lite) u.optString("regular").ifEmpty { u.getString("original") }
                else u.optString("original").ifEmpty { u.getString("regular") }
            val first = pickUrl(urls)
            var pages = listOf(first)
            if (body.optInt("pageCount", 1) > 1) runCatching {
                val arr = json("https://www.pixiv.net/ajax/illust/$id/pages", cookie).getJSONArray("body")
                pages = (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("urls").let { u -> pickUrl(u) } }
            }
            val bytes = runCatching { image(first) }.getOrElse { image(urls.getString("regular")) }
            return Picked(bytes, "https://www.pixiv.net/artworks/$id", pages)
        }
        throw lastErr ?: Msg("그림을 못 골랐어요")
    }

    /** Image files on i.pximg.net only need the Referer. */
    fun image(url: String, cookie: String = ""): ByteArray = get(url, cookie)

    /** Random work from the logged-in user's bookmarks (public + private). */
    private fun fromBookmarks(c: Context, cookie: String): String {
        val uid = Regex("PHPSESSID=(\\d+)_").find(cookie)?.groupValues?.get(1) ?: throw Msg("북마크는 픽시브 로그인이 필요해요")
        fun page(rest: String, offset: Int, limit: Int) =
            json("https://www.pixiv.net/ajax/user/$uid/illusts/bookmarks?tag=&offset=$offset&limit=$limit&rest=$rest", cookie).getJSONObject("body")
        val pub = page("show", 0, 1).optInt("total")
        val priv = runCatching { page("hide", 0, 1).optInt("total") }.getOrDefault(0)
        if (pub + priv == 0) throw Msg("북마크가 없어요")
        val r = (0 until pub + priv).random()
        val works = if (r < pub) page("show", r, 1) else page("hide", r - pub, 1)
        val arr = works.getJSONArray("works")
        if (arr.length() == 0) throw Msg("북마크를 못 읽었어요")
        val w = arr.getJSONObject(0)
        val id = w.opt("id")?.toString().orEmpty()
        if (id.isEmpty() || id == "null" || w.optInt("illustType") == 2) throw Msg("지워졌거나 움직이는 작품이에요")
        return id
    }

    private fun fromRanking(c: Context, cookie: String): String {
        val r18 = r18(c)
        if (r18 && !loggedIn(cookie)) throw Msg("R-18 랭킹은 픽시브 로그인이 필요해요")
        val p = period(c).let { if (r18 && it == "monthly") "weekly" else it }   // there's no monthly R-18 ranking
        val mode = if (r18) "${p}_r18" else p
        val rank = (1..top(c)).random()
        val page = (rank - 1) / 50 + 1
        val ids = rankingIds(mode, page, cookie)
        if (ids.isEmpty()) throw Msg(if (r18) "R-18 랭킹을 못 읽었어요 (픽시브 설정에서 R-18 보기를 켰는지 확인)" else "랭킹을 못 읽었어요")
        return ids.getOrNull((rank - 1) % 50) ?: ids.random()
    }

    /** Ids of one ranking page, in rank order. Tries the JSON form, the page's own HTML, then the mobile site's API. */
    private fun rankingIds(mode: String, page: Int, cookie: String): List<String> {
        val raw = runCatching {
            String(get("https://www.pixiv.net/ranking.php?mode=$mode&content=illust&format=json&p=$page", cookie))
        }.getOrDefault("")
        if (raw.trimStart().startsWith("{")) {
            runCatching {
                val list = JSONObject(raw).getJSONArray("contents")
                val ids = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optString("illust_type") != "2" }
                    .map { it.get("illust_id").toString() }
                if (ids.isNotEmpty()) return ids
            }
        }
        // HTML ranking page: items carry data-id / artworks links
        val html = raw.ifEmpty {
            runCatching { String(get("https://www.pixiv.net/ranking.php?mode=$mode&content=illust&p=$page", cookie)) }.getOrDefault("")
        }
        val fromHtml = Regex("""data-id="(\d+)"|/artworks/(\d+)|"illustId":"?(\d+)""").findAll(html)
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.distinct().toList()
        if (fromHtml.size >= 10) return fromHtml
        // mobile site API
        return runCatching {
            val o = json("https://www.pixiv.net/touch/ajax/ranking/illust?mode=$mode&type=illust&page=$page", cookie)
            val arr = o.getJSONObject("body").getJSONArray("ranking")
            (0 until arr.length()).map { arr.getJSONObject(it).get("illustId").toString() }
        }.getOrDefault(emptyList())
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

    private fun json(url: String, cookie: String): JSONObject {
        val t = String(get(url, cookie))
        if (!t.trimStart().startsWith("{")) {
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1)?.trim()?.take(40)
            throw Msg("픽시브가 데이터 대신 웹페이지를 줬어요" + (title?.let { " ($it)" } ?: ""))
        }
        return JSONObject(t)
    }

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

/**
 * Several pixiv accounts: each login's cookie is kept in this app's private prefs (never leaves the phone),
 * one is active. Requests use the active account's cookie, so switching is instant.
 */
object Accounts {
    class Acc(val uid: String, val name: String, val cookie: String)

    private fun p(c: Context) = Look.prefs(c)

    fun list(c: Context): List<Acc> = runCatching {
        val a = JSONArray(p(c).getString("pixivAccounts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Acc(it.getString("uid"), it.optString("name"), it.getString("cookie")) }
    }.getOrDefault(emptyList())

    private fun save(c: Context, l: List<Acc>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("uid", it.uid).put("name", it.name).put("cookie", it.cookie)) }
        p(c).edit().putString("pixivAccounts", a.toString()).apply()
    }

    fun activeUid(c: Context): String? = p(c).getString("pixivActive", null)?.takeIf { u -> list(c).any { it.uid == u } }
        ?: list(c).firstOrNull()?.uid
    fun active(c: Context): Acc? = activeUid(c)?.let { u -> list(c).firstOrNull { it.uid == u } }
    fun cookie(c: Context): String = active(c)?.cookie ?: ""
    fun setActive(c: Context, uid: String) = p(c).edit().putString("pixivActive", uid).apply()

    /** New login (or the same account again with a fresh cookie) → becomes active. */
    fun add(c: Context, cookie: String) {
        val uid = Pixiv.uidOf(cookie) ?: return
        val old = list(c).firstOrNull { it.uid == uid }
        save(c, list(c).filter { it.uid != uid } + Acc(uid, old?.name ?: "", cookie))
        setActive(c, uid)
    }

    fun setName(c: Context, uid: String, name: String) =
        save(c, list(c).map { if (it.uid == uid) Acc(it.uid, name, it.cookie) else it })

    fun remove(c: Context, uid: String) {
        save(c, list(c).filter { it.uid != uid })
        if (p(c).getString("pixivActive", null) == uid) p(c).edit().remove("pixivActive").apply()
    }

    /** Account name for the chip. Blocking. */
    fun fetchName(c: Context, a: Acc) = runCatching {
        val o = JSONObject(String(Pixiv.image("https://www.pixiv.net/ajax/user/${a.uid}?full=0", a.cookie)))
        val n = o.getJSONObject("body").optString("name")
        if (n.isNotEmpty()) setName(c, a.uid, n)
    }

    /** Before accounts existed the login lived only in the WebView's cookies. */
    fun migrate(c: Context) {
        if (p(c).contains("pixivAccounts")) return
        val ck = Pixiv.webCookie()
        if (Pixiv.loggedIn(ck)) add(c, ck) else save(c, emptyList())
    }
}

/** pixiv login page in a WebView (starts logged out, so another account can sign in); closes itself once logged in. */
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
                val ck = Pixiv.webCookie()
                if (url.startsWith("https://www.pixiv.net") && Pixiv.loggedIn(ck)) {
                    Accounts.add(this@PixivLogin, ck)
                    finish()
                }
            }
        }
        setContentView(web)
        // forget the WebView session so the login page shows even when another account is saved
        CookieManager.getInstance().removeAllCookies {
            web.loadUrl("https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F&source=touch")
        }
    }

    @Deprecated("back")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
