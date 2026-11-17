package space.nami.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves a file the page streams to us piece by piece (relayed service-worker downloads, e.g. kio.ac)
 * straight into Download/, so nothing has to fit in memory.
 */
class FsSaver(private val ctx: Context) {
    private class Sink(val uri: Uri, val pfd: ParcelFileDescriptor, val ch: FileChannel, val name: String,
                       val head: java.io.ByteArrayOutputStream, var pos: Long, var written: Long = 0)
    private val sinks = ConcurrentHashMap<String, Sink>()

    /** Bytes written so far, or -1 if there is no such file being written. */
    fun written(id: String): Long = sinks[id]?.written ?: -1L
    fun active(): List<Pair<String, Long>> = sinks.values.map { it.name to it.written }

    /** "a/b/../c" → "a/b/c" with each part cleaned; empty = Download/ itself. */
    private fun folder(dir: String): String = dir.split('/', '\\')
        .mapNotNull { FileName.clean(it)?.takeIf { p -> p != "." && p != ".." } }
        .take(8).joinToString("/")

    fun open(id: String, dir: String, name: String, mime: String): Boolean = runCatching {
        val n = FileName.clean(name) ?: "file"
        val sub = folder(dir)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, n)
            (mime.takeIf { it.isNotBlank() } ?: FileName.mimeFor(n))?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + (if (sub.isEmpty()) "" else "$sub/"))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")!!
        sinks[id] = Sink(uri, pfd, FileOutputStream(pfd.fileDescriptor).channel, n, java.io.ByteArrayOutputStream(), 0)
        RelayService.update(ctx, this)
        true
    }.getOrDefault(false)

    /** pos < 0 = where the last write ended. */
    fun write(id: String, b64: String, pos: Double): Boolean {
        val s = sinks[id] ?: return false
        return runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            var at = if (pos >= 0) pos.toLong() else s.pos
            if (at == 0L && s.head.size() == 0) s.head.write(bytes, 0, minOf(bytes.size, 32))
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) at += s.ch.write(buf, at)
            s.pos = at
            s.written += bytes.size
            true
        }.getOrElse { abort(id); false }
    }

    fun seek(id: String, pos: Double): Boolean { val s = sinks[id] ?: return false; s.pos = pos.toLong().coerceAtLeast(0); return true }

    fun truncate(id: String, size: Double): Boolean {
        val s = sinks[id] ?: return false
        return runCatching {
            val n = size.toLong().coerceAtLeast(0)
            if (n < s.ch.size()) s.ch.truncate(n) else if (n > s.ch.size()) s.ch.write(ByteBuffer.wrap(ByteArray(1)), n - 1)
            if (s.pos > n) s.pos = n
            true
        }.getOrDefault(false)
    }

    fun close(id: String): Boolean {
        val s = sinks.remove(id) ?: return false
        runCatching { s.ch.force(true) }
        runCatching { s.ch.close() }; runCatching { s.pfd.close() }
        BlobSaver.finish(ctx, s.uri, s.name, false, s.head.toByteArray())
        RelayService.update(ctx, this)
        return true
    }

    fun abort(id: String) {
        val s = sinks.remove(id) ?: return
        runCatching { s.ch.close() }; runCatching { s.pfd.close() }
        runCatching { ctx.contentResolver.delete(s.uri, null, null) }
        RelayService.update(ctx, this)
    }
}

/**
 * Keeps Nami in the foreground while a page streams a big file to us (screen off / other app),
 * and shows how much has arrived.
 */
class RelayService : android.app.Service() {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val nm by lazy { getSystemService(android.app.NotificationManager::class.java) }
    private val tick = object : Runnable {
        override fun run() {
            val list = saver?.active().orEmpty()
            if (list.isEmpty()) { stopSelf(); return }
            nm.notify(FG_ID, note(list)); h.postDelayed(this, 1000)
        }
    }

    private fun note(list: List<Pair<String, Long>>): android.app.Notification {
        val title = if (list.size == 1) "받는 중: ${list[0].first}" else "${list.size}개 받는 중"
        val open = android.app.PendingIntent.getActivity(this, 0, android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        return androidx.core.app.NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(DownloadService.size(list.sumOf { it.second }) + " · 그 사이트 탭은 닫지 말아 주세요")
            .setProgress(0, 0, true).setOngoing(true).setSilent(true).setContentIntent(open).build()
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(android.app.NotificationChannel(CH, "다운로드 진행", android.app.NotificationManager.IMPORTANCE_LOW))
        androidx.core.app.ServiceCompat.startForeground(this, FG_ID, note(saver?.active().orEmpty().ifEmpty { listOf("파일" to 0L) }),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        h.removeCallbacks(tick); h.post(tick)
        return START_NOT_STICKY
    }
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    companion object {
        private const val CH = "dl_progress"
        private const val FG_ID = 7002
        @Volatile private var saver: FsSaver? = null
        @Volatile private var running = false
        fun update(ctx: Context, s: FsSaver) {
            saver = s
            val any = s.active().isNotEmpty()
            if (any && !running) {
                running = true
                runCatching { ctx.startForegroundService(android.content.Intent(ctx, RelayService::class.java)) }
            } else if (!any) running = false
        }
    }
}
