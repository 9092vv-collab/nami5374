package space.sgu.next.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.Fields
import space.sgu.next.data.Scripts

// =====================================================================================
// List parts shown inside the 캐릭터 / 스크립트 / 기타 tabs
// =====================================================================================

@Composable
private fun ListHeader(text: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.weight(1f))
        actions()
    }
}

/** 로어북: folders (tap to open/close) with their entries, then the loose entries — like the original. */
fun LazyListScope.loreItems(cid: String, data: JsonObject?, expanded: MutableState<Set<String>>, open: (String) -> Unit) {
    val list = Scripts.lore(data)
    val indexed = list.withIndex().toList()
    val folders = indexed.filter { Scripts.isFolder(it.value) }
    val folderKeys = folders.map { Scripts.folderKey(it.value) }.toSet()
    data class LoreRow(val index: Int, val e: JsonObject, val child: Boolean, val count: Int = 0)
    val rows = buildList {
        folders.forEach { f ->
            val key = Scripts.folderKey(f.value)
            val kids = indexed.filter { !Scripts.isFolder(it.value) && Scripts.str(it.value, "folder") == key }
            add(LoreRow(f.index, f.value, false, kids.size))
            if (key in expanded.value) kids.forEach { add(LoreRow(it.index, it.value, true)) }
        }
        indexed.filter { !Scripts.isFolder(it.value) && Scripts.str(it.value, "folder") !in folderKeys }.forEach { add(LoreRow(it.index, it.value, false)) }
    }
    item(key = "lore-head") {
        ListHeader("로어북 ${list.count { !Scripts.isFolder(it) }}개") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PillChip("항목", selected = false, icon = Icons.Outlined.Add) {
                    val i = list.size
                    CharacterStore.updateData(cid) { Scripts.addLore(it, Scripts.newLoreEntry()).first }
                    open("lore/$cid/$i/1")
                }
                PillChip("폴더", selected = false, icon = Icons.Outlined.CreateNewFolder) {
                    val i = list.size
                    CharacterStore.updateData(cid) { Scripts.addLore(it, Scripts.newLoreFolder()).first }
                    open("lore/$cid/$i/1")
                }
            }
        }
    }
    if (rows.isEmpty()) item(key = "lore-empty") { LineCard { Text("항목이 없어요", color = Sgu.Sub) } }
    items(rows.size, key = { "lore-${rows[it].index}" }) { n ->
        val r = rows[n]
        val always = Scripts.bool(r.e, "constant")
        if (Scripts.isFolder(r.e)) {
            val key = Scripts.folderKey(r.e)
            val isOpen = key in expanded.value
            GroupRow(
                n, rows.size, Scripts.loreName(r.e).ifBlank { "(이름 없는 폴더)" },
                subtitle = "${r.count}개" + (if (always) " · 항상 활성화" else "") + (if (!isOpen) " · 접힘" else ""),
                icon = if (isOpen) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                onClick = { expanded.value = if (isOpen) expanded.value - key else expanded.value + key },
                trailing = { IconButton(onClick = { open("lore/$cid/${r.index}/0") }) { Icon(Icons.Outlined.Edit, "폴더 편집", tint = Sgu.Sub) } },
            )
        } else {
            val keys = Scripts.keysText(r.e)
            GroupRow(
                n, rows.size, Scripts.loreName(r.e).ifBlank { "(이름 없음)" },
                modifier = if (r.child) Modifier.padding(start = 20.dp) else Modifier,
                subtitle = "${if (always) "항상 활성화" else keys.ifBlank { "키 없음" }} · ${Scripts.str(r.e, "content").length}자",
                icon = Icons.AutoMirrored.Outlined.Notes,
                onClick = { open("lore/$cid/${r.index}/0") },
            )
        }
    }
}

