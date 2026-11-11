package space.sgu.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonObject
import space.sgu.next.data.RisuPreset
import space.sgu.next.data.RisuToggles
import space.sgu.next.data.s

private const val JB_KEY = "\u0000jailbreak"
private const val COT_KEY = "\u0000cot"

/** 토글을 켜고 끄면서, 적용된 프롬프트가 어떻게 나오는지 보기만 하는 화면 (프리셋은 바꾸지 않음). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RisuTogglePreviewDialog(p: JsonObject, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val presetName = p.s("name").ifBlank { "이름 없는 프리셋" }
    val values = remember(presetName) { mutableStateMapOf<String, String>().apply { putAll(RisuToggles.load(ctx, presetName)) } }
    fun set(k: String, v: String) { values[k] = v; RisuToggles.save(ctx, presetName, values.toMap()) }
    val toggles = remember(p) { RisuToggles.parse(p.s("customPromptTemplateToggle")) }
    val items = RisuPreset.items(p)
    val hasJb = items.any { it.s("type") == "jailbreak" } || (items.isEmpty() && p.s("jailbreak").isNotBlank())
    val hasCot = items.any { it.s("type") == "cot" }
    val tv = values.filterKeys { !it.startsWith("\u0000") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Sgu.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) }
                Column(Modifier.weight(1f)) {
                    Text("토글 미리보기", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(presetName, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                SectionLabel("토글")
                if (toggles.isEmpty() && !hasJb && !hasCot) Hint("이 프리셋에는 토글이 없어요.")
                ToggleList(toggles, values) { k, v -> set(k, v) }
                if (hasJb) CheckRow("탈옥 프롬프트 사용", "RisuAI 설정 쪽 스위치", values[JB_KEY] == "1") { set(JB_KEY, if (it) "1" else "0") }
                if (hasCot) CheckRow("생각의 사슬(CoT) 사용", "RisuAI 설정 쪽 스위치", values[COT_KEY] == "1") { set(COT_KEY, if (it) "1" else "0") }
                Hint("켠 상태는 이 앱에만 저장돼요. RisuAI의 토글 상태와는 따로예요.")

                SectionLabel("적용된 프롬프트")
                var hidden = 0
                fun show(text: String) = RisuToggles.render(text, tv).replace(Regex("\n{3,}"), "\n\n").trim()
                if (items.isEmpty()) {
                    listOf("mainPrompt" to "메인 프롬프트", "jailbreak" to "탈옥 프롬프트", "globalNote" to "글로벌 노트").forEach { (k, label) ->
                        if (p.s(k).isBlank()) return@forEach
                        if (k == "jailbreak" && values[JB_KEY] != "1") { hidden++; return@forEach }
                        val r = show(p.s(k))
                        if (r.isBlank()) hidden++ else PreviewCard(label, null, r)
                    }
                }
                items.forEach { it ->
                    val type = it.s("type")
                    val name = it.s("name").ifBlank { RisuPreset.typeLabel(it) }
                    when {
                        type == "jailbreak" && values[JB_KEY] != "1" -> hidden++
                        type == "cot" && values[COT_KEY] != "1" -> hidden++
                        type in RisuPreset.TEXT_TYPES -> {
                            val r = show(it.s("text"))
                            if (r.isBlank()) hidden++ else PreviewCard(name, RisuPreset.roleLabel(it.s("role").ifBlank { "system" }), r)
                        }
                        type in RisuPreset.FORMAT_TYPES && it.s("innerFormat").isNotBlank() -> {
                            val r = show(it.s("innerFormat"))
                            if (r.isBlank()) SlotLine(name) else PreviewCard("$name · 감싸는 글", null, r)
                        }
                        else -> SlotLine(name + if (type == "chat") " (${it.s("rangeStart")} ~ ${it.s("rangeEnd")})" else "")
                    }
                }
                if (hidden > 0) Hint("꺼진 토글 때문에 비게 된 블록 ${hidden}개는 숨겼어요.")
                Hint("{{char}}·{{user}} 같은 나머지 명령어는 그대로 보여줘요. 회색 줄은 캐릭터 설명·채팅처럼 RisuAI가 채워 넣는 자리예요.")
            }
        }
    }
}

@Composable
private fun ToggleList(list: List<RisuToggles.Toggle>, values: Map<String, String>, onSet: (String, String) -> Unit) {
    // RisuAI처럼 group … groupEnd 사이를 묶음으로
    val grouped = mutableListOf<Pair<RisuToggles.Toggle, MutableList<RisuToggles.Toggle>?>>()
    var open = false
    for (t in list) when {
        t.type == "group" -> { open = true; grouped.add(t to mutableListOf()) }
        t.type == "groupEnd" -> open = false
        open -> grouped.last().second!!.add(t)
        else -> grouped.add(t to null)
    }
    grouped.forEachIndexed { i, (t, children) ->
        if (children != null) {
            if (children.isEmpty()) return@forEachIndexed
            var expanded by remember(t.key, t.label) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Sgu.Sub)
            }
            if (expanded) Column(Modifier.padding(start = 12.dp)) { children.forEach { ToggleItem(it, values, onSet) } }
        } else {
            if (t.type == "divider" && i > 0 && grouped[i - 1].first.type == "divider" && grouped[i - 1].first.label == t.label) return@forEachIndexed
            ToggleItem(t, values, onSet)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToggleItem(t: RisuToggles.Toggle, values: Map<String, String>, onSet: (String, String) -> Unit) {
    when (t.type) {
        "caption" -> Hint(t.label)
        "divider" -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.label.isNotBlank()) { Text(t.label, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub); Spacer(Modifier.width(8.dp)) }
            HorizontalDivider(Modifier.weight(1f), color = Sgu.Line)
        }
        "select" -> Column(Modifier.padding(vertical = 6.dp, horizontal = 6.dp)) {
            Text(t.label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                t.options.forEachIndexed { i, o -> PillChip(o, values[t.key] == i.toString()) { onSet(t.key, i.toString()) } }
            }
        }
        "text", "textarea" -> Column(Modifier.padding(vertical = 6.dp, horizontal = 6.dp)) {
            Text(t.label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            InputBox(values[t.key] ?: "", { onSet(t.key, it) }, singleLine = t.type == "text")
        }
        else -> CheckRow(t.label, null, values[t.key] == "1") { onSet(t.key, if (it) "1" else "0") }
    }
}

@Composable
private fun CheckRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
        }
        SguSwitch(checked, onChange)
    }
}

@Composable
private fun PreviewCard(title: String, role: String?, text: String) {
    LineCard(Modifier.padding(vertical = 4.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (role != null) Badge(role, Sgu.Bubble, Sgu.Sub)
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, color = Sgu.Ink) }
        }
    }
}

@Composable
private fun SlotLine(label: String) {
    Text("· $label", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
}
