package space.sgu.next.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Search
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
    // 토글 찾기 대상: 블록 이름 → 글
    val sources = remember(p) {
        val legacy = if (items.isEmpty()) listOf("mainPrompt" to "메인 프롬프트", "jailbreak" to "탈옥 프롬프트", "globalNote" to "글로벌 노트")
            .filter { p.s(it.first).isNotBlank() }.map { it.second to p.s(it.first) } else emptyList()
        legacy + items.mapNotNull { x -> RisuPreset.textKey(x)?.let { k -> x.s(k).takeIf { it.isNotBlank() }?.let { (x.s("name").ifBlank { RisuPreset.typeLabel(x) }) to it } } }
    }
    val useCount = remember(sources, toggles) {
        toggles.associate { t -> t.key to sources.sumOf { RisuToggles.findUses(it.second, t.key, emptyMap()).size } }
    }
    var finding by remember { mutableStateOf<RisuToggles.Toggle?>(null) }

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
                ToggleList(toggles, values, { useCount[it] ?: 0 }, { finding = it }) { k, v -> set(k, v) }
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
                            if (r.isBlank()) hidden++ else PreviewCard(name, it.s("role").ifBlank { "system" }, r)
                        }
                        type in RisuPreset.FORMAT_TYPES && it.s("innerFormat").isNotBlank() -> {
                            val r = show(it.s("innerFormat"))
                            if (r.isBlank()) SlotLine(name) else PreviewCard("$name · 감싸는 글", null, r)
                        }
                        else -> SlotLine(name + if (type == "chat") " (${it.s("rangeStart")} ~ ${it.s("rangeEnd")})" else "")
                    }
                }
                if (hidden > 0) Hint("꺼진 토글 때문에 비게 된 블록 ${hidden}개는 숨겼어요.")
                Hint("역할별로 시스템은 흰 카드, 유저는 오른쪽 말풍선, 봇(프리필)은 주황빛 카드로 보여줘요. 탈옥 블록은 위 '탈옥 프롬프트 사용'을 켜야 나와요. {{char}}·{{user}} 같은 나머지 명령어는 그대로 보여줘요. 회색 줄은 캐릭터 설명·채팅처럼 RisuAI가 채워 넣는 자리예요.")
            }
        }
    }
    finding?.let { t -> ToggleFindDialog(t, sources, tv) { finding = null } }
}

@Composable
private fun ToggleList(list: List<RisuToggles.Toggle>, values: Map<String, String>, uses: (String) -> Int, onFind: (RisuToggles.Toggle) -> Unit, onSet: (String, String) -> Unit) {
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
            if (expanded) Column(Modifier.padding(start = 12.dp)) { children.forEach { ToggleItem(it, values, uses, onFind, onSet) } }
        } else {
            if (t.type == "divider" && i > 0 && grouped[i - 1].first.type == "divider" && grouped[i - 1].first.label == t.label) return@forEachIndexed
            ToggleItem(t, values, uses, onFind, onSet)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToggleItem(t: RisuToggles.Toggle, values: Map<String, String>, uses: (String) -> Int, onFind: (RisuToggles.Toggle) -> Unit, onSet: (String, String) -> Unit) {
    val n = uses(t.key)
    val findChip: @Composable () -> Unit = { FindChip(n) { onFind(t) } }
    val find: (@Composable () -> Unit)? = if (n > 0) findChip else null
    when (t.type) {
        "caption" -> Hint(t.label)
        "divider" -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.label.isNotBlank()) { Text(t.label, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub); Spacer(Modifier.width(8.dp)) }
            HorizontalDivider(Modifier.weight(1f), color = Sgu.Line)
        }
        "select" -> Column(Modifier.padding(vertical = 6.dp, horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(t.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); find?.invoke() }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                t.options.forEachIndexed { i, o -> PillChip(o, values[t.key] == i.toString()) { onSet(t.key, i.toString()) } }
            }
        }
        "text", "textarea" -> Column(Modifier.padding(vertical = 6.dp, horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(t.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); find?.invoke() }
            Spacer(Modifier.height(6.dp))
            InputBox(values[t.key] ?: "", { onSet(t.key, it) }, singleLine = t.type == "text")
        }
        else -> CheckRow(t.label, null, values[t.key] == "1", find) { onSet(t.key, if (it) "1" else "0") }
    }
}

@Composable
private fun CheckRow(label: String, sub: String?, checked: Boolean, find: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
        }
        if (find != null) { find(); Spacer(Modifier.width(8.dp)) }
        SguSwitch(checked, onChange)
    }
}

/** 역할별 모양: 시스템 = 흰 카드, 유저 = 오른쪽 회색 말풍선, 봇(어시스턴트) = 주황빛 카드 + ✦. */
@Composable
private fun PreviewCard(title: String, role: String?, text: String) {
    val body = @Composable { SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, color = Sgu.Ink) } }
    when (role) {
        "user" -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.End) {
            Text("유저 · $title", style = MaterialTheme.typography.labelMedium, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
            Box(Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(22.dp)).background(Sgu.Bubble).padding(horizontal = 16.dp, vertical = 12.dp)) { body() }
        }
        "bot", "assistant" -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Sgu.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("봇 · $title", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = Sgu.Accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Sgu.AccentSoft).padding(horizontal = 16.dp, vertical = 12.dp)) { body() }
        }
        else -> LineCard(Modifier.padding(vertical = 4.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (role != null) Badge(RisuPreset.roleLabel(role), Sgu.Bubble, Sgu.Sub)
                }
                Spacer(Modifier.height(6.dp))
                body()
            }
        }
    }
}

@Composable
private fun SlotLine(label: String) {
    Text("· $label", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
}

/** 토글 옆 "N곳" 버튼 */
@Composable
private fun FindChip(n: Int, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Sgu.Bubble).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, "쓰인 곳 찾기", tint = Sgu.Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text("${n}곳", style = MaterialTheme.typography.labelMedium, color = Sgu.Sub)
    }
}

/** 토글 찾기: 이 토글을 조건으로 감싼 구간을 블록별로. 지금 값으로 들어가는지/빠지는지 표시. */
@Composable
private fun ToggleFindDialog(t: RisuToggles.Toggle, sources: List<Pair<String, String>>, values: Map<String, String>, onDismiss: () -> Unit) {
    val found = remember(t, sources, values) { sources.map { (name, text) -> name to RisuToggles.findUses(text, t.key, values) }.filter { it.second.isNotEmpty() } }
    val total = found.sumOf { it.second.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Sgu.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) }
                Column(Modifier.weight(1f)) {
                    Text("‘${t.label}’ 쓰인 곳", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${found.size}개 블록 · ${total}곳 · 토글 키 ${t.key}", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                found.forEach { (name, uses) ->
                    SectionLabel(name)
                    uses.forEach { u ->
                        LineCard(Modifier.padding(vertical = 4.dp)) {
                            Column {
                                when (u.active) {
                                    true -> Badge("지금 들어감", Sgu.AccentSoft, Sgu.Accent)
                                    false -> Badge("지금 빠짐", Sgu.Bubble, Sgu.Sub)
                                    null -> {}
                                }
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(u.segment, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        color = if (u.active == false) Sgu.Sub else Sgu.Ink)
                                }
                            }
                        }
                    }
                }
                Hint("‘지금 들어감/빠짐’은 미리보기에서 켜 둔 토글 기준이에요. {{:else}}가 있는 구간은 빠질 때 else 뒤쪽이 들어가요.")
            }
        }
    }
}
