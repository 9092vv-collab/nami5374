package space.sgu.next.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.CardIO
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.ImageGenStore
import space.sgu.next.data.NaiModels
import space.sgu.next.data.SettingsStore
import space.sgu.next.data.Thumbs
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull
import space.sgu.next.data.str
import space.sgu.next.data.with
import java.io.File

private val imageTabs = listOf("이미지 데이터", "그림체 설정", "캐릭터 설정", "이미지 템플릿")

/** Image tab: one row of four tabs (no nested "이미지 생성" level). No inner swiping — the editor pager swipes. */
@Composable
fun ImagePage(cid: String, openField: (String) -> Unit, openPreset: (String) -> Unit) {
    var tab by rememberSaveable(cid) { mutableStateOf(3) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(imageTabs, tab, { tab = it })
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ImageDataTab(cid)
                1 -> StyleTab(cid, openField)
                2 -> GenCharacterTab(cid, openField)
                else -> TemplateTab(cid, openPreset)
            }
        }
    }
}

/** Generated/asset image, downsampled + cached. */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, maxPx: Int = 512) {
    val key = file?.let { it.path + ":" + it.lastModified() }
    val bmp by produceState<ImageBitmap?>(null, key) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Thumbs.load(file, key!!, maxPx) }
    }
    val b = bmp
    if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = modifier)
    else Box(modifier.background(Sgu.Bubble), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = Sgu.Sub.copy(alpha = 0.5f)) }
}

// ---------- 이미지 데이터 ----------

@Composable
private fun ImageDataTab(cid: String) {
    val card = remember(cid, CharacterStore.version) { CharacterStore.readCard(cid) }
    val assets = CardIO.assets(card)
    val x = File(CharacterStore.dir(cid), "x")
    val icon = File(CharacterStore.dir(cid), "icon.png").takeIf { it.exists() }
    val groups = listOf("emotion" to "감정 이미지", "x-risu-asset" to "추가 에셋")
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LineCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileImage(icon, Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("캐릭터 아이콘", style = MaterialTheme.typography.titleMedium)
                        Text(if (icon != null) "대표 이미지 (main)" else "아이콘이 없어요", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                }
            }
        }
        groups.forEach { (type, label) ->
            val list = assets.filter { CardIO.str(it, "type") == type }
            item(key = type) {
                LineCard {
                    Column {
                        Text("$label ${list.size}개", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        if (list.isEmpty()) Text("없음", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
                        else LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list.take(60)) { a ->
                                Column(Modifier.width(76.dp)) {
                                    FileImage(CardIO.assetFile(x, CardIO.str(a, "uri")), Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)), 256)
                                    Text(CardIO.str(a, "name"), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Hint("생성한 이미지를 에셋·감정으로 반영하는 기능은 다음 업데이트에서 열려요.") }
    }
}

// ---------- 그림체 설정 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleTab(cid: String, openField: (String) -> Unit) {
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val style = g.o("style")
    val providers = SettingsStore.providers.filter { it.s("format") == "novelai-image" }
    val current = ImageGenStore.provider(g)
    val medium = style.s("model") == NaiModels.V5_MEDIUM
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
        FieldLabel("이미지 모델 설정")
        if (providers.isEmpty()) {
            LineCard { Text("이미지 생성에는 NovelAI(image) 프로바이더와 Persistent API Token이 필요합니다. 설정 › 프로바이더에서 추가하세요.", style = MaterialTheme.typography.bodyMedium) }
        } else if (providers.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                providers.forEach { p -> PillChip(p.s("name"), current?.s("id") == p.s("id")) { ImageGenStore.patchStyle(cid, "providerId" to JsonPrimitive(p.s("id"))) } }
            }
        } else Text("프로바이더: ${providers[0].s("name")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))

        FieldLabel("모델")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NaiModels.list.forEach { (id, label) -> PillChip(label, style.s("model").ifBlank { NaiModels.list[0].first } == id) { ImageGenStore.patchStyle(cid, "model" to JsonPrimitive(id)) } }
        }
        if (medium) Hint("V5 Full Medium은 고정 설정으로 생성돼요: 스텝 14, Euler Ancestral, 네거티브·CFG 리스케일 미사용")

        FieldLabel("샘플러")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NaiModels.samplers.forEach { (id, label) -> PillChip(label, style.s("sampler") == id) { ImageGenStore.patchStyle(cid, "sampler" to JsonPrimitive(id)) } }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("스텝")
                NumberBox("$cid-steps", style.d("steps"), { v -> v?.let { ImageGenStore.patchStyle(cid, "steps" to JsonPrimitive(it.toInt().coerceIn(1, 50))) } }, "28")
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("가이던스")
                NumberBox("$cid-cfg", style.d("guidance"), { v -> v?.let { ImageGenStore.patchStyle(cid, "guidance" to JsonPrimitive(it)) } }, "5")
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("리스케일")
                NumberBox("$cid-rescale", style.d("rescale"), { v -> v?.let { ImageGenStore.patchStyle(cid, "rescale" to JsonPrimitive(it.coerceIn(0.0, 1.0))) } }, "0")
            }
        }
        Hint("스텝 수가 28을 넘으면 구독 등급에 따라 Anlas가 소모될 수 있습니다.")

        FieldLabel("그림체 (기본 프롬프트 / Undesired Content)")
        PromptCard("Art style prompt (그림체 프롬프트)", style.s("prompt"), "예: best quality, masterpiece, soft lighting") { openField("gen:style:prompt") }
        Spacer(Modifier.height(8.dp))
        PromptCard("Art style negative (그림체 네거티브)", style.s("negative"), "예: lowres, bad anatomy, text, watermark") { openField("gen:style:negative") }
        Hint("그림체 라이브러리(모든 캐릭터 공용)는 다음 업데이트에서 열려요.")
    }
}

