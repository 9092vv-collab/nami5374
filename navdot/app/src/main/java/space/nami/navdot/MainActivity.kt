package space.nami.navdot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextAlign
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

    override fun onResume() {
        super.onResume()
        tick++ // re-check the accessibility switch after coming back from settings
    }
}

private fun serviceOn(c: Context): Boolean {
    val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return list.split(':').any { it.startsWith(c.packageName + "/") }
}

@Composable
private fun Screen(tick: Int) {
    val c = LocalContext.current
    val sp = remember { Prefs.sp(c) }
    val service = remember(tick) { serviceOn(c) }
    var enabled by remember { mutableStateOf(Prefs.enabled(sp)) }
    var size by remember { mutableFloatStateOf(Prefs.size(sp).toFloat()) }
    var alpha by remember { mutableFloatStateOf(Prefs.alpha(sp).toFloat()) }
    var avoidIme by remember { mutableStateOf(Prefs.avoidIme(sp)) }
    var haptic by remember { mutableStateOf(Prefs.haptic(sp)) }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("내비 점", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
            Text("화면에 떠 있는 점 하나로 뒤로 가기·홈·최근 앱을 써요.",
                color = Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

            if (!service) {
                Card {
                    Text("처음 한 번만 허용해 주세요", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Step("접근성에서 '내비 점' 켜기", done = false) {
                        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("접근성에서 회색으로 막혀 있으면: 설정 › 애플리케이션 › 내비 점 › 오른쪽 위 ⋮ › '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
                        color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }

            Card {
                SwitchRow("점 보이기", if (enabled) "켜짐" else "꺼짐 · 점이 사라져요", enabled) {
                    enabled = it; Prefs.putBool(c, "enabled", it)
                }
            }

            Card {
                Text("동작", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("점을 꾹 누른 채 끌면 자리를 옮길 수 있어요.", color = Gray, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gest.entries.forEach { g -> ActionRow(c, g, tick) }
                }
                Spacer(Modifier.height(8.dp))
                Text("'두 번 누르기'를 쓰면 한 번 누르기가 아주 살짝 늦어져요(두 번째 탭을 기다려서).",
                    color = Gray, fontSize = 12.sp, lineHeight = 17.sp)
            }

            Card {
                Text("모양", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("크기 · ${size.toInt()}dp", color = Ink, fontSize = 14.sp)
                Slider(
                    value = size, valueRange = 28f..80f,
                    onValueChange = { size = it; Prefs.putInt(c, "size", it.toInt()) },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                Text("진하기 · ${alpha.toInt()}%", color = Ink, fontSize = 14.sp)
                Slider(
                    value = alpha, valueRange = 15f..100f,
                    onValueChange = { alpha = it; Prefs.putInt(c, "alpha", it.toInt()) },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                Spacer(Modifier.height(4.dp))
                Step("점 위치 처음으로", done = false) { Prefs.putSpot(c, Prefs.DEF_X, Prefs.DEF_Y) }
            }

            Card {
                SwitchRow("키보드 피하기", "키보드가 올라오면 점이 키보드 위로 올라가요", avoidIme) {
                    avoidIme = it; Prefs.putBool(c, "avoidIme", it)
                }
                Spacer(Modifier.height(12.dp))
                SwitchRow("진동", "누를 때 짧게 울려요", haptic) {
                    haptic = it; Prefs.putBool(c, "haptic", it)
                }
            }

            Card {
                Text("원래 내비게이션 바 숨기기", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("설정 › 디스플레이 › 내비게이션 바에서 '스와이프 제스처'를 고르고 '제스처 힌트'를 끄면 아래 막대가 사라져요. 화면 끝에서 밀기 제스처는 그대로 쓸 수 있어요.",
                    color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Step("디스플레이 설정 열기", done = false) {
                    c.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            }

            Text("내비 점 0.1.0" + (if (NavDotService.running) " · 서비스 실행 중" else ""),
                color = Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ActionRow(c: Context, g: Gest, tick: Int) {
    val sp = remember { Prefs.sp(c) }
    var act by remember(tick) { mutableStateOf(Prefs.act(sp, g)) }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Bg)
                .clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(g.label, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(act.label + " ▾", color = if (act == Act.NONE) Gray else Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Act.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a == act) Accent else Ink, fontWeight = if (a == act) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { act = a; Prefs.putAct(c, g, a); open = false },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Gray, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
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
