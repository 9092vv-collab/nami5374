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
    /** Second picture in line, so several "다음 그림" in a row stay instant. */
    private fun next2(c: Context) = File(c.filesDir, "booru_next2.jpg")
    /** Pages of the current multi-page work, already scaled (index.jpg). */
    private fun pageDir(c: Context) = File(c.cacheDir, "pages")
    private fun pageFile(c: Context, i: Int) = File(pageDir(c), "$i.jpg")
    @Volatile private var fetching = false

    fun tags(c: Context) = Look.prefs(c).getString("booruTags", "") ?: ""
    fun sensitive(c: Context) = Look.prefs(c).getBoolean("booruSensitive", false)
    fun minutes(c: Context) = Look.prefs(c).getInt("booruMinutes", 30).coerceAtLeast(1)
    /** Web page of the picture on screen (Danbooru post or pixiv artwork). */
    fun curUrl(c: Context) = Look.prefs(c).getString("netCurUrl", "") ?: ""
    fun hasNext(c: Context) = next(c).exists()
    /** Both slots filled. */
    fun queueFull(c: Context) = next(c).exists() && next2(c).exists()

    /** Settings changed → the prepared pictures no longer fit. */
    fun dropNext(c: Context) { next(c).delete(); next2(c).delete() }

    /** Switching between Danbooru and pixiv: forget all pictures so the new site's one shows. */
    fun dropAll(c: Context) { dropNext(c); cur(c).delete(); pageDir(c).deleteRecursively() }

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
        val e = p.edit().putLong("booruLast", now).putString("netCurUrl", p.getString("netNextUrl", ""))
            .putString("netCurPages", p.getString("netNextPages", "")).putInt("netCurPage", 0)
            .putString("netNextUrl", "").putString("netNextPages", "")
        // move the second one up
        if (next2(c).exists() && next2(c).renameTo(n))
            e.putString("netNextUrl", p.getString("netNext2Url", "")).putString("netNextPages", p.getString("netNext2Pages", ""))
        e.apply()
        // new work: forget the old pages, keep page 1 (what's on screen) so going back is instant
        pageDir(c).deleteRecursively()
        if (pages(c).size > 1) runCatching { pageDir(c).mkdirs(); cur(c).copyTo(pageFile(c, 0), true) }
        return true
    }

    /** Pages of the picture on screen (pixiv multi-page works); empty or one = single picture. */
    fun pages(c: Context): List<String> =
        (Look.prefs(c).getString("netCurPages", "") ?: "").split('\n').filter { it.isNotBlank() }
    fun page(c: Context) = Look.prefs(c).getInt("netCurPage", 0)

    /** Show page [i] of the current work in place of what's on screen. null = done, else a reason. Blocking. */
    fun showPage(c: Context, i: Int): String? {
        val list = pages(c)
        if (i !in list.indices) return null
        val err = cachePage(c, list, i)
        if (err != null) return err
        synchronized(this) {
            if (pages(c) != list) return null   // the work changed meanwhile
            if (runCatching { pageFile(c, i).copyTo(cur(c), true) }.isFailure) return "그림을 못 풀었어요"
            Look.prefs(c).edit().putInt("netCurPage", i).apply()
        }
        return null
    }

    /** Get the page after [i] ready in the background, so the next swipe is instant. Blocking. */
    fun prefetchPage(c: Context, i: Int) {
        val list = pages(c)
        if (i + 1 in list.indices) cachePage(c, list, i + 1)
    }

    private val pageLocks = HashMap<String, Any>()
    /** Download + scale page [i] into the page cache unless it's there. null = fine. */
    private fun cachePage(c: Context, list: List<String>, i: Int): String? {
        val lock = synchronized(pageLocks) { pageLocks.getOrPut(list[i]) { Any() } }
        synchronized(lock) {
            val f = pageFile(c, i)
            if (f.exists()) return null
            return try {
                pageDir(c).mkdirs()
                val raw = File(c.cacheDir, "page_dl_$i")
                raw.writeBytes(Pixiv.image(list[i]))
                val good = Look.writeScaled(ImageDecoder.createSource(raw), f)
                raw.delete()
                if (good) null else "그림을 못 풀었어요"
            } catch (e: Exception) {
                "못 받았어요 (${e.javaClass.simpleName})"
            }
        }
    }

    /** Download the next picture. null = fine (or already busy), otherwise a short reason. Blocking. */
    fun fetchNext(c: Context, pixivCookie: String = ""): String? {
        if (fetching || queueFull(c)) return null
        fetching = true
        // fill the first empty slot
        val slot = if (next(c).exists()) next2(c) else next(c)
        val urlKey = if (slot == next(c)) "netNextUrl" else "netNext2Url"
        val pagesKey = if (slot == next(c)) "netNextPages" else "netNext2Pages"
        try {
            if (Look.source(c) == "pixiv") {
                val picked = Pixiv.pick(c, pixivCookie)
                val raw = File(c.cacheDir, "net_dl")
                raw.writeBytes(picked.bytes)
                Look.prefs(c).edit().putString(urlKey, picked.page)
                    .putString(pagesKey, picked.pages.joinToString("\n")).commit()
                val good = Look.writeScaled(ImageDecoder.createSource(raw), slot)
                raw.delete()
                if (!good) return "그림을 못 풀었어요"
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
            Look.prefs(c).edit().putString(urlKey, "https://danbooru.donmai.us/posts/" + post.optLong("id"))
                .putString(pagesKey, "").commit()
            val good = Look.writeScaled(ImageDecoder.createSource(raw), slot)
            raw.delete()
            if (!good) return "그림을 못 풀었어요"
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
