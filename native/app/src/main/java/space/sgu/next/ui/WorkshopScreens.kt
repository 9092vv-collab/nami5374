package space.sgu.next.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import space.sgu.next.data.CardIO
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.Logs
import space.sgu.next.data.Perms
import space.sgu.next.data.Processors
import space.sgu.next.data.Prompt
import space.sgu.next.data.PromptBlocks
import space.sgu.next.data.Scripts
import space.sgu.next.data.Secrets
import space.sgu.next.data.SettingsStore
import space.sgu.next.data.WFields
import space.sgu.next.data.WebSearch
import space.sgu.next.data.Workshop
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.o
import space.sgu.next.data.orEmptyObj
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull

// Level colours of the original (module 1692).
private val VIEW_COLORS = listOf(Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFCA8A04), Color(0xFF16A34A))
private val EDIT_COLORS = listOf(Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFF16A34A))
private val GROUPS = listOf("character" to "01 캐릭터", "image" to "02 이미지", "script" to "03 스크립트", "etc" to "04 기타")

@Composable
private fun LevelPill(kind: String, level: Int?, color: Color, session: Boolean, onClick: (() -> Unit)?) {
    val c = if (level == null) Sgu.Line else color
    Text(
        if (level == null) "$kind -" else "$kind Lv$level",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = if (level == null) Sgu.Sub else c,
        modifier = Modifier.clip(CircleShape).border(if (session) 2.dp else 1.dp, c, CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun LevelLegend() {
    Text("열람: Lv0 금지 · Lv1 요청 · Lv2 판단 · Lv3 상시", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
    Text("편집: Lv0 금지 · Lv1 요청 · Lv2 자유", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun SubOffNotice() {
    if (Processors.isSubOff()) LineCard {
        Text("서브 프로세서가 꺼져 있어(또는 배정되지 않아) Lv1 이상인 필드는 모두 열람·편집 대상이 됩니다.", style = MaterialTheme.typography.bodyMedium)
    }
}

// =====================================================================================
// 권한 (module 1691 · this session) and the detail sheet (1693)
// =====================================================================================

@Composable
fun PermissionsScreen(cid: String, onBack: () -> Unit) {
    val doc = remember(cid, Workshop.version) { Workshop.doc(cid) }
    val perms = doc.o("permissions").orEmptyObj()
    var detail by remember { mutableStateOf<String?>(null) }
    val name = CharacterStore.items.firstOrNull { it.id == cid }?.name ?: "캐릭터"
    Page("권한", "$name · 이 세션의 열람/편집 권한", onBack) {
        SubOffNotice()
        Hint("레벨을 누르면 한 단계 올라가고, 최고 단계에서 다시 누르면 최저 단계로 돌아갑니다. 테두리가 굵은 칸은 기본값과 다른 세션 값입니다. 항목 이름을 누르면 기본값을 포함한 상세 설정을 엽니다.")
        LevelLegend()
        GROUPS.forEach { (g, label) ->
            val fields = WFields.all.filter { it.group == g }
            if (fields.isEmpty()) return@forEach
            SectionLabel(label)
            fields.forEach { f ->
                val p = perms.o(f.id)
                val v = Perms.view(p); val e = Perms.edit(p)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable { detail = f.id }.padding(vertical = 6.dp, horizontal = 6.dp), maxLines = 2)
                    if (f.viewable) LevelPill("열람", v, VIEW_COLORS[v.coerceIn(0, 3)], p.o("view")?.containsKey("session") == true) {
                        Workshop.setPermission(cid, f.id, "view", (v + 1) % 4, true)
                    } else LevelPill("열람", null, Sgu.Line, false, null)
                    Text(" / ", color = Sgu.Sub)
                    LevelPill("편집", e, EDIT_COLORS[e.coerceIn(0, 2)], p.o("edit")?.containsKey("session") == true) {
                        Workshop.setPermission(cid, f.id, "edit", (e + 1) % 3, true)
                    }
                }
            }
        }
    }
    detail?.let { id -> PermissionDetail(cid, id, perms.o(id)) { detail = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionDetail(cid: String, id: String, p: JsonObject?, onDismiss: () -> Unit) {
    val f = WFields.map[id] ?: return
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(f.label) }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(WFields.description(id), style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                listOf("view" to WFields.VIEW_LABELS, "edit" to WFields.EDIT_LABELS).forEach { (kind, labels) ->
                    if (kind == "view" && !f.viewable) return@forEach
                    val colors = if (kind == "view") VIEW_COLORS else EDIT_COLORS
                    val def = p.o(kind).d("default")?.toInt() ?: 0
                    val ses = p.o(kind).d("session")?.toInt()
                    FieldLabel(if (kind == "view") "열람 · 기본값" else "편집 · 기본값")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.indices.forEach { i -> LevelPill("Lv$i", i, colors[i], def == i) { Workshop.setPermission(cid, id, kind, i, false) } }
                    }
                    Text(labels.mapIndexed { i, l -> "Lv$i $l" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                    FieldLabel(if (kind == "view") "열람 · 이 세션" else "편집 · 이 세션")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.indices.forEach { i -> LevelPill("Lv$i", i, colors[i], ses == i) { Workshop.setPermission(cid, id, kind, i, true) } }
                        if (ses != null) TextButton(onClick = { Workshop.clearSessionPermission(cid, id, kind) }) { Text("기본값 따르기", color = Sgu.Ink) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) } },
    )
}

// =====================================================================================
// 새 캐릭터 기본 권한 (module 1709)
// =====================================================================================

@Composable
fun PermissionDefaultsScreen(onBack: () -> Unit) {
    val defs = SettingsStore.root.o("permissionDefaults").orEmptyObj()
    val builtin = Perms.builtinDefaults()
    fun set(id: String, kind: String, level: Int) = SettingsStore.update { m ->
        val cur = (m["permissionDefaults"] as? JsonObject).orEmptyObj()
        val p = cur.o(id) ?: builtin.o(id).orEmptyObj()
        m["permissionDefaults"] = JsonObject(cur + (id to JsonObject(p + (kind to JsonPrimitive(level)))))
    }
    var restore by remember { mutableStateOf(false) }
    Page("새 캐릭터 기본 권한", "새 캐릭터에 적용되는 기본값", onBack) {
        SubOffNotice()
        Hint("레벨을 누르면 한 단계 올라가고, 최고 단계에서 다시 누르면 최저 단계로 돌아갑니다. 여기에는 기본값만 있으며 세션값은 각 캐릭터의 권한 화면에서 따로 바꿉니다.")
        LevelLegend()
        GROUPS.forEach { (g, label) ->
            val fields = WFields.all.filter { it.group == g }
            if (fields.isEmpty()) return@forEach
            SectionLabel(label)
            fields.forEach { f ->
                val p = defs.o(f.id) ?: builtin.o(f.id)
                val v = p.d("view")?.toInt() ?: f.defaultView; val e = p.d("edit")?.toInt() ?: f.defaultEdit
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 2)
                    if (f.viewable) LevelPill("열람", v, VIEW_COLORS[v.coerceIn(0, 3)], false) { set(f.id, "view", (v + 1) % 4) }
                    else LevelPill("열람", null, Sgu.Line, false, null)
                    Text(" / ", color = Sgu.Sub)
                    LevelPill("편집", e, EDIT_COLORS[e.coerceIn(0, 2)], false) { set(f.id, "edit", (e + 1) % 3) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LineButton("내장 기본값으로 복원", onClick = { restore = true })
    }
    if (restore) ConfirmDialog("내장 기본값으로 복원", "새 캐릭터 기본 권한을 앱 내장 값으로 되돌릴까요? 이미 만든 캐릭터의 권한은 바뀌지 않아요.", "복원",
        onConfirm = { SettingsStore.update { it["permissionDefaults"] = builtin } }, onDismiss = { restore = false })
}

// =====================================================================================
// 항목 설명 (settings › fields): what the sub processor reads about each field
// =====================================================================================

@Composable
fun FieldDescriptionsScreen(onBack: () -> Unit) {
    val custom = SettingsStore.root.o("fieldDescriptions").orEmptyObj()
    var editing by remember { mutableStateOf<String?>(null) }
    Page("항목 설명", "서브 프로세서가 판단할 때 보는 항목별 설명", onBack) {
        Hint("비워 두면 내장 설명을 씁니다. 설명은 영어가 가장 정확하지만 한국어도 괜찮아요.")
        GROUPS.forEach { (g, label) ->
            val fields = WFields.all.filter { it.group == g }
            if (fields.isEmpty()) return@forEach
            SectionLabel(label)
            fields.forEach { f ->
                LineCard(Modifier.padding(vertical = 4.dp), onClick = { editing = f.id }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            if (custom.s(f.id).isNotBlank()) Badge("수정됨", Sgu.Bubble, Sgu.Ink)
                        }
                        Text(WFields.description(f.id), style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    editing?.let { id ->
        var text by remember(id) { mutableStateOf(custom.s(id).ifBlank { WFields.map[id]?.aiDescription ?: "" }) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(WFields.label(id)) }, containerColor = Sgu.Card,
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { InputBox(text, { text = it }, singleLine = false) } },
            confirmButton = { TextButton(onClick = {
                SettingsStore.update { m ->
                    val cur = (m["fieldDescriptions"] as? JsonObject).orEmptyObj()
                    val v = text.trim()
                    m["fieldDescriptions"] = if (v.isEmpty() || v == WFields.map[id]?.aiDescription) JsonObject(cur - id) else JsonObject(cur + (id to JsonPrimitive(v)))
                }
                editing = null
            }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = {
                SettingsStore.update { m -> m["fieldDescriptions"] = JsonObject((m["fieldDescriptions"] as? JsonObject).orEmptyObj() - id) }; editing = null
            }) { Text("내장 설명으로", color = Sgu.Sub) } },
        )
    }
}

// =====================================================================================
// 프롬프트 설정 · 미리보기 (module 1694)
// =====================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptPreviewScreen(cid: String, openRoute: (String) -> Unit, onBack: () -> Unit) {
    val doc = remember(cid, Workshop.version) { Workshop.doc(cid) }
    var wide by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val presets = PromptBlocks.presets()
    val chosen = doc.sOrNull("promptPresetId")
    val result = remember(cid, Workshop.version, SettingsStore.root, wide, command, tick) {
        val data = (CharacterStore.readCard(cid)["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val perms = doc.o("permissions").orEmptyObj()
        val view = WFields.all.filter { it.viewable && Perms.view(perms.o(it.id)) >= (if (wide) 2 else 3) }.map { it.id }
        val edit = if (wide) WFields.all.filter { Perms.edit(perms.o(it.id)) >= 2 }.map { it.id } else emptyList()
        Prompt.assemble(Prompt.Input(data, PromptBlocks.blocksFor(doc), view, edit, command.ifBlank { "(명령)" }, emptyList(),
            Workshop.historyTurns(doc), true, Processors.mainModels(doc).first?.o("params")?.d("maxContext")?.toInt(), summary = Workshop.summaryText(doc)))
    }
    Page("프롬프트 설정", "프리셋 선택 · 전송 순서 미리보기 · 약 ${result.tokenEstimate} 토큰", onBack) {
        SectionLabel("프롬프트 프리셋")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val def = PromptBlocks.activePreset()
            PillChip("기본 (${def.s("name")})", chosen == null || presets.none { it.s("id") == chosen }) { Workshop.set(cid, "promptPresetId", JsonNull) }
            presets.forEach { p -> PillChip(p.s("name"), chosen == p.s("id")) { Workshop.set(cid, "promptPresetId", JsonPrimitive(p.s("id"))) } }
        }
        Spacer(Modifier.height(8.dp))
        LineButton("프리셋 관리", onClick = { openRoute("promptpresets") })
        SectionLabel("미리보기")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChip("Lv2 이상 모두 열람·편집", wide) { wide = true }
            PillChip("Lv3(상시)만 열람", !wide) { wide = false }
        }
        Hint("실제 전송 시에는 서브 프로세서 판단과 유저 승인에 따라 열람/편집 필드가 결정됩니다. 여기서는 권한 등급 기준의 가능한 범위를 보여줍니다.")
        FieldLabel("가정할 유저 명령")
        InputBox(command, { command = it }, placeholder = "(명령)", singleLine = false)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillChip("블록별", !raw) { raw = false }
            PillChip("전체 원문", raw) { raw = true }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { tick++ }) { Text("다시 계산", color = Sgu.Ink) }
        }
        Spacer(Modifier.height(8.dp))
        if (raw) LineCard {
            Text(Prompt.preview(result.messages), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        } else result.parts.forEach { part ->
            if (part.type == "cachePoint") {
                Text("— 캐싱 포인트 · 보수적 캐싱에서만 활성화 · 여기까지만 캐시 —", style = MaterialTheme.typography.bodySmall, color = Sgu.Accent, modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp))
                return@forEach
            }
            var open by remember(part.name, part.type) { mutableStateOf(false) }
            LineCard(Modifier.padding(vertical = 4.dp), onClick = { open = !open }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(part.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        Badge(part.role, Sgu.Bubble, Sgu.Ink)
                        Spacer(Modifier.width(6.dp))
                        Text("${part.tokens}t", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    if (part.fieldIds.isNotEmpty()) Text(part.fieldIds.joinToString(", ") { WFields.label(it) }, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(part.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// =====================================================================================
// 프롬프트 프리셋 (module 1714) and the block editor (1715)
// =====================================================================================

private const val PRESET_FORMAT = "sgu-prompt-preset"

internal fun editPresets(f: (MutableList<JsonObject>) -> Unit) = SettingsStore.update { m ->
    val l = (m["promptPresets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.toMutableList() ?: mutableListOf()
    f(l); m["promptPresets"] = JsonArray(l)
}

internal fun addPreset(name: String, blocks: List<JsonObject>, extra: Map<String, JsonElement> = emptyMap()): String {
    val id = Scripts.newId("preset")
    editPresets { it.add(buildJsonObject { put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("blocks", JsonArray(blocks)); put("createdAt", JsonPrimitive(System.currentTimeMillis())); extra.forEach { (k, v) -> put(k, v) } }) }
    return id
}

/** Original 1714 v(): accepts one preset, a list, or {presets:[…]}; blocks get new ids. */
private fun parsePresets(e: JsonElement?): List<Pair<String, List<JsonObject>>> {
    val list = when (e) { is JsonArray -> e.toList(); is JsonObject -> (e["presets"] as? JsonArray)?.toList() ?: listOf(e); else -> emptyList() }
    return list.mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        val blocks = (o["blocks"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { (it["type"] as? JsonPrimitive)?.isString == true }?.map { b ->
            JsonObject(b + mapOf("id" to JsonPrimitive(Scripts.newId("blk")), "enabled" to JsonPrimitive(b.b("enabled", true)),
                "role" to JsonPrimitive(b.s("role").ifBlank { "system" }), "name" to JsonPrimitive(b.sOrNull("name") ?: b.s("type")), "text" to JsonPrimitive(b.s("text"))))
        } ?: return@mapNotNull null
        if (blocks.isEmpty()) null else (o.s("name").ifBlank { "가져온 프리셋" } to blocks)
    }
}

@Composable
fun PromptPresetsScreen(openRoute: (String) -> Unit, onBack: () -> Unit) {
    val presets = PromptBlocks.presets()
    val active = PromptBlocks.activePreset().s("id")
    // 블록 편집에 다녀와도 고른 프리셋이 유지되게 (전에는 기본 프리셋으로 되돌아가서 내보내기가 기본만 나갔음)
    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(active) }
    val cur = presets.firstOrNull { it.s("id") == selected } ?: presets.firstOrNull()
    var msg by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val save = rememberSaver("application/json")
    fun fileOf(list: List<JsonObject>): ByteArray = CardIO.json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("format", JsonPrimitive(PRESET_FORMAT)); put("version", JsonPrimitive(1))
        put("presets", JsonArray(list.map { p -> buildJsonObject { put("name", p["name"] ?: JsonPrimitive("")); put("blocks", p["blocks"] ?: JsonArray(emptyList())) } }))
    }).toByteArray(Charsets.UTF_8)
    val risuCtx = androidx.compose.ui.platform.LocalContext.current
    val risuPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) msg = runCatching {
            runCatching { risuCtx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            importRisuToSgu(space.sgu.next.data.RisuPreset.read(risuCtx, u), u, space.sgu.next.data.RisuPreset.nameOf(risuCtx, u))
                .also { PromptBlocks.presets().lastOrNull()?.let { np -> selected = np.s("id") } }
        }.getOrElse { it.message ?: "가져오지 못했어요" }
    }
    val pick = rememberJsonPicker { o ->
        val found = parsePresets(o)
        if (found.isEmpty()) msg = "가져올 프리셋을 찾지 못했습니다. 블록 목록(blocks)이 있는 JSON인지 확인하세요."
        else { var last = ""; found.forEach { (n, b) -> last = addPreset(n, b) }; selected = last; msg = "프리셋 ${found.size}개를 가져왔습니다." }
    }
    Page("프롬프트 프리셋", "여러 프리셋을 저장하고 캐릭터마다 골라 쓰기", onBack) {
        SectionLabel("프리셋")
        presets.forEach { p ->
            val id = p.s("id"); val isSel = id == cur?.s("id")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(Sgu.Card)
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) Sgu.Ink else Sgu.Line, RoundedCornerShape(18.dp)).clickable { selected = id }
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.s("name"), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text("블록 ${p.arr("blocks").size}개" + if (id == active) " · 기본" else "", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    if (p.s("risuUri").isNotBlank()) Text("연결: ${p.s("risuFile")} · ${java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA).format(java.util.Date(p.d("createdAt")?.toLong() ?: 0L))} 가져옴" +
                        if (p.b("risuDirty")) " · 저장 안 한 변경 있음" else "", style = MaterialTheme.typography.bodySmall, color = if (p.b("risuDirty")) Sgu.Accent else Sgu.Sub)
                }
                IconButton(onClick = { SettingsStore.update { it["activePromptPresetId"] = JsonPrimitive(id) } }) {
                    Icon(if (id == active) Icons.Filled.Star else Icons.Outlined.StarBorder, "기본 프리셋", tint = if (id == active) Sgu.Accent else Sgu.Sub)
                }
            }
        }
        Hint("기본 프리셋(★)은 따로 고르지 않은 모든 캐릭터에 적용됩니다. 캐릭터의 프롬프트 설정에서 다른 프리셋을 고를 수 있습니다.")
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LineButton("새 프리셋", onClick = { selected = addPreset("프리셋 ${presets.size + 1}", PromptBlocks.defaults()) }, icon = Icons.Outlined.Add)
            if (cur != null) {
                LineButton("블록 편집", onClick = { openRoute("promptpreset/${cur.s("id")}") })
                LineButton("복제", onClick = { selected = addPreset("${cur.s("name")} 사본", cur.arr("blocks").map { JsonObject(it + ("id" to JsonPrimitive(Scripts.newId("blk")))) }) }, icon = Icons.Outlined.ContentCopy)
                LineButton("이름 변경", onClick = { renaming = true })
                if (presets.size > 1) LineButton("삭제", onClick = { removing = true }, icon = Icons.Outlined.Delete)
            }
        }
        SectionLabel("RisuAI 프리셋")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LineButton("RisuAI 프리셋 가져오기", onClick = { risuPick.launch(arrayOf("*/*")) }, icon = Icons.Outlined.Add)
            LineButton("RisuAI 프리셋 편집기", onClick = { openRoute("risupreset") })
        }
        Hint("RisuAI 프롬프트 프리셋(.risup · .risupreset · .json)의 텍스트 블록을 SGU 기본 구조에 넣어 새 작업실 프리셋을 만들어요. 롤플레이 전용 칸은 건너뛰고, 봇 프리필은 꺼진 채로 넣어요.")
        SectionLabel("JSON 가져오기 · 내보내기")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cur != null) LineButton("선택한 프리셋 내보내기", onClick = {
                save("sgu-preset-${cur.s("name").replace(Regex("[^\\w가-힣-]+"), "_")}.json") { it.write(fileOf(listOf(cur))) }; msg = "'${cur.s("name")}' 프리셋을 내보냈습니다."
            })
            LineButton("전체 내보내기", onClick = {
                save("sgu-presets-${java.time.LocalDate.now()}.json") { it.write(fileOf(presets)) }; msg = "프리셋 ${presets.size}개를 내보냈습니다."
            })
            LineButton("JSON 가져오기", onClick = pick)
        }
        if (msg.isNotEmpty()) Hint(msg)
    }
    if (renaming && cur != null) {
        var name by remember { mutableStateOf(cur.s("name")) }
        AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("프리셋 이름 변경") }, containerColor = Sgu.Card,
            text = { InputBox(name, { name = it }) },
            confirmButton = { TextButton(onClick = {
                editPresets { l -> val i = l.indexOfFirst { it.s("id") == cur.s("id") }; if (i >= 0) l[i] = JsonObject(l[i] + ("name" to JsonPrimitive(name.trim().ifBlank { l[i].s("name") }))) }
                renaming = false
            }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소", color = Sgu.Sub) } },
        )
    }
    if (removing && cur != null) ConfirmDialog("프리셋 삭제", "'${cur.s("name")}' 프리셋을 삭제합니다. 이 프리셋을 쓰던 캐릭터는 기본 프리셋을 사용하게 됩니다.", "삭제", onConfirm = {
        val id = cur.s("id")
        editPresets { l -> l.removeAll { it.s("id") == id } }
        if (active == id) SettingsStore.update { m -> m["activePromptPresetId"] = (m["promptPresets"] as? JsonArray)?.firstOrNull()?.let { (it as JsonObject)["id"] } ?: JsonNull }
        selected = PromptBlocks.activePreset().s("id")
    }, onDismiss = { removing = false })
}

@Composable
fun PromptPresetScreen(presetId: String, onBack: () -> Unit) {
    val p = PromptBlocks.presets().firstOrNull { it.s("id") == presetId }
    if (p == null) { Page("블록 편집", null, onBack) { EmptyPane("프리셋을 찾을 수 없어요") }; return }
    val blocks = p.arr("blocks")
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val linked = p.s("risuUri").isNotBlank()
    var sync by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var reloading by remember { mutableStateOf(false) }
    val dirty = p.b("risuDirty")
    // RisuAI 토글: 정의(risuToggles)와 고른 값(risuToggleValues)은 프리셋에 저장. 프롬프트 조립 때 적용됨.
    val fromRisu = blocks.any { it["risuItem"] != null }
    var toggling by remember { mutableStateOf(false) }
    val toggleDefs = remember(p.s("risuToggles")) { space.sgu.next.data.RisuToggles.parse(p.s("risuToggles")) }
    val toggleCount = toggleDefs.count { it.type in setOf("check", "select", "text", "textarea") }
    fun patchPreset(vararg pairs: Pair<String, JsonElement>) = editPresets { l -> val i = l.indexOfFirst { it.s("id") == presetId }; if (i >= 0) l[i] = JsonObject(l[i] + pairs) }
    // 예전에 가져온 프리셋엔 토글 정의가 없음 → 원본 파일에서 한 번 읽어 옴. 값이 없으면 토글 미리보기에서 고른 값으로 시작.
    androidx.compose.runtime.LaunchedEffect(presetId) {
        if (!p.containsKey("risuToggles") && linked) runCatching {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { space.sgu.next.data.RisuPreset.read(ctx, android.net.Uri.parse(p.s("risuUri"))) }
            patchPreset("risuToggles" to JsonPrimitive(file.s("customPromptTemplateToggle")), "risuName" to JsonPrimitive(file.s("name")))
        }
        val cur = PromptBlocks.presets().firstOrNull { it.s("id") == presetId }
        if (cur != null && !cur.containsKey("risuToggleValues") && cur.s("risuName").isNotBlank()) {
            val seed = space.sgu.next.data.RisuToggles.load(ctx, cur.s("risuName").ifBlank { "이름 없는 프리셋" }).filterKeys { !it.startsWith("\u0000") }
            if (seed.isNotEmpty()) patchPreset("risuToggleValues" to JsonObject(seed.mapValues { JsonPrimitive(it.value) }))
        }
    }
    // 블록을 고쳐도 원본 파일은 건드리지 않음. '원본 파일에 저장'을 눌러야 덮어씀.
    fun setBlocks(f: (MutableList<JsonObject>) -> Unit) = editPresets { l ->
        val i = l.indexOfFirst { it.s("id") == presetId }
        if (i >= 0) {
            val b = l[i].arr("blocks").toMutableList(); f(b)
            l[i] = JsonObject(l[i] + ("blocks" to JsonArray(b)) + if (l[i].s("risuUri").isNotBlank()) mapOf("risuDirty" to JsonPrimitive(true)) else emptyMap())
        }
    }
    fun setDirty(v: Boolean) = editPresets { l -> val i = l.indexOfFirst { it.s("id") == presetId }; if (i >= 0) l[i] = JsonObject(l[i] + ("risuDirty" to JsonPrimitive(v))) }
    fun saveToFile() {
        val now = PromptBlocks.presets().firstOrNull { it.s("id") == presetId } ?: return
        sync = null to "원본 파일에 저장하는 중…"
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { space.sgu.next.data.RisuPreset.syncBack(ctx, now) } }
                .onSuccess { out ->
                    setDirty(false); sync = true to "원본 파일(${now.s("risuFile")})에 저장했어요."
                    if (RisuPresetEditor.uri?.toString() == now.s("risuUri")) { RisuPresetEditor.preset = out; RisuPresetEditor.dirty = false }
                }
                .onFailure { sync = false to "원본 파일에 저장하지 못했어요 (${it.message}). 파일이 옮겨졌거나 지워졌다면 다시 가져와 주세요." }
        }
    }
    // 원본 파일을 다시 읽어 RisuAI 블록을 통째로 바꿈 (SGU 고유 블록은 그대로 뒤에 둠)
    fun reloadFromFile() {
        val now = PromptBlocks.presets().firstOrNull { it.s("id") == presetId } ?: return
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { space.sgu.next.data.RisuPreset.read(ctx, android.net.Uri.parse(now.s("risuUri"))) } }
                .onSuccess { file ->
                    val fresh = space.sgu.next.data.RisuPreset.toSguBlocks(file).first.filter { it["risuItem"] != null }
                    editPresets { l ->
                        val i = l.indexOfFirst { it.s("id") == presetId }
                        if (i >= 0) l[i] = JsonObject(l[i] + mapOf("blocks" to JsonArray(fresh + l[i].arr("blocks").filter { it["risuItem"] == null }), "risuDirty" to JsonPrimitive(false),
                            "risuToggles" to JsonPrimitive(file.s("customPromptTemplateToggle")), "risuName" to JsonPrimitive(file.s("name"))))
                    }
                    sync = true to "원본 파일에서 다시 불러왔어요 (RisuAI 블록 ${fresh.size}개)."
                }
                .onFailure { sync = false to "원본 파일을 읽지 못했어요 (${it.message})." }
        }
    }
    Page("블록 편집", p.s("name"), onBack) {
        if (linked) {
            Text(sync.second.ifEmpty { "RisuAI 파일 ${p.s("risuFile")}과 연결됨 · 저장을 눌러야 원본 파일이 바뀌어요." }, style = MaterialTheme.typography.bodySmall,
                color = if (sync.first == false) Sgu.Danger else Sgu.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            InkButton(if (dirty) "원본 파일에 저장" else "원본 파일과 같음", onClick = { saveToFile() }, modifier = Modifier.fillMaxWidth(), enabled = dirty)
            Spacer(Modifier.height(8.dp))
            LineButton("원본에서 다시 불러오기", onClick = { reloading = true }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        if (fromRisu) {
            LineButton(if (toggleCount > 0) "토글 설정 · ${toggleCount}개" else "토글 설정", onClick = { toggling = true }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Tune)
            Hint("RisuAI 글 블록의 토글 조건문은 여기서 고른 값대로 풀려서 작업실 프롬프트에 들어가요.")
            Spacer(Modifier.height(6.dp))
        }
        Hint("위에서 아래 순서로 메인 프로세서에 전달됩니다. 블록을 누르면 편집합니다.")
        blocks.forEachIndexed { i, b ->
            val on = b.b("enabled", true)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(18.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp))
                    .clickable { editing = b }.padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(b.s("name").ifBlank { b.s("type") }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = if (on) Sgu.Ink else Sgu.Sub)
                    Text("${(b["risuItem"] as? JsonObject)?.let { space.sgu.next.data.RisuPreset.typeLabel(it) + if (b.s("type") == "risu") " · RisuAI 전용" else "" } ?: PromptBlocks.TYPE_LABELS[b.s("type")] ?: b.s("type")} · ${b.s("role").ifBlank { "system" }}" + if (!on) " · 꺼짐" else "", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                }
                SguSwitch(on) { v -> setBlocks { l -> l[i] = JsonObject(l[i] + ("enabled" to JsonPrimitive(v))) } }
                IconButton(onClick = { setBlocks { l -> if (i > 0) { val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t } } }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "위로", tint = Sgu.Sub) }
                IconButton(onClick = { setBlocks { l -> if (i < l.size - 1) { val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t } } }, enabled = i < blocks.size - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "아래로", tint = Sgu.Sub) }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("블록 추가", onClick = { adding = true }, icon = Icons.Outlined.Add)
            LineButton("기본값 복원", onClick = { restore = true })
        }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false }, title = { Text("블록 추가") }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (linked) {
                    val RP = space.sgu.next.data.RisuPreset
                    FieldLabel("RisuAI 블록 (원본 파일에 저장됨)")
                    RP.TYPES.forEach { (type, label) ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            val item = RP.retype(JsonObject(emptyMap()), "", type, "normal").let { JsonObject(it - "name") }
                            val isText = type in RP.TEXT_TYPES
                            val nb = JsonObject(mapOf("id" to JsonPrimitive(Scripts.newId("blk")), "type" to JsonPrimitive(if (isText) "text" else "risu"), "enabled" to JsonPrimitive(true),
                                "role" to JsonPrimitive("system"), "name" to JsonPrimitive("Risu · $label"), "text" to JsonPrimitive(""), "risuItem" to item))
                            // 다른 RisuAI 블록들 바로 뒤에 넣음 (SGU 고유 블록 앞)
                            setBlocks { l -> val at = l.indexOfLast { it["risuItem"] != null }; l.add(if (at >= 0) at + 1 else 0, nb) }
                            adding = false; editing = nb
                        }.padding(10.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                            Text(if (type in RP.TEXT_TYPES) "작업실 프롬프트에도 들어가요." else "RisuAI 전용 칸 · 작업실 프롬프트에는 안 들어가요.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        }
                    }
                    FieldLabel("SGU 작업실 블록 (원본 파일에는 저장 안 됨)")
                }
                PromptBlocks.TYPE_LABELS.forEach { (type, label) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        val nb = PromptBlocks.newBlock(type); setBlocks { it.add(nb) }; adding = false; editing = nb
                    }.padding(10.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(PromptBlocks.TYPE_DESCRIPTIONS[type] ?: "", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = false }) { Text("닫기", color = Sgu.Sub) } },
    )
    if (toggling) WorkshopToggleDialog(toggleDefs, PromptBlocks.toggleValues(p), onSet = { k, v ->
        patchPreset("risuToggleValues" to JsonObject(PromptBlocks.toggleValues(p).mapValues { JsonPrimitive(it.value) } + (k to JsonPrimitive(v))))
    }, onDismiss = { toggling = false })
    if (reloading) ConfirmDialog("원본에서 다시 불러오기", "저장하지 않은 변경을 버리고, RisuAI 블록을 원본 파일 내용으로 되돌립니다. SGU 작업실 블록은 그대로 둡니다.", "불러오기",
        onConfirm = { reloadFromFile() }, onDismiss = { reloading = false })
    if (restore) ConfirmDialog("기본값 복원", "이 프리셋의 블록을 기본 구성으로 되돌릴까요?", "복원", onConfirm = { setBlocks { it.clear(); it.addAll(PromptBlocks.defaults()) } }, onDismiss = { restore = false })
    editing?.let { b -> BlockEditDialog(b, onSave = { nb -> setBlocks { l -> val i = l.indexOfFirst { it.s("id") == nb.s("id") }; if (i >= 0) l[i] = nb } ; editing = null },
        onDelete = { setBlocks { l -> l.removeAll { it.s("id") == b.s("id") } }; editing = null }, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockEditDialog(b: JsonObject, onSave: (JsonObject) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(b.s("id")) { mutableStateOf(b.s("name")) }
    var role by remember(b.s("id")) { mutableStateOf(b.s("role").ifBlank { "system" }) }
    var text by remember(b.s("id")) { mutableStateOf(b.s("text")) }
    var enabled by remember(b.s("id")) { mutableStateOf(b.b("enabled", true)) }
    var fmt by remember(b.s("id")) { mutableStateOf(b.s("fieldFormat").ifBlank { PromptBlocks.DEFAULT_FIELD_FORMAT }) }
    var limit by remember(b.s("id")) { mutableStateOf((b.d("historyLimit")?.toInt() ?: 6).toString()) }
    var type by remember(b.s("id")) { mutableStateOf(b.s("type")) }
    // RisuAI 파일에서 온 블록: 타입 목록을 RisuAI 것으로 (원본 파일과 맞춤)
    val RP = space.sgu.next.data.RisuPreset
    val risu0 = b["risuItem"] as? JsonObject
    var rtype by remember(b.s("id")) { mutableStateOf(risu0?.s("type") ?: "") }
    var rtype2 by remember(b.s("id")) { mutableStateOf(risu0?.s("type2")?.ifBlank { "normal" } ?: "normal") }
    fun risuNow(): JsonObject? = risu0?.let { RP.retype(it, it.s("name"), rtype, rtype2) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("블록 편집") }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel("이름")
                InputBox(name, { name = it })
                FieldLabel("타입")
                if (risu0 != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (RP.TYPES + if (risu0.s("type") == "cot") listOf("cot" to "생각의 사슬(CoT)") else emptyList()).forEach { (k, label) -> PillChip(label, rtype == k) {
                            if (rtype != k) {
                                val oldLabel = RP.typeLabel(risuNow()!!)
                                rtype = k
                                type = if (k in RP.TEXT_TYPES) "text" else "risu"
                                if (name == "Risu · $oldLabel") name = "Risu · " + RP.typeLabel(risuNow()!!)
                            }
                        } }
                    }
                    if (rtype in setOf("plain", "jailbreak", "cot")) {
                        FieldLabel("특수 타입")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RP.SPECIAL.forEach { (k, label) -> PillChip(label, rtype2 == k) {
                                val oldLabel = RP.typeLabel(risuNow()!!); rtype2 = k
                                if (name == "Risu · $oldLabel") name = "Risu · " + RP.typeLabel(risuNow()!!)
                            } }
                        }
                    }
                    Text(if (type == "risu") "RisuAI 전용 칸이에요. 작업실 프롬프트에는 들어가지 않고, 이름·타입·순서·감싸는 글만 원본 파일에 저장돼요."
                        else "작업실 프롬프트에도 들어가고, 원본 RisuAI 파일에도 저장돼요.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PromptBlocks.TYPE_LABELS.forEach { (k, label) -> PillChip(label, type == k) {
                        if (type != k) {
                            type = k
                            // 내용이 들어가는 타입인데 자리 표시가 없으면 넣어 줌
                            if (k in PromptBlocks.SLOT_TYPES && PromptBlocks.SLOT !in text) text = if (text.isBlank()) PromptBlocks.SLOT else text + "\n\n" + PromptBlocks.SLOT
                            if (k == "command") role = "user"
                        }
                    } }
                }
                Text(PromptBlocks.TYPE_DESCRIPTIONS[type] ?: "", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                }
                if (type != "cachePoint" && type != "risu" && rtype != "chatML") {
                    FieldLabel("역할")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("system", "user", "assistant").forEach { r -> PillChip(r, role == r) { role = r } } }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("사용", modifier = Modifier.weight(1f).padding(start = 6.dp)); SguSwitch(enabled) { enabled = it }
                }
                if (type == "risu") {
                    if (rtype in RP.FORMAT_TYPES) { FieldLabel("감싸는 글 (inner format, ${PromptBlocks.SLOT} 자리에 내용)"); InputBox(text, { text = it }, singleLine = false) }
                } else if (type != "cachePoint") {
                    FieldLabel(if (type == "outputFormat") "outputFormat (비워 두면 내장 규칙)" else if (type in PromptBlocks.SLOT_TYPES) "텍스트 (${PromptBlocks.SLOT} 자리에 내용이 들어갑니다)" else "텍스트")
                    InputBox(text, { text = it }, singleLine = false)
                }
                if (type != "cachePoint" && text.isNotBlank()) {
                    KoToggle(Modifier.padding(top = 8.dp))
                    if (space.sgu.next.data.KoTranslate.on) Text(koText(text), style = MaterialTheme.typography.bodySmall, color = Sgu.Ink,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sgu.Bubble).padding(12.dp))
                }
                if (type == "fieldData") { FieldLabel("필드 포맷 ({{label}}, {{id}}, {{value}})"); InputBox(fmt, { fmt = it }, singleLine = false) }
                if (type == "history") { FieldLabel("포함할 이전 턴 수 (0 = 전체)"); InputBox(limit, { v -> limit = v.filter { it.isDigit() }.take(3) }, number = true) }
            }
        },
        confirmButton = { TextButton(onClick = {
            var nb = JsonObject(b + mapOf("type" to JsonPrimitive(type), "name" to JsonPrimitive(name), "role" to JsonPrimitive(role), "text" to JsonPrimitive(text), "enabled" to JsonPrimitive(enabled)))
            risuNow()?.let { nb = JsonObject(nb + ("risuItem" to it)) }
            if (type == "fieldData") nb = JsonObject(nb + ("fieldFormat" to JsonPrimitive(fmt)))
            if (type == "history") nb = JsonObject(nb + ("historyLimit" to JsonPrimitive(limit.toIntOrNull() ?: 0)))
            onSave(nb)
        }) { Text("저장", color = Sgu.Ink) } },
        dismissButton = { TextButton(onClick = onDelete) { Text("삭제", color = Sgu.Danger) } },
    )
}

