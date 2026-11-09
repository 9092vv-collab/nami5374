package space.sgu.next.ui

import kotlinx.serialization.json.JsonArray
import space.sgu.next.data.orEmptyObj
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Group
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.References
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.Elevation
import space.sgu.next.data.Processors
import space.sgu.next.data.SettingsStore
import space.sgu.next.data.SguData
import space.sgu.next.data.WFields
import space.sgu.next.data.Workshop
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SGU", text))
}

private fun fieldLabelW(id: String) = WFields.label(id)

/** 작업실: command → sub processor → permission check → main processor → edits (original 1655 chat panel). */
@Composable
fun WorkshopPage(cid: String, openFieldTab: (String) -> Unit, openRoute: (String) -> Unit) {
    val ctx = LocalContext.current
    val v = Workshop.version
    val doc = remember(cid, v) { Workshop.doc(cid) }
    val turns = doc.o("session").arr("turns")
    val job = Workshop.jobs[cid]
    val busy = job?.active == true
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable(cid) { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val mainAssigned = SettingsStore.root.o("processors").o("main").sOrNull("modelId") != null

    LaunchedEffect(turns.size, job?.phase) { if (listState.firstVisibleItemIndex < 3) listState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f), reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
        ) {
            if (job != null && job.phase != "idle" && job.phase != "done" && job.phase != "error") item(key = "job") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) Badge(Workshop.PHASES[job.phase] ?: job.phase, Sgu.Accent, Color.White)
                        else if (job.phase == "error") Badge("오류", Sgu.AccentSoft, Color(0xFF8A3B22))
                        if (job.phase == "generating" && (job.stream.isNotEmpty() || job.reasoning.isNotEmpty()))
                            Text(if (showRaw) "원문 숨기기" else "생성 중인 원문 보기", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.clickable { showRaw = !showRaw })
                    }
                    if (job.phase == "error" && job.error != null) Text(job.error, color = Sgu.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    val partial = SguData.partialMessage(job.stream)
                    if (job.phase == "generating" && partial.isNotEmpty()) Text(partial, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    if (showRaw && job.phase == "generating") {
                        Text((if (job.reasoning.isNotEmpty()) "[reasoning]\n${job.reasoning}\n\n" else "") + job.stream,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Sgu.Sub,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sgu.Bubble).padding(12.dp))
                    }
                }
            }
            items(turns.asReversed(), key = { it.s("id") }) { t ->
                TurnCard(cid, t, isLast = t.s("id") == turns.lastOrNull()?.s("id"), busy = busy, openFieldTab = openFieldTab)
            }
            if (turns.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Sgu.Accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("명령으로 캐릭터를 만듭니다", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(6.dp))
                    Text("예: \"냉소적인 엘프 궁수 캐릭터를 만들어줘. 디스크립션과 첫 메시지를 써줘\"\n서브 프로세서가 필요한 필드를 고르고, 권한이 부족하면 먼저 물어봅니다.",
                        style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (!mainAssigned) item(key = "noproc") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Sgu.Ink, RoundedCornerShape(20.dp)).clickable { openRoute("processors") }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = Sgu.Ink)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("프로세서가 아직 배정되지 않았습니다", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text("메인 프로세서를 설정하세요.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Sgu.Sub)
                }
            }
        }

        // Attached uploads and other characters used as references in this session (original 1655 chips).
        AttachedChips(cid, doc)
        // Claude-style input pill: white, soft shadow, grey round +, ink round send (■ while working).
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
                .shadow(8.dp, RoundedCornerShape(30.dp), ambientColor = Sgu.Ink.copy(alpha = 0.12f), spotColor = Sgu.Ink.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(30.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(30.dp)).padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val (canUndo, canRedo) = space.sgu.next.data.EditHistory.flags[cid] ?: (false to false)
            if (canUndo || canRedo) Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Workshop.undoLast(cid) }, enabled = canUndo && !busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "되돌리기", tint = if (canUndo && !busy) Sgu.Ink else Sgu.Sub.copy(alpha = 0.4f))
                }
                IconButton(onClick = { Workshop.redoNext(cid) }, enabled = canRedo && !busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "다시 실행", tint = if (canRedo && !busy) Sgu.Ink else Sgu.Sub.copy(alpha = 0.4f))
                }
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (doc.b("webSearch")) Sgu.AccentSoft else Sgu.Bubble).clickable { attach = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "첨부", tint = Sgu.Ink)
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (input.isEmpty()) Text("캐릭터에게 적용할 명령을 입력", color = Sgu.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(value = input, onValueChange = { input = it }, maxLines = 6, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Sgu.Ink),
                    cursorBrush = SolidColor(Sgu.Ink), modifier = Modifier.fillMaxWidth())
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (busy || input.isNotBlank()) Sgu.Ink else Sgu.Bubble).clickable {
                    when {
                        busy -> Workshop.cancel(cid)
                        input.isNotBlank() -> { Workshop.start(cid, input.trim()); input = ""; scope.launch { listState.animateScrollToItem(0) } }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) Icon(Icons.Filled.Stop, "중지", tint = Color.White)
                else Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (input.isBlank()) Sgu.Sub else Color.White)
            }
        }
        ModelBar(cid, doc) { modelSheet = true }
    }

    if (job?.phase == "awaiting") ElevationDialog(job.access?.elevation ?: emptyList(), job.access?.denied ?: emptyList(), job.decision?.task) { Workshop.approve(cid, it) }
    if (attach) AttachDialog(cid, doc) { attach = false }
    if (modelSheet) ModelListDialog(cid, doc) { modelSheet = false }
    @Suppress("UNUSED_VARIABLE") val unused = ctx
}

