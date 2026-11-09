package space.sgu.next.ui

import space.sgu.next.data.SettingsStore

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import space.sgu.next.data.CardIO
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.GenRun
import space.sgu.next.data.ImageGenStore
import space.sgu.next.data.StyleLibrary
import space.sgu.next.data.TemplateLibrary
import space.sgu.next.data.Templates
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull
import space.sgu.next.data.with
import java.io.File

// =====================================================================================
// Files: save / pick through the system file picker
// =====================================================================================

private val pretty = Json { prettyPrint = true }

/** Remembers a "save as" picker; call the returned function with a file name and the bytes to write. */
@Composable
fun rememberSaver(mime: String): (String, (java.io.OutputStream) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<((java.io.OutputStream) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri: Uri? ->
        val w = pending; pending = null
        if (uri != null && w != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { w(it) } }.isSuccess }
            Toast.makeText(ctx, if (ok) "저장했어요" else "저장하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    return { name, write -> pending = write; launcher.launch(name) }
}

/** Remembers a JSON file picker that hands back the parsed object (null = not JSON). */
@Composable
fun rememberJsonPicker(onPicked: (JsonObject?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onPicked(runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { CardIO.json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
        }.getOrNull())
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) }
}

private fun sguFile(kind: String, data: JsonObject): ByteArray = pretty.encodeToString(JsonElement.serializer(), buildJsonObject {
    put("sgu", JsonPrimitive(kind)); put("version", JsonPrimitive(1)); put("data", data)
}).toByteArray(Charsets.UTF_8)

@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) { view.keepScreenOn = on; onDispose { view.keepScreenOn = false } }
}

