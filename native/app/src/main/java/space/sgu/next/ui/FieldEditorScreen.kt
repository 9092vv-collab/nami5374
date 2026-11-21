package space.sgu.next.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import space.sgu.next.data.CharacterStore

/**
 * Full-screen editor for one card field. It is its own navigation destination, so the system
 * back gesture returns to the field list. Changes are saved when leaving the screen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FieldEditorScreen(characterId: String, key: String, onBack: () -> Unit) {
    val isGen = key.startsWith("gen:")
    val original = remember(characterId, key) {
        if (isGen) space.sgu.next.data.ImageGenStore.getText(characterId, key) else CharacterStore.getField(CharacterStore.readCard(characterId), key)
    }
    var text by rememberSaveable(characterId, key) { mutableStateOf(original) }
    var tfv by rememberSaveable(characterId, key, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text))
    }
    var deleted by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val latest by rememberUpdatedState(text)
    val isAlt = key.startsWith("alt:")
    // 트리거 V2 must stay valid JSON (the original would replace broken JSON with an empty trigger on export).
    val isV2 = remember(characterId, key) {
        key == "trig:code" && space.sgu.next.data.Scripts.trigger(CharacterStore.readCard(characterId)["data"] as? kotlinx.serialization.json.JsonObject).mode == space.sgu.next.data.Scripts.TriggerMode.V2
    }
    val mono = key == "trig:code" || key == "onb:html"
    val invalid = isV2 && text.isNotBlank() && space.sgu.next.data.Scripts.parseV2(text) == null
    val latestInvalid by rememberUpdatedState(invalid)
    var askLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (invalid && text != original) askLeave = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = invalid && text != original) { askLeave = true }

    // Save on leave (back gesture, back arrow or ✓).
    DisposableEffect(characterId, key) {
        onDispose {
            if (!deleted && !latestInvalid && latest != original && CharacterStore.items.any { it.id == characterId }) {
                if (isGen) space.sgu.next.data.ImageGenStore.setText(characterId, key, latest)
                else CharacterStore.setField(characterId, key, latest)
            }
        }
    }
    LaunchedEffect(Unit) { if (original.isEmpty()) runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = Sgu.Bg,
        topBar = {
            SguTopBar(
                title = if (isGen) space.sgu.next.data.ImageGenStore.label(characterId, key) else fieldLabel(key),
                subtitle = if (invalid) "JSON 형식 오류 · 고쳐야 저장돼요" else CharacterStore.items.firstOrNull { it.id == characterId }?.name,
                onBack = leave,
                actions = {
                    if (isAlt) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "삭제") }
                    IconButton(onClick = leave) { Icon(Icons.Outlined.Check, "완료") }
                },
            )
        },
    ) { pad ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
        Box(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            if (text.isEmpty()) Text(
                when (key) { "trig:code" -> if (isV2) "V2 트리거 JSON 배열" else "-- RisuAI Lua 트리거 코드"; else -> "여기에 내용을 쓰세요" },
                style = MaterialTheme.typography.bodyLarge, color = Sgu.Sub,
            )
            BasicTextField(
                value = tfv,
                onValueChange = { nv ->
                    var v = nv
                    // Image prompts: a long press (or double tap) selects the whole tag, not one word.
                    if (isGen && v.text == tfv.text && !v.selection.collapsed &&
                        (tfv.selection.collapsed || v.selection.max <= tfv.selection.min || v.selection.min >= tfv.selection.max)) {
                        PromptTags.expand(v.text, v.selection)?.let { v = v.copy(selection = it) }
                    }
                    tfv = v; text = v.text
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Sgu.Ink).let {
                    if (mono) it.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)) else it
                },
                cursorBrush = SolidColor(Sgu.Accent),
                modifier = Modifier.fillMaxSize().focusRequester(focus).padding(bottom = 200.dp),
            )
        }
        val wt = if (isGen) PromptTags.weightOf(tfv.text, tfv.selection) else null
        if (wt != null) WeightBar(wt.value) { delta ->
            val (rep, sel) = PromptTags.reweight(tfv.text, tfv.selection, wt, delta)
            tfv = androidx.compose.ui.text.input.TextFieldValue(rep, sel); text = rep
        }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("JSON이 올바르지 않아요") },
            text = { Text("V2 트리거는 JSON 배열이어야 해요. 고치지 않고 나가면 이번에 바꾼 내용은 저장되지 않아요.") },
            confirmButton = { TextButton(onClick = { askLeave = false; onBack() }) { Text("저장 안 하고 나가기", color = Sgu.Danger) } },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text("계속 편집", color = Sgu.Ink) } },
            containerColor = Sgu.Card,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 첫 메시지 삭제") },
            text = { Text("${fieldLabel(key)}을(를) 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    deleted = true
                    CharacterStore.removeAltGreeting(characterId, key.removePrefix("alt:").toIntOrNull() ?: -1)
                    confirmDelete = false
                    onBack()
                }) { Text("삭제", color = Sgu.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
}

@Composable
private fun WeightBar(value: java.math.BigDecimal, onStep: (java.math.BigDecimal) -> Unit) {
    val step = java.math.BigDecimal("0.1")
    androidx.compose.material3.Surface(color = Sgu.Card, shadowElevation = 4.dp) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text("가중치", color = Sgu.Sub, style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            IconButton(onClick = { onStep(step.negate()) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "내리기", tint = Sgu.Ink) }
            Text(PromptTags.fmt(value), color = Sgu.Ink, style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(64.dp))
            IconButton(onClick = { onStep(step) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "올리기", tint = Sgu.Ink) }
        }
    }
}

/** Tag selection and weights (`1.2::tag::`) for image prompt fields. */
internal object PromptTags {
    private val WEIGHTED = Regex("""-?\d+(?:\.\d+)?::.*?::""")
    private val WHOLE = Regex("""^(-?\d+(?:\.\d+)?)::(.*)::$""", RegexOption.DOT_MATCHES_ALL)
    private fun isSep(c: Char) = c == ',' || c == '\n' || c == '|'

