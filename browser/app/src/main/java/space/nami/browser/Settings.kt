@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package space.nami.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen(b: Browser, prefs: Prefs) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = C.Bg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ b.showSettings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = C.Ink) }
                Text("설정", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("툴바", "동작", "번역", "디스플레이", "일반").forEachIndexed { i, t ->
                    val sel = i == page
                    Text(t, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) Color.White else C.Ink, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(CircleShape).background(if (sel) C.Ink else Color.White)
                            .clickable { page = i }.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.widthIn(max = 760.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        0 -> ToolbarSection(prefs)
                        1 -> GestureSection(prefs)
                        2 -> TranslateSection(b, prefs)
                        3 -> DisplaySection(prefs)
                        else -> {
                            GeneralSection(prefs)
                            Text("Nami 0.1.1", color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, C.Line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            if (hint != null) Text(hint, fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
            content()
        }
    }
}

@Composable
private fun Label(s: String) = Text(s, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else C.Ink,
        modifier = Modifier.clip(CircleShape).background(if (selected) C.Accent else C.Drawer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** One line: label on the left, the current choice in a box on the right; tap opens the list. */
@Composable
private fun <T> SelectRow(label: String, items: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f).padding(end = 10.dp))
        Box {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(C.Drawer).clickable { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name(selected), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.Ink)
                Icon(Icons.Filled.ArrowDropDown, null, tint = C.Sub)
            }
            DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Color.White) {
                items.forEach { item ->
                    val sel = item == selected
                    val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, null, tint = C.Accent) }
                    DropdownMenuItem(
                        text = { Text(name(item), fontSize = 15.sp, color = if (sel) C.Accent else C.Ink,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { open = false; onPick(item) },
                        trailingIcon = if (sel) check else null)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White,
            uncheckedTrackColor = C.Drawer, uncheckedBorderColor = C.Line))
    }
}

@Composable
private fun ToolbarSection(prefs: Prefs) {
    Card("툴바 버튼", "툴바에 들어갈 버튼과 순서예요. 메뉴(⋮)는 설정으로 오는 길이라 뺄 수 없어요.") {
        val list = prefs.toolbar
        list.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Bg).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                a.icon?.let { Icon(it, null, tint = C.Ink, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(a.label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
                IconButton({ prefs.putToolbar(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "위로", tint = if (i > 0) C.Ink else C.Line)
                }
                IconButton({ prefs.putToolbar(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, "아래로", tint = if (i < list.size - 1) C.Ink else C.Line)
                }
                IconButton({ prefs.putToolbar(list - a) }, enabled = a != Action.MENU) {
                    Icon(Icons.Filled.Close, "빼기", tint = if (a != Action.MENU) C.Sub else Color.Transparent)
                }
            }
        }
        val rest = Action.toolbarChoices.filter { it !in list }
        if (rest.isNotEmpty()) {
            Label("눌러서 추가")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rest.forEach { a ->
                    Row(Modifier.clip(CircleShape).background(C.Drawer).clickable { prefs.putToolbar(list + a) }
                        .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = C.Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(a.label, fontSize = 14.sp, color = C.Ink)
                    }
                }
            }
        }
        SelectRow("툴바 부르는 점", listOf(false, true), prefs.dotOnLeft, { if (it) "왼쪽 아래" else "오른쪽 아래" }) { prefs.putDotOnLeft(it) }
    }
}

@Composable
private fun GestureSection(prefs: Prefs) {
    Card("동작", "동작마다 할 일을 골라요. 스크롤로는 툴바가 나오지 않아요.") {
        SelectRow("맨 위에서 끌어내리기", Action.gestureChoices, prefs.pullAction, { it.label }) { prefs.putPullAction(it) }
        SelectRow("끌어내리는 세기", PullStrength.entries, prefs.pullStrength, { it.label }) { prefs.putPullStrength(it) }
        SelectRow("두 번 탭 (글자·빈 곳)", Action.gestureChoices, prefs.doubleTapAction, { it.label }) { prefs.putDoubleTapAction(it) }
        Text("링크·그림 위를 두 번 탭하면 첫 탭에 이미 넘어가서 동작하지 않아요.", fontSize = 12.sp, color = C.Sub)
    }
}

@Composable
private fun TranslateSection(b: Browser, prefs: Prefs) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Card("번역", "크롬처럼 페이지 모양은 그대로 두고 글자만 한국어로 바꿔요. 구글이 안 되면 폰 안 번역으로 자동으로 넘어가요.") {
        SelectRow("번역 엔진", Engine.entries, prefs.engine, { it.label }) { prefs.putEngine(it); result = null }
        if (prefs.engine == Engine.VERTEX) VertexFields(prefs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({
                testing = true; result = null
                scope.launch { result = b.translator.test(); testing = false }
            }, enabled = !testing) { Text(if (testing) "확인 중…" else "번역 테스트", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        }
        result?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("실패")) Color(0xFFC0392B) else C.Ink) }
        if (prefs.alwaysHosts.isNotEmpty()) {
            Label("항상 번역하는 사이트")
            prefs.alwaysHosts.sorted().forEach { h ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(h, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
                    IconButton({ prefs.setAlways(h, false) }) { Icon(Icons.Filled.Close, "빼기", tint = C.Sub) }
                }
            }
        }
    }
}

