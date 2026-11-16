package space.nami.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Split (parallel) downloader, like Chrome's "parallel downloading".
 * - Probes with `Range: bytes=0-0`; if the server answers 206 with a total size, the file is split into N ranges
 *   fetched at the same time, each written straight into its place in the file (FileChannel positional writes).
 * - Each part resumes from where it stopped after a network error (5 tries). If splitting fails, it starts over as one stream.
 * - Runs as a foreground service with a progress notification so long downloads aren't killed.
 * - Saves into Downloads through MediaStore (IS_PENDING until finished).
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "다운로드 진행", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "다운로드 완료", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fg = NotificationCompat.Builder(this, CH_PROGRESS).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("다운로드 준비 중").setOngoing(true).setSilent(true).build()
        ServiceCompat.startForeground(this, FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getIntExtra("id", 0)
                jobs.remove(id)?.cancel()
                nm.cancel(id)
                stopIfIdle()
            }
            ACTION_START -> {
                val id = nextId.incrementAndGet()
                val req = Req(
                    url = intent.getStringExtra("url") ?: return START_NOT_STICKY,
                    name = intent.getStringExtra("name") ?: "download",
                    mime = intent.getStringExtra("mime"),
                    cookie = intent.getStringExtra("cookie"),
                    ua = intent.getStringExtra("ua"),
                    referer = intent.getStringExtra("referer"),
                    parts = intent.getIntExtra("parts", 4).coerceIn(1, 8),
                    guessed = intent.getBooleanExtra("guessed", false),
                    dual = intent.getBooleanExtra("dual", false),
                )
                val job = scope.launch { runJob(id, req) }
                jobs[id] = job
                job.invokeOnCompletion { jobs.remove(id); stopIfIdle() }
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private class Req(val url: String, var name: String, var mime: String?, val cookie: String?, val ua: String?,
                      val referer: String?, val parts: Int, var guessed: Boolean, val dual: Boolean = false)

    /** net = a specific network (mobile data in "와이파이 + 데이터" mode); null = normal path. */
    private fun open(req: Req, range: String?, net: Network? = null): HttpURLConnection {
        val c = (if (net != null) net.openConnection(URL(req.url)) else URL(req.url).openConnection(LocalProxy.javaProxy())) as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        req.cookie?.let { c.setRequestProperty("Cookie", it) }
        req.ua?.let { c.setRequestProperty("User-Agent", it) }
        req.referer?.let { c.setRequestProperty("Referer", it) }
        c.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private suspend fun runJob(id: Int, req: Req) {
        var uri: Uri? = null
        val cancel = PendingIntent.getService(this, id, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val got = AtomicLong(0)
        var total = -1L
        val totalRef = AtomicLong(-1)
        Downloads.active[id] = DlActive(req.name, got, totalRef)
        val started = System.currentTimeMillis()
        var cellNet: Network? = null
        var cellCb: ConnectivityManager.NetworkCallback? = null
        val ticker = scope.launch {
            var lastBytes = 0L; var lastT = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis(); val b = got.get()
                val speed = if (now > lastT) (b - lastBytes) * 1000 / (now - lastT) else 0
                lastBytes = b; lastT = now
                val pct = if (total > 0) (b * 100 / total).toInt() else 0
                val text = (if (total > 0) "${size(b)} / ${size(total)}" else size(b)) + " · ${size(speed)}/s" +
                    (if (cellNet != null) " · 와이파이 + 데이터" else "")
                nm.notify(id, NotificationCompat.Builder(this@DownloadService, CH_PROGRESS)
                    .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(req.name).setContentText(text)
                    .setProgress(100, pct, total <= 0).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                    .addAction(0, "취소", cancel).build())
                delay(700)
            }
        }
        try {
            // 1) probe
            var ranged = false
            val c = open(req, "bytes=0-0")
            try {
                val code = c.responseCode
                val cr = c.getHeaderField("Content-Range")
                // the real response often names the file even when the page didn't
                FileName.fromDisposition(c.getHeaderField("Content-Disposition"))?.let { n ->
                    if (req.guessed) { req.name = FileName.withExt(n, c.contentType); req.guessed = !FileName.hasRealExt(req.name) }
                }
                val ct = c.contentType?.substringBefore(';')?.trim()
                if (req.mime.isNullOrBlank() || req.mime == "application/octet-stream") if (!ct.isNullOrBlank() && ct != "application/octet-stream") req.mime = ct
                if (req.guessed) req.name = FileName.withExt(req.name, req.mime)
                if (code == 206 && cr != null) {
                    total = cr.substringAfter('/').trim().toLongOrNull() ?: -1L
                    ranged = total > 0
                } else if (code in 200..299) {
                    total = c.contentLengthLong
                } else throw IOException("서버 응답 $code")
            } finally { c.disconnect() }
            totalRef.set(total)
            // 2) space
            if (total > 0) {
                val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                if (free < total + 50L * 1024 * 1024) throw IOException("저장 공간이 부족해요 (필요 ${size(total)}, 남음 ${size(free)})")
            }
            // 3) target file in Downloads
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, req.name)
                (req.mime?.takeIf { it != "application/octet-stream" } ?: FileName.mimeFor(req.name))?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("파일을 만들 수 없어요")
            val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("파일을 열 수 없어요")
            pfd.use {
                FileOutputStream(it.fileDescriptor).channel.use { ch ->
                    val split = ranged && req.parts > 1 && total >= 2L * 1024 * 1024
                    // "와이파이 + 데이터": only while on Wi-Fi and for files of 20MB+; half of the parts go over mobile data
                    if (split && req.dual && total >= 20L * 1024 * 1024 && onWifi()) {
                        mobileData()?.let { (n, cb) -> cellNet = n; cellCb = cb }
                    }
                    val ok = if (split) runCatching { parallel(req, ch, total, req.parts, got, cellNet) }.isSuccess else false
                    if (!ok) {
                        if (split) { got.set(0); ch.truncate(0) }
                        single(req, ch, ranged, total, got)
                    }
                }
            }
            val head = ByteArray(32)
            runCatching { contentResolver.openInputStream(uri)?.use { it.read(head) } }
            ticker.cancel()
            nm.cancel(id)
            Downloads.active.remove(id)
            cellCb?.let { c -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(c) } }
            BlobSaver.finish(this, uri, req.name, req.guessed, head)
        } catch (e: Throwable) {
            Downloads.active.remove(id)
            cellCb?.let { c -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(c) } }
            ticker.cancel()
            nm.cancel(id)
            uri?.let { u -> runCatching { contentResolver.delete(u, null, null) } }
            if (e is kotlinx.coroutines.CancellationException) return
            nm.notify(id + 100000, NotificationCompat.Builder(this, CH_DONE).setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("받기 실패: ${req.name}").setContentText(e.message ?: e.javaClass.simpleName).setAutoCancel(true).build())
        }
    }

    /** N ranges at once, each resuming on its own after errors. */
    private suspend fun parallel(req: Req, ch: FileChannel, total: Long, parts: Int, got: AtomicLong, cell: Network? = null) = coroutineScope {
        val size = (total + parts - 1) / parts
        (0 until parts).map { i ->
            val start = i * size
            val end = minOf(total, start + size) - 1
            async { part(req, ch, start, end, got, if (cell != null && i % 2 == 1) cell else null) }
        }.awaitAll()
    }

    private suspend fun part(req: Req, ch: FileChannel, start: Long, end: Long, got: AtomicLong, cell: Network? = null) {
        var pos = start
        var tries = 0
        var net = cell
        while (pos <= end) {
            coroutineContext.ensureActive()
            try {
                val c = open(req, "bytes=$pos-$end", net)
                try {
                    if (c.responseCode != 206) throw IOException("조각 받기 거부 (${c.responseCode})")
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (pos <= end) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                            if (n < 0) break
                            val bb = ByteBuffer.wrap(buf, 0, n)
                            var p = pos
                            while (bb.hasRemaining()) p += ch.write(bb, p)
                            pos += n; got.addAndGet(n.toLong())
                        }
                    }
                } finally { c.disconnect() }
                if (pos <= end) throw IOException("연결이 끊겼어요")
            } catch (e: IOException) {
                // mobile data trouble → finish this part over Wi-Fi
                if (net != null && tries >= 1) { net = null; tries = 0; continue }
                if (++tries > 5) throw e
                delay(1000L * tries)
            }
        }
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Opens the mobile-data network alongside Wi-Fi (waits up to 6s). */
    private suspend fun mobileData(): Pair<Network, ConnectivityManager.NetworkCallback>? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        var cb: ConnectivityManager.NetworkCallback? = null
        val n = kotlinx.coroutines.withTimeoutOrNull(6000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Network> { cont ->
                val c = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resumeWith(Result.success(network)) }
                }
                cb = c
                runCatching { cm.requestNetwork(req, c) }.onFailure { if (cont.isActive) cont.cancel() }
            }
        }
        if (n == null) { cb?.let { c -> runCatching { cm.unregisterNetworkCallback(c) } }; return null }
        return n to cb!!
    }

    /** One stream; resumes with Range when the server allows it. */
    private suspend fun single(req: Req, ch: FileChannel, ranged: Boolean, total: Long, got: AtomicLong) {
        var pos = 0L
        var tries = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val c = open(req, if (ranged && pos > 0) "bytes=$pos-" else null)
                try {
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("서버 응답 $code")
                    if (code == 200 && pos > 0) { pos = 0; got.set(0); ch.truncate(0) }
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            val bb = ByteBuffer.wrap(buf, 0, n)
                            var p = pos
                            while (bb.hasRemaining()) p += ch.write(bb, p)
                            pos += n; got.addAndGet(n.toLong())
                        }
                    }
                } finally { c.disconnect() }
                if (total > 0 && pos < total) throw IOException("연결이 끊겼어요")
                return
            } catch (e: IOException) {
                if (!ranged || ++tries > 5) throw e
                delay(1000L * tries)
            }
        }
    }

    companion object {
        const val ACTION_START = "space.nami.browser.DOWNLOAD"
        const val ACTION_CANCEL = "space.nami.browser.CANCEL"
        private const val CH_PROGRESS = "dl_progress"
        private const val CH_DONE = "dl_done"
        private const val FG_ID = 7001
        private val nextId = AtomicInteger(1000)

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.2f GB", b / (1024.0 * 1024 * 1024))
            b >= 1L shl 20 -> String.format("%.1f MB", b / (1024.0 * 1024))
            b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
            else -> "$b B"
        }

        fun start(ctx: Context, url: String, name: String, mime: String?, cookie: String?, ua: String?, referer: String?, parts: Int, guessed: Boolean = false, dual: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).setAction(ACTION_START)
                .putExtra("url", url).putExtra("name", name).putExtra("mime", mime).putExtra("cookie", cookie)
                .putExtra("ua", ua).putExtra("referer", referer).putExtra("parts", parts).putExtra("guessed", guessed).putExtra("dual", dual))
        }
    }
}
