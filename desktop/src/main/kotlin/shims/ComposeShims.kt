@file:JvmName("ComposeAndroidShimsKt")
package androidx.compose.ui.graphics


/** 안드로이드 Bitmap(흉내) → Compose ImageBitmap. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = org.jetbrains.skia.Bitmap.makeFromImage(this.image).asComposeImageBitmap()