@Composable
private fun VertexFields(prefs: Prefs) {
    val saved = remember(prefs.vertexJson) { runCatching { JSONObject(prefs.vertexJson).optString("client_email") }.getOrNull().orEmpty() }
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var draft by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent)
    Label("서비스 계정")
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("저장됨 · $saved", fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
            TextButton({ editing = true; draft = "" }) { Text("바꾸기", color = C.Accent) }
        }
    } else {
        Text("SGU에 넣은 것과 같은 서비스 계정 JSON 파일 내용을 통째로 붙여 넣어 주세요.", fontSize = 12.sp, color = C.Sub)
        OutlinedTextField(draft, { draft = it; err = null }, Modifier.fillMaxWidth().height(140.dp),
            placeholder = { Text("{ \"type\": \"service_account\", … }", color = C.Sub) }, shape = RoundedCornerShape(16.dp), colors = colors)
        err?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC0392B)) }
        Row {
            TextButton({
                val o = runCatching { JSONObject(draft.trim()) }.getOrNull()
                if (o == null || o.optString("project_id").isBlank() || o.optString("client_email").isBlank() || o.optString("private_key").isBlank())
                    err = "project_id · client_email · private_key가 들어 있는 JSON이어야 해요."
                else { prefs.putVertexJson(draft.trim()); editing = false }
            }) { Text("저장", color = C.Accent, fontWeight = FontWeight.SemiBold) }
            if (saved.isNotEmpty()) TextButton({ editing = false }) { Text("취소", color = C.Ink) }
        }
    }
    Label("모델")
    OutlinedTextField(prefs.vertexModel, { prefs.putVertexModel(it.trim()) }, Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp), colors = colors)
    Label("위치")
    OutlinedTextField(prefs.vertexLocation, { prefs.putVertexLocation(it.trim()) }, Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp), colors = colors)
    Text("Vertex가 실패하면 구글 → 폰 번역 순서로 넘어가요. 쓸 때마다 요금이 들어요.", fontSize = 12.sp, color = C.Sub)
}

@Composable
private fun DisplaySection(prefs: Prefs) {
    Card("디스플레이") {
        SwitchRow("120Hz 고정", prefs.highRefresh) { prefs.putHighRefresh(it) }
        Text("켜면 Nami가 떠 있는 동안 폰에 가장 높은 주사율을 요청해서 스크롤이 항상 부드러워요. 배터리를 조금 더 써요. " +
            "끄면 폰의 '부드러운 화면 움직임' 설정대로 움직여요.", fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        SwitchRow("상태 표시줄 숨기기", prefs.hideStatusBar) { prefs.putHideStatusBar(it) }
    }
}

/** Samsung "배터리 사용량 제한 없음": asks Android to stop optimizing (pausing) Nami in the background. */
@Composable
private fun BatteryRow() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var free by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val owner = ctx as? ComponentActivity
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) free = pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text("배터리 사용량 제한 없음", fontSize = 15.sp, color = C.Ink)
            Text(if (free) "켜져 있어요. 뒤에 둔 탭도 멈추지 않아요." else "폰이 Nami를 뒤에서 멈출 수 있어요.",
                fontSize = 12.sp, color = if (free) C.Accent else C.Sub)
        }
        if (!free) TextButton({
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }.onFailure {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }) { Text("풀기", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        else TextButton({
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
        }) { Text("앱 정보", color = C.Sub) }
    }
}

@Composable
private fun GeneralSection(prefs: Prefs) {
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent)
    Card("일반") {
        BatteryRow()
        SelectRow("분할 다운로드", listOf(0, 2, 4, 8), prefs.splitParts, { if (it == 0) "끔 (폰 기본)" else "${it}조각" }) { prefs.putSplitParts(it) }
        Text("파일 하나를 여러 조각으로 나눠 동시에 받아요. 큰 파일일수록 빨라지고, 서버가 지원하지 않으면 자동으로 한 줄로 받아요.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        Label("홈페이지 · 새 탭")
        OutlinedTextField(prefs.homeUrl, { prefs.putHomeUrl(it.trim()) }, Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(16.dp), colors = colors)
    }
}
