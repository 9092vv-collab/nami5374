package space.sgu.next.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.Processors
import space.sgu.next.data.References
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull

private val SUB_BLUE = Color(0xFF2563EB)

fun refKindIcon(kind: String): ImageVector = when (kind) {
    "image" -> Icons.Outlined.Image
    "persona" -> Icons.Outlined.Face
    "text" -> Icons.AutoMirrored.Outlined.Notes
    else -> Icons.Outlined.AttachFile
}

@Composable
private fun ModeLabel(mode: String) {
    val (c, w) = when (mode) { "always" -> Sgu.Ink to FontWeight.SemiBold; "sub" -> SUB_BLUE to FontWeight.SemiBold; "keyword" -> Sgu.Accent to FontWeight.SemiBold; else -> Sgu.Sub to FontWeight.Normal }
    Text(References.MODE_LABELS[mode] ?: mode, color = c, fontWeight = w, style = MaterialTheme.typography.bodyMedium)
}

// =====================================================================================
// 참고 자료 탭 (module 1064)
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencesScreen(open: (String) -> Unit) {
    val v = References.version
    val ctx = LocalContext.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var addOpen by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<JsonObject?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    var persona by remember { mutableStateOf<Pair<android.net.Uri, References.Persona>?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        val p = References.peekPersona(ctx, u)
        if (p != null) persona = u to p
        else References.addFile(ctx, u)?.let { open("ref/${it.s("id")}") }
    }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val items = remember(v) { References.items.filter { it.sOrNull("sessionOf") == null } }
    val folders = remember(v) { References.folders.toList() }
    val loose = items.filter { r -> r.sOrNull("folderId") == null || folders.none { it.s("id") == r.s("folderId") } }
    Scaffold(
        containerColor = Sgu.Bg,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SguTopBar(
                title = "참고 자료", subtitle = "폴더로 분류하고 활성화 방식을 정합니다", scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "폴더 추가") }
                    IconButton(onClick = { addOpen = true }) { Icon(Icons.Outlined.Add, "참고 자료 추가") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp, start = 16.dp, end = 16.dp)) {
            if (Processors.isSubOff()) item { Text("서브 프로세서 비활성화 중: '서브 프로세서의 판단'으로 설정된 참고 자료는 현재 상시로 취급됩니다.",
                style = MaterialTheme.typography.bodySmall, color = Sgu.Danger, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp)) }
            if (items.isEmpty() && folders.isEmpty()) item {
                EmptyPane("참고 자료가 없습니다")
                Hint("설정 문서, 세계관 메모, 말투 샘플, 참고 이미지 등을 추가하세요. 활성화 방식은 상시 / 서브 프로세서의 판단 / 비활성화 중에서 고릅니다.")
            }
            folders.forEach { f ->
                val fid = f.s("id")
                val inside = items.filter { it.sOrNull("folderId") == fid }
                val isOpen = expanded[fid] == true
                item(key = "f-$fid") {
                    val angle by animateFloatAsState(if (isOpen) 0f else -90f, label = "arrow")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { expanded[fid] = !isOpen }.padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(angle), tint = Sgu.Sub)
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.Outlined.Folder, null, tint = Sgu.Sub)
                        Text("  ${f.s("name")}" + if (!isOpen) " (${inside.size})" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renaming = f }) { Icon(Icons.Outlined.Edit, "폴더 이름", tint = Sgu.Sub) }
                        IconButton(onClick = { removing = f }) { Icon(Icons.Outlined.Delete, "폴더 삭제", tint = Sgu.Sub) }
                    }
                }
                if (isOpen) {
                    item(key = "m-$fid") { FolderModes(fid, inside) }
                    if (inside.isEmpty()) item(key = "e-$fid") { Hint("비어 있는 폴더입니다. 자료를 편집해 이 폴더로 옮기거나 추가할 때 폴더를 고르세요.") }
                    items(inside, key = { it.s("id") }) { r -> RefRow(r) { open("ref/${r.s("id")}") } }
                }
            }
            if (loose.isNotEmpty()) {
                item(key = "loose") { Text("폴더 없음", style = MaterialTheme.typography.titleSmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp)) }
                item(key = "m-loose") { FolderModes(null, loose) }
                items(loose, key = { it.s("id") }) { r -> RefRow(r) { open("ref/${r.s("id")}") } }
            }
        }
    }
    if (addOpen) AlertDialog(
        onDismissRequest = { addOpen = false }, title = { Text("참고 자료 추가") }, containerColor = Sgu.Card,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("텍스트 직접 입력", onClick = { addOpen = false; open("ref/" + References.addText("새 자료", "").s("id")) }, icon = Icons.AutoMirrored.Outlined.Notes, modifier = Modifier.fillMaxWidth())
                LineButton("파일 선택 (텍스트·이미지·기타)", onClick = { addOpen = false; pickFile.launch(arrayOf("*/*")) }, icon = Icons.Outlined.AttachFile, modifier = Modifier.fillMaxWidth())
                LineButton("페르소나", onClick = {
                    addOpen = false
                    val pf = References.folders.firstOrNull { it.s("name") == "페르소나" }?.s("id")
                    open("ref/" + References.addPersona("페르소나", "", "", pf).s("id"))
                }, icon = Icons.Outlined.Face, modifier = Modifier.fillMaxWidth())
                Hint("텍스트/마크다운/JSON은 본문이 그대로 전달되고, 이미지는 비전 지원 모델일 때 전달됩니다. 다른 파일은 이름과 메모만 전달됩니다.")
            }
        },
        confirmButton = { TextButton(onClick = { addOpen = false }) { Text("닫기", color = Sgu.Sub) } },
    )
    if (newFolder) NameDialog("폴더 추가", "", "폴더 이름", onDismiss = { newFolder = false }) { References.addFolder(it) }
    persona?.let { (u, p) -> PersonaImportDialog(p,
        onPersona = {
            persona = null
            val r = References.addPersona(p.name, p.prompt, p.note)
            if (p.png) References.setPersonaImage(ctx, r.s("id"), u)
            open("ref/${r.s("id")}")
        },
        onPlain = { persona = null; References.addFile(ctx, u)?.let { open("ref/${it.s("id")}") } },
        onDismiss = { persona = null }) }
    renaming?.let { f -> NameDialog("폴더 이름", f.s("name"), "폴더 이름", onDismiss = { renaming = null }) { References.renameFolder(f.s("id"), it) } }
    removing?.let { f -> ConfirmDialog("폴더 삭제", "'${f.s("name")}' 폴더를 지울까요? 안의 자료는 '폴더 없음'으로 옮겨집니다.", "삭제",
        onConfirm = { References.removeFolder(f.s("id")) }, onDismiss = { removing = null }) }
}