// =====================================================================================
// 요청 로그 (module 958 / settings › logs)
// =====================================================================================

private fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SGU", text))
    Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val logging = SettingsStore.root.o("logging")
    var clear by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val pretty = remember { kotlinx.serialization.json.Json { prettyPrint = true } }
    val kinds = mapOf("sub" to "서브", "main" to "메인", "error" to "오류", "search" to "검색")
    Page("요청 로그", "서브/메인 프로세서 요청과 응답", onBack, actions = {
        IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.Delete, "모두 지우기") }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("로그 기록", modifier = Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodyLarge)
            SguSwitch(logging.b("enabled", true)) { v -> SettingsStore.update { m -> m["logging"] = JsonObject(logging.orEmptyObj() + ("enabled" to JsonPrimitive(v))) } }
        }
        FieldLabel("보관할 개수")
        InputBox((logging.d("maxEntries")?.toInt() ?: 50).toString(), { v ->
            v.filter { it.isDigit() }.take(4).toIntOrNull()?.let { n -> SettingsStore.update { m -> m["logging"] = JsonObject(logging.orEmptyObj() + ("maxEntries" to JsonPrimitive(n))) } }
        }, number = true)
        Hint("API 키는 기록할 때 가려집니다.")
        Spacer(Modifier.height(8.dp))
        if (Logs.entries.isEmpty()) EmptyPane("기록이 없어요")
        Logs.entries.forEach { e ->
            val id = e.s("id"); val open = openId == id
            val time = java.text.SimpleDateFormat("MM.dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(e.d("at")?.toLong() ?: 0L))
            LineCard(Modifier.padding(vertical = 4.dp), onClick = { openId = if (open) null else id }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(kinds[e.s("kind")] ?: e.s("kind"), if (e.s("kind") == "error") Sgu.AccentSoft else Sgu.Bubble, if (e.s("kind") == "error") Color(0xFF8A3B22) else Sgu.Ink)
                        Spacer(Modifier.width(8.dp))
                        Text(listOfNotNull(e.sOrNull("model"), e.d("ms")?.let { "${(it / 1000).let { s -> "%.1f".format(s) }}s" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(time, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    e.sOrNull("error")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Sgu.Danger, modifier = Modifier.padding(top = 4.dp)) }
                    e.o("usage")?.let { u -> Text("입력 ${u.d("inputTokens")?.toInt() ?: "-"} · 출력 ${u.d("outputTokens")?.toInt() ?: "-"} 토큰", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub) }
                    if (open) {
                        listOf("request" to "요청", "response" to "응답").forEach { (k, label) ->
                            val el = e[k] ?: return@forEach
                            val t = pretty.encodeToString(JsonElement.serializer(), el)
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                                IconButton(onClick = { copyText(ctx, t) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "복사", tint = Sgu.Sub, modifier = Modifier.size(16.dp)) }
                            }
                            Text(t.take(20000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sgu.Bubble).padding(10.dp))
                        }
                    }
                }
            }
        }
    }
    if (clear) ConfirmDialog("요청 로그 지우기", "기록을 모두 지울까요?", "지우기", onConfirm = { Logs.clear() }, onDismiss = { clear = false })
}

