package space.sgu.next.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import space.sgu.next.data.CharacterStore

/**
 * Full-screen editor for one card field. It is its own navigation destination, so the system
 * back gesture returns to the field list. Changes are saved when leaving the screen.
 */
@Composable
fun FieldEditorScreen(characterId: String, key: String, onBack: () -> Unit) {
    val original = remember(characterId, key) { CharacterStore.getField(CharacterStore.readCard(characterId), key) }
    var text by rememberSaveable(characterId, key) { mutableStateOf(original) }
    var deleted by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val latest by rememberUpdatedState(text)
    val isAlt = key.startsWith("alt:")

    // Save on leave (back gesture, back arrow or ✓).
    DisposableEffect(characterId, key) {
        onDispose {
            if (!deleted && latest != original && CharacterStore.items.any { it.id == characterId }) {
                CharacterStore.setField(characterId, key, latest)
            }
        }
    }
    LaunchedEffect(Unit) { if (original.isEmpty()) runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = Sgu.Bg,
        topBar = {
            SguTopBar(
                title = fieldLabel(key),
                subtitle = CharacterStore.items.firstOrNull { it.id == characterId }?.name,
                onBack = onBack,
                actions = {
                    if (isAlt) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "삭제") }
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.Check, "완료") }
                },
            )
        },
    ) { pad ->
        Box(
            Modifier.fillMaxSize().padding(pad).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            if (text.isEmpty()) Text("여기에 내용을 쓰세요", style = MaterialTheme.typography.bodyLarge, color = Sgu.Sub)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Sgu.Ink),
                cursorBrush = SolidColor(Sgu.Accent),
                modifier = Modifier.fillMaxSize().focusRequester(focus).padding(bottom = 200.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 첫 메시지 삭제") },
            text = { Text("${fieldLabel(key)}을(를) 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    deleted = true
                    CharacterStore.removeAltGreeting(characterId, key.removePrefix("alt:").toIntOrNull() ?: -1)
                    confirmDelete = false
                    onBack()
                }) { Text("삭제", color = Sgu.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
}