@Composable
internal fun NameDialog(title: String, initial: String, placeholder: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, containerColor = Sgu.Card,
        text = { InputBox(name, { name = it }, placeholder = placeholder) },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text("저장", color = Sgu.Ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Sgu.Sub) } },
    )
}

/** 전체 상시 / 전체 서브 판단 / 전체 비활성화 — the chip is filled when every item in the folder has that mode. */
@Composable
private fun FolderModes(folderId: String?, inside: List<JsonObject>) {
    Row(Modifier.padding(start = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("always" to "전체 상시", "sub" to "전체 서브 판단", "off" to "전체 비활성화").forEach { (m, label) ->
            val all = inside.isNotEmpty() && inside.all { it.s("mode").ifBlank { "sub" } == m }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (all) Color.White else Sgu.Ink,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (all) Sgu.Ink else Sgu.Card).border(1.dp, if (all) Sgu.Ink else Sgu.Line, RoundedCornerShape(50))
                    .clickable { References.setFolderMode(folderId, m) }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun RefRow(r: JsonObject, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(refKindIcon(r.s("kind")), null, tint = Sgu.Sub, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.s("name"), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(References.kindLabel(r.s("kind")), References.sizeLabel(r.d("bytes")?.toLong() ?: 0L), r.s("note")).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        ModeLabel(r.s("mode").ifBlank { "sub" })
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Sgu.Sub)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Sgu.Line))
}

// =====================================================================================
// 참고 자료 편집 (module 1064 sheet → full screen)
// =====================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReferenceEditScreen(id: String, openRoute: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val v = References.version
    val r = remember(v, id) { References.get(id) }
    if (r == null) { Page("참고 자료 편집", null, onBack) { EmptyPane("자료를 찾을 수 없어요") }; return }
    var name by remember(id) { mutableStateOf(r.s("name")) }
    var note by remember(id) { mutableStateOf(r.s("note")) }
    var keywords by remember(id) { mutableStateOf(r.s("keywords")) }
    var depth by remember(id) { mutableStateOf((r.d("keywordDepth")?.toInt() ?: 1).toString()) }
    var removing by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(listOf(name, note, keywords, depth))
    DisposableEffect(id) {
        onDispose {
            val (n, no, k, dp) = latest
            if (References.get(id) != null) References.update(id, mapOf("name" to JsonPrimitive(n.ifBlank { "자료" }), "note" to JsonPrimitive(no),
                "keywords" to JsonPrimitive(k), "keywordDepth" to JsonPrimitive(maxOf(1, dp.toIntOrNull() ?: 1))))
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) References.setPersonaImage(ctx, id, u) }
    val kind = r.s("kind")
    val mode = r.s("mode").ifBlank { "sub" }
    Page("참고 자료 편집", References.kindLabel(kind) + " · " + References.sizeLabel(r.d("bytes")?.toLong() ?: 0L), onBack, actions = {
        IconButton(onClick = { removing = true }) { Icon(Icons.Outlined.Delete, "삭제") }
    }) {
        FieldLabel("이름")
        InputBox(name, { name = it }, placeholder = "예: 세계관 설정")
        FieldLabel("메모 (AI에게 전달되는 설명)")
        InputBox(note, { note = it }, placeholder = "예: 말투 참고용", singleLine = false)
        if (kind == "persona") {
            FieldLabel("페르소나 이미지")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileImage(References.imageFile(r)?.takeIf { it.exists() }, Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)), 256)
                Spacer(Modifier.size(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineButton(if (r.o("personaImage") != null) "변경" else "선택", onClick = { pickImage.launch("image/*") }, icon = Icons.Outlined.Image)
                    if (r.o("personaImage") != null) TextButton(onClick = { References.setPersonaImage(ctx, id, null) }) { Text("이미지 지우기", color = Sgu.Sub) }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("메모도 함께 보내기", Modifier.weight(1f).padding(start = 6.dp)); SguSwitch(r.b("sendNote")) { References.update(id, mapOf("sendNote" to JsonPrimitive(it))) }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("이미지도 함께 보내기 (비전 모델)", Modifier.weight(1f).padding(start = 6.dp)); SguSwitch(r.b("sendImage")) { References.update(id, mapOf("sendImage" to JsonPrimitive(it))) }
            }
            Hint("페르소나는 {{user}}의 정보로 참고됩니다.")
        }
        FieldLabel("폴더")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChip("폴더 없음", r.sOrNull("folderId") == null) { References.update(id, mapOf("folderId" to JsonNull)) }
            References.folders.forEach { f -> PillChip(f.s("name"), r.sOrNull("folderId") == f.s("id")) { References.update(id, mapOf("folderId" to JsonPrimitive(f.s("id")))) } }
        }
        FieldLabel("활성화 설정")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            References.MODE_LABELS.forEach { (m, label) -> PillChip(label, mode == m) { References.update(id, mapOf("mode" to JsonPrimitive(m))) } }
        }
        Hint(when (mode) {
            "always" -> "모든 요청에 항상 함께 보냅니다."
            "sub" -> "서브 프로세서가 필요하다고 판단하면, 승인 후 보냅니다."
            "keyword" -> "유저 입력에 키워드가 있을 때만 보냅니다."
            else -> "보내지 않습니다."
        })
        if (mode == "keyword") {
            FieldLabel("키워드")
            InputBox(keywords, { keywords = it }, placeholder = "keyword1|keyword2|keyword3")
            Hint("| 로 구분해 여러 키워드를 쓸 수 있습니다. 대소문자는 구분하지 않습니다.")
            FieldLabel("감지 깊이")
            InputBox(depth, { d -> depth = d.filter { it.isDigit() }.take(2) }, number = true)
            Hint("기본 1 = 마지막 유저 입력만. 3이면 최근 유저 입력 3개까지 감지합니다.")
        }
        when (kind) {
            "text", "persona" -> {
                FieldLabel(if (kind == "persona") "내용 ({{user}} 정보)" else "텍스트 내용")
                val text = remember(v, id) { References.readText(id) ?: "" }
                LineCard(onClick = { openRoute("reftext/$id") }) {
                    if (text.isBlank()) Text("비어 있음 · 눌러서 작성", color = Sgu.Sub.copy(alpha = 0.7f))
                    else Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            "image" -> {
                FieldLabel("이미지")
                FileImage(References.file(r), Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)), 1024)
                Hint("비전(이미지 입력)을 지원하는 메인 모델일 때 전달됩니다.")
            }
            else -> Hint("이 파일은 본문 대신 이름과 메모만 전달됩니다.")
        }
    }
    if (removing) ConfirmDialog("참고 자료 삭제", "'${r.s("name")}'을(를) 삭제할까요?", "삭제", onConfirm = { References.remove(id); onBack() }, onDismiss = { removing = false })
}

