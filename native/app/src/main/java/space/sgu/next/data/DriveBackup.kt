package space.sgu.next.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "드라이브 백업": the user picks a file location once (system picker → Google Drive folder),
 * we keep a persistable permission on that file and overwrite it on every backup.
 * Google Drive's provider doesn't support folder (tree) picking, so it's one fixed file.
 */
object DriveBackup {
    data class Target(val uri: Uri, val name: String, val lastAt: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)

    fun target(ctx: Context): Target? {
        val p = prefs(ctx)
        val u = p.getString("uri", null) ?: return null
        return Target(Uri.parse(u), p.getString("name", null) ?: "백업 파일", p.getLong("lastAt", 0L))
    }

    fun withKeys(ctx: Context): Boolean = prefs(ctx).getBoolean("withKeys", false)
    fun setWithKeys(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("withKeys", v).apply() }

    /** Called with the uri the picker returned. Keeps permission across restarts. */
    fun setTarget(ctx: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
        // drop permission of an older target
        target(ctx)?.uri?.takeIf { it != uri }?.let { old ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs(ctx).edit().putString("uri", uri.toString()).putString("name", displayName(ctx, uri)).putLong("lastAt", 0L).apply()
    }

    fun clear(ctx: Context) {
        target(ctx)?.uri?.let { u ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs(ctx).edit().remove("uri").remove("name").remove("lastAt").apply()
    }

    private fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "백업 파일"

    /** Overwrites the target file with the current settings. Throws if the file is gone / permission lost. */
    suspend fun backup(ctx: Context): Target {
        val t = target(ctx) ?: error("백업 위치가 없어요")
        val bytes = SettingsStore.exportBackup(withKeys(ctx)).toByteArray()
        withContext(Dispatchers.IO) {
            val r = ctx.contentResolver
            // "wt" = truncate; some providers only accept "w"
            val out = runCatching { r.openOutputStream(t.uri, "wt") }.getOrNull() ?: r.openOutputStream(t.uri, "w")
            (out ?: error("파일을 열 수 없어요")).use { it.write(bytes) }
        }
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("lastAt", now).putString("name", displayName(ctx, t.uri)).apply()
        return t.copy(lastAt = now, name = displayName(ctx, t.uri))
    }

    suspend fun read(ctx: Context): String {
        val t = target(ctx) ?: error("백업 위치가 없어요")
        return withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(t.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
