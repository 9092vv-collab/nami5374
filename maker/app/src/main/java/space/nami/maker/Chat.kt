package space.nami.maker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ChatPane(chat: AiChat, mapId: Int, modifier: Modifier, onSettings: () -> Unit, onClose: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    @Suppress("UNUSED_VARIABLE") val apiRev = Api.rev + Prompts.rev
    LaunchedEffect(chat.items.size, chat.busy, chat.live.length / 200, chat.liveThink.length / 200) { if (chat.items.isNotEmpty()) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
    fun send(t: String) {
        val s = t.trim(); if (s.isEmpty() || chat.busy) return
        input = ""
        scope.launch { chat.send(s, mapId) }
    }

    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "닫기") { onClose() }
            Icon(Icons.Filled.AutoAwesome, null, tint = K.accent, modifier = Modifier.size(18.dp))
            Text("  AI", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.text)
            Text("  " + (Api.current()?.let { Api.label(it) } ?: "모델 없음") + "  ·  " + Prompts.active().optString("name"),
                fontSize = 12.sp, color = K.sub, maxLines = 1, modifier = Modifier.weight(1f).clickable { onSettings() })
            IconBtn(Icons.Filled.EditNote, "새 대화", enabled = !chat.busy) { chat.clear() }
            IconBtn(Icons.Filled.Settings, "설정") { onSettings() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.items.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("무엇을 만들어볼까요?", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = K.text)
                        Spacer(Modifier.padding(4.dp))
                        Text("AI가 지금 맵을 보면서 직접 고쳐요", fontSize = 13.sp, color = K.sub)
                        Spacer(Modifier.padding(10.dp))
                        listOf("이 맵을 보고 어떤지 말해줘", "집 안 맵을 만들고 문으로 연결해줘", "마을 사람 3명 놓고 대사 써줘", "상자를 열면 열쇠가 나오게 해줘").forEach { s ->
                            Text(s, fontSize = 14.sp, color = K.text, modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                                .border(1.dp, K.line, RoundedCornerShape(14.dp)).background(K.card).clickable { send(s) }
                                .padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            itemsIndexed(chat.items) { _, it ->
                when (it.role) {
                    "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(it.text, fontSize = 15.sp, color = K.text, modifier = Modifier.widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(18.dp)).background(K.bubble).padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    "ai" -> Text(it.text, fontSize = 15.sp, color = K.text, lineHeight = 22.sp, modifier = Modifier.padding(end = 12.dp))
                    "think" -> ThinkCard(it.text, live = false)
                    "tool" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, null, tint = K.sub, modifier = Modifier.size(13.dp))
                        Text("  " + it.text, fontSize = 12.sp, color = K.sub)
                    }
                    else -> Text(it.text, fontSize = 13.sp, color = K.danger, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFBE9E7)).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (chat.busy && chat.liveThink.isNotEmpty()) item { ThinkCard(chat.liveThink, live = true) }
            if (chat.busy && chat.live.isNotEmpty()) item { Text(chat.live, fontSize = 15.sp, color = K.text, lineHeight = 22.sp, modifier = Modifier.padding(end = 12.dp)) }
            if (chat.busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = K.accent, strokeWidth = 2.dp)
                    Text("  작업하는 중…", fontSize = 13.sp, color = K.sub)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(24.dp)).background(K.card)
                .border(1.dp, K.line, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (input.isEmpty()) Text("AI에게 부탁하기", fontSize = 15.sp, color = K.sub)
                BasicTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(max = 140.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = K.text), cursorBrush = SolidColor(K.accent))
            }
            val can = input.isNotBlank() && !chat.busy
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (can) K.accent else K.line).clickable(enabled = can) { send(input) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowUpward, "보내기", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}


@Composable
private fun ThinkCard(text: String, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.panel).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(if (live) "생각하는 중…" else if (open) "생각 접기" else "생각 보기", fontSize = 12.sp, color = K.sub, fontWeight = FontWeight.SemiBold)
        if (open || live) Text(if (open) text else text.takeLast(240), fontSize = 12.sp, color = K.sub, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
