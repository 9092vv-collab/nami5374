// PC판용 작은 흉내들: android.util.Base64, android.content.Context
@file:JvmName("MakerUtilShimsKt")
package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    fun encodeToString(b: ByteArray, flags: Int): String {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e.encodeToString(b)
    }

    /** MIME 디코더는 줄바꿈·공백을 건너뛰어서 PEM 키도 그대로 읽힘. */
    fun decode(s: String, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder().decode(s.trim()) else java.util.Base64.getMimeDecoder().decode(s)
}
