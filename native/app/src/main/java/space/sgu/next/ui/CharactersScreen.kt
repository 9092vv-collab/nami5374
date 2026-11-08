package space.sgu.next.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(selectedId: String, onOpen: (String) -> Unit, inPane: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CharMeta?>(null) }
    var moveTarget by remember { mutableStateOf<CharMeta?>(null) }
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
    val list = all.filter { c ->
        (folder == null || c.folder == folder) &&
            (query.isBlank() || c.name.contains(query, true) || c.creator.contains(query, true))
    }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item(key = "search") {
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (CharacterStore.folders.isNotEmpty()) {
                    item(key = "folders") {
                        Row(Modifier.fillMaxWidth()) {
                            Box {
                                PillTabs(
                                    options = listOf("전체") + CharacterStore.folders,
                                    selected = if (folder == null) 0 else CharacterStore.folders.indexOf(folder) + 1,
                                    onSelect = { i -> folder = if (i == 0) null else CharacterStore.folders[i - 1] },
                                )
                            }
                        }
                        if (folder != null) {
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${list.size}개", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.weight(1f))
                                TextButton(onClick = { folderMenu = folder }) { Text("폴더 삭제", color = Sgu.Sub) }
                            }
                        }
                    }
                }
                if (all.isEmpty()) {
                    item(key = "empty") {
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
                } else if (list.isEmpty()) {
                    item(key = "none") { EmptyPane("찾는 캐릭터가 없어요", Modifier.padding(top = 40.dp)) }
                }
                items(list, key = { it.id }) { c ->
                    CharacterRow(
                        c, selected = c.id == selectedId,
                        onClick = { onOpen(c.id) },
                        onDuplicate = { CharacterStore.duplicate(c.id) },
                        onMove = { moveTarget = c },
                        onDelete = { deleteTarget = c },
                    )
                }
            }

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
    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("캐릭터 삭제") },
            text = { Text("'${c.name}'을(를) 삭제할까요? 되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = { CharacterStore.delete(c.id); deleteTarget = null }) { Text("삭제", color = Sgu.Danger) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
    moveTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("폴더로 이동") },
            text = {
                Column {
                    (listOf<String?>(null) + CharacterStore.folders).forEach { f ->
                        Text(
                            f ?: "폴더 없음",
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { CharacterStore.moveToFolder(c.id, f); moveTarget = null }
                                .background(if (c.folder == f) Sgu.Bubble else Sgu.Card)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (CharacterStore.folders.isEmpty()) Text("폴더가 없어요. 위쪽 폴더 아이콘으로 만들 수 있어요.", color = Sgu.Sub)
                }
            },
            confirmButton = { TextButton(onClick = { moveTarget = null }) { Text("닫기", color = Sgu.Sub) } },
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

private val dateFmt = SimpleDateFormat("M월 d일", Locale.KOREA)

@Composable
private fun CharacterRow(
    c: CharMeta,
    selected: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Sgu.Bubble else Sgu.Card)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharIcon(c.icon, "${c.id}:${c.updated}", c.name, size = 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.tagline.isNotEmpty()) {
                Text(c.tagline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Sgu.Sub)
            }
            Text(
                listOfNotNull(c.creator.ifBlank { "제작자 미지정" }, dateFmt.format(Date(c.updated)), c.folder).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, color = Sgu.Sub,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더보기", tint = Sgu.Sub) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Sgu.Card, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem(text = { Text("열기") }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("폴더로 이동") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("복제") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("삭제", color = Sgu.Danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
