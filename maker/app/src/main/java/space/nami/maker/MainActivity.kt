package space.nami.maker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.light(t, t), SystemBarStyle.light(t, t))
        setContent { MakerTheme { App() } }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<Doc?>(null) }
    var chat by remember { mutableStateOf<AiChat?>(null) }
    var playing by remember { mutableStateOf(false) }
    val d = doc
    val c = chat
    when {
        d != null && playing -> Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            PlayScreen(d.p) { playing = false }
        }
        d != null && c != null -> Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding()) {
            BackHandler { d.save(); doc = null; chat = null }
            EditorScreen(d, c, onBack = { d.save(); doc = null; chat = null }, onPlay = { playing = true })
        }
        else -> Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding()) {
            Home(onOpen = { nd -> doc = nd; chat = AiChat(ctx, nd) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(onOpen: (Doc) -> Unit) {
    val ctx = LocalContext.current
    var games by remember { mutableStateOf(Store.list(ctx)) }
    var newGame by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Store.Entry?>(null) }
    var settings by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                IconBtn(androidx.compose.material.icons.Icons.Filled.Settings, "API 설정") { settings = true }
            }
            Text("Nami Maker", fontSize = 32.sp, fontFamily = FontFamily.Serif, color = K.text)
            Text("AI와 함께 만드는 나만의 게임", fontSize = 14.sp, color = K.sub, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.padding(top = 18.dp))
            Text(
                "+  새 게임 만들기", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(K.accent)
                    .combinedClickable(onClick = { newGame = true }).padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Spacer(Modifier.padding(top = 18.dp))
            if (games.isEmpty()) Text("아직 만든 게임이 없어요", color = K.sub, fontSize = 14.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(games) { g ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.card).border(1.dp, K.line, RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = { runCatching { Store.open(g.file) }.onSuccess(onOpen) }, onLongClick = { del = g })
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                            Text(fmt.format(Date(g.modified)), fontSize = 12.sp, color = K.sub)
                        }
                    }
                }
                item { Text("꾹 누르면 지울 수 있어요", fontSize = 12.sp, color = K.sub, modifier = Modifier.padding(top = 6.dp, bottom = 30.dp)) }
            }
        }
    }

    if (newGame) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGame = false }, containerColor = K.bg,
            title = { Text("새 게임") },
            text = { Field(name, { name = it }, "게임 이름", Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { newGame = false; onOpen(Store.create(ctx, name.ifBlank { "새 게임" })) }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { newGame = false }) { Text("취소", color = K.sub) } },
        )
    }
    if (settings) SettingsDialog(onClose = { settings = false })
    del?.let { g ->
        AlertDialog(
            onDismissRequest = { del = null }, containerColor = K.bg,
            title = { Text("'${g.name}' 지울까요?") },
            text = { Text("되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = { Store.delete(g); del = null; games = Store.list(ctx) }) { Text("지우기", color = K.danger) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("취소", color = K.sub) } },
        )
    }
}
