package space.nami.browser

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/** A video the page loaded (seen in its network requests or in a <video> tag). */
class MediaFound(val url: String, val hls: Boolean, val headers: Map<String, String>) {
    val label: String get() = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()?.take(60) ?: url.take(60)
    val host: String get() = runCatching { Uri.parse(url).host }.getOrNull() ?: ""
}

@OptIn(UnstableApi::class)
object VideoSniff {
    private val files = setOf("mp4", "m4v", "webm", "mov")

    /** 1 = HLS playlist, 2 = video file, 0 = something else. */
    fun kind(url: String): Int {
        if (!url.startsWith("http")) return 0
        val p = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase() ?: return 0
        val ext = p.substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> 1
            ext in files -> 2
            else -> 0
        }
    }

    /** Request headers worth sending again (not the ones the player/downloader sets itself). */
    fun keep(h: Map<String, String>?): Map<String, String> =
        h.orEmpty().filterKeys { k -> k.lowercase() !in setOf("range", "accept-encoding", "cookie", "host", "connection") }

    fun toJson(h: Map<String, String>): String = JSONObject(h).toString()
    fun fromJson(s: String?): Map<String, String> = runCatching {
        val o = JSONObject(s ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** JS that lists <video>/<source> addresses on the page (top frame). */
    const val SCAN_JS = """(function(){var o=[];document.querySelectorAll('video,video source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&/^https?:/.test(s))o.push(s);});return JSON.stringify(o);})()"""

    /** OkHttp that goes through Nami's network path and sends the browser's cookies for each address. */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .proxy(LocalProxy.javaProxy())
        .addInterceptor { ch ->
            val r = ch.request()
            val c = runCatching { CookieManager.getInstance().getCookie(r.url.toString()) }.getOrNull()
            ch.proceed(if (c.isNullOrEmpty() || r.header("Cookie") != null) r else r.newBuilder().header("Cookie", c).build())
        }
        .build()
}

/** Full-screen Android player (ExoPlayer) for a video found on a page. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val hls = intent.getBooleanExtra("hls", false)
        val headers = VideoSniff.fromJson(intent.getStringExtra("headers"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val ds = OkHttpDataSource.Factory(VideoSniff.client()).setDefaultRequestProperties(headers)
        headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.let { ds.setUserAgent(it.value) }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(ds)).build()
        player = p
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "재생할 수 없어요 (${error.errorCodeName})", Toast.LENGTH_LONG).show()
            }
            override fun onVideoSizeChanged(s: VideoSize) {
                if (s.width <= 0 || s.height <= 0) return
                requestedOrientation = if (s.width >= s.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        })
        p.prepare()
        p.playWhenReady = true

        val view = PlayerView(this).apply {
            player = p
            setBackgroundColor(Color.BLACK)
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        fun open(ctx: Context, m: MediaFound) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", m.url).putExtra("hls", m.hls)
                .putExtra("headers", VideoSniff.toJson(m.headers)))
        }
    }
}

/**
 * Saves an HLS video (m3u8): picks the best quality, fetches the pieces (4 at a time, in order, each retried),
 * decrypts plain AES-128 pieces, joins them and turns .ts into .mp4 (no re-encoding). DRM-protected and live
 * streams are refused.
 */
@OptIn(UnstableApi::class)
class HlsJob(private val ctx: Context, private val nm: NotificationManager) {
    private class Seg(val url: String, val key: String?, val iv: ByteArray?, val range: LongArray?)

    private fun get(url: String, headers: Map<String, String>, range: LongArray? = null): ByteArray {
        var last: Exception? = null
        repeat(5) { n ->
            try {
                val c = URL(url).openConnection(LocalProxy.javaProxy()) as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                headers.forEach { (k, v) -> runCatching { c.setRequestProperty(k, v) } }
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                c.setRequestProperty("Accept-Encoding", "identity")
                if (range != null) c.setRequestProperty("Range", "bytes=${range[1]}-${range[1] + range[0] - 1}")
                try {
                    val code = c.responseCode
                    if (code == 403 || code == 404 || code == 410) throw IOException("서버 응답 $code")
                    if (code !in 200..299) throw java.io.InterruptedIOException("서버 응답 $code")
                    return c.inputStream.use { it.readBytes() }
                } finally { c.disconnect() }
            } catch (e: IOException) {
                last = e
                if (e !is java.io.InterruptedIOException && e.message?.startsWith("서버 응답") == true) throw e
                Thread.sleep(800L * (n + 1))
            }
        }
        throw last ?: IOException("받기 실패")
    }

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    private fun resolve(base: String, rel: String): String = URL(URL(base), rel).toString()

    suspend fun run(id: Int, url: String, name0: String, headers: Map<String, String>, cancel: PendingIntent) {
        val got = AtomicLong(0)
        var name = name0
        Downloads.active[id] = DlActive(name, got, AtomicLong(-1))
        val tmp = File(ctx.cacheDir, "hls-$id").apply { mkdirs() }
        var done = 0; var total = 0; var phase = "받는 중"
        val ticker = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                nm.notify(id, NotificationCompat.Builder(ctx, "dl_progress").setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(name).setContentText("$phase · ${DownloadService.size(got.get())}" + if (total > 0) " · $done / $total 조각" else "")
                    .setProgress(100, if (total > 0) done * 100 / total else 0, total <= 0 || phase != "받는 중")
                    .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).addAction(0, "취소", cancel).build())
                delay(700)
            }
        }
        var uri: Uri? = null
        try {
            withContext(Dispatchers.IO) {
                // 1) playlist (master → best quality)
                var pl = url
                var txt = String(get(pl, headers))
                if (!txt.trimStart().startsWith("#EXTM3U")) throw IOException("HLS 재생 목록이 아니에요")
                if ("#EXT-X-STREAM-INF" in txt) {
                    val lines = txt.lines().map { it.trim() }
                    var best: Pair<Long, String>? = null; var bestAudio: String? = null
                    lines.forEachIndexed { i, l ->
                        if (l.startsWith("#EXT-X-STREAM-INF")) {
                            val a = attrs(l)
                            val u = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                            val bw = a["BANDWIDTH"]?.toLongOrNull() ?: 0
                            if (best == null || bw > best!!.first) { best = bw to u; bestAudio = a["AUDIO"] }
                        }
                    }
                    val b = best ?: throw IOException("화질 목록이 비어 있어요")
                    bestAudio?.let { g ->
                        val separate = lines.any { it.startsWith("#EXT-X-MEDIA") && attrs(it).let { a -> a["TYPE"] == "AUDIO" && a["GROUP-ID"] == g && a["URI"] != null } }
                        if (separate) throw IOException("영상과 소리가 따로 나뉜 방식이라 아직 못 받아요")
                    }
                    pl = resolve(pl, b.second)
                    txt = String(get(pl, headers))
                }
                if ("#EXT-X-ENDLIST" !in txt) throw IOException("실시간 방송은 받을 수 없어요")

                // 2) pieces
                var seq = 0L; var key: String? = null; var iv: ByteArray? = null; var map: Seg? = null
                var nextRange: LongArray? = null; var lastEnd = 0L
                val segs = ArrayList<Seg>()
                val lines = txt.lines().map { it.trim() }
                for (l in lines) when {
                    l.startsWith("#EXT-X-MEDIA-SEQUENCE") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                    l.startsWith("#EXT-X-KEY") -> {
                        val a = attrs(l)
                        when (a["METHOD"]) {
                            "NONE", null -> { key = null; iv = null }
                            "AES-128" -> {
                                if (a["KEYFORMAT"].let { it != null && it != "identity" }) throw IOException("보호된(DRM) 영상이라 받을 수 없어요")
                                key = resolve(pl, a["URI"] ?: throw IOException("키 주소가 없어요"))
                                iv = a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                            }
                            else -> throw IOException("보호된(DRM) 영상이라 받을 수 없어요")
                        }
                    }
                    l.startsWith("#EXT-X-MAP") -> {
                        val a = attrs(l)
                        val r = a["BYTERANGE"]?.let { br -> br.split('@').let { p -> longArrayOf(p[0].toLong(), p.getOrNull(1)?.toLong() ?: 0) } }
                        map = Seg(resolve(pl, a["URI"] ?: continue), null, null, r)
                    }
                    l.startsWith("#EXT-X-BYTERANGE") -> {
                        val p = l.substringAfter(':').split('@')
                        val len = p[0].trim().toLong()
                        val off = p.getOrNull(1)?.trim()?.toLong() ?: lastEnd
                        nextRange = longArrayOf(len, off); lastEnd = off + len
                    }
                    l.isNotEmpty() && !l.startsWith("#") -> {
                        val sIv = iv ?: if (key != null) ByteArray(16).also { b -> var s = seq; for (k in 15 downTo 8) { b[k] = (s and 0xff).toByte(); s = s shr 8 } } else null
                        segs.add(Seg(resolve(pl, l), key, sIv, nextRange))
                        nextRange = null; seq++
                    }
                }
                if (segs.isEmpty()) throw IOException("받을 조각이 없어요")
                total = segs.size
                val fmp4 = map != null || segs.first().url.substringBefore('?').let { it.endsWith(".m4s", true) || it.endsWith(".mp4", true) }

                val keys = HashMap<String, ByteArray>()
                fun piece(s: Seg): ByteArray {
                    val b = get(s.url, headers, s.range)
                    val k = s.key ?: return b
                    val kb = synchronized(keys) { keys[k] } ?: get(k, headers).also { synchronized(keys) { keys[k] = it } }
                    return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(kb, "AES"), IvParameterSpec(s.iv)); doFinal(b)
                    }
                }

                val raw = File(tmp, if (fmp4) "all.mp4" else "all.ts")
                FileOutputStream(raw).use { out ->
                    map?.let { out.write(get(it.url, headers, it.range)) }
                    coroutineScope {
                        val q = ArrayDeque<Deferred<ByteArray>>()
                        var next = 0
                        fun more() { val s = segs[next++]; q.addLast(async(Dispatchers.IO) { piece(s) }) }
                        while (next < segs.size && q.size < 4) more()
                        while (q.isNotEmpty()) {
                            val b = q.removeFirst().await()
                            out.write(b); got.addAndGet(b.size.toLong()); done++
                            if (next < segs.size) more()
                        }
                    }
                }

                // 3) .ts → .mp4 (copy, no re-encoding); keep .ts if that fails
                var final = raw
                if (!fmp4) {
                    phase = "mp4로 바꾸는 중"
                    val mp4 = File(tmp, "out.mp4")
                    if (remux(raw, mp4)) { final = mp4; raw.delete() } else name = name.substringBeforeLast('.') + ".ts"
                }

                // 4) into Downloads
                phase = "저장하는 중"
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".ts")) "video/mp2t" else "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("파일을 만들 수 없어요")
                uri = u
                (ctx.contentResolver.openOutputStream(u) ?: throw IOException("파일을 열 수 없어요")).use { o -> final.inputStream().use { it.copyTo(o, 1 shl 20) } }
            }
            ticker.cancel(); nm.cancel(id); Downloads.active.remove(id)
            BlobSaver.finish(ctx, uri!!, name, false, ByteArray(0))
        } catch (e: Throwable) {
            ticker.cancel(); nm.cancel(id); Downloads.active.remove(id)
            uri?.let { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
            if (e is kotlinx.coroutines.CancellationException) throw e
            nm.notify(id + 100000, NotificationCompat.Builder(ctx, "dl_done").setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("받기 실패: $name").setContentText(e.message ?: e.javaClass.simpleName).setAutoCancel(true).build())
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Media3 Transformer with no edits = copies the video/audio into an .mp4 (needs the main looper). */
    private suspend fun remux(input: File, output: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(false) }
            }).build()
            runCatching { t.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).build(), output.path) }
                .onFailure { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { runCatching { t.cancel() } }
        }
    }
}
