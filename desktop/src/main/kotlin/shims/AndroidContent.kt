// 데스크톱용 안드로이드 API 흉내(shim): 공용 소스가 그대로 컴파일되도록 쓰는 부분만 같은 이름으로 만든 것.
package android.content

import android.content.res.AssetManager
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

open class Context(val dataDir: File) {
    open val filesDir: File get() = File(dataDir, "files").apply { mkdirs() }
    open val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
    open val applicationContext: Context get() = this
    val contentResolver = ContentResolver()
    val assets = AssetManager()
    val packageName = "space.sgu.next"
    private val prefs = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(prefs) { prefs.getOrPut(name) { SharedPreferences(File(dataDir, "shared_prefs/$name.properties")) } }
    fun getSystemService(name: String): Any? = if (name == CLIPBOARD_SERVICE) ClipboardManager() else null
    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
    }
}

class ContentResolver {
    private fun file(u: Uri) = File(u.path ?: error("파일 경로가 없어요: $u"))
    fun openInputStream(u: Uri): InputStream? = file(u).inputStream()
    fun openOutputStream(u: Uri): OutputStream? = openOutputStream(u, "w")
    fun openOutputStream(u: Uri, mode: String): OutputStream? = file(u).also { it.parentFile?.mkdirs() }.outputStream()
    fun query(u: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        Cursor(listOf(file(u).name))
    fun takePersistableUriPermission(u: Uri, flags: Int) {}
    fun releasePersistableUriPermission(u: Uri, flags: Int) {}
}

class SharedPreferences(private val f: File) {
    private val p = java.util.Properties().apply { runCatching { f.inputStream().use { load(it) } } }
    fun getString(k: String, d: String?): String? = p.getProperty(k) ?: d
    fun getBoolean(k: String, d: Boolean): Boolean = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
    fun getLong(k: String, d: Long): Long = p.getProperty(k)?.toLongOrNull() ?: d
    fun getInt(k: String, d: Int): Int = p.getProperty(k)?.toIntOrNull() ?: d
    fun getFloat(k: String, d: Float): Float = p.getProperty(k)?.toFloatOrNull() ?: d
    fun contains(k: String) = p.containsKey(k)
    val all: Map<String, *> get() = p.entries.associate { it.key.toString() to it.value }
    fun edit() = Editor()
    inner class Editor {
        private val ops = mutableListOf<() -> Unit>()
        fun putString(k: String, v: String?) = apply { ops += { if (v == null) p.remove(k) else p.setProperty(k, v) } }
        fun putBoolean(k: String, v: Boolean) = apply { ops += { p.setProperty(k, v.toString()) } }
        fun putLong(k: String, v: Long) = apply { ops += { p.setProperty(k, v.toString()) } }
        fun putInt(k: String, v: Int) = apply { ops += { p.setProperty(k, v.toString()) } }
        fun putFloat(k: String, v: Float) = apply { ops += { p.setProperty(k, v.toString()) } }
        fun remove(k: String) = apply { ops += { p.remove(k) } }
        fun clear() = apply { ops += { p.clear() } }
        fun apply() { commit() }
        fun commit(): Boolean = synchronized(p) {
            ops.forEach { it() }; ops.clear()
            runCatching { f.parentFile?.mkdirs(); f.outputStream().use { p.store(it, null) } }.isSuccess
        }
    }
}

class ClipData private constructor(val text: String) {
    companion object { fun newPlainText(label: CharSequence?, text: CharSequence): ClipData = ClipData(text.toString()) }
}

class ClipboardManager {
    fun setPrimaryClip(c: ClipData) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(c.text), null) }
    }
}

open class Intent {
    companion object {
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
    }
}
