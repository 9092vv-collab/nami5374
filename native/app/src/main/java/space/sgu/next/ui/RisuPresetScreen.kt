package space.sgu.next.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.RisuPreset
import space.sgu.next.data.s

/** The preset open in the RisuAI preset editor (kept while the app runs). */
object RisuPresetEditor {
    var preset by mutableStateOf<JsonObject?>(null)
    var fileName by mutableStateOf("")
    var dirty by mutableStateOf(false)
    /** 연 원본 파일 (맨 위 '저장'이 여기에 덮어씀) */
    var uri by mutableStateOf<android.net.Uri?>(null)
    var sourceName by mutableStateOf("")
    fun set(p: JsonObject) { preset = p; dirty = true }
    fun setItems(list: List<JsonObject>) { preset?.let { set(JsonObject(it + ("promptTemplate" to JsonArray(list)))) } }
}

/** RisuAI 프리셋을 SGU 작업실 프리셋으로 추가. Returns a short result message. */
fun importRisuToSgu(p: JsonObject, uri: android.net.Uri? = null, fileName: String = ""): String {
    val (blocks, slots) = RisuPreset.toSguBlocks(p)
    val base = "Risu · " + p.s("name").ifBlank { "가져온 프리셋" }
    // 같은 이름이 이미 있으면 (2), (3)… 을 붙여 구분
    val taken = space.sgu.next.data.PromptBlocks.presets().map { it.s("name") }.toSet()
    val name = if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
    val linked = uri != null && RisuPreset.items(p).isNotEmpty()
    addPreset(name, blocks, (if (linked) mapOf("risuUri" to JsonPrimitive(uri.toString()), "risuFile" to JsonPrimitive(fileName)) else emptyMap()) +
        mapOf("risuToggles" to JsonPrimitive(p.s("customPromptTemplateToggle")), "risuName" to JsonPrimitive(p.s("name"))))
    val texts = blocks.count { it.s("type") == "text" && (it["risuItem"] != null || it.s("name") in setOf("메인 프롬프트", "탈옥 프롬프트", "글로벌 노트")) }
    return "'$name' 프리셋을 만들었어요. RisuAI 텍스트 블록 ${texts}개" + (if (slots > 0) ", RisuAI 전용 칸 ${slots}개(작업실 프롬프트에는 안 들어가요)" else "") + "를 넣었어요." +
        if (linked) " 블록을 고치면 원본 파일($fileName)에도 저장돼요." else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RisuPresetScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = RisuPresetEditor.preset
    var editing by remember { mutableStateOf<Int?>(null) }
    var removing by remember { mutableStateOf<Int?>(null) }
    var msg by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var retyping by remember { mutableStateOf<Int?>(null) }
    val saveRisup = rememberSaver("application/octet-stream")
    val saveJson = rememberSaver("application/json")
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        runCatching { RisuPreset.read(ctx, u) }
            .onSuccess {
                RisuPresetEditor.preset = it; RisuPresetEditor.fileName = it.s("name"); RisuPresetEditor.dirty = false; msg = ""; saveMsg = null to ""
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                RisuPresetEditor.uri = u; RisuPresetEditor.sourceName = RisuPreset.nameOf(ctx, u)
            }
            .onFailure { msg = it.message ?: "열지 못했어요" }
    }
    val baseName = (p?.s("name") ?: "preset").replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifBlank { "preset" }

    Page("RisuAI 프리셋 편집기", p?.let { it.s("name").ifBlank { "이름 없는 프리셋" } + if (RisuPresetEditor.dirty) " · 수정됨" else "" } ?: ".risup · .risupreset · .json", onBack) {
        if (p == null) {
            Spacer(Modifier.height(12.dp))
            InkButton("RisuAI 프리셋 열기", { opener.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.FileOpen)
            Hint("RisuAI에서 내보낸 프롬프트 프리셋(.risup, .risupreset, .json)을 열어서 보고 고친 뒤, 다시 .risup으로 저장해 RisuAI에 넣을 수 있어요. SGU 작업실 프리셋으로 가져올 수도 있어요.")
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Page
        }
        val src = RisuPresetEditor.uri
        if (src != null) {
            Spacer(Modifier.height(8.dp))
            InkButton(if (RisuPresetEditor.dirty) "저장 · 원본 파일에 덮어쓰기" else "저장됨", enabled = RisuPresetEditor.dirty, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Save, onClick = {
                val name = RisuPresetEditor.sourceName
                scope.launch {
                    runCatching {
                        val bytes = RisuPreset.encodeFor(name, p)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val r = ctx.contentResolver
                            val out = runCatching { r.openOutputStream(src, "wt") }.getOrNull() ?: r.openOutputStream(src, "w")
                            (out ?: error("파일을 열 수 없어요")).use { it.write(bytes) }
                        }
                    }.onSuccess { RisuPresetEditor.dirty = false; saveMsg = true to "'$name'에 덮어썼어요." }
                        .onFailure { saveMsg = false to "덮어쓰지 못했어요 (${it.message}). 아래 '.risup으로 저장'으로 새 파일에 저장해 주세요." }
                }
            })
            if (saveMsg.second.isNotEmpty()) Text(saveMsg.second, style = MaterialTheme.typography.bodySmall,
                color = if (saveMsg.first == false) MaterialTheme.colorScheme.error else Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            else Hint("원본: ${RisuPresetEditor.sourceName}")
        }
        FieldLabel("프리셋 이름")
        InputBox(p.s("name"), { RisuPresetEditor.set(JsonObject(p + ("name" to JsonPrimitive(it)))) })
        val models = listOf(p.s("aiModel"), p.s("subModel")).filter { it.isNotBlank() }
        if (models.isNotEmpty()) Hint("RisuAI 모델 설정: ${models.joinToString(" / ")} (SGU에서는 쓰지 않고 그대로 보관만 해요)")
        Spacer(Modifier.height(12.dp))
        val toggleCount = remember(p) { space.sgu.next.data.RisuToggles.parse(p.s("customPromptTemplateToggle")).count { it.type in setOf("check", "select", "text", "textarea") } }
        LineButton(if (toggleCount > 0) "토글 미리보기 · 토글 ${toggleCount}개" else "적용된 프롬프트 미리보기", onClick = { previewing = true },
            modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Visibility)

        // RisuAI '프롬프트' 탭의 메인 프롬프트 · 탈옥 프롬프트 · 글로벌 노트 (비어 있어도 항상 보이고 고칠 수 있음)
        SectionLabel("메인 · 탈옥 · 글로벌 노트")
        if (RisuPreset.items(p).isNotEmpty()) Hint("이 프리셋엔 프롬프트 템플릿이 있어서, RisuAI는 아래 템플릿을 쓰고 이 세 칸은 쓰지 않아요. 파일에는 그대로 저장돼요.")
        listOf("mainPrompt" to "메인 프롬프트", "jailbreak" to "탈옥 프롬프트", "globalNote" to "글로벌 노트").forEach { (k, label) ->
            FieldLabel(label)
            PromptCard(label, p.s(k)) { editing = -1 - listOf("mainPrompt", "jailbreak", "globalNote").indexOf(k) }
        }
        val items = RisuPreset.items(p)
        SectionLabel("프롬프트 템플릿 ${items.size}개")
        KoToggle(Modifier.padding(bottom = 6.dp))
        items.forEachIndexed { i, it ->
            val key = RisuPreset.textKey(it)
            val role = when (it.s("type")) { in RisuPreset.TEXT_TYPES -> it.s("role"); "cache" -> it.s("role"); else -> it.s("role2") }
            LineCard(Modifier.padding(vertical = 4.dp), onClick = if (key != null) ({ editing = i }) else null) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. " + it.s("name").ifBlank { RisuPreset.typeLabel(it) }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { RisuPresetEditor.setItems(items.toMutableList().apply { if (i > 0) add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "위로") }
                        IconButton(onClick = { RisuPresetEditor.setItems(items.toMutableList().apply { if (i < size - 1) add(i + 1, removeAt(i)) }) }, enabled = i < items.size - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "아래로") }
                        IconButton(onClick = { removing = i }) { Icon(Icons.Outlined.Delete, "삭제", tint = Sgu.Sub) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Badge(RisuPreset.typeLabel(it), Sgu.Bubble, Sgu.Ink)
                        if (role.isNotBlank()) Badge(RisuPreset.roleLabel(role), Sgu.Bubble, Sgu.Sub)
                        if (it.s("type") == "chat") Badge("${it.s("rangeStart")} ~ ${it.s("rangeEnd")}", Sgu.Bubble, Sgu.Sub)
                        if (it.s("type") == "cache") Badge("깊이 ${it.s("depth")}", Sgu.Bubble, Sgu.Sub)
                        Row(Modifier.clip(RoundedCornerShape(50)).background(Sgu.Bubble).clickable { retyping = i }.padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Edit, "이름 · 타입 바꾸기", tint = Sgu.Sub, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("이름 · 타입", style = MaterialTheme.typography.labelMedium, color = Sgu.Sub)
                        }
                    }
                    if (key != null) {
                        val text = it.s(key)
                        Text(if (text.isBlank()) "비어 있음 · 탭하여 편집" else koText(text.take(700)), style = MaterialTheme.typography.bodySmall, color = if (text.isBlank()) Sgu.Sub else Sgu.Ink,
                            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (it.s("type") in RisuPreset.TEXT_TYPES && it.s("type") != "chatML") {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system", "user", "bot").forEach { r ->
                                PillChip(RisuPreset.roleLabel(r), it.s("role").ifBlank { "system" } == r) {
                                    RisuPresetEditor.setItems(items.toMutableList().apply { this[i] = JsonObject(it + ("role" to JsonPrimitive(r))) })
                                }
                            }
                        }
                    }
                }
            }
        }
        LineButton("텍스트 블록 추가", onClick = { RisuPresetEditor.setItems(items + RisuPreset.newPlain()); editing = items.size }, icon = Icons.Outlined.Add)
        Hint("텍스트 블록(메인·일반·탈옥·CoT)과 설명·페르소나·로어북 같은 칸의 감싸는 글(inner format)을 고칠 수 있어요. 채팅·캐시 같은 칸은 순서만 바꿀 수 있어요. 나머지 설정(모델·샘플링 등)은 그대로 보관돼요.")

        SectionLabel("저장 · 가져오기")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LineButton(".risup으로 저장", onClick = {
                val bytes = RisuPreset.encodeRisup(p)
                saveRisup("${baseName}_preset.risup") { it.write(bytes) }; RisuPresetEditor.dirty = false
            }, icon = Icons.Outlined.Save)
            LineButton("JSON으로 저장", onClick = {
                val bytes = RisuPreset.encodeJson(p)
                saveJson("${baseName}_preset.json") { it.write(bytes) }; RisuPresetEditor.dirty = false
            })
            LineButton("SGU 작업실 프리셋으로", onClick = { msg = importRisuToSgu(p, RisuPresetEditor.uri, RisuPresetEditor.sourceName) })
            LineButton("다른 파일 열기", onClick = { opener.launch(arrayOf("*/*")) }, icon = Icons.Outlined.FileOpen)
        }
        if (msg.isNotEmpty()) Hint(msg)
        Hint("RisuAI에서는 설정 › 프롬프트 프리셋 › 가져오기로 저장한 .risup 파일을 불러와요. API 키 같은 칸은 RisuAI 내보내기처럼 비워서 저장해요.")
    }

    if (previewing && p != null) RisuTogglePreviewDialog(p) { previewing = false }
    retyping?.let { i ->
        val items = RisuPreset.items(p ?: JsonObject(emptyMap()))
        val item = items.getOrNull(i)
        if (item == null) { retyping = null; return@let }
        BlockTypeDialog(item, onDismiss = { retyping = null }) { name, type, type2 ->
            RisuPresetEditor.setItems(items.toMutableList().apply { this[i] = RisuPreset.retype(item, name, type, type2) })
            retyping = null
        }
    }
    editing?.let { idx ->
        val legacyKey = if (idx < 0) listOf("mainPrompt", "jailbreak", "globalNote")[-1 - idx] else null
        val items = RisuPreset.items(p ?: JsonObject(emptyMap()))
        val item = if (idx >= 0) items.getOrNull(idx) else null
        val key = legacyKey ?: item?.let { RisuPreset.textKey(it) }
        if (p == null || key == null) { editing = null; return@let }
        val title = legacyKey?.let { mapOf("mainPrompt" to "메인 프롬프트", "jailbreak" to "탈옥 프롬프트", "globalNote" to "글로벌 노트")[it] } ?: (item!!.s("name").ifBlank { RisuPreset.typeLabel(item) })
        TextEditDialog(title, if (legacyKey != null) p.s(legacyKey) else item!!.s(key), onDismiss = { editing = null }) { v ->
            if (legacyKey != null) RisuPresetEditor.set(JsonObject(p + (legacyKey to JsonPrimitive(v))))
            else RisuPresetEditor.setItems(items.toMutableList().apply { this[idx] = JsonObject(item!! + (key to JsonPrimitive(v))) })
            editing = null
        }
    }
    removing?.let { i ->
        val items = RisuPreset.items(p ?: JsonObject(emptyMap()))
        val it = items.getOrNull(i)
        if (it == null) { removing = null; return@let }
        ConfirmDialog("블록 삭제", "'${it.s("name").ifBlank { RisuPreset.typeLabel(it) }}'을(를) 프리셋에서 지웁니다.", "삭제",
            onConfirm = { RisuPresetEditor.setItems(items.toMutableList().apply { removeAt(i) }) }, onDismiss = { removing = null })
    }
}

