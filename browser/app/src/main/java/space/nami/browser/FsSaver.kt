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
 * Backs the page-side File System Access stand-in (showSaveFilePicker / showDirectoryPicker) on
 * 호환 모드 sites: the site writes a big file piece by piece and we put the pieces straight into
 * Download/ (with sub folders), so nothing has to fit in memory.
 */
class FsSaver(private val ctx: Context) {
    private class Sink(val uri: Uri, val pfd: ParcelFileDescriptor, val ch: FileChannel, val name: String,
                       val head: java.io.ByteArrayOutputStream, var pos: Long)
    private val sinks = ConcurrentHashMap<String, Sink>()

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
        return true
    }

    fun abort(id: String) {
        val s = sinks.remove(id) ?: return
        runCatching { s.ch.close() }; runCatching { s.pfd.close() }
        runCatching { ctx.contentResolver.delete(s.uri, null, null) }
    }
}