// =====================================================================================
// 백그라운드 작업 · 검색 키 (settings › background)
// =====================================================================================

@Composable
fun BackgroundScreen(onBack: () -> Unit) {
    val bg = SettingsStore.root.o("background").orEmptyObj()
    fun set(k: String, v: Boolean) = SettingsStore.update { m -> m["background"] = JsonObject(bg + (k to JsonPrimitive(v))) }
    var key by remember { mutableStateOf(Secrets.get(WebSearch.KEY_ID) ?: "") }
    Page("백그라운드 작업", "앱이 뒤로 가도 작업을 이어갑니다", onBack) {
        LineCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("포그라운드 서비스", style = MaterialTheme.typography.bodyLarge)
                        Text("작업실·이미지 생성 중에 알림을 띄워 안드로이드가 앱을 멈추지 않게 합니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    SguSwitch(bg.b("foregroundService", true)) { set("foregroundService", it) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("화면이 꺼져도 계속", style = MaterialTheme.typography.bodyLarge)
                        Text("작업 중 CPU가 잠들지 않게 붙잡아 둡니다(배터리를 더 씁니다).", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    SguSwitch(bg.b("keepAwake")) { set("keepAwake", it) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("끝나면 알림", style = MaterialTheme.typography.bodyLarge)
                        Text("앱 밖에 있을 때 작업이 끝나면 알려 줍니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    SguSwitch(bg.b("notifyOnComplete", true)) { set("notifyOnComplete", it) }
                }
            }
        }
        SectionLabel("웹 검색")
        FieldLabel("검색 API 키 (Tavily, 선택)")
        InputBox(key, { v -> key = v; if (v.isBlank()) Secrets.remove(WebSearch.KEY_ID) else Secrets.put(WebSearch.KEY_ID, v.trim()) }, placeholder = "tvly-… (없으면 무료 웹 검색을 시도합니다)", password = true)
        Hint("검색 기능을 쓸 때, Claude·Gemini·OpenAI 공식 API는 자체 검색을 쓰고 그 외 모델은 앱이 검색합니다. 무료 검색은 막힐 수 있어 Tavily 키를 넣으면 더 안정적입니다.")
    }
}

// =====================================================================================
// 세션 요약 설정 (SGU Next)
// =====================================================================================

@Composable
fun SessionSummaryScreen(onBack: () -> Unit) {
    val cfg = SettingsStore.root.o("sessionSummary").orEmptyObj()
    fun set(k: String, v: JsonElement) = SettingsStore.update { m -> m["sessionSummary"] = JsonObject((m["sessionSummary"] as? JsonObject).orEmptyObj() + (k to v)) }
    Page("세션 요약", "긴 세션의 이전 턴을 짧게 요약해 보냅니다", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("자동 요약", style = MaterialTheme.typography.bodyLarge)
                Text("최근 턴만 원문으로 보내고, 그보다 오래된 턴은 짧은 요약으로 합칩니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            }
            SguSwitch(Workshop.summaryEnabled()) { set("enabled", JsonPrimitive(it)) }
        }
        FieldLabel("원문으로 보낼 최근 턴 수")
        InputBox(Workshop.summaryKeep().toString(), { v -> v.filter { it.isDigit() }.take(2).toIntOrNull()?.let { set("keepRecent", JsonPrimitive(it.coerceIn(1, 50))) } }, number = true)
        Hint("프롬프트 프리셋 '세션 기록' 블록의 '포함할 이전 턴 수'(기본 6)와 같게 두는 걸 권장해요.")
        FieldLabel("요약할 턴이 몇 개 쌓이면 요약할지")
        InputBox(Workshop.summaryEvery().toString(), { v -> v.filter { it.isDigit() }.take(2).toIntOrNull()?.let { set("everyTurns", JsonPrimitive(it.coerceIn(2, 50))) } }, number = true)
        Hint("기본 6: 최근 턴 바깥으로 밀려난 턴이 6개 쌓일 때마다 기존 요약과 합쳐 다시 요약해요. 요약은 서브 프로세서 모델(Jev·꺼짐이면 메인 모델)로 만들고, 10줄·1,200자 이내로 짧게 씁니다.")
        Spacer(Modifier.height(12.dp))
        Hint("요약은 세션 기록에만 저장돼 캐릭터 카드(RisuAI 호환)에는 영향이 없어요. 작업실 맨 위 요약 카드에서 보기·편집·지우기, ⋮ › 세션 요약하기로 바로 요약할 수 있어요.")
    }
}
