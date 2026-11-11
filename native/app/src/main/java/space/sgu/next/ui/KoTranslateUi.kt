package space.sgu.next.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
