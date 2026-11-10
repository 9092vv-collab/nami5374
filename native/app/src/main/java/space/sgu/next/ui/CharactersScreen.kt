package space.sgu.next.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import space.sgu.next.data.CharMeta
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.Thumbs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(selectedId: String, onOpen: (String) -> Unit, inPane: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<String?>(null) }
    val targets = remember { mutableMapOf<String, Rect>() }
    val haptic = LocalHapticFeedback.current
    var folderMenu by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                runCatching { CharacterStore.import(ctx, uri) }
                    .onSuccess { Toast.makeText(ctx, "가져왔어요", Toast.LENGTH_SHORT).show(); onOpen(it) }
                    .onFailure { Toast.makeText(ctx, "가져오지 못했어요: ${it.message}", Toast.LENGTH_LONG).show() }
                busy = false
            }
        }
    }

    val all = CharacterStore.items
    androidx.activity.compose.BackHandler(!inPane && folder != null && query.isBlank()) { folder = null }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        containerColor = if (inPane) Sgu.Drawer else Sgu.Bg,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SguTopBar(
                title = "캐릭터",
                subtitle = "RisuAI 캐릭터 제작",
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { newFolderDialog = true }) { Icon(Icons.Outlined.CreateNewFolder, "폴더 추가") }
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.FileUpload, "가져오기") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            val searching = query.isNotBlank()
            val inFolder = folder?.takeIf { it in CharacterStore.folders }
            val shown = all.filter { c ->
                if (searching) c.name.contains(query, true) || c.creator.contains(query, true)
                else c.folder?.takeIf { it in CharacterStore.folders } == inFolder
            }
            fun drop(id: String) {
                val key = hover; drag = null; hover = null; dragOffset = Offset.Zero
                when {
                    key == null -> Unit
                    key == OUT -> CharacterStore.moveToFolder(id, null)
                    else -> CharacterStore.moveToFolder(id, key.substringAfter(':'))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("이름 또는 제작자 검색", color = Sgu.Sub) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Sgu.Sub) },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Sgu.Card, focusedContainerColor = Sgu.Card,
                            unfocusedBorderColor = Sgu.Line, focusedBorderColor = Sgu.Ink,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                if (inFolder != null && !searching) {
                    item(key = "folderhead", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { folder = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "폴더 밖으로") }
                            Icon(Icons.Outlined.Folder, null, tint = Sgu.Sub)
                            Text("  $inFolder · ${shown.size}개", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { folderMenu = inFolder }) { Text("폴더 삭제", color = Sgu.Sub) }
                        }
                    }
                }
                if (all.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = Sgu.Accent, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("아직 캐릭터가 없어요", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text("새로 만들거나 CHARX · PNG · JSON 카드를 가져오세요", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub)
                            Spacer(Modifier.height(24.dp))
                            LineButton("가져오기", onClick = { importer.launch(arrayOf("*/*")) }, icon = Icons.Outlined.FileUpload)
                        }
                    }
                } else if (shown.isEmpty() && (searching || inFolder != null)) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) { EmptyPane(if (searching) "찾는 캐릭터가 없어요" else "폴더가 비어 있어요", Modifier.padding(top = 40.dp)) }
                }
                // 폴더도 캐릭터와 같은 크기의 칸. 캐릭터를 꾹 눌러 끌어다 놓으면 들어감.
                if (inFolder == null && !searching) items(CharacterStore.folders.toList(), key = { "f:$it" }) { f ->
                    val key = "f:$f"
                    androidx.compose.runtime.DisposableEffect(key) { onDispose { targets.remove(key) } }
                    FolderTile(f, all.count { it.folder == f }, hover == key,
                        Modifier.onGloballyPositioned { targets[key] = it.boundsInRoot() }, onClick = { folder = f })
                }
                items(shown, key = { it.id }) { c ->
                    val dragging = drag == c.id
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    CharTile(c, selected = c.id == selectedId,
                        modifier = Modifier
                            .zIndex(if (dragging) 10f else 0f)
                            .onGloballyPositioned { if (!dragging) origin = it.positionInRoot() }
                            .graphicsLayer {
                                if (dragging) { translationX = dragOffset.x; translationY = dragOffset.y; alpha = 0.85f }
                            }
                            .pointerInput(c.id, c.folder) {
                                var pointer = Offset.Zero
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { o ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        drag = c.id; dragOffset = Offset.Zero; pointer = origin + o
                                    },
                                    onDrag = { ch, d ->
                                        ch.consume(); dragOffset += d; pointer += d
                                        hover = targets.entries.firstOrNull { (k, r) -> r.contains(pointer) && (k == OUT || k.substringAfter(':') != c.folder) }?.key
                                    },
                                    onDragEnd = { drop(c.id) },
                                    onDragCancel = { drag = null; hover = null; dragOffset = Offset.Zero },
                                )
                            },
                        onClick = { onOpen(c.id) })
                }
            }

            // 끄는 동안 위에 뜨는 폴더 줄: 목록을 내려 폴더 칸이 안 보여도, 폴더 안에서 밖으로 꺼낼 때도 여기에 놓으면 됨.
            if (drag != null) {
                val cur = all.firstOrNull { it.id == drag }?.folder
                Row(
                    Modifier.fillMaxWidth().zIndex(20f).background(Sgu.Bg.copy(alpha = 0.96f)).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (cur != null) DropChip("폴더 밖으로", hover == OUT, Modifier.onGloballyPositioned { targets[OUT] = it.boundsInRoot() })
                    CharacterStore.folders.filter { it != cur }.forEach { f ->
                        val key = "s:$f"
                        DropChip(f, hover == key, Modifier.onGloballyPositioned { targets[key] = it.boundsInRoot() })
                    }
                    if (cur == null && CharacterStore.folders.isEmpty()) Text("폴더가 없어요. 위쪽 폴더 아이콘으로 만들 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(8.dp))
                }
            } else androidx.compose.runtime.SideEffect { targets.keys.removeAll { it == OUT || it.startsWith("s:") } }

            // "+ 새 캐릭터" floating ink pill (like Claude's "+ 새 세션").
            InkButton(
                text = "새 캐릭터",
                icon = Icons.Outlined.Add,
                onClick = { onOpen(CharacterStore.create()) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).then(if (inPane) Modifier.navigationBarsPadding() else Modifier),
            )
            if (busy) {
                Box(Modifier.fillMaxSize().background(Sgu.Bg.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Sgu.Ink)
                }
            }
        }
    }

    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("새 폴더") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("폴더 이름") }, shape = RoundedCornerShape(14.dp))
            },
            confirmButton = { TextButton(onClick = { CharacterStore.createFolder(name); newFolderDialog = false }) { Text("만들기", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
    folderMenu?.let { f ->
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text("폴더 삭제") },
            text = { Text("'$f' 폴더를 지울까요? 안의 캐릭터는 지워지지 않고 '폴더 없음'으로 가요.") },
            confirmButton = { TextButton(onClick = { CharacterStore.deleteFolder(f); folder = null; folderMenu = null }) { Text("삭제", color = Sgu.Danger) } },
            dismissButton = { TextButton(onClick = { folderMenu = null }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
}

private const val OUT = "out"

/** 캐릭터 칸: 이미지 + 이름만 (원본 SGU 목록처럼). */
@Composable
private fun CharTile(c: CharMeta, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val key = "${c.id}:${c.updated}:tile"
    val bmp by produceState<ImageBitmap?>(null, key) {
        value = c.icon?.let { f -> withContext(Dispatchers.IO) { Thumbs.load(f, key, 512) } }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(shape).background(Sgu.Bubble)
                .border(if (selected) 2.dp else 1.dp, if (selected) Sgu.Ink else Sgu.Line, shape),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) Image(b, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Outlined.Person, null, tint = Sgu.Sub, modifier = Modifier.size(36.dp))
        }
        Text(c.name.ifBlank { "이름 없는 캐릭터" }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp))
    }
}

@Composable
private fun FolderTile(name: String, count: Int, hover: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(shape).background(if (hover) Sgu.Bubble else Sgu.Card)
                .border(if (hover) 2.dp else 1.dp, if (hover) Sgu.Ink else Sgu.Line, shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (hover) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, tint = if (hover) Sgu.Ink else Sgu.Sub, modifier = Modifier.size(40.dp))
                Text(if (hover) "여기에 놓기" else "${count}개", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp))
    }
}

@Composable
private fun DropChip(text: String, hover: Boolean, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(if (hover) Sgu.Ink else Sgu.Card).border(1.dp, if (hover) Sgu.Ink else Sgu.Line, CircleShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = if (hover) Color.White else Sgu.Sub, modifier = Modifier.size(18.dp))
        Text("  $text", color = if (hover) Color.White else Sgu.Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
