@file:JvmName("FontResShimsKt")
package androidx.compose.ui.text.font

/** 안드로이드 R.font 번호 → 데스크톱 리소스 파일. */
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.Font(space.sgu.next.R.font.path(resId), weight, style)
