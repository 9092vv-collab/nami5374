package space.nami.browser

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One file Nami downloaded (from our own MediaStore entries or the system DownloadManager). */
class DlItem(val uri: Uri?, val name: String, val size: Long, val time: Long, val mime: String?, val dmId: Long? = null,
             val running: Boolean = false, val got: Long = 0)

/** A download in progress in DownloadService (for the 받은 파일 screen). */
class DlActive(val name: String, val got: AtomicLong, val total: AtomicLong)

object Downloads {
    val active = ConcurrentHashMap<Int, DlActive>()

    /** Set by Browser: called on the main thread when a download finishes (to show "다 받았어요 · 열기"). */
    @Volatile var onDone: ((Uri, String, String?) -> Unit)? = null

    fun notifyDone(uri: Uri, name: String, mime: String?) {
        Handler(Looper.getMainLooper()).post { onDone?.invoke(uri, name, mime) }
    }

    fun list(ctx: Context): List<DlItem> {
        val out = ArrayList<DlItem>()
        // files Nami wrote itself (split downloader, blob/data saves)
        runCatching {
            val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.MIME_TYPE)
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?", arrayOf(ctx.packageName), "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(DlItem(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), c.getString(1) ?: "",
                        c.getLong(2), c.getLong(3) * 1000, c.getString(4)))
                }
            }
        }
        // files the system download manager fetched for us (분할 다운로드 끔)
        runCatching {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val iSize = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iGot = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTime = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                val iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val status = c.getInt(iStatus)
                    if (status == DownloadManager.STATUS_FAILED) continue
                    val ok = status == DownloadManager.STATUS_SUCCESSFUL
                    out.add(DlItem(if (ok) dm.getUriForDownloadedFile(id) else null, c.getString(iTitle) ?: "", c.getLong(iSize),
                        c.getLong(iTime), c.getString(iMime), dmId = id, running = !ok, got = c.getLong(iGot)))
                }
            }
        }
        return out.sortedByDescending { it.time }
    }

    const val APK = "application/vnd.android.package-archive"
    const val ACTION_OPEN_FILE = "space.nami.browser.OPEN_FILE"

    /** An .apk waiting for the user to allow "install unknown apps" for Nami; installed when they come back. */
    @Volatile var pendingApk: Uri? = null

    /** Intent the "다 받았어요" notification sends: goes through Nami so .apk files get the install-permission step. */
    fun openIntent(ctx: Context, uri: Uri, name: String, mime: String?): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN_FILE).setData(uri)
            .putExtra("name", name).putExtra("mime", mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Opens a downloaded file right away in its default app (.apk → installer, asking for permission first if needed). */
    fun open(ctx: Context, uri: Uri, mime: String?, name: String) {
        val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: FileName.mimeFor(name) ?: ctx.contentResolver.getType(uri) ?: "*/*"
        if (type == APK || name.endsWith(".apk", true)) { install(ctx, uri); return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { Toast.makeText(ctx, "이 파일을 열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show() }
    }

    fun install(ctx: Context, uri: Uri) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pendingApk = uri
            Toast.makeText(ctx, "Nami에서 앱 설치를 허용해 주세요. 허용하고 돌아오면 바로 설치해요.", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        pendingApk = null
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "설치 화면을 열지 못했어요", Toast.LENGTH_SHORT).show() }
    }

    /** Back from the permission screen: continue the install if it's allowed now. */
    fun resumePending(ctx: Context) {
        val u = pendingApk ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) install(ctx, u)
    }

    fun share(ctx: Context, it: DlItem) {
        val u = it.uri ?: return
        val type = it.mime ?: FileName.mimeFor(it.name) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), it.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun delete(ctx: Context, it: DlItem): Boolean = runCatching {
        if (it.dmId != null) (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(it.dmId) > 0
        else it.uri != null && ctx.contentResolver.delete(it.uri, null, null) > 0
    }.getOrDefault(false)
}
