package android.net

import java.io.File

/** 데스크톱에서는 파일 경로만 다룸 (file:// 주소). */
class Uri private constructor(private val s: String) {
    val path: String? get() = if (s.startsWith("file:")) runCatching { File(java.net.URI(s)).path }.getOrNull() else s
    val lastPathSegment: String? get() = path?.let { File(it).name }
    val scheme: String? get() = s.substringBefore(':', "").ifEmpty { null }
    override fun toString() = s
    override fun equals(other: Any?) = other is Uri && other.s == s
    override fun hashCode() = s.hashCode()
    companion object {
        fun parse(s: String): Uri = Uri(s)
        fun fromFile(f: File): Uri = Uri(f.toURI().toString())
        fun encode(s: String?): String = (s ?: "").let { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        fun decode(s: String?): String = s?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } ?: ""
        val EMPTY = Uri("")
    }
}
