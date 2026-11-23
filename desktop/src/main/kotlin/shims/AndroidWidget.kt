package android.widget

import android.content.Context

/** 화면 아래에 잠깐 뜨는 알림 — 데스크톱 창이 그려 줌(space.sgu.next.DesktopToast). */
class Toast private constructor(private val text: String, private val long: Boolean) {
    fun show() { space.sgu.next.DesktopToast.show(text, long) }
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        fun makeText(ctx: Context?, text: CharSequence, duration: Int) = Toast(text.toString(), duration == LENGTH_LONG)
    }
}
