@file:JvmName("MakerComposeShimsKt")
package androidx.compose.ui.graphics

/** Bitmap(흉내) → Compose ImageBitmap. 같은 픽셀을 쓰므로 그 뒤에 그린 것도 보임. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = this.sk.asComposeImageBitmap()
