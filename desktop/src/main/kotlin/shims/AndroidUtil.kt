package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    fun encodeToString(b: ByteArray, flags: Int): String {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e.encodeToString(b)
    }
    fun encode(b: ByteArray, flags: Int): ByteArray = encodeToString(b, flags).toByteArray()
    fun decode(s: String, flags: Int): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        return if (flags and URL_SAFE != 0 || clean.contains('-') || clean.contains('_')) java.util.Base64.getUrlDecoder().decode(clean.trimEnd('='))
        else java.util.Base64.getMimeDecoder().decode(clean)
    }
    fun decode(b: ByteArray, flags: Int): ByteArray = decode(String(b), flags)
}

open class LruCache<K : Any, V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized fun get(k: K): V? = map[k]
    @Synchronized fun put(k: K, v: V): V? = map.put(k, v)
    @Synchronized fun remove(k: K): V? = map.remove(k)
    @Synchronized fun evictAll() = map.clear()
    @Synchronized fun size() = map.size
}
