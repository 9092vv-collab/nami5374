package space.nami.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Picks a real file name instead of Android's "downloadfile.bin". */
object FileName {
    /** Content-Disposition → name. Handles RFC 5987 `filename*=UTF-8''%ED%95%9C…`, quoted/unquoted, %-encoded and mis-decoded UTF-8. */
    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^';]*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val cs = m.groupValues[1].trim().ifEmpty { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"').replace("+", "%2B"), cs) }.getOrNull()
                ?.let { clean(it) }?.let { return it }
        }
        Regex("filename\\s*=\\s*(\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            var n = m.groupValues[2].ifEmpty { m.groupValues[3] }.trim()
            if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(n)) n = runCatching { URLDecoder.decode(n.replace("+", "%2B"), "UTF-8") }.getOrDefault(n)
            return clean(fixMojibake(n))
        }
        return null
    }

    /** "íê¸" (UTF-8 bytes read as Latin-1) → "한글". */
    private fun fixMojibake(s: String): String {
        if (s.none { it.code in 0x80..0xFF } || s.any { it.code > 0xFF }) return s
        val fixed = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if ('\uFFFD' in fixed) s else fixed
    }

    fun clean(n: String?): String? {
        val x = n?.substringAfterLast('/')?.substringAfterLast('\\')?.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")?.trim()?.trim('.')
        return x?.take(150)?.ifEmpty { null }
    }

    fun fromUrl(url: String): String? = runCatching {
        val seg = Uri.parse(url).lastPathSegment ?: return null
        if ('.' in seg) clean(seg) else null
    }.getOrNull()

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    fun hasRealExt(n: String) = ext(n).let { it.isNotEmpty() && it != "bin" && it.length <= 6 && n.contains('.') }

    /** Adds an extension from the MIME type when the name has none (or only .bin). */
    fun withExt(n: String, mime: String?): String {
        if (hasRealExt(n)) return n
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        val e = m?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val base = if (ext(n) == "bin") n.substringBeforeLast('.') else n
        return if (e != null && e != "bin") "$base.$e" else n
    }

    /** Best name we can get before downloading. `guessed` = nothing reliable was found (try headers / file bytes later). */
    fun build(url: String, cd: String?, mime: String?, hint: String?): Pair<String, Boolean> {
        val fromHint = clean(hint)
        val fromCd = fromDisposition(cd)
        val n = fromHint ?: fromCd ?: fromUrl(url) ?: "download"
        val named = withExt(n, mime)
        return named to (fromHint == null && fromCd == null && !hasRealExt(named))
    }

    /** Extension from the first bytes of the file. */
    fun sniff(h: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = h.size >= i + b.size && b.indices.all { (h[i + it].toInt() and 0xFF) == b[it] }
        fun str(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it].toInt().toChar() == s[it] }
        return when {
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> "zip"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            str(0, "Rar!") -> "rar"
            at(0, 0x1F, 0x8B) -> "gz"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            str(0, "GIF8") -> "gif"
            str(0, "RIFF") && str(8, "WEBP") -> "webp"
            str(0, "RIFF") && str(8, "WAVE") -> "wav"
            str(0, "RIFF") && str(8, "AVI ") -> "avi"
            str(0, "%PDF") -> "pdf"
            str(4, "ftyp") -> if (str(8, "qt")) "mov" else if (str(8, "M4A")) "m4a" else if (str(8, "avif")) "avif" else if (str(8, "heic")) "heic" else "mp4"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "mkv"
            str(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) -> "mp3"
            str(0, "OggS") -> "ogg"
            str(0, "fLaC") -> "flac"
            str(0, "BM") && h.size > 14 -> null
            else -> null
        }
    }

    fun mimeFor(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name))
}

/**
 * Saves blob: / data: downloads (files a page builds in memory — e.g. decrypted files from userscripts).
 * The page streams the blob through the bridge in base64 chunks; we write them into Downloads.
 */
class BlobSaver(private val ctx: Context) {
    private class Sink(val uri: Uri, val out: OutputStream, var name: String, val guessed: Boolean, val head: java.io.ByteArrayOutputStream, var written: Long)
    private val sinks = ConcurrentHashMap<String, Sink>()
    /** blob: URLs we're already saving (so the DownloadListener call for the same click is ignored). */
    val taken: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(id: String, name: String, mime: String?, guessed: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            (mime?.takeIf { it.isNotBlank() } ?: FileName.mimeFor(name))?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        val out = ctx.contentResolver.openOutputStream(uri)!!
        sinks[id] = Sink(uri, out, name, guessed, java.io.ByteArrayOutputStream(), 0)
        true
    }.getOrDefault(false)

    fun chunk(id: String, b64: String): Boolean {
        val s = sinks[id] ?: return false
        return runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (s.head.size() < 32) s.head.write(bytes, 0, minOf(bytes.size, 32 - s.head.size()))
            s.out.write(bytes); s.written += bytes.size
            true
        }.getOrElse { fail(id); false }
    }

    fun end(id: String) {
        val s = sinks.remove(id) ?: return
        runCatching { s.out.close() }
        finish(ctx, s.uri, s.name, s.guessed, s.head.toByteArray())
    }

    fun fail(id: String) {
        val s = sinks.remove(id) ?: return
        runCatching { s.out.close() }
        runCatching { ctx.contentResolver.delete(s.uri, null, null) }
    }

    /** data: URL straight to a file. */
    fun saveDataUrl(url: String, hint: String?): String? = runCatching {
        val comma = url.indexOf(',')
        val meta = url.substring(5, comma)
        val mime = meta.substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = if (meta.contains(";base64")) Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            else URLDecoder.decode(url.substring(comma + 1), "UTF-8").toByteArray()
        val (name, guessed) = FileName.build("", null, mime, hint)
        val id = "d" + System.nanoTime()
        if (!start(id, name, mime, guessed)) return null
        chunk(id, Base64.encodeToString(bytes, Base64.NO_WRAP))
        end(id)
        name
    }.getOrNull()

    companion object {
        /** Rename by file bytes if needed, clear IS_PENDING, post a "done" notification. */
        fun finish(ctx: Context, uri: Uri, name: String, guessed: Boolean, head: ByteArray): String {
            var final = name
            if (guessed || !FileName.hasRealExt(name)) {
                FileName.sniff(head)?.let { e ->
                    val base = if (name.endsWith(".bin", true)) name.dropLast(4) else name
                    final = "$base.$e"
                }
            }
            val v = ContentValues().apply {
                if (final != name) { put(MediaStore.Downloads.DISPLAY_NAME, final); FileName.mimeFor(final)?.let { put(MediaStore.Downloads.MIME_TYPE, it) } }
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            runCatching { ctx.contentResolver.update(uri, v, null, null) }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("dl_done", "다운로드 완료", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileName.mimeFor(final) ?: ctx.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val id = (System.currentTimeMillis() % 100000).toInt() + 200000
            runCatching {
                nm.notify(id, NotificationCompat.Builder(ctx, "dl_done").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("다 받았어요: $final")
                    .setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .setAutoCancel(true).build())
            }
            return final
        }
    }
}
