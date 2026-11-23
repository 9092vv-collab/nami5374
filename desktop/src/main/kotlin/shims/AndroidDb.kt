package android.database

class Cursor(private val row: List<String?>) : java.io.Closeable {
    private var pos = -1
    fun moveToFirst(): Boolean { pos = 0; return row.isNotEmpty() }
    fun getString(i: Int): String? = row.getOrNull(i)
    fun getColumnIndex(name: String): Int = 0
    val count: Int get() = 1
    override fun close() {}
}
