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
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Badge
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.FakeData
import space.sgu.next.data.RefKind
import space.sgu.next.data.SettingsStore
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(open: (String) -> Unit) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var editingCreator by remember { mutableStateOf(false) }
    val procs = SettingsStore.root.o("processors")
    val mainModel = SettingsStore.model(procs.o("main").sOrNull("modelId"))
    val subLabel = when {
        procs.o("sub").b("disabled") -> "서브 꺼짐"
        procs.o("sub").s("strategy") == "jev" -> "서브 Jev"
        else -> SettingsStore.model(procs.o("sub").sOrNull("modelId"))?.s("name")?.let { "서브 $it" } ?: "서브 미지정"
    }
    Scaffold(
        containerColor = Sgu.Bg,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { SguTopBar(title = "설정", scrollBehavior = scroll) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item { SectionLabel("실행") }
            item { GroupRow(0, 3, "프로세서 배정", subtitle = "메인 ${mainModel?.s("name") ?: "미지정"} · $subLabel", icon = Icons.Outlined.Tune, onClick = { open("processors") }) }
            item { GroupRow(1, 3, "프로바이더", subtitle = SettingsStore.providers.size.let { if (it == 0) "없음 · 눌러서 추가" else "${it}개 · " + SettingsStore.providers.joinToString(", ") { p -> p.s("name") } }, icon = Icons.Outlined.Key, onClick = { open("providers") }) }
            item { GroupRow(2, 3, "모델", subtitle = "등록된 모델 ${SettingsStore.models.size}개", icon = Icons.Outlined.Memory, onClick = { open("models") }) }
            item { SectionLabel("권한 · 프롬프트") }
            item { GroupRow(0, 4, "새 캐릭터 기본 권한", subtitle = "필드별 열람/편집 기본 등급", icon = Icons.Outlined.Shield, onClick = { open("permdefaults") }) }
            item { GroupRow(1, 4, "항목 설명", subtitle = "서브 프로세서가 판단할 때 보는 항목별 설명", icon = Icons.Outlined.Description, onClick = { open("fielddescs") }) }
            item {
                val presets = SettingsStore.root.arr("promptPresets")
                val active = presets.firstOrNull { it.s("id") == SettingsStore.root.s("activePromptPresetId") }?.s("name") ?: "-"
                GroupRow(2, 4, "프롬프트 프리셋", subtitle = "${presets.size}개 프리셋 · 기본값: $active", icon = Icons.Outlined.SmartToy, onClick = { open("promptpresets") })
            }
            item { GroupRow(3, 4, "세션 요약", subtitle = if (space.sgu.next.data.Workshop.summaryEnabled()) "자동 요약 켜짐 · 최근 ${space.sgu.next.data.Workshop.summaryKeep()}턴 원문" else "자동 요약 꺼짐", icon = Icons.Outlined.Summarize, onClick = { open("sessionsummary") }) }
            item { SectionLabel("프로젝트 · 저장") }
            item { GroupRow(0, 3, "제작자 이름", subtitle = SettingsStore.root.s("creatorName").trim().ifEmpty { "비워 두면 새 프로젝트의 제작자도 비어 있습니다" }, icon = Icons.Outlined.Badge, onClick = { editingCreator = true }) }
            item { GroupRow(1, 3, "되돌리기 버튼을 프로젝트명 아래에 표시", subtitle = "켜면 채팅 입력창 옆 대신 상단 프로젝트명 아래로 이동합니다.",
                trailing = { SguSwitch(SettingsStore.root.b("undoUnderTitle")) { v -> SettingsStore.update { it["undoUnderTitle"] = JsonPrimitive(v) } } }) }
            item { GroupRow(2, 3, "저장된 캐릭터", subtitle = "${CharacterStore.items.size}개 · 폴더 ${CharacterStore.folders.size}개", icon = Icons.Outlined.Folder) }
            item { SectionLabel("기타") }
            item { GroupRow(0, 5, "백업 · 복원", subtitle = "드라이브 백업 · 설정 JSON 내보내기/가져오기", icon = Icons.Outlined.Backup, onClick = { open("backup") }) }
            item { GroupRow(1, 5, "백그라운드 작업 · 웹 검색", subtitle = if (SettingsStore.root.o("background").b("foregroundService", true)) "포그라운드 서비스 켜짐" else "포그라운드 서비스 꺼짐", icon = Icons.Outlined.Notifications, onClick = { open("background") }) }
            item { GroupRow(2, 5, "요청 로그", subtitle = "서브/메인 프로세서 요청과 응답", icon = Icons.Outlined.History, onClick = { open("logs") }) }
            item { GroupRow(3, 5, "RisuAI 프리셋 편집기", subtitle = ".risup 열기 · 고치기 · 저장, 작업실 프리셋으로 가져오기", icon = Icons.Outlined.EditNote, onClick = { open("risupreset") }) }
            item { GroupRow(4, 5, "첫 실행 안내 다시 보기", subtitle = "SGU 소개 화면", icon = Icons.Outlined.Info, onClick = { open("welcome") }) }
            item {
                Text(
                    "SGU Next 0.8.2 · 캐릭터·스크립트·기타·로어북·설정·이미지·작업실·참고 자료·내보내기 작동",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodySmall, color = Sgu.Sub,
                )
            }
        }
        if (editingCreator) NameDialog("제작자 이름", SettingsStore.root.s("creatorName"), "비워 두면 새 프로젝트의 제작자도 비어 있습니다",
            onDismiss = { editingCreator = false }) { v -> SettingsStore.update { it["creatorName"] = JsonPrimitive(v.trim()) } }
    }
}
