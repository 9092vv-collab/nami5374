package space.nami.rotate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.widget.Toast
import rikka.shizuku.Shizuku
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFFFAF9F7)
private val Ink = Color(0xFF141413)
private val Gray = Color(0xFF6B6A66)
private val Line = Color(0xFFE6E5E0)
private val Accent = Color(0xFFD97757)
private val AccentSoft = Color(0xFFF7E3DA)

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Bg)) {
                Screen(tick)
            }
        }
    }

    private val permResult = Shizuku.OnRequestPermissionResultListener { _, _ -> tick++ }
    private val binderUp = Shizuku.OnBinderReceivedListener { tick++ }

    override fun onStart() {
        super.onStart()
        runCatching {
            Shizuku.addRequestPermissionResultListener(permResult)
            Shizuku.addBinderReceivedListenerSticky(binderUp)
        }
    }

    override fun onStop() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permResult)
            Shizuku.removeBinderReceivedListener(binderUp)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        tick++ // re-read permissions after coming back from system settings
    }
}

private fun connectShizuku(c: android.content.Context) {
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    if (running) {
        runCatching { Shizuku.requestPermission(1) }
    } else {
        val open = c.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (open != null) c.startActivity(open)
        else Toast.makeText(c, "Shizuku 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}

private fun serviceOn(c: android.content.Context): Boolean {
    val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return list.split(':').any { it.startsWith(c.packageName + "/") }
}

@Composable
private fun Screen(tick: Int) {
    val c = LocalContext.current
    val canWrite = remember(tick) { Settings.System.canWrite(c) }
    val service = remember(tick) { serviceOn(c) }
    val shizuku = remember(tick) { Sh.ready() }
    var enabled by remember { mutableStateOf(Prefs.enabled(c)) }
    var rotation by remember { mutableIntStateOf(Prefs.rotation(c)) }
    var force by remember { mutableStateOf(Prefs.force(c)) }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("회전 고정", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("펼쳤을 때만 메인 화면을 정한 방향으로 고정해요. 접으면 커버 화면은 원래대로예요.",
                color = Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            if (!shizuku || !service) {
                Card {
                    Text("처음 한 번만 허용해 주세요", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Step("1. Shizuku 연결", done = shizuku) { connectShizuku(c) }
                    if (!shizuku) {
                        Spacer(Modifier.height(8.dp))
                        Step("또는 시스템 설정 변경 허용", done = canWrite) {
                            c.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Shizuku 없이도 돌아가지만, 방향을 정해 둔 앱은 못 막고 잘 안 먹힐 수 있어요. Shizuku가 꺼져 있으면 Shizuku 앱이 열려요.",
                            color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Step("2. 접근성에서 '회전 고정' 켜기", done = service) {
                        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!service) {
                        Spacer(Modifier.height(10.dp))
                        Text("접근성에서 회색으로 막혀 있으면: 설정 › 애플리케이션 › 회전 고정 › 오른쪽 위 ⋮ › '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
                            color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("펼쳤을 때 고정", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (enabled) "켜짐" else "꺼짐 · 원래 회전 설정으로 돌아가요", color = Gray, fontSize = 13.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; Prefs.setEnabled(c, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                }
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("모든 앱에 강제", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !shizuku -> "Shizuku 연결이 필요해요"
                                force -> "방향을 정해 둔 앱도 따라와요"
                                else -> "앱이 정한 방향은 그대로 둬요"
                            }, color = Gray, fontSize = 13.sp)
                    }
                    Switch(
                        checked = force && shizuku,
                        enabled = shizuku,
                        onCheckedChange = { force = it; Prefs.setForce(c, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                }
            }

            Card {
                Text("방향", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(holeBottomRight = true, rotation == Surface.ROTATION_90, Modifier.weight(1f)) {
                        rotation = Surface.ROTATION_90; Prefs.setRotation(c, rotation)
                    }
                    Pill(holeBottomRight = false, rotation == Surface.ROTATION_270, Modifier.weight(1f)) {
                        rotation = Surface.ROTATION_270; Prefs.setRotation(c, rotation)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("누르면 바로 바뀌어요.",
                    color = Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(24.dp)).padding(18.dp)
    ) { content() }
}

@Composable
private fun Step(text: String, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (done) AccentSoft else Bg)
            .clickable(enabled = !done, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(if (done) "완료" else "열기 ›", color = if (done) Gray else Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(holeBottomRight: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (selected) Color.White else Ink
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Bg)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A tall phone: outline, a line across the middle (the fold), and the punch hole.
        Canvas(Modifier.size(width = 30.dp, height = 46.dp)) {
            val sw = 2.5.dp.toPx()
            val r = 6.dp.toPx()
            drawRoundRect(ink, topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(r, r), style = Stroke(sw))
            drawLine(ink, Offset(sw, size.height / 2), Offset(size.width - sw, size.height / 2),
                strokeWidth = 1.5.dp.toPx())
            val inset = 7.dp.toPx()
            val c = if (holeBottomRight) Offset(size.width - inset, size.height - inset) else Offset(inset, inset)
            drawCircle(ink, radius = 2.6.dp.toPx(), center = c)
        }
    }
}
