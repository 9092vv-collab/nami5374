package space.nami.finder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Danbooru background: a random post matching the user's tags, swapped at most every N minutes.
 * No background job — when the search window opens we compare the clock with the last swap.
 * The next picture is always downloaded ahead (booru_next.jpg) so it shows instantly.
 */
object Booru {
    private const val UA = "NamiFinder/0.3 (personal Android app)"
    fun cur(c: Context) = File(c.filesDir, "booru_cur.jpg")
    private fun next(c: Context) = File(c.filesDir, "booru_next.jpg")
    @Volatile private var fetching = false

    fun tags(c: Context) = Look.prefs(c).getString("booruTags", "") ?: ""
    fun sensitive(c: Context) = Look.prefs(c).getBoolean("booruSensitive", false)
    fun minutes(c: Context) = Look.prefs(c).getInt("booruMinutes", 30).coerceAtLeast(1)
    /** Web page of the picture on screen (Danbooru post or pixiv artwork). */
    fun curUrl(c: Context) = Look.prefs(c).getString("netCurUrl", "") ?: ""
    fun hasNext(c: Context) = next(c).exists()

    /** Settings changed → the prepared picture no longer fits. */
    fun dropNext(c: Context) { next(c).delete() }

    /** Switching between Danbooru and pixiv: forget both pictures so the new site's one shows. */
    fun dropAll(c: Context) { next(c).delete(); cur(c).delete() }

    /** Show the prepared picture if it's time (or [force]). True when the shown picture changed. */
    @Synchronized
    fun rotate(c: Context, force: Boolean): Boolean {
        val n = next(c)
        if (!n.exists()) return false
        val p = Look.prefs(c)
        val now = System.currentTimeMillis()
        val due = now - p.getLong("booruLast", 0L) >= minutes(c) * 60_000L
        if (!force && cur(c).exists() && !due) return false
        if (!n.renameTo(cur(c))) return false
        p.edit().putLong("booruLast", now).putString("netCurUrl", p.getString("netNextUrl", ""))
            .putString("netCurPages", p.getString("netNextPages", "")).putInt("netCurPage", 0).apply()
        return true
    }

    /** Pages of the picture on screen (pixiv multi-page works); empty or one = single picture. */
    fun pages(c: Context): List<String> =
        (Look.prefs(c).getString("netCurPages", "") ?: "").split('\n').filter { it.isNotBlank() }
    fun page(c: Context) = Look.prefs(c).getInt("netCurPage", 0)

    /** Show page [i] of the current work in place of what's on screen. null = done, else a reason. Blocking. */
    @Synchronized
    fun showPage(c: Context, i: Int): String? {
        val list = pages(c)
        if (i !in list.indices) return null
        return try {
            val raw = File(c.cacheDir, "page_dl")
            raw.writeBytes(Pixiv.image(list[i]))
            val good = Look.writeScaled(ImageDecoder.createSource(raw), cur(c))
            raw.delete()
            if (!good) "그림을 못 풀었어요" else { Look.prefs(c).edit().putInt("netCurPage", i).apply(); null }
        } catch (e: Exception) {
            "못 받았어요 (${e.javaClass.simpleName})"
        }
    }

    /** Download the next picture. null = fine (or already busy), otherwise a short reason. Blocking. */
    fun fetchNext(c: Context, pixivCookie: String = ""): String? {
        if (fetching || next(c).exists()) return null
        fetching = true
        try {
            if (Look.source(c) == "pixiv") {
                val picked = Pixiv.pick(c, pixivCookie)
                val raw = File(c.cacheDir, "net_dl")
                raw.writeBytes(picked.bytes)
                val good = Look.writeScaled(ImageDecoder.createSource(raw), next(c))
                raw.delete()
                if (!good) return "그림을 못 풀었어요"
                Look.prefs(c).edit().putString("netNextUrl", picked.page)
                    .putString("netNextPages", picked.pages.joinToString("\n")).apply()
                return null
            }
            val allowed = if (sensitive(c)) setOf("g", "s") else setOf("g")
            val q = URLEncoder.encode(tags(c).trim(), "UTF-8")
            val arr = JSONArray(String(get("https://danbooru.donmai.us/posts.json?limit=100&random=true&tags=$q")))
            val ok = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                it.optString("rating") in allowed &&
                    it.optString("file_ext") in setOf("jpg", "jpeg", "png", "webp") &&
                    it.optString("file_url").isNotEmpty()
            }
            if (ok.isEmpty()) return "조건에 맞는 그림이 없어요"
            val post: JSONObject = ok.random()
            val size = post.optLong("file_size", 0L)
            val url = if (size in 1..15_000_000) post.getString("file_url")
                else post.optString("large_file_url").ifEmpty { post.getString("file_url") }
            val raw = File(c.cacheDir, "booru_dl")
            raw.writeBytes(get(url))
            val good = Look.writeScaled(ImageDecoder.createSource(raw), next(c))
            raw.delete()
            if (!good) return "그림을 못 풀었어요"
            Look.prefs(c).edit().putString("netNextUrl", "https://danbooru.donmai.us/posts/" + post.optLong("id"))
                .putString("netNextPages", "").apply()
            return null
        } catch (e: Pixiv.Msg) {
            return e.message
        } catch (e: Exception) {
            return "못 받았어요 (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(40) } ?: ""})"
        } finally {
            fetching = false
        }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }
}