// =====================================================================================
// 이미지 템플릿 탭 (module 1682 TemplateTab)
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateTab(cid: String, openPreset: (String) -> Unit, openRoute: (String) -> Unit) {
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val presets = g.arr("presets")
    val running = GenRun.running(cid)
    val progress = GenRun.progress[cid]
    val currentPid = GenRun.current[cid]
    val selected = remember(cid) { mutableStateListOf<String>() }
    var batchIds by remember { mutableStateOf<List<String>?>(null) }
    var compressOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val saveZip = rememberSaver("application/zip")
    KeepScreenOn(running)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { openRoute("gentemplates/$cid") }) { Icon(Icons.Outlined.Menu, "템플릿 목록") }
            Text(g.s("templateName").ifBlank { Templates.DEFAULT_NAME }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (progress != null) LineCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("이미지 작업중 ${progress.done}/${progress.total} · 예상 잔여 시간: ${GenRun.eta(progress)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        PillChip("중지", selected = false, icon = Icons.Outlined.Stop) { GenRun.stop(cid) }
                    }
                    LinearProgressIndicator(progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape), color = Sgu.Ink, trackColor = Sgu.Line)
                }
            }
            GenRun.compressing[cid]?.let { (d, t) ->
                Spacer(Modifier.height(6.dp))
                LineCard {
                    Column {
                        Text("이미지 압축중 $d/$t", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { if (t > 0) d.toFloat() / t else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape), color = Sgu.Ink, trackColor = Sgu.Line)
                    }
                }
            } ?: GenRun.compressMsg[cid]?.let { m ->
                Spacer(Modifier.height(6.dp))
                LineCard(onClick = { GenRun.compressMsg.remove(cid) }) { Text(m, style = MaterialTheme.typography.bodyMedium) }
            }
            GenRun.error[cid]?.let { e ->
                Spacer(Modifier.height(6.dp))
                LineCard(onClick = { GenRun.error.remove(cid) }) { Text(e, style = MaterialTheme.typography.bodyMedium, color = Sgu.Danger) }
            }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val anyImages = presets.any { it.arr("images").isNotEmpty() }
                PillChip("일괄 생성", selected = false) { if (!running && presets.isNotEmpty()) batchIds = presets.map { it.s("id") } }
                PillChip("이미지 선별", selected = false) { if (anyImages) openRoute("genpick/$cid") }
                PillChip("이미지 압축", selected = false) { if (anyImages && GenRun.compressing[cid] == null) compressOpen = true }
                PillChip("내보내기", selected = false) {
                    if (ImageGenStore.selectedCount(g) == 0) GenRun.compressMsg[cid] = "선별된 이미지가 없습니다. 이미지 선별에서 먼저 선택하세요."
                    else saveZip(ImageGenStore.safeName(g.s("templateName").ifBlank { "image-template" }) + ".zip") { ImageGenStore.writeSelectedZip(cid, it) }
                }
            }
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(20.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size}개 선택됨", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected.clear(); selected.addAll(presets.map { it.s("id") }) }) { Text("전체", color = Sgu.Ink) }
                    TextButton(onClick = { if (!running) batchIds = presets.map { it.s("id") }.filter { it in selected } }) { Text("생성", color = Sgu.Ink) }
                    TextButton(onClick = { if (!running) deleteOpen = true }) { Text("삭제", color = Sgu.Danger) }
                    IconButton(onClick = { selected.clear() }) { Icon(Icons.Outlined.Close, "선택 해제", tint = Sgu.Sub) }
                }
            } else Text("프리셋마다 에셋/감정 이미지를 생성해 둡니다. 프리셋을 길게 누르면 여러 개를 선택할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(presets, key = { it.s("id") }) { p ->
                val id = p.s("id")
                val isSel = id in selected
                val isNow = currentPid == id
                Column(Modifier.combinedClickable(
                    onClick = { if (selected.isNotEmpty()) { if (isSel) selected.remove(id) else selected.add(id) } else openPreset(id) },
                    onLongClick = { if (!running && !isSel) selected.add(id) },
                )) {
                    Box {
                        FileImage(ImageGenStore.thumb(p)?.let { ImageGenStore.imageFile(cid, it.s("file")) },
                            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(18.dp))
                                .border(if (isSel || isNow) 2.dp else 1.dp, if (isNow) Sgu.Accent else if (isSel) Sgu.Ink else Sgu.Line, RoundedCornerShape(18.dp)), 384)
                        if (isNow) Box(Modifier.matchParentSize().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        }
                        if (selected.isNotEmpty()) Icon(if (isSel) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                            tint = if (isSel) Sgu.Ink else Sgu.Sub, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Sgu.Bg))
                        val n = p.arr("images").count { it.b("selected") }
                        if (n > 0) Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { Badge("반영 $n") }
                    }
                    Text(p.s("name").ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
            item(key = "add") {
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(18.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)).clickable {
                            openPreset(ImageGenStore.addPreset(cid, "프리셋 ${presets.size + 1}"))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, null, tint = Sgu.Sub) }
                    Text("프리셋 추가", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
        }
    }

    batchIds?.let { ids -> BatchDialog(ids.size, onDismiss = { batchIds = null }) { n -> batchIds = null; selected.clear(); GenRun.run(cid, ids, n) } }
    if (compressOpen) CompressDialog(cid) { compressOpen = false }
    if (deleteOpen) AlertDialog(
        onDismissRequest = { deleteOpen = false }, title = { Text("선택한 프리셋 ${selected.size}개 삭제") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("프리셋 삭제 (프리셋 자체를 삭제)", onClick = { val ids = selected.toList(); deleteOpen = false; selected.clear(); ids.forEach { ImageGenStore.removePresetFull(cid, it) } })
                LineButton("이미지만 삭제 (프리셋은 유지)", onClick = { val ids = selected.toList(); deleteOpen = false; selected.clear(); ids.forEach { ImageGenStore.clearPresetImages(cid, it) } })
            }
        },
        confirmButton = { TextButton(onClick = { deleteOpen = false }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
    )
}