/** 정규식 스크립트 list (original 1670 O). */
fun LazyListScope.regexItems(cid: String, data: JsonObject?, open: (String) -> Unit) {
    val list = Scripts.objects(data, Scripts.REGEX_PATH)
    item(key = "rx-head") {
        ListHeader("정규식 스크립트 ${list.size}개") {
            PillChip("정규식 추가", selected = false, icon = Icons.Outlined.Add) {
                val i = list.size
                CharacterStore.updateData(cid) { Scripts.append(it, Scripts.REGEX_PATH, Scripts.newRegex()).first }
                open("regex/$cid/$i/1")
            }
        }
    }
    if (list.isEmpty()) item(key = "rx-empty") { LineCard { Text("정규식이 없어요", color = Sgu.Sub) } }
    items(list.size, key = { "rx-$it" }) { i ->
        val r = list[i]
        val off = Scripts.bool(r, "disabled")
        GroupRow(
            i, list.size, Scripts.str(r, "comment").ifBlank { Scripts.str(r, "in") }.ifBlank { "(이름 없음)" },
            modifier = Modifier.alpha(if (off) 0.5f else 1f),
            subtitle = (if (off) "비활성화됨 · " else "") + "${Scripts.regexTypeLabel(Scripts.str(r, "type"))} · /${Scripts.str(r, "in")}/${Scripts.str(r, "flag")}",
            icon = if (off) Icons.Outlined.Block else Icons.Outlined.Code,
            onClick = { open("regex/$cid/$i/0") },
        )
    }
}

