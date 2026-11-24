package space.nami.maker

import androidx.compose.ui.graphics.nativeCanvas

/** PC: Compose 캔버스(Skia)를 도형 그림용 Canvas 흉내로 감쌈. */
fun androidx.compose.ui.graphics.Canvas.art(): android.graphics.Canvas = android.graphics.Canvas(nativeCanvas)

/** 파일 하나 고르기 → (파일 이름, 내용). PC: 윈도우 파일 열기 창. */
@androidx.compose.runtime.Composable
fun rememberFilePicker(onPick: (String, ByteArray) -> Unit): () -> Unit {
    val cb = androidx.compose.runtime.rememberUpdatedState(onPick)
    return androidx.compose.runtime.remember {
        {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, "파일 고르기", java.awt.FileDialog.LOAD)
            fd.isVisible = true
            val f = fd.file
            if (f != null) runCatching { java.io.File(fd.directory, f) }.getOrNull()?.takeIf { it.isFile }?.let { cb.value(it.name, it.readBytes()) }
        }
    }
}
