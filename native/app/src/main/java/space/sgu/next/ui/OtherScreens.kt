package space.sgu.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import space.sgu.next.data.Activation
import space.sgu.next.data.AppState
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.FakeData
import space.sgu.next.data.RefKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencesScreen() {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val groups = FakeData.references.groupBy { it.folder }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    Scaffold(
        containerColor = Sgu.Bg,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SguTopBar(
                title = "참고 자료",
                subtitle = "미리보기 (6단계에서 실제 기능)",
                scrollBehavior = scroll,
                actions = { IconButton(onClick = {}) { Icon(Icons.Outlined.Add, "참고 자료 추가") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            groups.forEach { (folder, refs) ->
                val open = collapsed[folder] != true
                item(key = "f-$folder") {
                    val angle by animateFloatAsState(if (open) 0f else -90f, label = "arrow")
                    Row(
                        Modifier.fillMaxWidth().clickable { collapsed[folder] = open }
                            .padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = Sgu.Sub)
                        Text("  $folder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${refs.size}개  ", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(angle), tint = Sgu.Sub)
                    }
                }
                item(key = "l-$folder") {
                    AnimatedVisibility(visible = open) {
                        Column {
                            refs.forEachIndexed { i, r ->
                                GroupRow(
                                    i, refs.size, r.name,
                                    subtitle = r.kind.label + " · " + r.memo,
                                    icon = kindIcon(r.kind),
                                    onClick = {},
                                    trailing = { ActivationBadge(r.activation) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun kindIcon(k: RefKind): ImageVector = when (k) {
    RefKind.Text -> Icons.Outlined.Description
    RefKind.Image -> Icons.Outlined.Image
    RefKind.Persona -> Icons.Outlined.Face
    RefKind.File -> Icons.Outlined.AttachFile
}

@Composable
private fun ActivationBadge(a: Activation) = when (a) {
    Activation.Always -> Badge(a.label)
    Activation.Sub -> Badge(a.label, Sgu.AccentSoft, Color(0xFF8A3B22))
    Activation.Keyword -> Badge(a.label, Sgu.Bubble, Sgu.Ink)
    Activation.Off -> Badge(a.label, Color.Transparent, Sgu.Sub)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    Scaffold(
        containerColor = Sgu.Bg,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { SguTopBar(title = "설정", scrollBehavior = scroll) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item { SectionLabel("실행") }
            item { GroupRow(0, 3, "프로세서 배정", subtitle = "메인 · 서브 모델 지정", icon = Icons.Outlined.Tune, onClick = {}) }
            item { GroupRow(1, 3, "프로바이더", subtitle = "OpenAI · Anthropic · Gemini · Vertex · Copilot", icon = Icons.Outlined.Key, onClick = {}) }
            item { GroupRow(2, 3, "모델", subtitle = "등록된 모델과 파라미터", icon = Icons.Outlined.Memory, onClick = {}) }
            item { SectionLabel("권한 · 프롬프트") }
            item { GroupRow(0, 2, "새 캐릭터 기본 권한", subtitle = "필드별 열람/편집 기본 등급", icon = Icons.Outlined.Shield, onClick = {}) }
            item { GroupRow(1, 2, "프롬프트 프리셋", subtitle = "여러 프리셋을 저장하고 캐릭터마다 골라 쓰기", icon = Icons.Outlined.SmartToy, onClick = {}) }
            item { SectionLabel("화면 · 저장") }
            item { GroupRow(0, 3, "세이프 모드", subtitle = "모든 이미지를 썸네일로 표시", trailing = { SguSwitch(AppState.safeMode) { AppState.safeMode = it } }) }
            item { GroupRow(1, 3, "자동 압축", subtitle = "내보낼 때 이미지를 WebP 80%로 압축", trailing = { SguSwitch(AppState.autoCompress) { AppState.autoCompress = it } }) }
            item { GroupRow(2, 3, "저장된 캐릭터", subtitle = "${CharacterStore.items.size}개 · 폴더 ${CharacterStore.folders.size}개", icon = Icons.Outlined.Folder) }
            item { SectionLabel("기타") }
            item { GroupRow(0, 4, "백그라운드 작업", subtitle = "앱이 뒤로 가도 작업을 이어갑니다", icon = Icons.Outlined.Notifications, onClick = {}) }
            item { GroupRow(1, 4, "요청 로그", subtitle = "서브/메인 프로세서 요청과 응답", icon = Icons.Outlined.History, onClick = {}) }
            item { GroupRow(2, 4, "백업 · 복원", subtitle = "설정 JSON 내보내기/가져오기", icon = Icons.Outlined.Backup, onClick = {}) }
            item { GroupRow(3, 4, "언어", subtitle = "한국어", icon = Icons.Outlined.Language, onClick = {}) }
            item {
                Text(
                    "SGU Next 0.2.0 · 캐릭터 저장·가져오기·내보내기 작동, 나머지는 미리보기",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodySmall, color = Sgu.Sub,
                )
            }
        }
    }
}