/** 전체 화면 텍스트 편집 (긴 프롬프트용). */
@Composable
fun TextEditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var ko by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Sgu.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("취소", color = Sgu.Sub) }
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${text.length}자", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { ko = !ko }) { Text(if (ko) "원문" else "한글", color = Sgu.Accent) }
                TextButton(onClick = { onSave(text) }) { Text("저장", color = Sgu.Ink, fontWeight = FontWeight.Bold) }
            }
            if (ko) {
                // 번역은 보기 전용 (원문은 그대로). '원문'을 누르면 다시 편집
                if (space.sgu.next.data.KoTranslate.status.isNotEmpty()) Text(space.sgu.next.data.KoTranslate.status, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(horizontal = 16.dp))
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(koText(text, true), style = MaterialTheme.typography.bodyMedium, color = Sgu.Ink)
                }
            } else BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Sgu.Ink, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
        }
    }
}

/** RisuAI 프롬프트 편집의 "이름 · 타입" (+ 순수/탈옥 프롬프트의 특수 타입). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTypeDialog(item: JsonObject, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(item.s("name")) }
    var type by remember { mutableStateOf(item.s("type")) }
    var type2 by remember { mutableStateOf(item.s("type2").ifBlank { "normal" }) }
    val types = RisuPreset.TYPES + if (item.s("type") == "cot") listOf("cot" to "생각의 사슬(CoT)") else emptyList()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 · 타입") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel("이름")
                InputBox(name, { name = it })
                FieldLabel("타입")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { (k, label) -> PillChip(label, type == k) { type = k } }
                }
                if (type in setOf("plain", "jailbreak", "cot")) {
                    FieldLabel("특수 타입")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RisuPreset.SPECIAL.forEach { (k, label) -> PillChip(label, type2 == k) { type2 = k } }
                    }
                }
                if (type != item.s("type")) Hint("타입을 바꿔도 이미 적어 둔 글은 지우지 않고 그대로 보관해요.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, type, type2) }) { Text("저장", color = Sgu.Ink, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Sgu.Sub) } },
        containerColor = Sgu.Card,
    )
}
