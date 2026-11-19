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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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

    // What the system is actually doing right now (checked every second), shown under each item.
    var live by remember { mutableStateOf(Live()) }
    LaunchedEffect(Unit) {
        while (true) {
            live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Live.read(c) }
            delay(1000)
        }
    }

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
                        val paused = RotateService.pausedFor
                        when {
                            !live.unfolded -> LiveText("지금: 접혀 있어서 쉬는 중", null)
                            paused != null -> LiveText("지금: 풀림 · 뺀 앱(${appLabel(c, paused)})이 앞에 있어서 자동 회전", null)
                            !enabled -> LiveText("지금: 고정 안 함 · 화면 ${live.deg}°", null)
                            live.accel == 0 && live.rot == rotation -> LiveText("지금: 고정됨 · ${live.deg}°", true)
                            else -> LiveText("지금: 아직 안 걸림 · 화면 ${live.deg}° · 자동 회전 " +
                                (if (live.accel == 1) "켜짐" else "꺼짐"), false)
                        }
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
                                force -> "방향을 정해 둔 앱도 따라와요. 이 앱을 지우기 전엔 꼭 끄세요(안 끄면 설정이 남아요)"
                                else -> "앱이 정한 방향은 그대로 둬요"
                            }, color = Gray, fontSize = 13.sp)
                        if (shizuku) {
                            val want = force && enabled && live.unfolded && RotateService.pausedFor == null
                            when (live.ignore) {
                                null -> LiveText("지금 시스템 값: 확인 못 함", null)
                                else -> LiveText("지금 시스템 값: 앱 방향 무시 " + (if (live.ignore == true) "켜짐" else "꺼짐"),
                                    live.ignore == want)
                            }
                        }
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
                    Pill(holeBottomRight = false, rotation == Surface.ROTATION_90, Modifier.weight(1f)) {
                        rotation = Surface.ROTATION_90; Prefs.setRotation(c, rotation)
                    }
                    Pill(holeBottomRight = true, rotation == Surface.ROTATION_270, Modifier.weight(1f)) {
                        rotation = Surface.ROTATION_270; Prefs.setRotation(c, rotation)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("누르면 바로 바뀌어요.",
                    color = Gray, fontSize = 13.sp)
                LiveText("지금 화면: ${live.deg}°", if (live.unfolded && enabled) live.rot == rotation else null)
            }

            ExcludeCard()

            // Status line for finding problems (screenshot this when something is off).
            var status by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    val r = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                        ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1
                    val accel = Settings.System.getInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
                    val user = Settings.System.getInt(c.contentResolver, Settings.System.USER_ROTATION, -1)
                    status = "상태 · 서비스 " + (if (RotateService.running) "실행 중" else "꺼짐") +
                        " · Shizuku " + (if (Sh.ready()) "연결" else "없음") +
                        " · " + (if (isUnfolded(c)) "펼침" else "접힘") + " (${panelSize(c)})" +
                        "\n화면 ${r * 90}° · 자동 회전 $accel · 잠금 값 ${user * 90}°" +
                        (RotateService.pausedFor?.let { "\n고정 풀림: $it (뺀 앱)" } ?: "") +
                        (if (Sh.last.isNotEmpty()) "\n마지막 명령: ${Sh.last}" else "")
                    delay(1000)
                }
            }
            Text(status, color = Gray, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

/** The system's real state, for the "지금" lines. */
private data class Live(
    val rot: Int = -1, val accel: Int = -1, val ignore: Boolean? = null, val unfolded: Boolean = false,
) {
    val deg get() = if (rot < 0) "?" else (rot * 90).toString()

    companion object {
        fun read(c: android.content.Context): Live {
            val r = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1
            val accel = Settings.System.getInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
            val ign = Sh.peek("cmd", "window", "get-ignore-orientation-request")?.let { it.contains("true") }
            return Live(r, accel, ign, isUnfolded(c))
        }
    }
}

private fun appLabel(c: android.content.Context, pkg: String): String = runCatching {
    c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Green when it matches what you set, orange when it doesn't, gray when there's nothing to compare. */
@Composable
private fun LiveText(text: String, ok: Boolean?) {
    Text(
        (if (ok == true) "● " else if (ok == false) "● " else "") + text,
        color = when (ok) { true -> Color(0xFF2F8F5B); false -> Accent; null -> Gray },
        fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private data class LaunchApp(val pkg: String, val label: String)

private fun launchApps(c: android.content.Context): List<LaunchApp> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { LaunchApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != c.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** Apps that get the screen back (lock let go) while they're in front. */
@Composable
private fun ExcludeCard() {
    val c = LocalContext.current
    var excluded by remember { mutableStateOf(Prefs.excluded(c)) }
    var picking by remember { mutableStateOf(false) }
    val apps by androidx.compose.runtime.produceState<List<LaunchApp>>(emptyList(), picking) {
        if (picking || value.isEmpty()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { launchApps(c) }
    }
    fun set(v: Set<String>) { excluded = v; Prefs.setExcluded(c, v) }

    Card {
        Text("고정에서 뺄 앱", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("이 앱이 맨 앞에 있는 동안은 고정을 풀고 자동 회전으로 둬요. 다른 앱으로 가면 다시 고정해요.",
            color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(10.dp))
        excluded.map { p -> p to (apps.firstOrNull { it.pkg == p }?.label ?: p) }.sortedBy { it.second }.forEach { (p, label) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(50)).background(Bg)
                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("빼기 취소", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { set(excluded - p) }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("+ 앱 추가", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Accent)
                .clickable { picking = true }.padding(vertical = 11.dp))
    }

    if (picking) {
        var q by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("고정에서 뺄 앱") },
            text = {
                Column {
                    androidx.compose.foundation.text.BasicTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Bg)
                                .padding(horizontal = 16.dp, vertical = 11.dp)) {
                                if (q.isEmpty()) Text("앱 이름 검색", color = Gray, fontSize = 15.sp)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(380.dp)) {
                        items(shown.size) { i ->
                            val a = shown[i]
                            val on = a.pkg in excluded
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .clickable { set(if (on) excluded - a.pkg else excluded + a.pkg) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, color = Ink, fontSize = 15.sp)
                                    Text(a.pkg, color = Gray, fontSize = 11.sp, maxLines = 1)
                                }
                                Text(if (on) "뺌" else "", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text("닫기", color = Accent) } },
            containerColor = Color.White,
        )
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
