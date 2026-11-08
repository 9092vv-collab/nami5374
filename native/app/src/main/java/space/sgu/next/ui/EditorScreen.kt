package space.sgu.next.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.sgu.next.data.CardIO
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.FakeData
import space.sgu.next.data.Turn

private val editorTabs = listOf("캐릭터", "이미지", "작업실", "스크립트", "기타")

/** Card text fields shown on the 캐릭터 tab, in order. */
val CardFields = listOf(
    "name" to "이름",
    "nickname" to "닉네임",
    "creator" to "제작자",
    "description" to "디스크립션",
    "personality" to "성격",
    "scenario" to "시나리오",
    "first_mes" to "첫 메시지",
    "mes_example" to "예시 대화",
    "system_prompt" to "시스템 프롬프트",
    "post_history_instructions" to "탈옥 프롬프트",
    "creator_notes" to "제작자 노트",
    "character_version" to "버전",
)

fun fieldLabel(key: String): String =
    if (key.startsWith("alt:")) "추가 첫 메시지 ${(key.removePrefix("alt:").toIntOrNull() ?: 0) + 1}"
    else CardFields.firstOrNull { it.first == key }?.second ?: key

/** Character workspace. onBack == null when shown as the right pane on the unfolded screen. */
@Composable
fun EditorScreen(characterId: String, onBack: (() -> Unit)?, openField: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = CharacterStore.items.firstOrNull { it.id == characterId }
    val card = remember(characterId, CharacterStore.version) { CharacterStore.readCard(characterId) }
    val pager = rememberPagerState(initialPage = 0) { editorTabs.size }
    var menu by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            runCatching { CharacterStore.exportCharx(ctx, characterId, uri) }
                .onSuccess { Toast.makeText(ctx, "CHARX로 내보냈어요", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "내보내지 못했어요: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val name = meta?.name ?: "(삭제된 캐릭터)"

    Scaffold(
        containerColor = Sgu.Bg,
        topBar = {
            SguTopBar(
                title = name,
                subtitle = listOfNotNull(meta?.creator?.ifBlank { "제작자 미지정" }, "세션 1").joinToString(" · "),
                onBack = onBack,
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Search, "검색") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더보기") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Sgu.Card, shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem(
                                text = { Text("CHARX로 내보내기") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                                onClick = { menu = false; exporter.launch("${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.charx") },
                            )
                            DropdownMenuItem(text = { Text("권한") }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("프롬프트 미리보기") }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("세션 이동") }, onClick = { menu = false })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            PillTabs(
                options = editorTabs,
                selected = pager.currentPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            )
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> FieldsPage(characterId, card, openField)
                    1 -> ImagesPage()
                    2 -> WorkshopPage()
                    3 -> ScriptsPage(card)
                    else -> MiscPage()
                }
            }
        }
    }
}

// ---------- 캐릭터 tab: preview cards, no inner scrolling ----------

@Composable
private fun FieldsPage(id: String, card: JsonObject, openField: (String) -> Unit) {
    val alts = CharacterStore.altGreetings(card)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(CardFields, key = { it.first }) { (key, label) ->
            FieldCard(label, CharacterStore.getField(card, key)) { openField(key) }
        }
        item(key = "altHeader") {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("추가 첫 메시지 ${alts.size}개", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.weight(1f))
                PillChip("추가", selected = false, icon = Icons.Outlined.Add) {
                    val i = CharacterStore.addAltGreeting(id)
                    openField("alt:$i")
                }
            }
        }
        items(alts.size, key = { "alt$it" }) { i ->
            FieldCard("추가 첫 메시지 ${i + 1}", alts[i]) { openField("alt:$i") }
        }
        item(key = "book") {
            val entries = (((card["data"] as? JsonObject)?.get("character_book") as? JsonObject)?.get("entries") as? JsonArray)?.size ?: 0
            Spacer(Modifier.height(6.dp))
            FieldCard("로어북", if (entries == 0) "" else "항목 ${entries}개 · 편집은 다음 단계에서 열려요", onClick = null)
        }
    }
}