/** Full-screen text editor for a reference (same feel as the field editor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefTextScreen(id: String, onBack: () -> Unit) {
    val original = remember(id) { References.readText(id) ?: "" }
    var text by remember(id) { mutableStateOf(original) }
    val latest by rememberUpdatedState(text)
    DisposableEffect(id) { onDispose { if (latest != original && References.get(id) != null) References.updateText(id, latest) } }
    BackHandler { onBack() }
    Scaffold(
        containerColor = Sgu.Bg,
        topBar = {
            SguTopBar(title = "내용", subtitle = References.get(id)?.s("name"), onBack = onBack,
                actions = { IconButton(onClick = onBack) { Icon(Icons.Outlined.Check, "완료") } })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (text.isEmpty()) Text("직접 입력하거나 아래에서 파일을 선택하세요", color = Sgu.Sub, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Sgu.Ink), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 페르소나 감지 시트 (original 1071 usePersonaImport). */
@Composable
private fun PersonaImportDialog(p: References.Persona, onPersona: () -> Unit, onPlain: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Sgu.Card,
        title = { Text(if (p.png) "페르소나 양식이 감지되었습니다" else "페르소나 데이터 감지") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RisuAI 페르소나 형식의 파일입니다. 페르소나 유형으로 등록할까요? 등록하면 내용이 {{user}}의 정보로 참고됩니다.", style = MaterialTheme.typography.bodyMedium)
                LineCard {
                    Column {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                        Text(p.prompt.previewText(5).ifBlank { "(내용 없음)" }, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                InkButton(if (p.png) "페르소나로 가져오기" else "페르소나로 등록", onPersona, Modifier.fillMaxWidth())
                LineButton(if (p.png) "이미지로 가져오기" else "일반 자료로 등록", onPlain, Modifier.fillMaxWidth())
                Text("페르소나 자료는 참고 자료 목록에서 일반 자료와 같이 활성화 방식(상시 / 서브 판단 / 비활성화)을 정할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            }
        },
        confirmButton = {},
    )
}
