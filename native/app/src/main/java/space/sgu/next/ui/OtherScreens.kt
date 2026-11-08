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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.sgu.next.data.Activation
import space.sgu.next.data.AppState
import space.sgu.next.data.FakeData
import space.sgu.next.data.RefKind
import space.sgu.next.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencesScreen() {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val groups = FakeData.references.groupBy { it.folder }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { BarTitle("참고 자료", "폴더로 분류하고 활성화 방식을 정합니다") },
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.Add, "참고 자료 추가") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            groups.forEach { (folder, refs) ->
                val open = collapsed[folder] != true
                item(key = "f-$folder") {
                    val angle by animateFloatAsState(if (open) 0f else -90f, label = "arrow")
                    Row(
                        Modifier.fillMaxWidth().clickable { collapsed[folder] = open }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(folder, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${refs.size}개", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(angle))
                    }
                }
                item(key = "l-$folder") {
                    AnimatedVisibility(visible = open) {
                        Column {
                            refs.forEach { r ->
                                ListItem(
                                    leadingContent = { Icon(kindIcon(r.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    headlineContent = { Text(r.name) },
                                    supportingContent = { Text(r.kind.label + " · " + r.memo) },
                                    trailingContent = { ActivationPill(r.activation) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {},
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
    RefKind.Text -> Icons.Filled.Description
    RefKind.Image -> Icons.Filled.Image
    RefKind.Persona -> Icons.Filled.Face
    RefKind.File -> Icons.Filled.AttachFile
}

@Composable
private fun ActivationPill(a: Activation) {
    val cs = MaterialTheme.colorScheme
    when (a) {
        Activation.Always -> Pill(a.label, cs.primary, cs.onPrimary)
        Activation.Sub -> Pill(a.label, cs.primaryContainer, cs.onPrimaryContainer)
        Activation.Keyword -> Pill(a.label, cs.secondaryContainer, cs.onSecondaryContainer)
        Activation.Off -> Pill(a.label, cs.surfaceVariant, cs.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { BarTitle("설정") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp)) {
            item { SectionLabel("01 실행") }
            item { SettingRow(Icons.Filled.Tune, "프로세서 배정", "메인 · 서브 모델 지정") }
            item { SettingRow(Icons.Filled.Key, "프로바이더", "OpenAI · Anthropic · Gemini · Vertex · Copilot") }
            item { SettingRow(Icons.Filled.Memory, "모델", "등록된 모델과 파라미터") }
            item { SectionLabel("02 권한 · 프롬프트") }
            item { SettingRow(Icons.Filled.Shield, "새 캐릭터 기본 권한", "필드별 열람/편집 기본 등급") }
            item { SettingRow(Icons.Filled.SmartToy, "프롬프트 프리셋", "여러 프리셋을 저장하고 캐릭터마다 골라 쓰기") }
            item { SectionLabel("03 화면") }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = AppState.themeMode == m,
                                onClick = { AppState.setTheme(ctx, m) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                label = { Text(m.label) },
                            )
                        }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("세이프 모드") },
                    supportingContent = { Text("모든 이미지를 썸네일로 표시합니다") },
                    trailingContent = { SguSwitch(AppState.safeMode) { AppState.safeMode = it } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { SectionLabel("04 기타") }
            item { SettingRow(Icons.Filled.Notifications, "백그라운드 작업", "앱이 뒤로 가도 작업을 이어갑니다") }
            item { SettingRow(Icons.Filled.History, "요청 로그", "서브/메인 프로세서 요청과 응답") }
            item { SettingRow(Icons.Filled.Backup, "백업 · 복원", "설정 JSON 내보내기/가져오기") }
            item { SettingRow(Icons.Filled.Language, "언어", "한국어") }
            item {
                ListItem(
                    headlineContent = { Text("자동 압축") },
                    supportingContent = { Text("내보낼 때 이미지를 WebP 80%로 압축") },
                    trailingContent = { SguSwitch(AppState.autoCompress) { AppState.autoCompress = it } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                Text(
                    "SGU Next 0.1.0 · 뼈대 미리보기 (가짜 데이터)",
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable {},
    )
}