/** 트리거 스크립트: Lua (기본) or V2 (JSON); only one at a time, like the original. */
@Composable
fun TriggerCard(cid: String, data: JsonObject?, openField: (String) -> Unit) {
    val t = Scripts.trigger(data)
    var switchTo by remember { mutableStateOf<Scripts.TriggerMode?>(null) }
    LineCard {
        Column {
            Text("트리거 스크립트", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Scripts.TriggerMode.Lua to "Lua (기본)", Scripts.TriggerMode.V2 to "V2 (JSON)").forEach { (m, label) ->
                    PillChip(label, selected = t.mode == m) {
                        if (m != t.mode) {
                            if (t.code.isBlank()) CharacterStore.updateData(cid) { Scripts.setTrigger(it, Scripts.Trigger(m, "")) }
                            else switchTo = m
                        }
                    }
                }
            }
            Text("Lua와 V2는 동시에 사용할 수 없습니다. 하나를 고르면 다른 쪽 코드는 비워집니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            Spacer(Modifier.height(10.dp))
            LineCard(onClick = { openField("trig:code") }) {
                if (t.code.isBlank()) Text(if (t.mode == Scripts.TriggerMode.Lua) "-- RisuAI Lua 트리거 코드 · 눌러서 작성" else "V2 트리거 JSON · 눌러서 작성", color = Sgu.Sub.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                else Text(t.code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (t.code.isNotBlank()) Text("${t.code.lines().size}줄", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }
    switchTo?.let { m ->
        val target = if (m == Scripts.TriggerMode.V2) "V2" else "Lua"
        val current = if (m == Scripts.TriggerMode.V2) "Lua" else "V2"
        ConfirmDialog("${target}로 전환", "Lua와 V2는 동시에 사용할 수 없습니다. 전환하면 현재 $current 코드가 삭제됩니다.", "전환",
            onConfirm = { CharacterStore.updateData(cid) { Scripts.setTrigger(it, Scripts.Trigger(m, "")) } },
            onDismiss = { switchTo = null })
    }
}

@Composable
fun OnboardingCard(cid: String, data: JsonObject?, open: (String) -> Unit) {
    val ob = Scripts.onboarding(data)
    val qs = (ob["questions"] as? JsonArray)?.size ?: 0
    val html = Scripts.str(ob, "mode") == "html"
    LineCard(onClick = { open("onboarding/$cid") }) {
        Column {
            Text("온보딩", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Spacer(Modifier.height(4.dp))
            Text(
                if (!Scripts.hasOnboarding(data)) "없음 · 눌러서 설정"
                else (if (Scripts.bool(ob, "enabled")) "사용 중" else "꺼짐") + " · " + (if (html) "HTML" else "질문 폼 · 질문 ${qs}개"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (Scripts.hasOnboarding(data)) Sgu.Ink else Sgu.Sub.copy(alpha = 0.7f),
            )
            Scripts.str(ob, "title").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun BiasCard(cid: String, data: JsonObject?, open: (String) -> Unit) {
    val rows = Scripts.bias(data)
    LineCard(onClick = { open("bias/$cid") }) {
        Column {
            Text("Bias ${rows.size}개", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Spacer(Modifier.height(4.dp))
            if (rows.isEmpty()) Text("없음 · 눌러서 추가", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
            rows.take(5).forEach { Text("${it.text} → ${fmtNum(it.value)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (rows.size > 5) Text("외 ${rows.size - 5}개", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
        }
    }
}

private fun fmtNum(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

// =====================================================================================
// Shared bits for the edit screens
// =====================================================================================

@Composable
private fun ToggleRow(label: String, value: Boolean, hint: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
        }
        Spacer(Modifier.width(8.dp))
        SguSwitch(value, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> PillChip(label, selected = v == selected) { onPick(v) } }
    }
}

/** A preview of long text; tapping opens the full-screen editor. */
@Composable
private fun LongTextCard(text: String, empty: String, mono: Boolean = false, onClick: () -> Unit) {
    LineCard(onClick = onClick) {
        if (text.isBlank()) Text(empty, style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
        else Text(text, style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it }, maxLines = 8, overflow = TextOverflow.Ellipsis)
    }
}

private fun charName(cid: String) = CharacterStore.items.firstOrNull { it.id == cid }?.name

// =====================================================================================
// 정규식 편집
// =====================================================================================

@Composable
fun RegexScreen(cid: String, index: Int, isNew: Boolean, onBack: () -> Unit) {
    val initial = remember(cid, index) { Scripts.objects(CharacterStore.readCard(cid)["data"] as? JsonObject, Scripts.REGEX_PATH).getOrNull(index) }
    if (initial == null) { Page("정규식", charName(cid), onBack) { EmptyPane("이 정규식을 찾을 수 없어요") }; return }
    var comment by remember { mutableStateOf(Scripts.str(initial, "comment")) }
    var inp by remember { mutableStateOf(Scripts.str(initial, "in")) }
    var out by remember { mutableStateOf(Scripts.str(initial, "out")) }
    var type by remember { mutableStateOf(Scripts.str(initial, "type").takeIf { t -> Scripts.REGEX_TYPES.any { it.first == t } } ?: "editdisplay") }
    var flag by remember { mutableStateOf(if (initial.containsKey("flag")) Scripts.str(initial, "flag") else "g") }
    var ableFlag by remember { mutableStateOf(Scripts.bool(initial, "ableFlag")) }
    var disabled by remember { mutableStateOf(Scripts.bool(initial, "disabled")) }
    var deleted by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val built by rememberUpdatedState(Scripts.regex(initial, comment, inp, out, type, flag, ableFlag, disabled))
    val gone by rememberUpdatedState(deleted)

    DisposableEffect(cid, index) {
        onDispose {
            if (gone || CharacterStore.items.none { it.id == cid }) return@onDispose
            val item = built
            when {
                isNew && Scripts.isBlankRegex(item) -> CharacterStore.updateData(cid) { Scripts.setListAt(it, Scripts.REGEX_PATH, index, null) }
                item != initial -> CharacterStore.updateData(cid) { Scripts.setListAt(it, Scripts.REGEX_PATH, index, item) }
            }
        }
    }

    Page("정규식", charName(cid), onBack, actions = {
        IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "삭제") }
    }) {
        FieldLabel("이름")
        InputBox(comment, { comment = it }, placeholder = "정규식 이름")
        FieldLabel("종류")
        ChoiceRow(Scripts.REGEX_TYPES, type) { type = it }
        FieldLabel("IN (정규식)")
        InputBox(inp, { inp = it }, singleLine = false)
        FieldLabel("OUT (치환)")
        InputBox(out, { out = it }, singleLine = false)
        ToggleRow("플래그 사용", ableFlag) { ableFlag = it }
        if (ableFlag) {
            FieldLabel("플래그")
            InputBox(flag, { flag = it }, placeholder = "g")
        }
        ToggleRow("비활성화됨", disabled, "켜 두면 스크립트는 저장되지만 적용되지 않습니다.") { disabled = it }
    }
    if (confirm) ConfirmDialog("정규식 삭제", "이 정규식을 지울까요?", "삭제", onConfirm = {
        deleted = true
        CharacterStore.updateData(cid) { Scripts.setListAt(it, Scripts.REGEX_PATH, index, null) }
        onBack()
    }, onDismiss = { confirm = false })
}

// =====================================================================================
// 로어북 항목 / 폴더 편집
// =====================================================================================

@Composable
fun LoreScreen(cid: String, index: Int, isNew: Boolean, openField: (String) -> Unit, onBack: () -> Unit) {
    // Content is edited in the full-screen editor, so it is always read live from the store.
    val live = remember(cid, CharacterStore.version) { Scripts.lore(CharacterStore.readCard(cid)["data"] as? JsonObject) }
    val initial = remember(cid, index) { Scripts.lore(CharacterStore.readCard(cid)["data"] as? JsonObject).getOrNull(index) }
    if (initial == null) { Page("로어북", charName(cid), onBack) { EmptyPane("이 항목을 찾을 수 없어요") }; return }
    val folderMode = Scripts.isFolder(initial)
    var name by remember { mutableStateOf(Scripts.loreName(initial)) }
    var keys by remember { mutableStateOf(Scripts.keysText(initial)) }
    var second by remember { mutableStateOf(Scripts.keysText(initial, "secondary_keys")) }
    var folder by remember { mutableStateOf(Scripts.str(initial, "folder").ifBlank { null }) }
    var always by remember { mutableStateOf(Scripts.bool(initial, "constant")) }
    var selective by remember { mutableStateOf(Scripts.bool(initial, "selective")) }
    var useRegex by remember { mutableStateOf(Scripts.bool(initial, "use_regex")) }
    var caseSens by remember { mutableStateOf(Scripts.caseSensitive(initial)) }
    var order by remember { mutableStateOf(Scripts.insertOrder(initial).toString()) }
    var deleted by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val save by rememberUpdatedState {
        CharacterStore.updateData(cid) { d ->
            val cur = Scripts.lore(d).getOrNull(index) ?: return@updateData d
            val e = Scripts.loreEntry(cur, name, keys, second, folder, always, selective, useRegex, caseSens, order.trim().toIntOrNull() ?: 100)
            when {
                isNew && Scripts.isBlankLore(e) -> Scripts.removeLore(d, index)
                e != cur -> Scripts.saveLore(d, index, e)
                else -> d
            }
        }
    }
    val gone by rememberUpdatedState(deleted)
    DisposableEffect(cid, index) {
        onDispose { if (!gone && CharacterStore.items.any { it.id == cid }) save() }
    }

    val folders = live.filter { Scripts.isFolder(it) }
    val content = Scripts.str(live.getOrNull(index), "content")
    Page(if (folderMode) "로어북 폴더" else "로어북 항목", charName(cid), onBack, actions = {
        IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "삭제") }
    }) {
        FieldLabel("이름")
        InputBox(name, { name = it }, placeholder = if (folderMode) "폴더 이름" else "항목 이름")
        if (folderMode) {
            ToggleRow("항상 활성화", always, "켜면 폴더 안의 모든 로어북이 항상 활성화되고, 끄면 모두 꺼집니다.") { always = it }
            val kids = live.filter { !Scripts.isFolder(it) && Scripts.str(it, "folder") == Scripts.folderKey(initial) }
            Hint("이 폴더 안의 항목 ${kids.size}개")
        } else {
            FieldLabel("활성화 키 (쉼표 구분)")
            InputBox(keys, { keys = it }, singleLine = false)
            FieldLabel("내용")
            LongTextCard(content, "비어 있음 · 눌러서 작성") { openField("lore:$index:content") }
            if (content.isNotEmpty()) Hint("${content.length}자")
            if (folders.isNotEmpty()) {
                FieldLabel("배치할 폴더")
                ChoiceRow(listOf("" to "없음") + folders.map { Scripts.folderKey(it) to Scripts.loreName(it).ifBlank { "(이름 없음)" } }, folder ?: "") {
                    folder = it.ifBlank { null }
                }
            }
            ToggleRow("항상 활성화", always) { always = it }
            ToggleRow("2차 키 사용 (selective)", selective) { selective = it }
            if (selective) {
                FieldLabel("2차 키 (쉼표 구분)")
                InputBox(second, { second = it }, singleLine = false)
            }
            ToggleRow("정규식 키", useRegex) { useRegex = it }
            ToggleRow("대소문자 구분", caseSens) { caseSens = it }
            FieldLabel("삽입 순서")
            InputBox(order, { v -> order = v.filter { it.isDigit() || it == '-' } }, placeholder = "100", number = true)
        }
    }
    if (confirm) ConfirmDialog(
        if (folderMode) "폴더 삭제" else "항목 삭제",
        if (folderMode) "폴더를 지울까요? 안에 있던 항목은 폴더 밖으로 나와요." else "이 로어북 항목을 지울까요?",
        "삭제",
        onConfirm = { deleted = true; CharacterStore.updateData(cid) { Scripts.removeLore(it, index) }; onBack() },
        onDismiss = { confirm = false },
    )
}

// =====================================================================================
// Bias
// =====================================================================================

private class BiasEdit(val id: Long, text: String, value: String) {
    var text by mutableStateOf(text)
    var value by mutableStateOf(value)
}

@Composable
fun BiasScreen(cid: String, onBack: () -> Unit) {
    val initial = remember(cid) { Scripts.bias(CharacterStore.readCard(cid)["data"] as? JsonObject) }
    var seq by remember { mutableStateOf(initial.size.toLong() + 1000) }
    val rows = remember(cid) { mutableStateListOf<BiasEdit>().apply { initial.forEachIndexed { i, r -> add(BiasEdit(i.toLong(), r.text, fmtNum(r.value))) } } }
    val current by rememberUpdatedState(rows.map { Scripts.BiasRow(it.text, it.value.trim().toDoubleOrNull() ?: 0.0) })
    DisposableEffect(cid) {
        onDispose {
            val now = current
            if (now != initial && CharacterStore.items.any { it.id == cid }) CharacterStore.updateData(cid) { Scripts.setBias(it, now) }
        }
    }
    Page("Bias", charName(cid), onBack) {
        Hint("특정 텍스트(토큰)가 나올 확률을 올리거나(+) 내려요(-).")
        Spacer(Modifier.height(8.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                InputBox(r.text, { r.text = it }, placeholder = "텍스트", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                InputBox(r.value, { v -> r.value = v.filter { it.isDigit() || it == '-' || it == '.' } }, placeholder = "값", number = true, modifier = Modifier.width(96.dp))
                IconButton(onClick = { rows.remove(r) }) { Icon(Icons.Outlined.Close, "삭제", tint = Sgu.Sub) }
            }
        }
        Spacer(Modifier.height(14.dp))
        LineButton("Bias 추가", onClick = { rows.add(BiasEdit(seq++, "", "0")) }, icon = Icons.Outlined.Add)
    }
}

// =====================================================================================
// 온보딩
// =====================================================================================

@Composable
fun OnboardingScreen(cid: String, openField: (String) -> Unit, onBack: () -> Unit) {
    val existed = remember(cid) { Scripts.hasOnboarding(CharacterStore.readCard(cid)["data"] as? JsonObject) }
    val initial = remember(cid) { Scripts.onboarding(CharacterStore.readCard(cid)["data"] as? JsonObject) }
    val liveHtml = remember(cid, CharacterStore.version) { Scripts.str(Scripts.onboarding(CharacterStore.readCard(cid)["data"] as? JsonObject), "html") }
    var ob by remember { mutableStateOf(initial) }
    val latest by rememberUpdatedState(ob)
    DisposableEffect(cid) {
        onDispose {
            if (CharacterStore.items.none { it.id == cid }) return@onDispose
            val now = latest
            // html is edited in the full-screen editor and saved there; never overwrite it with an old copy.
            if (now - "html" != initial - "html") CharacterStore.updateData(cid) { d ->
                val html = Scripts.onboarding(d)["html"] ?: JsonPrimitive("")
                Fields.set(d, Scripts.ONBOARDING_PATH, JsonObject(now + ("html" to html)))
            }
        }
    }
    fun put(k: String, v: kotlinx.serialization.json.JsonElement) { ob = JsonObject(ob + (k to v)) }
    val questions = ((ob["questions"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
    fun setQuestions(list: List<JsonObject>) = put("questions", JsonArray(list))

    Page("온보딩", charName(cid), onBack) {
        if (!existed) Hint("저장하면 이 캐릭터에 온보딩 설정이 생겨요.")
        ToggleRow("온보딩 사용", Scripts.bool(ob, "enabled")) { put("enabled", JsonPrimitive(it)) }
        FieldLabel("방식")
        ChoiceRow(listOf("form" to "질문 폼", "html" to "HTML"), if (Scripts.str(ob, "mode") == "html") "html" else "form") { put("mode", JsonPrimitive(it)) }
        FieldLabel("제목")
        InputBox(Scripts.str(ob, "title"), { put("title", JsonPrimitive(it)) })
        FieldLabel("설명")
        InputBox(Scripts.str(ob, "description"), { put("description", JsonPrimitive(it)) }, singleLine = false)
        if (Scripts.str(ob, "mode") == "html") {
            FieldLabel("HTML")
            LongTextCard(liveHtml, "비어 있음 · 눌러서 작성", mono = true) { openField("onb:html") }
        } else {
            questions.forEachIndexed { i, q ->
                Spacer(Modifier.height(14.dp))
                QuestionCard(i, q, onChange = { nq -> setQuestions(questions.toMutableList().also { it[i] = nq }) },
                    onRemove = { setQuestions(questions.filterIndexed { j, _ -> j != i }) })
            }
            Spacer(Modifier.height(14.dp))
            LineButton("질문 추가", onClick = { setQuestions(questions + Scripts.newQuestion(questions.size + 1)) }, icon = Icons.Outlined.Add)
        }
        ToggleRow("플로팅 버튼", Scripts.bool(ob, "floatingButton")) { put("floatingButton", JsonPrimitive(it)) }
    }
}

@Composable
private fun QuestionCard(i: Int, q: JsonObject, onChange: (JsonObject) -> Unit, onRemove: () -> Unit) {
    val qid = Scripts.str(q, "id").ifBlank { "q$i" }
    fun put(k: String, v: kotlinx.serialization.json.JsonElement) = onChange(JsonObject(q + (k to v)))
    var optText by remember(qid) { mutableStateOf(Scripts.optionsText(q)) }
    val type = Scripts.str(q, "type").ifBlank { "single" }
    LineCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("질문 ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "질문 삭제", tint = Sgu.Sub) }
            }
            FieldLabel("제목")
            InputBox(Scripts.str(q, "title"), { put("title", JsonPrimitive(it)) })
            FieldLabel("변수 이름")
            InputBox(Scripts.str(q, "varName"), { put("varName", JsonPrimitive(it)) })
            Spacer(Modifier.height(10.dp))
            ChoiceRow(listOf("single" to "단일 선택", "multiple" to "복수 선택", "text" to "텍스트"), type) { put("type", JsonPrimitive(it)) }
            if (type != "text") {
                FieldLabel("선택지 (쉼표 구분)")
                InputBox(optText, { t -> optText = t; onChange(Scripts.withOptions(q, t)) }, singleLine = false)
            } else {
                FieldLabel("플레이스홀더")
                InputBox(Scripts.str(q, "placeholder"), { put("placeholder", JsonPrimitive(it)) })
            }
            ToggleRow("필수", Scripts.bool(q, "required")) { put("required", JsonPrimitive(it)) }
        }
    }
}