/** Main model for this session: default + ★ favorites as pills under the input (module 1690). */
@Composable
private fun ModelBar(cid: String, doc: JsonObject, openList: () -> Unit) {
    val defId = SettingsStore.root.o("processors").o("main").sOrNull("modelId")
    val current = Processors.mainModelId(doc)
    val shown = (listOfNotNull(SettingsStore.model(defId)) + SettingsStore.models.filter { it.b("favorite") && it.s("id") != defId }.take(2))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { m ->
            val name = m.s("name").ifBlank { m.s("modelId") } + if (m.s("id") == defId) " · 기본" else ""
            SmallPill(name, m.s("id") == current) { Workshop.set(cid, "mainModelId", if (m.s("id") == defId) JsonNull else JsonPrimitive(m.s("id"))) }
        }
        SmallPill("모델 목록", false, onClick = openList)
    }
}

@Composable
private fun SmallPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else Sgu.Sub,
        modifier = Modifier.clip(CircleShape).background(if (selected) Sgu.Ink else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun ModelListDialog(cid: String, doc: JsonObject, onDismiss: () -> Unit) {
    val defId = SettingsStore.root.o("processors").o("main").sOrNull("modelId")
    val current = Processors.mainModelId(doc)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("모델 목록") }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (SettingsStore.models.isEmpty()) Text("등록된 모델이 없습니다 · 설정 › 프로바이더에서 추가하세요", color = Sgu.Sub)
                Text("모델을 누르면 이 세션의 메인 모델로 선택됩니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                Spacer(Modifier.height(6.dp))
                SettingsStore.models.forEach { m ->
                    val id = m.s("id")
                    Text((m.s("name").ifBlank { m.s("modelId") }) + (if (id == defId) "  (기본값)" else "") + (if (m.b("favorite")) " ★" else ""),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (id == current) Sgu.Bubble else Color.Transparent)
                            .clickable { Workshop.set(cid, "mainModelId", if (id == defId) JsonNull else JsonPrimitive(id)); onDismiss() }.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Sub) } },
    )
}

