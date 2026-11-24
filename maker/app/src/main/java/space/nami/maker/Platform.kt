package space.nami.maker

import androidx.compose.ui.graphics.nativeCanvas

/** The platform canvas the shape art draws on (phone: android.graphics.Canvas; the PC build has its own Platform.kt). */
fun androidx.compose.ui.graphics.Canvas.art(): android.graphics.Canvas = nativeCanvas

/** 파일 하나 고르기 → (파일 이름, 내용). 폰: 시스템 파일 선택기. */
@androidx.compose.runtime.Composable
fun rememberFilePicker(onPick: (String, ByteArray) -> Unit): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cb = androidx.compose.runtime.rememberUpdatedState(onPick)
    val l = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment ?: "캐릭터.png"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        }
        cb.value(name, bytes)
    }
    return { l.launch("*/*") }
}

/** 파일로 저장하기 (이름, 내용) → onDone(성공?). 폰: 시스템 저장 창. */
@androidx.compose.runtime.Composable
fun rememberFileSaver(onDone: (Boolean) -> Unit = {}): (String, ByteArray) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cb = androidx.compose.runtime.rememberUpdatedState(onDone)
    val pending = androidx.compose.runtime.remember { arrayOfNulls<ByteArray>(1) }
    val l = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val b = pending[0]; pending[0] = null
        if (uri == null || b == null) return@rememberLauncherForActivityResult
        cb.value(runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) } != null }.getOrDefault(false))
    }
    return { name, bytes -> pending[0] = bytes; l.launch(name) }
}
