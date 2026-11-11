package space.sgu.next.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.sgu.next.data.BuiltinPrompts

/** 설정 › 내장 프롬프트: 앱에 박혀 있던 문구를 전부 보고 고침. 항목마다 기본값으로 되돌리기. */
@Composable
fun BuiltinPromptsScreen(onBack: () -> Unit) {
    var editing by remember { mutableStateOf<BuiltinPrompts.Item?>(null) }
    var resetting by remember { mutableStateOf<BuiltinPrompts.Item?>(null) }
    var resetAll by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf("") } // 펼친 묶음 이름
    val groups = remember { BuiltinPrompts.items.groupBy { it.group } }
    val changed = BuiltinPrompts.items.count { BuiltinPrompts.overridden(it.key) }
    Page("내장 프롬프트", if (changed > 0) "${changed}개 수정됨" else "모두 기본값", onBack) {
        Hint("프리셋 블록 말고 앱이 직접 붙이는 문구예요. 누르면 전체를 보고 고칠 수 있어요. [[이름]]은 앱이 내용을 채워 넣는 자리라 지우면 그 내용이 빠져요.")
        KoToggle(Modifier.padding(vertical = 6.dp))
        groups.forEach { (group, list) ->
            val isOpen = open == group
            val n = list.count { BuiltinPrompts.overridden(it.key) }
            Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) "" else group }.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text("${list.size}개" + if (n > 0) " · ${n}개 수정됨" else "", style = MaterialTheme.typography.bodySmall, color = if (n > 0) Sgu.Accent else Sgu.Sub)
                Spacer(Modifier.width(6.dp))
                Icon(if (isOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Sgu.Sub)
            }
            if (isOpen) list.forEach { item ->
                val mod = BuiltinPrompts.overridden(item.key)
                LineCard(Modifier.padding(vertical = 4.dp), onClick = { editing = item }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (mod) { Badge("수정됨", Sgu.AccentSoft, Sgu.Accent); TextButton(onClick = { resetting = item }) { Text("되돌리기", color = Sgu.Sub) } }
                        }
                        if (item.where.isNotBlank()) Text(item.where, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        if (item.warn != null) Text("주의: ${item.warn}", style = MaterialTheme.typography.bodySmall, color = Sgu.Danger, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(koText(BuiltinPrompts.get(item.key).take(700)), style = MaterialTheme.typography.bodySmall, color = Sgu.Ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (changed > 0) LineButton("전부 기본값으로 되돌리기", onClick = { resetAll = true }, modifier = Modifier.fillMaxWidth())
    }
    editing?.let { item ->
        TextEditDialog(item.title, BuiltinPrompts.get(item.key), onDismiss = { editing = null }) { v -> BuiltinPrompts.set(item.key, v); editing = null }
    }
    resetting?.let { item ->
        ConfirmDialog("기본값으로 되돌리기", "'${item.title}'을(를) 앱 기본 문구로 되돌립니다.", "되돌리기", onConfirm = { BuiltinPrompts.set(item.key, "") }, onDismiss = { resetting = null })
    }
    if (resetAll) ConfirmDialog("전부 기본값으로", "수정한 내장 프롬프트 ${changed}개를 모두 앱 기본 문구로 되돌립니다.", "되돌리기",
        onConfirm = { BuiltinPrompts.items.forEach { if (BuiltinPrompts.overridden(it.key)) BuiltinPrompts.set(it.key, "") } }, onDismiss = { resetAll = false })
}