    class Weight(val value: java.math.BigDecimal, val inner: String, val start: Int, val end: Int)

    /** The tag around [sel]: a weighted `n::…::` that covers it, else the comma-separated piece (brackets and spaces trimmed). */
    fun expand(text: String, sel: androidx.compose.ui.text.TextRange): androidx.compose.ui.text.TextRange? {
        val a = sel.min; val b = sel.max
        if (a < 0 || b > text.length) return null
        var s = a; while (s > 0 && !isSep(text[s - 1])) s--
        var e = b; while (e < text.length && !isSep(text[e])) e++
        if ((a until b).any { isSep(text[it]) }) return null
        val seg = text.substring(s, e)
        WEIGHTED.findAll(seg).firstOrNull { s + it.range.first <= a && s + it.range.last + 1 >= b }?.let {
            return androidx.compose.ui.text.TextRange(s + it.range.first, s + it.range.last + 1)
        }
        val trim = " \t{}[]()"
        var ts = s; while (ts < e && seg[ts - s] in trim) ts++
        var te = e; while (te > ts && seg[te - 1 - s] in trim) te--
        if (te <= ts || ts > a || te < b) return null
        return androidx.compose.ui.text.TextRange(ts, te)
    }

    /** Weight of the selected tag (a plain tag counts as 1), or null when the selection isn't one tag. */
    fun weightOf(text: String, sel: androidx.compose.ui.text.TextRange): Weight? {
        if (sel.collapsed || sel.max > text.length) return null
        val t = text.substring(sel.min, sel.max)
        WHOLE.find(t)?.let { m ->
            val v = m.groupValues[1].toBigDecimalOrNull() ?: return null
            return Weight(v, m.groupValues[2], sel.min, sel.max)
        }
        if (t.isBlank() || t.any { isSep(it) } || t.contains("::")) return null
        return Weight(java.math.BigDecimal.ONE, t, sel.min, sel.max)
    }

    fun reweight(text: String, sel: androidx.compose.ui.text.TextRange, w: Weight, delta: java.math.BigDecimal): Pair<String, androidx.compose.ui.text.TextRange> {
        val rep = "${fmt(w.value + delta)}::${w.inner}::"
        val out = text.substring(0, w.start) + rep + text.substring(w.end)
        return out to androidx.compose.ui.text.TextRange(w.start, w.start + rep.length)
    }

    fun fmt(v: java.math.BigDecimal): String {
        val r = v.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        return if (r.signum() == 0) "0" else r.toPlainString()
    }
}
