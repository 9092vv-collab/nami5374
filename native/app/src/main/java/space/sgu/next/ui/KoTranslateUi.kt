package space.sgu.next.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.sgu.next.data.KoTranslate

/** 번역이 켜져 있으면 한글 번역문(준비되기 전엔 원문), 꺼져 있으면 원문. 원문은 바뀌지 않음. */
@Composable
fun koText(text: String, on: Boolean = KoTranslate.on): String {
    if (!on || text.isBlank()) return text
    val cached = KoTranslate.cache[text]
    LaunchedEffect(text) { if (KoTranslate.cache[text] == null) runCatching { KoTranslate.translate(text) } }
    return cached ?: text
}

/** "한글로 보기" 스위치 칩 + 상태 한 줄 */
@Composable
fun KoToggle(modifier: Modifier = Modifier) {
    Column(modifier) {
        PillChip(if (KoTranslate.on) "한글로 보는 중" else "한글로 보기", KoTranslate.on, Icons.Outlined.Translate) { KoTranslate.on = !KoTranslate.on }
        if (KoTranslate.on && KoTranslate.status.isNotEmpty())
            Text(KoTranslate.status, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    }
}

/** 블록마다 다는 번역 버튼 */
@Composable
fun KoButton(on: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        androidx.compose.material3.Icon(Icons.Outlined.Translate, if (on) "번역 닫기" else "번역", tint = if (on) Sgu.Accent else Sgu.Sub)
    }
}

/** 블록 하나의 한글 번역 (원문은 그대로) */
@Composable
fun KoBubble(text: String, modifier: Modifier = Modifier) {
    val cached = KoTranslate.cache[text]
    var err by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf("") }
    LaunchedEffect(text) { if (KoTranslate.cache[text] == null) runCatching { KoTranslate.translate(text) }.onFailure { err = "번역하지 못했어요 (${it.message})" } }
    Text(
        when { text.isBlank() -> "비어 있음"; cached != null -> cached; err.isNotEmpty() -> err; else -> KoTranslate.status.ifEmpty { "번역 중…" } },
        style = MaterialTheme.typography.bodySmall, color = if (cached != null) Sgu.Ink else Sgu.Sub,
        modifier = modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Sgu.Bubble).padding(12.dp),
    )
}
