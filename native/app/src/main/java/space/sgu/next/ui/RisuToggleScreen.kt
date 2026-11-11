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
import androidx.compose.material.icons.outlined.Translate
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
    // 토글 찾기 대상: 블록 이름 · 글 · 위치(ref: 0 이상 = 템플릿 순번, 음수 = 옛 형식 칸)
    val legacyKeys = listOf("mainPrompt", "jailbreak", "globalNote")
    val sources = remember(p) {
        val legacy = if (items.isEmpty()) listOf("메인 프롬프트", "탈옥 프롬프트", "글로벌 노트")
            .mapIndexedNotNull { i, label -> p.s(legacyKeys[i]).takeIf { it.isNotBlank() }?.let { FindSrc(label, it, -1 - i) } } else emptyList()
        legacy + items.mapIndexedNotNull { i, x -> RisuPreset.textKey(x)?.let { k -> x.s(k).takeIf { it.isNotBlank() }?.let { FindSrc(x.s("name").ifBlank { RisuPreset.typeLabel(x) }, it, i) } } }
    }
    val useCount = remember(sources, toggles) {
        toggles.associate { t -> t.key to sources.sumOf { RisuToggles.findUses(it.text, t.key, emptyMap()).size } }
    }
    // 구간 고치기/지우기: 프리셋의 해당 글에서 [start, end)를 바꿔 넣음
    fun replaceIn(src: FindSrc, u: RisuToggles.Use, new: String?) {
        if (u.end > src.text.length || src.text.substring(u.start, u.end) != u.segment) return
        var st = u.start; var en = u.end
        if (new == null && en < src.text.length && src.text[en] == '\n' && (st == 0 || src.text[st - 1] == '\n')) en++ // 혼자 차지하던 줄도 같이 지움
        val out = src.text.substring(0, st) + (new ?: "") + src.text.substring(en)
        if (src.ref < 0) RisuPresetEditor.set(JsonObject(p + (legacyKeys[-1 - src.ref] to JsonPrimitive(out))))
        else {
            val item = items.getOrNull(src.ref) ?: return
            val key = RisuPreset.textKey(item) ?: return
            RisuPresetEditor.setItems(items.toMutableList().apply { this[src.ref] = JsonObject(item + (key to JsonPrimitive(out))) })
        }
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
                KoToggle(Modifier.padding(bottom = 6.dp))
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
    finding?.let { t -> ToggleFindDialog(t, sources, tv, { s, u, n -> replaceIn(s, u, n) }) { finding = null } }
}

@Composable
internal fun ToggleList(list: List<RisuToggles.Toggle>, values: Map<String, String>, uses: (String) -> Int, onFind: (RisuToggles.Toggle) -> Unit, onSet: (String, String) -> Unit) {
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
    val shown = koText(text)
    val body = @Composable { SelectionContainer { Text(shown, style = MaterialTheme.typography.bodySmall, color = Sgu.Ink) } }
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

class FindSrc(val name: String, val text: String, val ref: Int)

/**
 * 토글 찾기: 이 토글을 조건으로 감싼 구간을 블록별로. 맨 위 칩으로 선택지(0, 1, 2…)를 고르면
 * 그 선택지일 때 들어가는 구간만 보여줌. 구간마다 수정·삭제.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToggleFindDialog(t: RisuToggles.Toggle, sources: List<FindSrc>, values: Map<String, String>,
                             onReplace: (FindSrc, RisuToggles.Use, String?) -> Unit, onDismiss: () -> Unit) {
    val options = when (t.type) { "select" -> t.options; "check" -> listOf("꺼짐", "켜짐"); else -> emptyList() }
    var pick by remember(t.key) { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Pair<FindSrc, RisuToggles.Use>?>(null) }
    var removing by remember { mutableStateOf<Pair<FindSrc, RisuToggles.Use>?>(null) }
    val found = remember(t, sources, values, pick) {
        val v = pick?.let { values + (t.key to it.toString()) } ?: values
        sources.map { src -> src to RisuToggles.findUses(src.text, t.key, v).filter { pick == null || it.active == true } }.filter { it.second.isNotEmpty() }
    }
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
                if (options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        PillChip("전체", pick == null) { pick = null }
                        options.forEachIndexed { i, o -> PillChip("$i · $o", pick == i) { pick = i } }
                    }
                }
                if (space.sgu.next.data.KoTranslate.status.isNotEmpty()) Hint(space.sgu.next.data.KoTranslate.status)
                if (found.isEmpty()) Hint(if (pick != null) "이 선택지일 때 들어가는 구간이 없어요." else "이 토글로 감싼 구간이 없어요.")
                found.forEach { (src, uses) ->
                    SectionLabel(src.name)
                    uses.forEach { u ->
                        var ko by remember(src.ref, u.start, u.segment) { mutableStateOf(false) }
                        LineCard(Modifier.padding(vertical = 4.dp)) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when {
                                        pick != null -> Badge("${pick} · ${options.getOrElse(pick!!) { "" }}일 때 들어감", Sgu.AccentSoft, Sgu.Accent)
                                        u.active == true -> Badge("지금 들어감", Sgu.AccentSoft, Sgu.Accent)
                                        u.active == false -> Badge("지금 빠짐", Sgu.Bubble, Sgu.Sub)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { ko = !ko }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Translate, "한글로 보기", tint = if (ko) Sgu.Accent else Sgu.Sub, modifier = Modifier.size(19.dp)) }
                                    IconButton(onClick = { editing = src to u }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Edit, "수정", tint = Sgu.Sub, modifier = Modifier.size(19.dp)) }
                                    IconButton(onClick = { removing = src to u }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, "삭제", tint = Sgu.Sub, modifier = Modifier.size(19.dp)) }
                                }
                                Spacer(Modifier.height(6.dp))
                                SelectionContainer {
                                    Text(koText(u.segment, ko), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        color = if (pick == null && u.active == false) Sgu.Sub else Sgu.Ink)
                                }
                            }
                        }
                    }
                }
                Hint("칩의 숫자는 RisuAI가 토글 값으로 쓰는 번호예요(0 = 첫 번째). 선택지를 고르면 다른 토글은 미리보기에서 켜 둔 그대로 두고 계산해요. 수정·삭제는 프리셋에 바로 반영되고, 편집기에서 .risup으로 저장해야 파일에 남아요.")
            }
        }
    }
    editing?.let { (src, u) ->
        TextEditDialog("${src.name} · 구간 수정", u.segment, onDismiss = { editing = null }) { v -> onReplace(src, u, v); editing = null }
    }
    removing?.let { (src, u) ->
        ConfirmDialog("구간 삭제", "'${src.name}'에서 이 구간을 조건문({{#…}} ~ {{/…}})째로 지웁니다.", "삭제",
            onConfirm = { onReplace(src, u, null) }, onDismiss = { removing = null })
    }
}

/** 작업실 프리셋용 토글 설정: 여기서 고른 값이 작업실 프롬프트에 실제로 적용됨. */
@Composable
fun WorkshopToggleDialog(toggles: List<RisuToggles.Toggle>, values: Map<String, String>, onSet: (String, String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Sgu.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) }
                Text("토글 설정", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Hint("여기서 고른 대로 작업실 프롬프트가 만들어져요. 고르는 즉시 적용되고, 원본 RisuAI 파일은 바뀌지 않아요.")
                if (toggles.isEmpty()) Hint("이 프리셋에는 토글이 없어요.")
                ToggleList(toggles, values, { 0 }, {}) { k, v -> onSet(k, v) }
            }
        }
    }
}
