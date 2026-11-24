package space.nami.maker

import androidx.compose.ui.graphics.nativeCanvas

/** PC: Compose 캔버스(Skia)를 도형 그림용 Canvas 흉내로 감쌈. */
fun androidx.compose.ui.graphics.Canvas.art(): android.graphics.Canvas = android.graphics.Canvas(nativeCanvas)