@Composable
private fun AttachedChips(cid: String, doc: JsonObject) {
    val ups = (doc["attachedUploads"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.mapNotNull { References.get(it) } ?: emptyList()
    val chars = (doc["refChars"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    if (ups.isEmpty() && chars.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ups.size) { i ->
            val r = ups[i]
            Chip(refKindIcon(r.s("kind")), r.s("name")) {
                Workshop.updateDoc(cid) { d ->
                    val list = (d["attachedUploads"] as? JsonArray)?.filter { (it as? JsonPrimitive)?.content != r.s("id") } ?: emptyList()
                    JsonObject(d + mapOf("attachedUploads" to JsonArray(list), "refOverrides" to JsonObject(d.o("refOverrides").orEmptyObj() + (r.s("id") to JsonPrimitive("off")))))
                }
                References.remove(r.s("id"))
            }
        }
        items(chars.size) { i ->
            val id = chars[i]
            Chip(Icons.Outlined.Group, CharacterStore.items.firstOrNull { it.id == id }?.name ?: "(삭제된 캐릭터)") {
                Workshop.set(cid, "refChars", JsonArray(chars.filter { it != id }.map { JsonPrimitive(it) }))
            }
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onRemove: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(50)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sgu.Sub, modifier = Modifier.size(14.dp))
        Text(" " + label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Icon(Icons.Outlined.Close, "빼기", tint = Sgu.Sub, modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onRemove).padding(4.dp))
    }
}

/** + sheet (module 1688): web search, 참고 자료 활성화 (this session), 타 캐릭터 참고, 기기에서 업로드. */
@Composable
private fun AttachDialog(cid: String, doc: JsonObject, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("menu") }
    val v = References.version
    fun attachUploads(uris: List<android.net.Uri>) {
        val ids = uris.mapNotNull { References.addFile(ctx, it, null, cid)?.s("id") }
        if (ids.isEmpty()) return
        Workshop.updateDoc(cid) { d ->
            val ups = ((d["attachedUploads"] as? JsonArray)?.toList() ?: emptyList()) + ids.map { JsonPrimitive(it) }
            JsonObject(d + mapOf("attachedUploads" to JsonArray(ups), "refOverrides" to JsonObject(d.o("refOverrides").orEmptyObj() + ids.associateWith { JsonPrimitive("on") })))
        }
        if (ids.any { References.get(it)?.s("kind") == "image" } && !space.sgu.next.data.Chat.effectiveVision(Processors.mainModels(Workshop.doc(cid)).first))
            android.widget.Toast.makeText(ctx, "현재 메인 모델은 비전(이미지 입력)이 꺼져 있어 이미지를 볼 수 없습니다. 모델 설정에서 비전을 켜세요", android.widget.Toast.LENGTH_LONG).show()
        onDismiss()
    }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { attachUploads(it) }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { attachUploads(it) }
    @Composable
    fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, trailing: String, highlight: Boolean = false, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (highlight) Sgu.Accent else Sgu.Sub)
            Text("  $label", modifier = Modifier.weight(1f))
            Text(trailing, fontWeight = FontWeight.SemiBold, color = if (highlight) Sgu.Ink else Sgu.Sub)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Sgu.Card,
        title = { Text(when (page) { "refs" -> "참고 자료 활성화"; "chars" -> "타 캐릭터 참고"; else -> "첨부" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (page) {
                    "refs" -> {
                        if (Processors.isSubOff()) Text("서브 프로세서 비활성화 중: '서브 프로세서의 판단'으로 설정된 참고 자료는 현재 상시로 취급됩니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Danger)
                        Hint("이 세션에서만 바꿉니다. '기본'은 참고 자료 탭의 설정을 따릅니다.")
                        val list = remember(v, Workshop.version) { References.items.filter { it.sOrNull("sessionOf") == null || it.s("sessionOf") == cid } }
                        if (list.isEmpty()) Hint("참고 자료가 없습니다.")
                        list.forEach { r -> RefSessionRow(cid, doc, r) }
                    }
                    "chars" -> {
                        Hint("선택한 캐릭터의 데이터는 읽기 전용 참고로만 쓰입니다(열람 Lv1, 편집 Lv0). 서브 프로세서가 필요한 항목을 고르고, 승인하면 메인 프로세서에 전달됩니다.")
                        val chosen = (doc["refChars"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                        val others = CharacterStore.items.filter { it.id != cid }
                        if (others.isEmpty()) Hint("참고할 다른 캐릭터가 없습니다.")
                        others.forEach { c ->
                            val on = c.id in chosen
                            Row(Modifier.fillMaxWidth().clickable {
                                Workshop.set(cid, "refChars", JsonArray((if (on) chosen - c.id else chosen + c.id).map { JsonPrimitive(it) }))
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = if (on) Sgu.Ink else Sgu.Sub)
                                Text("  " + c.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    else -> {
                        MenuRow(Icons.Outlined.TravelExplore, "검색 기능 (웹 검색)", if (doc.b("webSearch")) "켜짐" else "꺼짐", doc.b("webSearch")) {
                            Workshop.set(cid, "webSearch", JsonPrimitive(!doc.b("webSearch")))
                        }
                        Hint("검색 기능을 켜면 메인 프로세서가 제공자 내장 웹 검색(Anthropic·Gemini·OpenAI Responses·OpenRouter)을 쓸 수 있습니다. 지원하지 않는 모델에서는 앱이 직접 검색해 결과를 넣어 줍니다. 서브 프로세서에는 적용되지 않습니다.")
                        Spacer(Modifier.height(6.dp))
                        val onCount = remember(v, Workshop.version) { References.items.count { References.state(doc, it) == "on" } }
                        MenuRow(Icons.AutoMirrored.Outlined.LibraryBooks, "참고 자료 활성화", "사용 $onCount") { page = "refs" }
                        val rc = (doc["refChars"] as? JsonArray)?.size ?: 0
                        MenuRow(Icons.Outlined.Group, "타 캐릭터 참고", if (rc > 0) "${rc}명" else "") { page = "chars" }
                        MenuRow(Icons.Outlined.Image, "기기에서 업로드 (이미지)", "") { pickImages.launch("image/*") }
                        MenuRow(Icons.Outlined.AttachFile, "기기에서 업로드 (파일)", "") { pickFiles.launch(arrayOf("*/*")) }
                        Hint("기기에서 올린 파일은 참고 자료에 저장되고 이 세션에서 바로 사용됩니다.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) } },
        dismissButton = { if (page != "menu") TextButton(onClick = { page = "menu" }) { Text("뒤로", color = Sgu.Sub) } },
    )
}

/** One reference in the session sheet (module 1689): 기본 / 사용 / 서브 판단 / 사용 안 함 for this session. */
@Composable
private fun RefSessionRow(cid: String, doc: JsonObject, r: JsonObject) {
    val id = r.s("id")
    val ov = doc.o("refOverrides")?.sOrNull(id) ?: "default"
    val st = References.state(doc, r)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(refKindIcon(r.s("kind")), null, tint = Sgu.Sub, modifier = Modifier.size(18.dp))
            Text("  " + r.s("name"), modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(when (st) { "on" -> "사용 중"; "sub" -> "서브 판단"; else -> "사용 안 함" }, style = MaterialTheme.typography.labelMedium,
                color = when (st) { "on" -> Sgu.Ink; "sub" -> Color(0xFF2563EB); else -> Sgu.Sub })
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("default" to "기본 (${References.MODE_LABELS[r.s("mode").ifBlank { "sub" }]})", "on" to "사용", "sub" to "서브 판단", "off" to "사용 안 함").forEach { (k, label) ->
                PillChip(label, ov == k) {
                    Workshop.updateDoc(cid) { d ->
                        val o = d.o("refOverrides").orEmptyObj()
                        val next = if (k == "default") JsonObject(o - id) else JsonObject(o + (id to JsonPrimitive(k)))
                        val active = (d["activeReferences"] as? JsonArray)?.filter { (it as? JsonPrimitive)?.content != id } ?: emptyList()
                        JsonObject(d + mapOf("refOverrides" to next, "activeReferences" to JsonArray(active)))
                    }
                }
            }
        }
    }
}

/** 권한 확인 요청 (module 1667). */
@Composable
private fun ElevationDialog(list: List<Elevation>, denied: List<Elevation>, task: String?, onDecide: (List<Elevation>) -> Unit) {
    var picked by remember(list) { mutableStateOf(list.map { "${it.kind}:${it.field}" }.toSet()) }
    AlertDialog(
        onDismissRequest = { onDecide(emptyList()) }, title = { Text("권한 확인 요청") }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("서브 프로세서가 이 작업에 아래 필드가 필요하다고 판단했습니다. 승인한 필드는 이번 세션 동안 Lv2로 상향됩니다.", style = MaterialTheme.typography.bodyMedium)
                if (!task.isNullOrBlank()) Hint("작업: $task")
                Spacer(Modifier.height(8.dp))
                list.forEach { e ->
                    val k = "${e.kind}:${e.field}"; val on = k in picked
                    Row(Modifier.fillMaxWidth().clickable { picked = if (on) picked - k else picked + k }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = if (on) Sgu.Ink else Sgu.Sub)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fieldLabelW(e.field), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                if (e.kind == "view") Badge("열람", Sgu.Bubble, Sgu.Ink) else Badge("편집")
                            }
                            Text(e.reason ?: WFields.map[e.field]?.aiDescription ?: "", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (denied.isNotEmpty()) Hint("요청했지만 Lv0(절대 금지)이라 자동 거부됨: " + denied.joinToString(", ") { "${fieldLabelW(it.field)}(${if (it.kind == "view") "열람" else "편집"})" })
                Hint("거부된 필드 없이도 작업은 계속 진행됩니다. 작업 자체를 멈추려면 입력창의 ■ 버튼으로 취소하세요.")
            }
        },
        confirmButton = { TextButton(onClick = { onDecide(list.filter { "${it.kind}:${it.field}" in picked }) }) { Text("승인 (${picked.size})", color = Sgu.Ink, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onDecide(emptyList()) }) { Text("모두 거부", color = Sgu.Sub) } },
    )
}

/** One turn: my command, the sub processor's call, SGU's answer with its edits (module 1658). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnCard(cid: String, t: JsonObject, isLast: Boolean, busy: Boolean, openFieldTab: (String) -> Unit) {
    val ctx = LocalContext.current
    val id = t.s("id")
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<String?>(null) }
    val status = t.s("status")
    val variants = t.arr("variants"); val vCount = variants.size; val vIndex = t.d("vi")?.toInt() ?: (vCount - 1)

    @Composable
    fun Actions(items: List<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>>, enabled: Boolean) {
        Row { items.forEach { (icon, label, act) -> IconButton(onClick = act, enabled = enabled, modifier = Modifier.size(36.dp)) { Icon(icon, label, Modifier.size(18.dp), tint = if (enabled) Sgu.Sub else Sgu.Line) } } }
    }

    @Composable
    fun Editor(which: String) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = null }) { Text("취소", color = Sgu.Sub) }
                TextButton(onClick = {
                    if (editText.isNotBlank()) Workshop.updateTurn(cid, id, if (which == "command") mapOf("command" to JsonPrimitive(editText))
                        else mapOf("response" to JsonPrimitive(editText)) + (if (status == "error") mapOf("status" to JsonPrimitive("done"), "error" to null) else emptyMap()))
                    editing = null
                }) { Text("저장", color = Sgu.Ink) }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // My message (right).
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (editing == "command") Editor("command") else SelectionContainer {
                Text(t.s("command"), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(22.dp)).background(Sgu.Bubble).border(1.dp, Sgu.Line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp))
            }
            if (editing != "command") Actions(listOf(
                Triple(Icons.Outlined.ContentCopy, "복사") { copy(ctx, t.s("command")); Toast.makeText(ctx, "복사됨", Toast.LENGTH_SHORT).show() },
                Triple(Icons.Outlined.Edit, "편집") { editText = t.s("command"); editing = "command" },
                Triple(Icons.Outlined.DeleteOutline, "삭제") { deleting = "command" },
            ), enabled = !busy)
        }
        // Sub processor's analysis.
        val analysis = t.o("analysis")
        val badge = when {
            t.b("noResponse") || status == "done" -> null
            status == "running" -> "진행 중"
            status == "error" -> "실패"
            else -> "취소"
        }
        if (analysis != null || badge != null) Row(Modifier.padding(top = 6.dp).clickable(enabled = analysis != null) { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            if (analysis != null) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Sgu.Sub, modifier = Modifier.size(18.dp))
                Text("서브 판단 · 열람 ${analysis.arr("view").size + (analysis["view"] as? kotlinx.serialization.json.JsonArray)?.count { it !is JsonObject }.let { (it ?: 0) - analysis.arr("view").size }} · 편집 ${(analysis["edit"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0}",
                    style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                Spacer(Modifier.width(6.dp))
            }
            badge?.let { Badge(it, if (it == "진행 중") Sgu.Accent else Sgu.Bubble, if (it == "진행 중") Color.White else Sgu.Sub) }
        }
        if (expanded && analysis != null) {
            Column(Modifier.padding(start = 8.dp, top = 6.dp).border(width = 0.dp, color = Color.Transparent).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (analysis.s("task").isNotBlank()) Text(analysis.s("task"), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    strings(analysis, "view").forEach { Badge("열람 · ${fieldLabelW(it)}", Sgu.Bubble, Sgu.Ink) }
                    strings(analysis, "edit").forEach { Badge("편집 · ${fieldLabelW(it)}") }
                }
                t.o("elevation")?.let { el ->
                    val a = strings(el, "approved"); val dn = strings(el, "denied")
                    if (a.isNotEmpty() || dn.isNotEmpty()) Text((if (a.isNotEmpty()) "승인 ${a.joinToString(", ") { lab(it) }}" else "") + (if (dn.isNotEmpty()) " · 거부 ${dn.joinToString(", ") { lab(it) }}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                }
                if (t.o("raw")?.sOrNull("sub") != null) Text("서브 프로세서 출력 보기", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Sgu.Accent,
                    modifier = Modifier.clickable { raw = "sub" })
            }
        }
        // SGU's answer (left).
        if (!t.b("noResponse") && (t.s("response").isNotEmpty() || t.s("error").isNotEmpty())) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                if (vCount >= 2) IconButton(onClick = { Workshop.switchVariant(cid, id, vIndex - 1) }, enabled = !busy && vIndex > 0, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.ChevronLeft, "이전", tint = Sgu.Sub) }
                Icon(Icons.Filled.AutoAwesome, null, tint = Sgu.Accent, modifier = Modifier.padding(top = 3.dp).size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (t.s("model").isNotEmpty()) Text("by ${t.s("model")}", style = MaterialTheme.typography.labelSmall, color = Sgu.Sub, maxLines = 1)
                    if (editing == "response") Editor("response") else {
                        if (status == "error" && t.s("error").isNotEmpty()) SelectionContainer { Text(t.s("error"), color = Sgu.Danger, style = MaterialTheme.typography.bodyMedium) }
                        if (t.s("response").isNotEmpty()) SelectionContainer { Text(t.s("response"), style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (status == "cancelled" && t.s("response").isNotEmpty()) Text("중단됨 · 진행된 부분까지만 표시됩니다 (변경은 적용되지 않았습니다)", style = MaterialTheme.typography.labelSmall, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp))
                    val edits = t.arr("edits")
                    if (edits.isNotEmpty()) Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        edits.forEach { e ->
                            Row(Modifier.fillMaxWidth().clickable { openFieldTab(e.s("field")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Edit, null, tint = Sgu.Accent, modifier = Modifier.size(14.dp))
                                Text("  " + e.s("summary"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.ChevronRight, null, tint = Sgu.Sub, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else if (status == "done") Text("변경 사항 없음", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp))
                    if (editing != "response") {
                        val canReroll = isLast && status != "running"
                        Actions(listOfNotNull(
                            if (canReroll) Triple(Icons.Outlined.Refresh, "리롤") { Workshop.reroll(cid, id) } else null,
                            Triple(Icons.Outlined.ContentCopy, "복사") { copy(ctx, t.s("response")); Toast.makeText(ctx, "복사됨", Toast.LENGTH_SHORT).show() },
                            Triple(Icons.Outlined.Edit, "편집") { if (t.s("response").isNotEmpty()) { editText = t.s("response"); editing = "response" } },
                            if (t.o("snapshot")?.isNotEmpty() == true && !t.b("undone")) Triple(Icons.AutoMirrored.Outlined.Undo, "되돌림") { Workshop.undoTurn(cid, id) } else null,
                            Triple(Icons.Outlined.DeleteOutline, "삭제") { deleting = "response" },
                        ), enabled = !busy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val u = t.o("usage")
                        if (u != null && (u.d("inputTokens") != null || u.d("outputTokens") != null))
                            Text("${u.d("inputTokens")?.toInt() ?: "?"} in · ${u.d("outputTokens")?.toInt() ?: "?"} out", style = MaterialTheme.typography.labelSmall, color = Sgu.Sub)
                        if (t.o("raw")?.sOrNull("main") != null) Row(Modifier.clickable { raw = "main" }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Code, null, tint = Sgu.Accent, modifier = Modifier.size(14.dp))
                            Text(" 메인 출력 보기", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Sgu.Accent)
                        }
                        if (vCount >= 2) Text("${vIndex + 1}/$vCount", style = MaterialTheme.typography.labelSmall, color = Sgu.Sub)
                        if (t.b("undone")) Text("되돌려진 편집", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFB7791F))
                    }
                }
                if (vCount >= 2) IconButton(onClick = { Workshop.switchVariant(cid, id, vIndex + 1) }, enabled = !busy && vIndex < vCount - 1, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.ChevronRight, "다음", tint = Sgu.Sub) }
            }
        }
    }

    raw?.let { which ->
        val text = t.o("raw")?.s(which) ?: ""
        AlertDialog(
            onDismissRequest = { raw = null }, containerColor = Sgu.Card,
            title = { Text(if (which == "sub") "서브 프로세서 출력" else "메인 프로세서 출력 (원문)") },
            text = { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { copy(ctx, text); Toast.makeText(ctx, "복사됨", Toast.LENGTH_SHORT).show() }) { Text("복사", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { raw = null }) { Text("닫기", color = Sgu.Sub) } },
        )
    }
    deleting?.let { which ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Sgu.Card, title = { Text("메시지 삭제") },
            text = { Text(if (which == "response") "SGU의 응답을 삭제하고, 그 응답이 한 작업은 취소됩니다. 내 메시지는 그대로 남습니다." else "이 메시지와 SGU의 응답을 삭제하고, 그 응답이 한 작업은 취소됩니다.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { deleting = null; Workshop.deleteTurns(cid, id, "only", which) }) { Text("이 메시지만 삭제", color = Sgu.Danger) }
                    TextButton(onClick = { deleting = null; Workshop.deleteTurns(cid, id, "after", which) }) { Text("이 이후 메시지도 삭제", color = Sgu.Danger) }
                    TextButton(onClick = { deleting = null }) { Text("취소", color = Sgu.Sub) }
                }
            },
        )
    }
}

private fun strings(o: JsonObject, k: String) = ((o[k] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.content }
private fun lab(k: String): String { val i = k.indexOf(':'); return if (i < 0) k else fieldLabelW(k.substring(i + 1)) + "(" + (if (k.startsWith("view")) "열람" else "편집") + ")" }

/** 세션 이동 (module 1657). */
@Composable
fun SessionDialog(cid: String, busy: Boolean, onDismiss: () -> Unit) {
    val v = Workshop.version
    val doc = remember(cid, v) { Workshop.doc(cid) }
    val cur = Workshop.currentSessionId(doc)
    var renaming by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("세션 이동") }, containerColor = Sgu.Card,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("봇 데이터는 모든 세션이 공유합니다. 세션마다 턴 기록과 권한은 따로이며, 다른 세션의 대화는 프롬프트에 전달되지 않습니다." + if (busy) " 작업 중에는 이동할 수 없습니다." else "")
                val list = Workshop.sessionList(doc)
                list.forEach { s ->
                    val sid = s.s("id"); val isCur = sid == cur
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(if (isCur) 2.dp else 1.dp, if (isCur) Sgu.Ink else Sgu.Line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (renaming == sid) {
                            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                            TextButton(onClick = { Workshop.renameSession(cid, sid, name); renaming = null }) { Text("저장", color = Sgu.Ink) }
                        } else {
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { Workshop.switchSession(cid, sid); onDismiss() }) {
                                Text(s.s("name"), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text((if (isCur) "현재 세션 · " else "") + "${Workshop.sessionTurnCount(doc, sid)}턴", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                            }
                            IconButton(onClick = { renaming = sid; name = s.s("name") }) { Icon(Icons.Outlined.Edit, "이름 변경", tint = Sgu.Sub, modifier = Modifier.size(18.dp)) }
                            if (list.size > 1) IconButton(onClick = { removing = sid }) { Icon(Icons.Outlined.DeleteOutline, "삭제", tint = Sgu.Sub, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Ink) } },
    )
    removing?.let { sid ->
        ConfirmDialog("세션 삭제", "이 세션의 턴 기록과 세션 권한이 삭제됩니다. 봇 데이터는 그대로 유지됩니다.", "삭제",
            onConfirm = { Workshop.removeSession(cid, sid) }, onDismiss = { removing = null })
    }
}
