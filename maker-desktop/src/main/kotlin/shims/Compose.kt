@file:JvmName("MakerComposeShimsKt")
package androidx.compose.ui.graphics

/** Bitmap(흉내) → Compose ImageBitmap. 그 순간의 그림을 복사해 둠 — 바뀌면 다시 불러야 함(에디터는 그릴 때마다 다시 부름). */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = org.jetbrains.skia.Image.makeFromBitmap(this.sk.makeClone()).toComposeImageBitmap()