@Composable
private fun PromptCard(label: String, value: String, placeholder: String = "비어 있음 · 탭하여 편집", onClick: () -> Unit) {
    LineCard(onClick = onClick) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Spacer(Modifier.height(4.dp))
            if (value.isBlank()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
            else Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 캐릭터 설정 (blocks + folders) ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenCharacterTab(cid: String, openField: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val chars = g.arr("characters")
    val selId = g.sOrNull("selectedCharacterId")?.takeIf { s -> chars.any { it.s("id") == s } } ?: chars.firstOrNull()?.s("id")
    val c = chars.firstOrNull { it.s("id") == selId }
    var editBlock by remember { mutableStateOf<JsonObject?>(null) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var generating by remember { mutableStateOf<Job?>(null) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "chars") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PillTabs(chars.map { it.s("name").ifBlank { "(이름 없음)" } }, chars.indexOfFirst { it.s("id") == selId }.coerceAtLeast(0),
                        { i -> ImageGenStore.update(cid) { it.with("selectedCharacterId" to JsonPrimitive(chars[i].s("id"))) } },
                        contentPadding = PaddingValues(0.dp))
                }
                IconButton(onClick = { ImageGenStore.addCharacter(cid) }) { Icon(Icons.Outlined.Add, "캐릭터 추가") }
            }
        }
        if (c == null) {
            item { EmptyPane("캐릭터가 없습니다. 위의 + 버튼으로 만드세요.") }
            return@LazyColumn
        }
        val charId = c.s("id")
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.s("name"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { rename = true }) { Text("이름", color = Sgu.Ink) }
                TextButton(onClick = { confirmDelete = true }) { Text("삭제", color = Sgu.Danger) }
            }
            Text("블록 (위에서 아래 순서로 NovelAI에 전달됩니다)", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 4.dp))
            Text("체크한 블록만 아래 '생성'에 들어가요. 폴더 체크를 끄면 안의 블록이 모두 빠지고, 각 블록 체크는 그대로 기억해요. 템플릿 프리셋은 프리셋마다 따로 체크해요.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        val blocks = c.arr("blocks")
        val folders = blocks.filter { it.s("type") == "folder" }
        val loose = blocks.filter { it.s("type") != "folder" && (it.sOrNull("folderId") == null || folders.none { f -> f.s("id") == it.s("folderId") }) }
        fun on(b: JsonObject) = b["free"]?.toString() != "false"
        fun toggle(b: JsonObject) = ImageGenStore.patchBlocks(cid, charId) { l ->
            val i = l.indexOfFirst { it.s("id") == b.s("id") }; if (i >= 0) l[i] = l[i].with("free" to JsonPrimitive(!on(l[i])))
        }
        items(loose, key = { it.s("id") }) { b ->
            BlockRow(b, checked = on(b), enabled = true, onToggle = { toggle(b) }, onClick = { openField("gen:block:$charId:${b.s("id")}") }, onMenu = { editBlock = b })
        }
        folders.forEach { f ->
            val open = collapsed[f.s("id")] != true
            val kids = blocks.filter { it.sOrNull("folderId") == f.s("id") }
            item(key = f.s("id")) {
                val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f, label = "fold")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sgu.Bubble)
                        .clickable { collapsed[f.s("id")] = open }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fOn = on(f)
                    IconButton(onClick = { toggle(f) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (fOn) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, if (fOn) "폴더 켜짐" else "폴더 꺼짐", tint = Sgu.Ink)
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Folder, null, tint = if (fOn) Sgu.Ink else Sgu.Sub)
                    Text("  ${f.s("name").ifBlank { "폴더" }}", style = MaterialTheme.typography.titleSmall, color = if (fOn) Sgu.Ink else Sgu.Sub, modifier = Modifier.weight(1f))
                    Text("${kids.size}개 ", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(angle), tint = Sgu.Sub)
                    IconButton(onClick = { editBlock = f }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "폴더 메뉴", tint = Sgu.Sub) }
                }
            }
            if (open) items(kids, key = { it.s("id") }) { b ->
                Box(Modifier.padding(start = 18.dp)) {
                    BlockRow(b, checked = on(b), enabled = on(f), onToggle = { toggle(b) }, onClick = { openField("gen:block:$charId:${b.s("id")}") }, onMenu = { editBlock = b })
                }
            }
        }
        item(key = "add") {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("블록 추가", onClick = { ImageGenStore.patchBlocks(cid, charId) { it.add(ImageGenStore.block("새 블록", "prompt")) } }, icon = Icons.Outlined.Add)
                LineButton("폴더 추가", onClick = { ImageGenStore.patchBlocks(cid, charId) { it.add(ImageGenStore.block("새 폴더", "folder")) } }, icon = Icons.Outlined.CreateNewFolder)
            }
        }
        item(key = "size") {
            FieldLabel("생성 규격 (자유 프롬프트)")
            SizePicker(c.o("size")) { sz -> ImageGenStore.patchCharacter(cid, charId) { it.with("size" to sz) } }
        }
        item(key = "free") {
            FieldLabel("자유 프롬프트 (테스트·아이콘용, 템플릿에는 반영되지 않아요)")
            PromptCard("자유 프롬프트 (캐릭터 프롬프트 맨 뒤에 추가)", c.s("freePrompt")) { openField("gen:free:$charId:freePrompt") }
            Spacer(Modifier.height(8.dp))
            PromptCard("자유 네거티브 (Undesired Content 맨 뒤에 추가)", c.s("freeNegative")) { openField("gen:free:$charId:freeNegative") }
            Spacer(Modifier.height(10.dp))
            val preview = g.sOrNull("preview")?.let { ImageGenStore.imageFile(cid, it) }?.takeIf { it.exists() }
            if (preview != null) FileImage(preview, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(20.dp)), 1024)
            Spacer(Modifier.height(10.dp))
            InkButton(if (generating != null) "생성 중… (탭하면 중지)" else "생성", icon = Icons.Filled.AutoAwesome, onClick = {
                generating?.let { it.cancel(); generating = null; return@InkButton }
                generating = scope.launch {
                    runCatching { ImageGenStore.generateForCharacter(cid, charId) }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) Toast.makeText(ctx, it.message ?: "실패", Toast.LENGTH_LONG).show() }
                    generating = null
                }
            })
        }
    }

    editBlock?.let { b -> BlockDialog(cid, c?.s("id") ?: "", b, c?.arr("blocks")?.filter { it.s("type") == "folder" } ?: emptyList()) { editBlock = null } }
    if (rename && c != null) {
        var name by remember { mutableStateOf(c.s("name")) }
        AlertDialog(
            onDismissRequest = { rename = false }, title = { Text("캐릭터 이름") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton(onClick = { ImageGenStore.patchCharacter(cid, c.s("id")) { it.with("name" to JsonPrimitive(name)) }; rename = false }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
    if (confirmDelete && c != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("캐릭터 삭제") },
            text = { Text("이 캐릭터와, 템플릿에서의 사용 항목이 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { ImageGenStore.removeCharacter(cid, c.s("id")); confirmDelete = false }) { Text("삭제", color = Sgu.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
}

@Composable
private fun BlockRow(b: JsonObject, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, onClick: () -> Unit, onMenu: () -> Unit) {
    val active = checked && enabled
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(start = 2.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, if (checked) "켜짐" else "꺼짐",
                tint = if (enabled) Sgu.Ink else Sgu.Sub.copy(alpha = 0.5f))
        }
        Column(Modifier.weight(1f).alpha(if (active) 1f else 0.45f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.s("name").ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                if (b.s("type") == "negative") Badge("네거티브", Sgu.AccentSoft, Color(0xFF8A3B22)) else Badge("프롬프트", Sgu.Bubble, Sgu.Ink)
                if (b.b("keep")) { Spacer(Modifier.width(4.dp)); Badge("보존", Sgu.Bubble, Sgu.Sub) }
            }
            Text(b.s("text").ifBlank { "비어 있음 · 탭하여 편집" }, style = MaterialTheme.typography.bodyMedium, color = if (b.s("text").isBlank()) Sgu.Sub else Sgu.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreVert, "블록 메뉴", tint = Sgu.Sub) }
    }
}

/** Block / folder options: name, type, keep, move up/down, folder, delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockDialog(cid: String, charId: String, b: JsonObject, folders: List<JsonObject>, onDismiss: () -> Unit) {
    val id = b.s("id")
    val isFolder = b.s("type") == "folder"
    var name by remember(id) { mutableStateOf(b.s("name")) }
    fun patch(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        ImageGenStore.patchBlocks(cid, charId) { l -> val i = l.indexOfFirst { it.s("id") == id }; if (i >= 0) l[i] = l[i].with(*pairs) }
    fun move(delta: Int) = ImageGenStore.patchBlocks(cid, charId) { l ->
        val i = l.indexOfFirst { it.s("id") == id }; val j = i + delta
        if (i >= 0 && j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t }
    }
    AlertDialog(
        onDismissRequest = { patch("name" to JsonPrimitive(name)); onDismiss() },
        title = { Text(if (isFolder) "폴더 편집" else "블록 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                if (!isFolder) {
                    FieldLabel("종류")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillChip("프롬프트", b.s("type") == "prompt") { patch("type" to JsonPrimitive("prompt")) }
                        PillChip("네거티브", b.s("type") == "negative") { patch("type" to JsonPrimitive("negative")) }
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("템플릿에 보존", style = MaterialTheme.typography.bodyLarge)
                            Text("템플릿을 내보낼 때 블록 텍스트도 함께 저장", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        }
                        SguSwitch(b.b("keep")) { patch("keep" to JsonPrimitive(it)) }
                    }
                    if (folders.isNotEmpty()) {
                        FieldLabel("폴더")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillChip("폴더 없음", b.sOrNull("folderId") == null) { patch("folderId" to kotlinx.serialization.json.JsonNull) }
                            folders.forEach { f -> PillChip(f.s("name").ifBlank { "폴더" }, b.sOrNull("folderId") == f.s("id")) { patch("folderId" to JsonPrimitive(f.s("id"))) } }
                        }
                    }
                }
                FieldLabel("순서")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineButton("위로", onClick = { move(-1) })
                    LineButton("아래로", onClick = { move(1) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { patch("name" to JsonPrimitive(name)); onDismiss() }) { Text("완료", color = Sgu.Ink) } },
        dismissButton = {
            TextButton(onClick = {
                ImageGenStore.patchBlocks(cid, charId) { l ->
                    l.removeAll { it.s("id") == id }
                    // Deleting a folder keeps its blocks (they move out of the folder).
                    for (i in l.indices) if (l[i].sOrNull("folderId") == id) l[i] = l[i].with("folderId" to kotlinx.serialization.json.JsonNull)
                }
                onDismiss()
            }) { Text(if (isFolder) "폴더 삭제 (블록은 유지)" else "블록 삭제", color = Sgu.Danger) }
        },
        containerColor = Sgu.Card,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SizePicker(size: JsonObject?, onChange: (JsonObject) -> Unit) {
    val kind = size.s("kind").ifBlank { "portrait" }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NaiModels.sizes.forEach { (k, v) -> PillChip(v.third, kind == k) { onChange(ImageGenStore.size(k, v.first, v.second)) } }
        PillChip("Custom", kind == "custom") { val (w, h) = ImageGenStore.sizeDims(size); onChange(ImageGenStore.size("custom", w, h)) }
    }
    if (kind == "custom") {
        val (w, h) = ImageGenStore.sizeDims(size)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("너비 (64 단위)")
                NumberBox("custom-w", w.toDouble(), { v -> v?.let { onChange(ImageGenStore.size("custom", ImageGenStore.clamp64(it), h)) } })
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("높이 (64 단위)")
                NumberBox("custom-h", h.toDouble(), { v -> v?.let { onChange(ImageGenStore.size("custom", w, ImageGenStore.clamp64(it))) } })
            }
        }
    }
}

// ---------- 이미지 템플릿: preset grid ----------

@Composable
private fun TemplateTab(cid: String, openPreset: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val presets = g.arr("presets")
    var adding by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.sOrNull("templateName") ?: "기본 템플릿", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            PillChip(if (batch != null) "중지" else "일괄 생성", selected = batch != null) {
                batch?.let { it.cancel(); batch = null; progress = ""; return@PillChip }
                batch = scope.launch {
                    val ids = ImageGenStore.load(cid).arr("presets").map { it.s("id") }
                    for ((i, pid) in ids.withIndex()) {
                        progress = "생성 중 ${i + 1}/${ids.size}"
                        val ok = runCatching { ImageGenStore.generateForPreset(cid, pid) }
                        ok.exceptionOrNull()?.let { if (it !is kotlinx.coroutines.CancellationException) { Toast.makeText(ctx, it.message ?: "실패", Toast.LENGTH_LONG).show(); return@launch } }
                    }
                    progress = ""; batch = null
                }
            }
        }
        Text(if (progress.isNotEmpty()) progress else "프리셋마다 에셋/감정 이미지를 생성해 둡니다. 템플릿 관리·선별·압축·반영은 다음 업데이트에서 열려요.",
            style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(presets.size, key = { presets[it].s("id") }) { i ->
                val p = presets[i]
                Column(Modifier.clickable { openPreset(p.s("id")) }) {
                    FileImage(ImageGenStore.thumb(p)?.let { ImageGenStore.imageFile(cid, it.s("file")) },
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(18.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)), 384)
                    Text(p.s("name"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
            item(key = "add") {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(18.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)).clickable { adding = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Add, null, tint = Sgu.Sub)
                        Text("새 프리셋", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false }, title = { Text("새 프리셋") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("예: neutral, smiling") }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton(onClick = { adding = false; openPreset(ImageGenStore.addPreset(cid, name.trim())) }) { Text("만들기", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
}

// ---------- 프리셋 상세 (its own screen, so system back returns to the grid) ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GenPresetScreen(cid: String, presetId: String, openField: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val p = ImageGenStore.preset(g, presetId)
    if (p == null) { LaunchedOnce(onBack); return }
    var expanded by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf<Job?>(null) }
    var showPrompt by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addChar by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val images = p.arr("images")
    val typeLabel = if (p.s("imageType") == "emotion") "감정" else "추가 에셋"
    fun set(k: String, v: kotlinx.serialization.json.JsonElement) = ImageGenStore.patchPreset(cid, presetId) { it.with(k to v) }

    Scaffold(
        containerColor = Sgu.Bg,
        topBar = {
            SguTopBar(p.s("name").ifBlank { "프리셋" }, CharacterStore.items.firstOrNull { it.id == cid }?.name, onBack = onBack, actions = {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "프리셋 삭제") }
            })
        },
        bottomBar = {
            // Pinned: generate is always one tap away.
            Row(
                Modifier.fillMaxWidth().background(Sgu.Bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                LineButton("프롬프트", onClick = { showPrompt = true }, icon = Icons.Outlined.Visibility)
                InkButton(if (gen != null) "생성 중… (탭하면 중지)" else "이미지 생성", icon = Icons.Filled.AutoAwesome, modifier = Modifier.weight(1f), onClick = {
                    gen?.let { it.cancel(); gen = null; return@InkButton }
                    gen = scope.launch {
                        runCatching { ImageGenStore.generateForPreset(cid, presetId) }
                            .onFailure { if (it !is kotlinx.coroutines.CancellationException) Toast.makeText(ctx, it.message ?: "실패", Toast.LENGTH_LONG).show() }
                        gen = null
                    }
                })
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // One-line summary that expands.
            item(key = "summary") {
                LineCard(onClick = { expanded = !expanded }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOf(p.s("name").ifBlank { "프리셋" }, typeLabel, ImageGenStore.sizeLabel(p.o("size"))).joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(if (expanded) 180f else 0f), tint = Sgu.Sub)
                        }
                        AnimatedVisibility(expanded) {
                            Column {
                                FieldLabel("프리셋 이름")
                                InputBox(p.s("name"), { set("name", JsonPrimitive(it)) })
                                FieldLabel("이미지 이름 (업로드될 때의 기본 이름)")
                                InputBox(p.s("imageName"), { set("imageName", JsonPrimitive(it)) }, placeholder = p.s("name"))
                                Hint("여러 장이 반영되면 뒤에 .1, .2, .3 … 이 순서대로 붙습니다.")
                                FieldLabel("이미지 타입")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PillChip("추가 에셋", p.s("imageType") != "emotion") { set("imageType", JsonPrimitive("asset")) }
                                    PillChip("감정", p.s("imageType") == "emotion") { set("imageType", JsonPrimitive("emotion")) }
                                }
                                FieldLabel("에셋 규격")
                                SizePicker(p.o("size")) { set("size", it) }
                            }
                        }
                    }
                }
            }
            // Generated images: one horizontal row.
            item(key = "images") {
                Text("생성된 이미지 (${images.size}) · 탭하면 대표로 선택", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                if (images.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Text("아직 생성된 이미지가 없습니다", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub)
                    }
                } else LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images.reversed(), key = { it.s("id") }) { im ->
                        Box {
                            FileImage(ImageGenStore.imageFile(cid, im.s("file")),
                                Modifier.width(150.dp).aspectRatio(ImageGenStore.sizeDims(p.o("size")).let { (w, h) -> w.toFloat() / h })
                                    .clip(RoundedCornerShape(16.dp)).clickable { ImageGenStore.selectImage(cid, presetId, im.s("id")) }, 640)
                            if (im.b("selected")) Icon(Icons.Filled.CheckCircle, "선택됨", tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Sgu.Ink))
                            IconButton(onClick = { ImageGenStore.deleteImage(cid, presetId, im.s("id")) }, modifier = Modifier.align(Alignment.BottomEnd).size(34.dp)) {
                                Icon(Icons.Outlined.Delete, "이미지 삭제", tint = Color.White, modifier = Modifier.clip(CircleShape).background(Sgu.Ink.copy(alpha = 0.55f)).padding(4.dp))
                            }
                        }
                    }
                }
            }
            // Characters in this preset with block checklists (folders switch their blocks).
            p.arr("chars").forEach { pc ->
                val c = ImageGenStore.character(g, pc.s("characterId")) ?: return@forEach
                val ids = ImageGenStore.blockIds(pc)
                val blocks = c.arr("blocks")
                val folders = blocks.filter { it.s("type") == "folder" }
                item(key = "pc-" + pc.s("id")) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.s("name"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ImageGenStore.patchPreset(cid, presetId) { pr -> pr.with("chars" to kotlinx.serialization.json.JsonArray(pr.arr("chars").filter { it.s("id") != pc.s("id") })) } }) {
                            Text("빼기", color = Sgu.Sub)
                        }
                    }
                    Text("블록 (위에서 아래 순서로 NovelAI에 전달됩니다)", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 4.dp))
                }
                val loose = blocks.filter { it.s("type") != "folder" && (it.sOrNull("folderId") == null || folders.none { f -> f.s("id") == it.s("folderId") }) }
                items(loose, key = { "pcb-" + pc.s("id") + it.s("id") }) { b ->
                    CheckRow(b, b.s("id") in ids, enabled = true) { ImageGenStore.toggleBlock(cid, presetId, pc.s("id"), b.s("id")) }
                }
                folders.forEach { f ->
                    val fOn = f.s("id") in ids
                    val open = collapsed[pc.s("id") + f.s("id")] != true
                    val kids = blocks.filter { it.sOrNull("folderId") == f.s("id") }
                    item(key = "pcf-" + pc.s("id") + f.s("id")) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sgu.Bubble).padding(start = 4.dp, end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { ImageGenStore.toggleBlock(cid, presetId, pc.s("id"), f.s("id")) }) {
                                Icon(if (fOn) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, if (fOn) "폴더 켜짐" else "폴더 꺼짐", tint = Sgu.Ink)
                            }
                            Icon(Icons.Outlined.Folder, null, tint = Sgu.Ink, modifier = Modifier.size(20.dp))
                            Text("  ${f.s("name").ifBlank { "폴더" }}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).clickable { collapsed[pc.s("id") + f.s("id")] = open }.padding(vertical = 12.dp))
                            Text(if (fOn) "${kids.count { it.s("id") in ids }}/${kids.size}" else "꺼짐", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        }
                    }
                    if (open) items(kids, key = { "pcfk-" + pc.s("id") + it.s("id") }) { b ->
                        Box(Modifier.padding(start = 18.dp)) {
                            CheckRow(b, b.s("id") in ids, enabled = fOn) { ImageGenStore.toggleBlock(cid, presetId, pc.s("id"), b.s("id")) }
                        }
                    }
                }
                item(key = "pcp-" + pc.s("id")) {
                    Spacer(Modifier.height(4.dp))
                    PromptCard("${c.s("name")} 추가 프롬프트 (이 프리셋에서만)", pc.s("prompt")) { openField("gen:pchar:$presetId:${pc.s("id")}:prompt") }
                    Spacer(Modifier.height(6.dp))
                    PromptCard("${c.s("name")} 추가 네거티브", pc.s("negative")) { openField("gen:pchar:$presetId:${pc.s("id")}:negative") }
                }
            }
            item(key = "addchar") {
                val missing = g.arr("characters").filter { c -> p.arr("chars").none { it.s("characterId") == c.s("id") } }
                if (missing.isNotEmpty()) LineButton("캐릭터 넣기", onClick = { addChar = true }, icon = Icons.Outlined.Add)
            }
            item(key = "asset") {
                FieldLabel("에셋 프롬프트 (모든 캐릭터 공통, 그림체 프롬프트 뒤에 붙음)")
                PromptCard("에셋 프롬프트", p.s("assetPrompt")) { openField("gen:preset:$presetId:assetPrompt") }
                Spacer(Modifier.height(6.dp))
                PromptCard("에셋 네거티브", p.s("assetNegative")) { openField("gen:preset:$presetId:assetNegative") }
            }
        }
    }

    if (showPrompt) {
        val job = ImageGenStore.jobForPreset(g, p)
        AlertDialog(
            onDismissRequest = { showPrompt = false }, title = { Text("전송될 프롬프트") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("모델 ${NaiModels.label(g.o("style").s("model"))} · ${job.w}×${job.h}", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    FieldLabel("기본 프롬프트"); Text(job.basePrompt.ifBlank { "(비어 있음)" }, style = MaterialTheme.typography.bodyMedium)
                    FieldLabel("Undesired Content"); Text(job.baseNegative.ifBlank { "(비어 있음)" }, style = MaterialTheme.typography.bodyMedium)
                    job.chars.forEachIndexed { i, c ->
                        FieldLabel("캐릭터 ${i + 1}"); Text(c.prompt.ifBlank { "(비어 있음)" }, style = MaterialTheme.typography.bodyMedium)
                        if (c.negative.isNotBlank()) Text("UC: ${c.negative}", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPrompt = false }) { Text("닫기", color = Sgu.Ink) } }, containerColor = Sgu.Card,
        )
    }
    if (addChar) {
        val missing = g.arr("characters").filter { c -> p.arr("chars").none { it.s("characterId") == c.s("id") } }
        AlertDialog(
            onDismissRequest = { addChar = false }, title = { Text("캐릭터 넣기") },
            text = {
                Column {
                    missing.forEach { c ->
                        Text(c.s("name"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            ImageGenStore.patchPreset(cid, presetId) { pr -> pr.with("chars" to kotlinx.serialization.json.JsonArray(pr.arr("chars") + ImageGenStore.presetChar(c))) }
                            addChar = false
                        }.padding(14.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addChar = false }) { Text("닫기", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("프리셋 삭제") },
            text = { Text("이 프리셋과 생성된 이미지가 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; ImageGenStore.removePreset(cid, presetId); onBack() }) { Text("삭제", color = Sgu.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소", color = Sgu.Sub) } }, containerColor = Sgu.Card,
        )
    }
}

@Composable
private fun LaunchedOnce(action: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { action() }
}

@Composable
private fun CheckRow(b: JsonObject, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val active = checked && enabled
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = if (enabled) Sgu.Ink else Sgu.Sub.copy(alpha = 0.5f))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.s("name").ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) Sgu.Ink else Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                if (b.s("type") == "negative") Badge("네거티브", Sgu.AccentSoft, Color(0xFF8A3B22))
                if (b.b("keep")) { Spacer(Modifier.width(4.dp)); Badge("보존", Sgu.Bubble, Sgu.Sub) }
            }
            Text(b.s("text").ifBlank { "비어 있음" }, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