/** A few lines of preview. Tapping opens the full-screen editor, so the list never traps scrolling. */
@Composable
private fun FieldCard(label: String, value: String, onClick: (() -> Unit)?) {
    LineCard(onClick = onClick) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Spacer(Modifier.height(4.dp))
            if (value.isBlank()) {
                Text("비어 있음 · 눌러서 작성", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------- other tabs (skeleton until their steps) ----------

@Composable
private fun ImagesPage() {
    val emotions = listOf("neutral", "smiling", "sad", "angry", "surprised", "shy", "sleepy", "serious", "flustered")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(emotions.size) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(18.dp)).background(Sgu.Card).border(1.dp, Sgu.Line, RoundedCornerShape(18.dp)))
                Spacer(Modifier.height(6.dp))
                Text(emotions[i], style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            }
        }
    }
}

@Composable
private fun WorkshopPage() {
    val turns = remember { mutableStateListOf<Turn>().apply { addAll(FakeData.turns) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (turns.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(turns, key = { it.id }) { t -> TurnCard(t) }
        }
        // Claude-style input pill: white, soft shadow, grey round +, ink round send.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                .shadow(8.dp, RoundedCornerShape(30.dp), ambientColor = Sgu.Ink.copy(alpha = 0.12f), spotColor = Sgu.Ink.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(30.dp))
                .background(Sgu.Card)
                .border(1.dp, Sgu.Line, RoundedCornerShape(30.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Sgu.Bubble).clickable {}, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "첨부", tint = Sgu.Ink)
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (input.isEmpty()) Text("캐릭터에게 적용할 명령", color = Sgu.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    value = input, onValueChange = { input = it }, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Sgu.Ink),
                    cursorBrush = SolidColor(Sgu.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (input.isBlank()) Sgu.Bubble else Sgu.Ink).clickable {
                    if (input.isNotBlank()) {
                        val id = (turns.maxOfOrNull { it.id } ?: 0) + 1
                        turns.add(Turn(id, input.trim(), "(아직 AI 연결 전이라 가짜 응답이에요)", emptyList()))
                        input = ""
                        scope.launch { listState.animateScrollToItem(turns.size - 1) }
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (input.isBlank()) Sgu.Sub else Color.White) }
        }
    }
}

@Composable
private fun TurnCard(t: Turn) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                t.command,
                modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(22.dp)).background(Sgu.Bubble)
                    .border(1.dp, Sgu.Line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Sgu.Accent, modifier = Modifier.padding(top = 3.dp).size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.reply, style = MaterialTheme.typography.bodyLarge)
                if (t.edited.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.edited.forEach { Badge("편집 · $it", Sgu.AccentSoft, Color(0xFF8A3B22)) }
                    }
                }
                Row {
                    IconButton(onClick = {}, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.ContentCopy, "복사", Modifier.size(18.dp), tint = Sgu.Sub) }
                    IconButton(onClick = {}, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Refresh, "리롤", Modifier.size(18.dp), tint = Sgu.Sub) }
                    IconButton(onClick = {}, modifier = Modifier.size(38.dp)) { Icon(Icons.AutoMirrored.Outlined.Undo, "되돌림", Modifier.size(18.dp), tint = Sgu.Sub) }
                }
            }
        }
    }
}

@Composable
private fun ScriptsPage(card: JsonObject) {
    val data = card["data"] as? JsonObject
    val book = ((data?.get("character_book") as? JsonObject)?.get("entries") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)) {
        item { SectionLabel("로어북 ${book.size}개") }
        if (book.isEmpty()) item { EmptyPane("로어북 항목이 없어요", Modifier.padding(top = 20.dp)) }
        items(book.size) { i ->
            val e = book[i]
            val title = CardIO.str(e, "comment").ifBlank { CardIO.str(e, "name") }.ifBlank { "항목 ${i + 1}" }
            val keys = (e["keys"] as? JsonArray)?.joinToString(", ") { CardIO.str(JsonObject(mapOf("k" to it)), "k") } ?: ""
            GroupRow(i, book.size, title, subtitle = keys.ifBlank { null })
        }
    }
}

@Composable
private fun MiscPage() {
    var lowLevel by rememberSaveable { mutableStateOf(true) }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)) {
        item { SectionLabel("기타") }
        item { GroupRow(0, 2, "저수준 접근", trailing = { SguSwitch(lowLevel) { lowLevel = it } }) }
        item { GroupRow(1, 2, "에셋 관리", subtitle = "이미지 단계에서 열려요") }
    }
}

@Composable
fun SguSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Sgu.Ink, checkedThumbColor = Color.White,
            uncheckedTrackColor = Sgu.Bubble, uncheckedBorderColor = Sgu.Line, uncheckedThumbColor = Sgu.Sub,
        ),
    )
}
