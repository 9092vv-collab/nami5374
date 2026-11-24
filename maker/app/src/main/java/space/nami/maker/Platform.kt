package space.nami.maker

import androidx.compose.ui.graphics.nativeCanvas

/** The platform canvas the shape art draws on (phone: android.graphics.Canvas; the PC build has its own Platform.kt). */
fun androidx.compose.ui.graphics.Canvas.art(): android.graphics.Canvas = nativeCanvas
