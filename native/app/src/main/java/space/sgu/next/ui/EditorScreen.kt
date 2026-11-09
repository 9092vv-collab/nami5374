package space.sgu.next.ui

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.automirrored.outlined.Assignment
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
import space.sgu.next.data.FieldKind
import space.sgu.next.data.Fields
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.Workshop
import space.sgu.next.data.arr
import space.sgu.next.data.o
import space.sgu.next.data.WFields

private val editorTabs = listOf("캐릭터", "이미지", "작업실", "스크립트", "기타")

fun fieldLabel(key: String): String = Fields.label(key)

/** Character workspace. onBack == null when shown as the right pane on the unfolded screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(characterId: String, onBack: (() -> Unit)?, openField: (String) -> Unit, openPreset: (String) -> Unit = {}, openRoute: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = CharacterStore.items.firstOrNull { it.id == characterId }
    val card = remember(characterId, CharacterStore.version) { CharacterStore.readCard(characterId) }
    // One flat pager: 캐릭터 | 이미지 데이터 · 그림체 · 캐릭터 설정 · 템플릿 | 작업실 | 스크립트 | 기타,
    // so swiping from an image sub tab on to the next big tab is one smooth swipe (no pager inside a pager).
    val pager = rememberPagerState(initialPage = 0) { 8 }
    var lastImage by rememberSaveable(characterId) { mutableStateOf(4) }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage in 1..4) lastImage = pager.currentPage }
    val bigOf = { p: Int -> when (p) { 0 -> 0; in 1..4 -> 1; 5 -> 2; 6 -> 3; else -> 4 } }
    val pageOfBig = { b: Int -> when (b) { 0 -> 0; 1 -> lastImage; 2 -> 5; 3 -> 6; else -> 7 } }
    var menu by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val wdoc = remember(characterId, Workshop.version) { Workshop.doc(characterId) }
    val busy = Workshop.isActive(characterId)

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
                subtitle = "${Workshop.currentSessionName(wdoc)} · ${wdoc.o("session").arr("turns").size}턴",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { openRoute("perms/$characterId") }) { Icon(Icons.Outlined.CheckCircleOutline, "권한") }
                    IconButton(onClick = { openRoute("prompt/$characterId") }) { Icon(Icons.AutoMirrored.Outlined.Assignment, "프롬프트 설정") }
                    IconButton(onClick = { exporter.launch("${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.charx") }) { Icon(Icons.Outlined.IosShare, "CHARX로 내보내기") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더보기") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Sgu.Card, shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem(text = { Text("새로운 세션 추가") }, enabled = !busy, onClick = { menu = false; newSession = true })
                            DropdownMenuItem(text = { Text("세션 이동") }, onClick = { menu = false; sessions = true })
                            DropdownMenuItem(text = { Text("세션 요약하기") }, enabled = !busy && Workshop.summarizing[characterId] != true, onClick = { menu = false; Workshop.summarizeNow(characterId) })
                            DropdownMenuItem(text = { Text("복제") }, onClick = {
                                menu = false
                                val nid = CharacterStore.duplicate(characterId)
                                Toast.makeText(ctx, "복제했어요", Toast.LENGTH_SHORT).show()
                                openRoute("character/$nid")
                            })
                            DropdownMenuItem(text = { Text("삭제", color = Sgu.Danger) }, enabled = !busy, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            PillTabs(
                options = editorTabs,
                selected = bigOf(pager.currentPage),
                onSelect = { scope.launch { pager.animateScrollToPage(pageOfBig(it)) } },
            )
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> GroupPage("character", characterId, card, openField, openRoute)
                    in 1..4 -> ImageSubPage(characterId, page - 1, { sub -> scope.launch { pager.animateScrollToPage(sub + 1) } }, openField, openPreset, openRoute)
                    5 -> WorkshopPage(characterId, openFieldTab = { fid ->
                        val target = when (WFields.map[fid]?.group ?: Fields.byId[fid]?.group) { "image" -> 1; "script" -> 6; "etc" -> 7; else -> 0 }
                        scope.launch { pager.animateScrollToPage(target) }
                    }, openRoute = openRoute)
                    6 -> GroupPage("script", characterId, card, openField, openRoute)
                    else -> GroupPage("etc", characterId, card, openField, openRoute)
                }
            }
        }
    }
    if (newSession) ConfirmDialog("새로운 세션 추가", "턴 기록이 없고 권한이 초기화된 새 세션을 만들어 이동합니다. 기존 세션은 그대로 보관되며, 봇 데이터는 모든 세션이 공유합니다.", "추가",
        onConfirm = { Workshop.addSession(characterId) }, onDismiss = { newSession = false })
    if (sessions) SessionDialog(characterId, busy) { sessions = false }
    if (deleting) ConfirmDialog("캐릭터 삭제", "'$name'과(와) 작업실 기록이 모두 삭제됩니다.", "삭제",
        onConfirm = { CharacterStore.delete(characterId); onBack?.invoke() }, onDismiss = { deleting = false })
}

// ---------- 캐릭터 / 스크립트 / 기타 tabs: the original's field groups ----------

@Composable
private fun GroupPage(group: String, id: String, card: JsonObject, openField: (String) -> Unit, openRoute: (String) -> Unit) {
    val data = card["data"] as? JsonObject
    val fields = Fields.inGroup(group)
    val expanded = remember(id) { mutableStateOf(setOf<String>()) }
    androidx.compose.runtime.CompositionLocalProvider(LocalGroupInset provides 0.dp) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.forEach { f ->
            when (f.kind) {
                FieldKind.Text, FieldKind.LongText -> item(key = f.id) {
                    FieldCard(f.label, CharacterStore.getField(card, f.id), maxLines = if (f.kind == FieldKind.Text) 1 else 4) { openField(f.id) }
                }
                FieldKind.Toggle -> item(key = f.id) {
                    LineCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                                if (f.hint.isNotEmpty()) Text(f.hint, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                            }
                            SguSwitch(Fields.getBool(data, f.path)) { CharacterStore.setBool(id, f.id, it) }
                        }
                    }
                }
                FieldKind.TextList -> {
                    val alts = CharacterStore.altGreetings(card)
                    item(key = f.id) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${f.label} ${alts.size}개", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.weight(1f))
                            PillChip("추가", selected = false, icon = Icons.Outlined.Add) {
                                val i = CharacterStore.addAltGreeting(id)
                                openField("alt:$i")
                            }
                        }
                    }
                    items(alts.size, key = { "alt$it" }) { i -> FieldCard("${f.label} ${i + 1}", alts[i]) { openField("alt:$i") } }
                }
                FieldKind.Lorebook -> loreItems(id, data, expanded, openRoute)
                FieldKind.LoreSettings -> item(key = f.id) { LoreSettingsCard(id, card) }
                FieldKind.Regex -> regexItems(id, data, openRoute)
                FieldKind.Trigger -> item(key = f.id) { TriggerCard(id, data, openField) }
                FieldKind.Onboarding -> item(key = f.id) { OnboardingCard(id, data, openRoute) }
                FieldKind.Bias -> item(key = f.id) { BiasCard(id, data, openRoute) }
            }
        }
    }
    }
}

/** 로어북 설정: "글로벌 설정 사용" or own scan depth / token budget / recursive / full-word. */
@Composable
private fun LoreSettingsCard(id: String, card: JsonObject) {
    val ls = CharacterStore.loreSettings(card)
    LineCard {
        Column {
            Text("로어북 설정", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("글로벌 설정 사용", style = MaterialTheme.typography.bodyLarge)
                    Text("켜 두면 앱/RisuAI의 전역 로어북 설정을 따릅니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                }
                SguSwitch(ls.usesGlobal) { on ->
                    CharacterStore.setLoreSettings(id, if (on) CharacterStore.LoreSettings(null, null, null, null)
                    else CharacterStore.LoreSettings(4, 2048, true, false))
                }
            }
            if (!ls.usesGlobal) {
                FieldLabel("스캔 깊이")
                NumberBox("$id-scan", ls.scanDepth?.toDouble(), { v -> CharacterStore.setLoreSettings(id, ls.copy(scanDepth = v?.toInt())) }, "글로벌")
                FieldLabel("토큰 예산")
                NumberBox("$id-budget", ls.tokenBudget?.toDouble(), { v -> CharacterStore.setLoreSettings(id, ls.copy(tokenBudget = v?.toInt())) }, "글로벌")
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("재귀 검색", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SguSwitch(ls.recursiveScanning == true) { CharacterStore.setLoreSettings(id, ls.copy(recursiveScanning = it)) }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("단어 단위 일치", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SguSwitch(ls.fullWordMatching == true) { CharacterStore.setLoreSettings(id, ls.copy(fullWordMatching = it)) }
                }
            }
        }
    }
}

/** A few lines of preview. Tapping opens the full-screen editor, so the list never traps scrolling. */
@Composable
private fun FieldCard(label: String, value: String, maxLines: Int = 4, onClick: (() -> Unit)?) {
    LineCard(onClick = onClick) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
            Spacer(Modifier.height(4.dp))
            if (value.isBlank()) {
                Text("비어 있음 · 눌러서 작성", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub.copy(alpha = 0.7f))
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------- other tabs (skeleton until their steps) ----------

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
