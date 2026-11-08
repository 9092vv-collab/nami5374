package space.sgu.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import space.sgu.next.data.FakeData
import space.sgu.next.data.Turn

private val editorTabs = listOf("캐릭터", "이미지", "작업실", "스크립트", "기타")

/** Character workspace. onBack == null when shown as the right pane on the unfolded screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(characterId: Int, onBack: (() -> Unit)?) {
    val c = FakeData.character(characterId)
    val pager = rememberPagerState(initialPage = 2) { editorTabs.size }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BarTitle(c?.name ?: "(삭제된 캐릭터)", "세션 1 · ${FakeData.turns.size}턴 · Claude Sonnet 5.5") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, "검색") }
                    IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.Undo, "되돌리기") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더보기") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("다시 실행") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Redo, null) }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("내보내기") }, leadingIcon = { Icon(Icons.Filled.IosShare, null) }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("권한") }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("프롬프트 미리보기") }, onClick = { menu = false })
                            DropdownMenuItem(text = { Text("세션 이동") }, onClick = { menu = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            TabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicator = { pos ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(pos[pager.currentPage]),
                        height = 2.dp, color = MaterialTheme.colorScheme.primary,
                    )
                },
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
            ) {
                editorTabs.forEachIndexed { i, label ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> FieldsPage()
                    1 -> ImagesPage()
                    2 -> WorkshopPage()
                    3 -> ScriptsPage()
                    else -> MiscPage()
                }
            }
        }
    }
}

@Composable
private fun FieldsPage() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(FakeData.fields, key = { it.first }) { (label, value) ->
            var text by rememberSaveable { mutableStateOf(value) }
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ImagesPage() {
    val emotions = listOf("기본", "웃음", "화남", "슬픔", "놀람", "부끄러움", "졸림", "진지", "당황")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(emotions.size) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                        .background(Color.hsl((i * 40f) % 360f, 0.35f, 0.75f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(emotions[i], style = MaterialTheme.typography.labelMedium)
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
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(turns, key = { it.id }) { t -> TurnCard(t) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("캐릭터에게 적용할 명령을 입력", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { IconButton(onClick = {}) { Icon(Icons.Filled.AttachFile, "첨부") } },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        val id = (turns.maxOfOrNull { it.id } ?: 0) + 1
                        turns.add(Turn(id, input.trim(), "(뼈대 단계라 실제 AI 응답은 아직 없어요)", emptyList()))
                        input = ""
                        scope.launch { listState.animateScrollToItem(turns.size - 1) }
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Icon(Icons.Filled.ArrowUpward, "보내기") }
        }
    }
}

@Composable
private fun TurnCard(t: Turn) {
    Column(Modifier.fillMaxWidth()) {
        // User command — right aligned bubble
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                t.command,
                modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        // SGU reply
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.reply, style = MaterialTheme.typography.bodyMedium)
                if (t.edited.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.edited.forEach {
                            Pill("편집 · $it", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Row {
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.ContentCopy, "복사", Modifier.size(18.dp)) }
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, "리롤", Modifier.size(18.dp)) }
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, "되돌림", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScriptsPage() {
    val items = listOf(
        "정규식 · 대사 따옴표 정리" to "출력 수정",
        "정규식 · {{user}} 호칭 통일" to "입력 수정",
        "트리거 · 호감도 변수" to "채팅 시작 시",
        "트리거 · 비 오는 밤 이벤트" to "키워드",
        "로어북 · 은빛 숲" to "키워드: 숲, 은빛",
        "로어북 · 경계의 서약" to "상시",
    )
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { it.first }) { (title, sub) ->
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(sub) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun MiscPage() {
    var nsfw by rememberSaveable { mutableStateOf(false) }
    var lowLevel by rememberSaveable { mutableStateOf(true) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            ListItem(
                headlineContent = { Text("제작자 노트") },
                supportingContent = { Text("카드를 받는 사람에게 보여줄 메모") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("저수준 접근") },
                trailingContent = { SguSwitch(lowLevel) { lowLevel = it } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("성인 콘텐츠 표시") },
                trailingContent = { SguSwitch(nsfw) { nsfw = it } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
fun SguSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