@Composable
fun BatchDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var n by remember { mutableStateOf("1") }
    val times = (n.toIntOrNull() ?: 1).coerceAtLeast(1)
    val total = count * times
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (count == 1) "생성" else "일괄 생성") },
        text = {
            Column {
                FieldLabel(if (count == 1) "생성할 횟수 (n)" else "프리셋마다 생성할 횟수 (n)")
                InputBox(n, { v -> n = v.filter { it.isDigit() }.take(3) }, number = true)
                Hint(if (count == 1) "이 프리셋을 ${times}회 생성합니다." else "프리셋 ${count}개 × ${times}회 = 총 ${total}장을 차례로 생성합니다.")
                Text("예상 할당량 소모: ${Math.ceil(100.0 * total / 1730).toInt()}%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp, top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onGo(times) }) { Text("진행", color = Sgu.Ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
    )
}

@Composable
private fun CompressDialog(cid: String, onDismiss: () -> Unit) {
    var webp by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("80") }
    val quality = (q.toIntOrNull() ?: 80).coerceIn(5, 100)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("이미지 압축") },
        text = {
            Column {
                Hint("선택한 형식과 품질로 이미지를 다시 저장합니다. 봇에 반영된 이미지도 새 파일로 다시 반영됩니다. 진행 중에도 다른 작업을 할 수 있습니다.")
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("WebP", webp) { webp = true }
                    PillChip("JPEG", !webp) { webp = false }
                }
                FieldLabel("품질 (%)")
                InputBox(q, { v -> q = v.filter { it.isDigit() }.take(3) }, number = true)
                Hint("기본 80. 낮을수록 용량이 작아집니다.")
                Spacer(Modifier.height(12.dp))
                InkButton("모두 압축 (${ImageGenStore.countCompressTargets(cid, false)}장)", onClick = { onDismiss(); GenRun.compress(cid, webp, quality, false) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LineButton("압축되지 않은 에셋만 (${ImageGenStore.countCompressTargets(cid, true)}장)", onClick = { onDismiss(); GenRun.compress(cid, webp, quality, true) }, modifier = Modifier.fillMaxWidth())
                Hint("'압축되지 않은 에셋만'은 WebP/JPEG가 아닌 무거운 이미지(PNG 등)만 변환합니다.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Sub) } }, containerColor = Sgu.Card,
    )
}

// =====================================================================================
// 이미지 템플릿 목록 (module 1683 TemplateManager)
// =====================================================================================

@Composable
fun TemplateManagerScreen(cid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(cid) { TemplateLibrary.syncFrom(cid) }
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val currentId = g.sOrNull("templateId") ?: "current"
    var msg by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<JsonObject?>(null) }
    var importing by remember { mutableStateOf<JsonObject?>(null) }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    val saveJson = rememberSaver("application/json")
    fun fileName(n: String) = "sgu-image-template-${n.ifBlank { "template" }.replace(Regex("[^\\w가-힣-]+"), "_")}.json"
    val pick = rememberJsonPicker { o ->
        when {
            o == null -> msg = "가져오기 실패: JSON 파일이 아니에요."
            o.s("sgu") != Templates.KIND -> msg = "이미지 템플릿 파일이 아닙니다."
            Templates.importTemplate(g, o.o("data")) == null -> msg = "가져올 수 없는 데이터입니다."
            else -> {
                val name = o.o("data").s("name").ifBlank { "가져온 템플릿" }
                TemplateLibrary.add(name, o.o("data")!!)
                msg = "'$name'을(를) 목록에 추가했습니다. 탭해서 선택하세요."
            }
        }
    }

    Page("이미지 템플릿", CharacterStore.items.firstOrNull { it.id == cid }?.name, onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("새 템플릿", onClick = { TemplateLibrary.add("이미지 템플릿 ${TemplateLibrary.items.size + 1}", Templates.exportTemplate(ImageGenStore.blankState())) }, icon = Icons.Outlined.Add)
            LineButton("업로드", onClick = pick, icon = Icons.Outlined.FileUpload)
        }
        Hint("목록에서 템플릿을 탭해 선택합니다(모든 캐릭터 공용). ★는 새 프로젝트가 시작할 때 쓰는 기본 템플릿입니다.")
        if (msg.isNotEmpty()) Hint(msg)
        Spacer(Modifier.height(8.dp))
        TemplateLibrary.items.forEach { t ->
            val id = t.s("id"); val isCur = currentId == id
            var menu by remember(id) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Sgu.Card)
                    .border(if (isCur) 2.dp else 1.dp, if (isCur) Sgu.Ink else Sgu.Line, RoundedCornerShape(20.dp))
                    .clickable { ImageGenStore.update(cid) { Templates.selectLibrary(it, id, t.s("name"), t.o("data") ?: JsonObject(emptyMap())) } }
                    .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.s("name"), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (isCur) "사용 중 · " else "탭하여 선택 · ") + (if (t.b("favorite")) "새 프로젝트 기본값 · " else "") + "프리셋 ${t.o("data").arr("presets").size}개",
                        style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                }
                IconButton(onClick = { TemplateLibrary.toggleFavorite(id) }) {
                    Icon(if (t.b("favorite")) Icons.Filled.Star else Icons.Outlined.StarBorder, "기본 템플릿", tint = if (t.b("favorite")) Sgu.Accent else Sgu.Sub)
                }
                if (!isCur) TextButton(onClick = { importing = t }) { Text("가져오기", color = Sgu.Ink) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더보기", tint = Sgu.Sub) }
                    DropdownMenu(menu, { menu = false }, containerColor = Sgu.Card) {
                        if (isCur) DropdownMenuItem(text = { Text("현재 설정 저장") }, onClick = {
                            menu = false
                            val cur = Templates.all(g).first { it.first.id == id }.first
                            TemplateLibrary.setData(id, Templates.exportTemplate(Templates.asState(g, cur)).with("name" to JsonPrimitive(t.s("name"))))
                            msg = "'${t.s("name")}'에 현재 설정을 저장했습니다."
                        })
                        DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { menu = false; renaming = t })
                        DropdownMenuItem(text = { Text("내보내기") }, onClick = {
                            menu = false
                            val data = (t.o("data") ?: JsonObject(emptyMap())).with("name" to JsonPrimitive(t.s("name")))
                            saveJson(fileName(t.s("name"))) { it.write(sguFile(Templates.KIND, data)) }
                        })
                        if (id != "default") DropdownMenuItem(text = { Text("삭제", color = Sgu.Danger) }, onClick = { menu = false; removing = t })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint("내보낸 파일에는 프리셋의 모든 설정(생성 이미지 제외)과 캐릭터 구성 블록(이름·타입·순서)이 들어갑니다. 캐릭터 이름은 character 1, 2…로 바뀌고, 블록 텍스트는 '템플릿에 보존'을 켠 블록만 포함됩니다.")
    }

    renaming?.let { t ->
        var name by remember(t.s("id")) { mutableStateOf(t.s("name")) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("템플릿 이름") },
            text = { InputBox(name, { name = it }) },
            confirmButton = { TextButton(onClick = {
                TemplateLibrary.rename(t.s("id"), name); ImageGenStore.update(cid) { Templates.rename(it, t.s("id"), name) }; renaming = null
            }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
    removing?.let { t ->
        ConfirmDialog("템플릿 삭제", "'${t.s("name")}'을(를) 목록에서 지울까요?", "삭제", onConfirm = { TemplateLibrary.remove(t.s("id")) }, onDismiss = { removing = null })
    }
    importing?.let { t ->
        // This character's own copy of that template keeps the block texts; the library copy only keeps '템플릿에 보존' texts.
        val stashed = Templates.stored(g).firstOrNull { it.id == t.s("id") }
        val source = (stashed?.characters as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: Templates.importTemplate(g, t.o("data"))?.arr("characters") ?: emptyList()
        AlertDialog(
            onDismissRequest = { importing = null }, title = { Text("캐릭터 설정 가져오기") },
            text = {
                Column {
                    Text("'${t.s("name")}'의 캐릭터 ${source.size}명(블록 태그)을 지금 템플릿으로 가져와요.", style = MaterialTheme.typography.bodyMedium)
                    if (stashed == null) Hint("이 캐릭터에서 쓴 적 없는 템플릿이라 '템플릿에 보존'을 켠 블록 텍스트만 들어 있어요.")
                    Spacer(Modifier.height(12.dp))
                    InkButton("바꾸기", onClick = {
                        ImageGenStore.update(cid) { Templates.importCharacters(it, source, true) }; importing = null
                        Toast.makeText(ctx, "캐릭터 설정을 바꿨어요", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth())
                    Hint("같은 순서의 캐릭터끼리, 이름이 같은 블록의 태그를 덮어써요. 프리셋 체크는 그대로예요.")
                    Spacer(Modifier.height(8.dp))
                    LineButton("뒤에 추가", onClick = {
                        ImageGenStore.update(cid) { Templates.importCharacters(it, source, false) }; importing = null
                        Toast.makeText(ctx, "캐릭터를 추가했어요", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth())
                    Hint("가져온 캐릭터를 지금 캐릭터들 뒤에 새로 넣어요.")
                }
            },
            confirmButton = { TextButton(onClick = { importing = null }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
}

// =====================================================================================
// 생성된 이미지 (preset detail tiles, module 1682 F) + 원본 보기
// =====================================================================================

@Composable
fun PresetImages(cid: String, preset: JsonObject) {
    val pid = preset.s("id")
    val images = preset.arr("images")
    val (w, h) = ImageGenStore.sizeDims(preset.o("size"))
    var zoom by remember { mutableStateOf<File?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val save = rememberSaver("image/*")
    Text("생성된 이미지 (${images.size}) · 체크 = 봇에 반영", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    if (images.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Text("아직 생성된 이미지가 없습니다", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val cols = if (maxWidth > 560.dp) 6 else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images.chunked(cols).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, im ->
                        val order = r * cols + c + 1
                        val file = ImageGenStore.imageFile(cid, im.s("file"))
                        val on = im.b("selected")
                        Box(Modifier.weight(1f).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(14.dp))
                            .border(if (on) 2.dp else 1.dp, if (on) Sgu.Ink else Sgu.Line, RoundedCornerShape(14.dp))) {
                            FileImage(file, Modifier.fillMaxSize().clickable { zoom = file }, 512)
                            Text("$order", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Ink).padding(horizontal = 6.dp, vertical = 1.dp))
                            Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "봇에 반영", tint = if (on) Sgu.Ink else Sgu.Sub,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Bg).clickable { ImageGenStore.toggleUse(cid, pid, im.s("id")) }.padding(2.dp))
                            Icon(Icons.Outlined.Close, "이미지 삭제", tint = Sgu.Sub,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Bg).clickable { removing = im.s("id") }.padding(3.dp).size(16.dp))
                            Icon(Icons.Outlined.Download, "다운로드", tint = Sgu.Ink,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Bg).clickable {
                                    save("sgu-$order.${file.extension.ifBlank { "png" }}") { o -> file.inputStream().use { it.copyTo(o) } }
                                }.padding(3.dp).size(16.dp))
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    zoom?.let { ZoomDialog(it) { zoom = null } }
    removing?.let { iid -> ConfirmDialog("이미지 삭제", "이 이미지를 삭제하시겠습니까?", "삭제", onConfirm = { ImageGenStore.deleteImage(cid, pid, iid) }, onDismiss = { removing = null }) }
}

/** 원본 보기: the image at full size, tap anywhere to close. */
@Composable
fun ZoomDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            val bmp = remember(file.path) { runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
            if (bmp != null) androidx.compose.foundation.Image(bmp, "원본", contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            else Text("이미지 파일이 없습니다.", color = Color.White)
        }
    }
}

// =====================================================================================
// 위치 (module 1678 position grid)
// =====================================================================================

@Composable
fun PositionPicker(cid: String, preset: JsonObject, pc: JsonObject) {
    val pos = pc.o("pos")
    val custom = pos.s("mode") == "custom"
    val taken = preset.arr("chars").filter { it.s("id") != pc.s("id") && it.o("pos").s("mode") == "custom" }
        .map { (it.o("pos").d("col") ?: 0.0).toInt() to (it.o("pos").d("row") ?: 0.0).toInt() }.toSet()
    FieldLabel("위치")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PillChip("AI's choice", !custom) { ImageGenStore.setCharPos(cid, preset.s("id"), pc.s("id"), ImageGenStore.normalizePos(null)) }
        PillChip("Custom", custom) {
            if (!custom) ImageGenStore.setCharPos(cid, preset.s("id"), pc.s("id"), buildJsonObject { put("mode", JsonPrimitive("custom")); put("col", JsonPrimitive(2)); put("row", JsonPrimitive(2)) })
        }
    }
    if (custom) {
        val col = (pos.d("col") ?: 2.0).toInt(); val row = (pos.d("row") ?: 2.0).toInt()
        Column(Modifier.padding(top = 8.dp).width(200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until 5) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 5) {
                    val mine = c == col && r == row
                    val other = (c to r) in taken
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(if (mine) Sgu.Ink else Sgu.Card)
                        .border(1.dp, Sgu.Line, RoundedCornerShape(8.dp))
                        .clickable(enabled = !other) {
                            ImageGenStore.setCharPos(cid, preset.s("id"), pc.s("id"), buildJsonObject { put("mode", JsonPrimitive("custom")); put("col", JsonPrimitive(c)); put("row", JsonPrimitive(r)) })
                        }, contentAlignment = Alignment.Center) {
                        if (other) Text("X", color = Sgu.Sub, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    Hint("한 캐릭터가 AI's choice 면 전원 AI's choice, Custom 이면 전원 Custom 입니다. 다른 캐릭터가 차지한 칸은 X 로 표시됩니다.")
}

// =====================================================================================
// 이미지 선별 (module 1685 ImagePicker)
// =====================================================================================

@Composable
fun ImagePickerScreen(cid: String, openPreset: (String) -> Unit, onBack: () -> Unit) {
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val presets = g.arr("presets").filter { it.arr("images").isNotEmpty() }
    val changes = remember(cid) { mutableStateMapOf<String, Boolean>() }
    val pinned = (SettingsStore.root["imagePickerScale"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
    var size by rememberSaveable { mutableFloatStateOf(pinned ?: 110f) }
    var zoom by remember { mutableStateOf<File?>(null) }
    var genFor by remember { mutableStateOf<String?>(null) }
    val apply = { if (changes.isNotEmpty()) { ImageGenStore.setUses(cid, changes.toMap()); changes.clear() } }
    DisposableEffect(cid) { onDispose { if (changes.isNotEmpty()) ImageGenStore.setUses(cid, changes.toMap()) } }
    val count = g.arr("presets").sumOf { p -> p.arr("images").count { changes[it.s("id")] ?: it.b("selected") } }

    Page("이미지 선별", "${count}장 선택", { apply(); onBack() }) {
        Text("이미지 크기", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp))
        Slider(size, { size = it }, valueRange = 70f..240f, colors = SliderDefaults.colors(thumbColor = Sgu.Ink, activeTrackColor = Sgu.Ink, inactiveTrackColor = Sgu.Line),
            onValueChangeFinished = { if (pinned != null) SettingsStore.update { it["imagePickerScale"] = kotlinx.serialization.json.JsonPrimitive(size) } })
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("지금 값 기본으로 고정", style = MaterialTheme.typography.bodyMedium)
                Text("켜면 다른 프로젝트에서도 같은 크기로 시작합니다. 끄면 새 프로젝트는 기본 크기로 시작합니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            }
            SguSwitch(pinned != null) { on -> SettingsStore.update { m -> if (on) { m["imagePickerScale"] = kotlinx.serialization.json.JsonPrimitive(size) } else { m.remove("imagePickerScale") } } }
        }
        Hint("체크한 이미지가 봇(에셋·감정)에 반영돼요. 나가면 저장돼요. 이미지를 누르면 원본으로 볼 수 있어요.")
        presets.forEach { p ->
            val (w, h) = ImageGenStore.sizeDims(p.o("size"))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.s("name").ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { apply(); openPreset(p.s("id")) }) { Icon(Icons.Outlined.Edit, "프리셋 편집", tint = Sgu.Sub) }
                IconButton(onClick = { genFor = p.s("id") }, enabled = !GenRun.running(cid)) { Icon(Icons.Filled.AutoAwesome, "생성", tint = Sgu.Ink) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.arr("images"), key = { it.s("id") }) { im ->
                    val on = changes[im.s("id")] ?: im.b("selected")
                    val file = ImageGenStore.imageFile(cid, im.s("file"))
                    Box(Modifier.width(size.dp).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(14.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) Sgu.Ink else Sgu.Line, RoundedCornerShape(14.dp))) {
                        FileImage(file, Modifier.fillMaxSize().clickable { changes[im.s("id")] = !on }, 512)
                        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = if (on) Sgu.Ink else Sgu.Sub,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Bg).padding(2.dp))
                        Icon(androidx.compose.material.icons.Icons.Outlined.Image, "원본으로 보기", tint = Sgu.Ink,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Sgu.Bg).clickable { zoom = file }.padding(3.dp).size(16.dp))
                    }
                }
            }
        }
        if (presets.isEmpty()) EmptyPane("생성된 이미지가 없어요")
    }
    zoom?.let { ZoomDialog(it) { zoom = null } }
    genFor?.let { pid -> BatchDialog(1, onDismiss = { genFor = null }) { n -> genFor = null; GenRun.run(cid, listOf(pid), n) } }
}

// =====================================================================================
// 그림체 라이브러리 (module 1677 / 909)
// =====================================================================================

@Composable
fun StyleLibrarySection(cid: String) {
    LaunchedEffect(Unit) { StyleLibrary.load() }
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val saveJson = rememberSaver("application/json")
    fun applyData(data: JsonObject?): Boolean {
        val pairs = StyleLibrary.patch(data) ?: return false
        ImageGenStore.patchStyle(cid, *pairs.toTypedArray()); return true
    }
    val pick = rememberJsonPicker { o ->
        msg = when {
            o == null -> "가져오기 실패: JSON 파일이 아니에요."
            o.s("sgu") != StyleLibrary.KIND -> "이 파일은 '${StyleLibrary.KIND}' 형식이 아닙니다."
            applyData(o.o("data")) -> "가져왔습니다."
            else -> "가져올 수 없는 데이터입니다."
        }
    }
    Spacer(Modifier.height(16.dp))
    LineCard {
        Column {
            Text("그림체 라이브러리 (모든 캐릭터 공용)", style = MaterialTheme.typography.titleSmall)
            Hint("저장한 그림체는 어느 캐릭터에서든 불러올 수 있습니다. ★로 지정한 그림체는 새 프로젝트의 기본 그림체가 됩니다. 이미지 모델 프로바이더는 포함되지 않습니다.")
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputBox(name, { name = it }, placeholder = "저장할 그림체 이름", modifier = Modifier.weight(1f))
                LineButton("현재 그림체 저장", onClick = { StyleLibrary.add(name.ifBlank { "그림체" }, StyleLibrary.fromStyle(g.o("style"))); name = "" })
            }
            if (StyleLibrary.items.isEmpty()) Text("저장된 그림체가 없습니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            StyleLibrary.items.forEach { st ->
                val id = st.s("id")
                if (renaming == id) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputBox(newName, { newName = it }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { StyleLibrary.rename(id, newName); renaming = null }) { Text("저장", color = Sgu.Ink) }
                } else Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.s("name"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { renaming = id; newName = st.s("name") }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Edit, "이름 바꾸기", tint = Sgu.Sub, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { StyleLibrary.toggleFavorite(id) }, modifier = Modifier.size(36.dp)) {
                        Icon(if (st.b("favorite")) Icons.Filled.Star else Icons.Outlined.StarBorder, "기본 그림체", tint = if (st.b("favorite")) Sgu.Accent else Sgu.Sub, modifier = Modifier.size(20.dp))
                    }
                    TextButton(onClick = { applyData(st.o("data")); msg = "'${st.s("name")}' 적용됨" }) { Text("적용", color = Sgu.Ink) }
                    TextButton(onClick = { StyleLibrary.remove(id) }) { Text("삭제", color = Sgu.Danger) }
                }
            }
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineButton("내보내기 (JSON)", onClick = { saveJson("sgu-art-style.json") { it.write(sguFile(StyleLibrary.KIND, StyleLibrary.fromStyle(g.o("style")))) } })
        LineButton("가져오기 (JSON)", onClick = pick)
    }
    if (msg.isNotEmpty()) Hint(msg)
}
