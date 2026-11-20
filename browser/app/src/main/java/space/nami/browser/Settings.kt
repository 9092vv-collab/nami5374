@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package space.nami.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
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
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
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
                listOf("툴바", "동작", "번역", "확장", "네트워크", "디스플레이", "일반", "부스트").forEachIndexed { i, t ->
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
                        3 -> ExtensionsSection(b, prefs)
                        4 -> NetworkSection(b, prefs)
                        5 -> DisplaySection(prefs)
                        7 -> BoostSection(b, prefs)
                        else -> {
                            GeneralSection(prefs)
                            Text("Nami 0.7.8", color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
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
        DotPosition(prefs)
    }
}

// Move the dot from its corner: X (left/right) and Y (up) with a small screen preview
@Composable
private fun DotPosition(prefs: Prefs) {
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val w = conf.screenWidthDp; val h = conf.screenHeightDp
    val maxX = (w - 58).coerceAtLeast(1); val maxY = (h - 160).coerceAtLeast(1)
    val (dx, dy) = dotOffset(prefs, conf)
    Label("점 위치")
    Text("지금 자리에서 옮겨요. X는 가로(+ 오른쪽), Y는 세로(+ 위)예요.", fontSize = 12.sp, color = C.Sub)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Preview of the screen
        val pw = 72f; val ph = pw * h / w
        Box(Modifier.size(pw.dp, ph.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(C.Ink.copy(alpha = 0.06f))) {
            val s = pw / w
            val left = if (prefs.dotOnLeft) (14 + dx) * s else (w - 44 + dx) * s
            val top = (h - 44 - dy) * s
            Box(Modifier.offset(left.dp, top.dp).size((30 * s).coerceAtLeast(6f).dp)
                .clip(androidx.compose.foundation.shape.CircleShape).background(C.Accent))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("X  ${dx}dp", fontSize = 13.sp, color = C.Ink)
            androidx.compose.material3.Slider(
                value = dx.toFloat(),
                onValueChange = { prefs.putDotX(it.toInt()) },
                valueRange = if (prefs.dotOnLeft) 0f..maxX.toFloat() else -maxX.toFloat()..0f)
            Text("Y  ${dy}dp", fontSize = 13.sp, color = C.Ink)
            androidx.compose.material3.Slider(
                value = dy.toFloat(),
                onValueChange = { prefs.putDotY(it.toInt()) },
                valueRange = 0f..maxY.toFloat())
            Row {
                listOf(-4 to "X −", 4 to "X +").forEach { (d, t) -> Nudge(t) { prefs.putDotX(if (prefs.dotOnLeft) (dx + d).coerceIn(0, maxX) else (dx + d).coerceIn(-maxX, 0)) } }
                listOf(-4 to "Y −", 4 to "Y +").forEach { (d, t) -> Nudge(t) { prefs.putDotY((dy + d).coerceIn(0, maxY)) } }
                Nudge("원래대로") { prefs.putDotX(0); prefs.putDotY(0) }
            }
        }
    }
}

@Composable
private fun Nudge(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = C.Accent, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).background(C.Accent.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 5.dp))
}

@Composable
private fun GestureSection(prefs: Prefs) {
    Card("동작", "동작마다 할 일을 골라요. 스크롤로는 툴바가 나오지 않아요.") {
        SelectRow("맨 위에서 끌어내리기", Action.gestureChoices, prefs.pullAction, { it.label }) { prefs.putPullAction(it) }
        SelectRow("끌어내리는 세기", PullStrength.entries, prefs.pullStrength, { it.label }) { prefs.putPullStrength(it) }
        SelectRow("두 번 탭 (글자·빈 곳)", Action.gestureChoices, prefs.doubleTapAction, { it.label }) { prefs.putDoubleTapAction(it) }
        Text("링크·그림 위를 두 번 탭하면 첫 탭에 이미 넘어가서 동작하지 않아요.", fontSize = 12.sp, color = C.Sub)
        Label("툴바 부르는 점")
        Text("점을 누르면 툴바가 나와요. 점을 밀면 아래 동작을 해요.", fontSize = 12.sp, color = C.Sub)
        SelectRow("왼쪽으로 밀기", Action.gestureChoices, prefs.dotLeftAction, { it.label }) { prefs.putDotLeftAction(it) }
        SelectRow("오른쪽으로 밀기", Action.gestureChoices, prefs.dotRightAction, { it.label }) { prefs.putDotRightAction(it) }
        SelectRow("위로 밀기", Action.gestureChoices, prefs.dotUpAction, { it.label }) { prefs.putDotUpAction(it) }
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
        SelectRow("글자 크기", listOf(80, 90, 100, 110, 125, 150, 175, 200), prefs.textZoom, { "$it%" }) { prefs.putTextZoom(it) }
        SwitchRow("확대/축소 항상 허용", prefs.forceZoom) { prefs.putForceZoom(it) }
        Text("사이트가 막아 둔 두 손가락 확대·축소도 되게 해요. 새로고침하면 적용돼요.", fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
    }
}

/** "기본 브라우저로 설정": asks Android for the browser role (system dialog); falls back to the default-apps screen. */
@Composable
private fun DefaultBrowserRow() {
    val ctx = LocalContext.current
    val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
    fun held() = runCatching { rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) }.getOrDefault(false)
    var isDefault by remember { mutableStateOf(held()) }
    val owner = ctx as? ComponentActivity
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) isDefault = held() }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = held() }
    fun openDefaultApps() = runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text("기본 브라우저", fontSize = 15.sp, color = C.Ink)
            Text(if (isDefault) "Nami가 기본 브라우저예요. 다른 앱의 링크가 Nami 새 탭에서 열려요." else "다른 앱에서 링크를 누르면 지금은 다른 브라우저가 열려요.",
                fontSize = 12.sp, color = if (isDefault) C.Accent else C.Sub)
        }
        if (!isDefault) TextButton({
            val ok = runCatching {
                if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    ask.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)); true
                } else false
            }.getOrDefault(false)
            if (!ok) openDefaultApps()
        }) { Text("설정", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        else TextButton({ openDefaultApps() }) { Text("바꾸기", color = C.Sub) }
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

/** The WebView app (the page engine) has its own battery setting; Nami can't change it, only show it and open it. */
@Composable
private fun WebViewBatteryRow() {
    val ctx = LocalContext.current
    var free by remember { mutableStateOf(WebViewPower.free(ctx)) }
    val owner = ctx as? ComponentActivity
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) free = WebViewPower.free(ctx) }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(WebViewPower.label(ctx), fontSize = 15.sp, color = C.Ink)
            Text(when (free) { true -> "제한 없음이에요. 앱을 내려도 페이지가 계속 돌아요."; false -> "최적화예요. 앱 정보 → 배터리 → 제한 없음으로 바꿔 주세요."; null -> "확인할 수 없어요." },
                fontSize = 12.sp, color = if (free == true) C.Accent else C.Sub)
        }
        TextButton({ WebViewPower.open(ctx) }) { Text(if (free == true) "앱 정보" else "설정 열기", color = if (free == true) C.Sub else C.Accent, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun GeneralSection(prefs: Prefs) {
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent)
    Card("일반") {
        DefaultBrowserRow()
        SwitchRow("링크를 앱으로 열기", prefs.openApps) { prefs.putOpenApps(it) }
        Text("X, 유튜브처럼 앱이 깔린 사이트로 가는 링크를 누르면 그 앱으로 열어요. 주소창에 직접 쓴 주소나 같은 사이트 안에서 이동할 땐 Nami에서 열어요.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        BatteryRow()
        SelectRow("분할 다운로드", listOf(0, 2, 4, 8), prefs.splitParts, { if (it == 0) "끔 (폰 기본)" else "${it}조각" }) { prefs.putSplitParts(it) }
        Text("파일 하나를 여러 조각으로 나눠 동시에 받아요. 큰 파일일수록 빨라지고, 서버가 지원하지 않으면 자동으로 한 줄로 받아요.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        if (prefs.splitParts > 0) {
            SwitchRow("와이파이 + 데이터 같이 쓰기", prefs.dualNetwork) { prefs.putDualNetwork(it) }
            SwitchRow("받는 중 나가면 작은 창으로", prefs.pipWhileRelay) { prefs.putPipWhileRelay(it) }
            Text("웹뷰 배터리를 제한 없음으로 해도 멈출 때만 켜요. 페이지가 받아 주는 다운로드 중에 홈으로 나가면 Nami가 구석의 작은 창이 돼서 계속 받아요.",
                fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
            Text("와이파이에 연결돼 있고 20MB 이상인 파일만, 조각 절반을 모바일 데이터로 받아요. 데이터 요금이 나가요. 데이터가 끊기면 그 조각은 와이파이로 마저 받아요.",
                fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        }
        Label("홈페이지 · 새 탭")
        OutlinedTextField(prefs.homeUrl, { prefs.putHomeUrl(it.trim()) }, Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(16.dp), colors = colors)
    }
}

@Composable
private fun ExtensionsSection(b: Browser, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { b.blocker.findApps() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent)
    fun refresh() { busy = true; scope.launch { b.blocker.refresh(true); busy = false } }

    Card("광고 차단", "삼성 인터넷용 광고 차단 앱의 필터를 그대로 받아 써요. 바꾸면 새로고침했을 때 적용돼요.") {
        SwitchRow("광고 차단", prefs.adblock) { prefs.putAdblock(it) }
        Text(b.blocker.status, fontSize = 13.sp, color = if (b.blocker.ruleCount > 0) C.Accent else C.Sub)
        Label("차단 앱")
        if (apps.isEmpty()) {
            Text("설치된 차단 앱이 없어요. 삼성 인터넷용 광고 차단 앱(Adblock Plus for Samsung Internet, AdGuard Content Blocker 등)을 설치하면 여기 나와요.",
                fontSize = 13.sp, color = C.Sub, lineHeight = 18.sp)
            TextButton({
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=samsung%20internet%20adblock"))) }
            }) { Text("스토어에서 찾기", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        }
        apps.forEach { a ->
            SwitchRow(a.label, a.pkg in prefs.blockerApps) { on ->
                prefs.putBlockerApps(if (on) prefs.blockerApps + a.pkg else prefs.blockerApps - a.pkg); refresh()
            }
        }
        Label("필터 목록 주소")
        prefs.filterUrls.forEach { u ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(u, fontSize = 13.sp, color = C.Ink, modifier = Modifier.weight(1f))
                IconButton({ prefs.putFilterUrls(prefs.filterUrls - u); refresh() }) { Icon(Icons.Filled.Close, "빼기", tint = C.Sub) }
            }
        }
        var url by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("https://…/filter.txt", color = C.Sub) },
                shape = RoundedCornerShape(16.dp), colors = colors)
            TextButton({ if (url.startsWith("http")) { prefs.putFilterUrls(prefs.filterUrls + url.trim()); url = ""; refresh() } }) { Text("추가", color = C.Accent) }
        }
        if ("https://easylist.to/easylist/easylist.txt" !in prefs.filterUrls)
            TextButton({ prefs.putFilterUrls(prefs.filterUrls + "https://easylist.to/easylist/easylist.txt"); refresh() }) { Text("+ EasyList 추가", color = C.Accent) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ refresh() }, enabled = !busy) { Text(if (busy) "받는 중…" else "필터 새로 받기", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        }
        Label("광고 차단을 끈 사이트")
        Text("여기 있는 사이트(와 그 하위 주소)에서는 광고 차단을 완전히 꺼요. 요청도 막지 않고 숨기지도 않아요.",
            fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
        var site by remember { mutableStateOf("") }
        fun addSite() {
            val h = site.trim().let { if (it.contains("://")) Uri.parse(it).host ?: "" else it.substringBefore('/') }.lowercase()
            if (h.contains('.')) { prefs.putAdblockOff(h, true); site = "" }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(site, { site = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("예: kio.ac", color = C.Sub) },
                shape = RoundedCornerShape(16.dp), colors = colors,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addSite() }))
            TextButton({ addSite() }) { Text("등록", color = C.Accent) }
        }
        run {
            prefs.adblockOff.sorted().forEach { h ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(h, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
                    IconButton({ prefs.putAdblockOff(h, false) }) { Icon(Icons.Filled.Close, "빼기", tint = C.Sub) }
                }
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching { "${b.scripts.importFile(uri)}개 가져왔어요" }.getOrElse { "가져오기 실패: ${it.message}" }
        }
    }
    Card("유저스크립트", "템퍼몽키용 스크립트를 그대로 돌려요. 그리스포크 같은 곳에서 설치 버튼을 누르면 바로 추가돼요. 바꾸면 새로고침했을 때 적용돼요.") {
        var url by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("https://…/script.user.js", color = C.Sub) },
                shape = RoundedCornerShape(16.dp), colors = colors)
            TextButton({
                val u = url.trim()
                if (u.startsWith("http")) scope.launch {
                    msg = runCatching { "'${b.scripts.installFromUrl(u).name}' 추가했어요" }.getOrElse { "추가 실패: ${it.message}" }
                    url = ""
                }
            }) { Text("추가", color = C.Accent) }
        }
        TextButton({ pick.launch("*/*") }) { Text("파일에서 가져오기 (.user.js · 템퍼몽키 백업)", color = C.Accent, fontWeight = FontWeight.SemiBold) }
        msg?.let { Text(it, fontSize = 13.sp, color = C.Ink) }
        if (b.scripts.list.isEmpty()) Text("아직 설치한 스크립트가 없어요.", fontSize = 13.sp, color = C.Sub)
        b.scripts.list.forEach { sc ->
            var confirm by remember(sc.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Bg).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sc.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                        Text(listOf(sc.version, sc.matches.firstOrNull() ?: "모든 사이트").filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp, color = C.Sub, maxLines = 1)
                    }
                    Switch(sc.enabled, { b.scripts.setEnabled(sc, it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent,
                        checkedThumbColor = Color.White, uncheckedTrackColor = C.Drawer, uncheckedBorderColor = C.Line))
                }
                Row {
                    if (sc.updateUrl.isNotEmpty()) TextButton({ scope.launch { msg = runCatching { b.scripts.update(sc) }.getOrElse { "업데이트 실패: ${it.message}" } } }) {
                        Text("업데이트 확인", color = C.Ink, fontSize = 13.sp)
                    }
                    if (!confirm) TextButton({ confirm = true }) { Text("삭제", color = C.Sub, fontSize = 13.sp) }
                    else TextButton({ b.scripts.remove(sc) }) { Text("정말 삭제", color = Color(0xFFC0392B), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun HostList(title: String, hint: String, hosts: Set<String>, placeholder: String, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent)
    Label(title)
    if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = C.Sub, lineHeight = 17.sp)
    var site by remember { mutableStateOf("") }
    fun add() {
        val h = site.trim().let { if (it.contains("://")) Uri.parse(it).host ?: "" else it.substringBefore('/') }.lowercase()
        if (h.contains('.')) { onAdd(h); site = "" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(site, { site = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(placeholder, color = C.Sub) },
            shape = RoundedCornerShape(16.dp), colors = colors,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
        TextButton({ add() }) { Text("등록", color = C.Accent) }
    }
    hosts.sorted().forEach { h ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(h, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
            IconButton({ onRemove(h) }) { Icon(Icons.Filled.Close, "빼기", tint = C.Sub) }
        }
    }
}

@Composable
private fun NetworkSection(b: Browser, prefs: Prefs) {
    Text("상태: ${b.netStatus}", fontSize = 13.sp, color = C.Sub, modifier = Modifier.padding(start = 6.dp))

    Card("웹뷰 배터리", "페이지는 Nami가 아니라 Android System WebView 앱이 돌려요. 그 앱이 '최적화'면 앱을 내렸을 때 kio.ac 같은 다운로드가 멈출 수 있어요.") {
        WebViewBatteryRow()
    }

    Card("보안 DNS", "사이트 주소를 통신사 대신 아래 곳에 암호화해서 물어봐요. Nami 안에서만 적용돼요.") {
        SelectRow("DNS", DnsMode.entries, prefs.dnsMode, { it.label }) { prefs.putDnsMode(it); LocalProxy.clearDns() }
    }

    Card("차단 우회", "유니콘 HTTPS처럼, 사이트에 처음 보내는 인사 패킷을 둘로 쪼개서 통신사가 사이트 이름을 읽고 막는 걸 피해요. 새로고침하면 적용돼요.") {
        SwitchRow("차단 우회", prefs.dpiBypass) { prefs.putDpiBypass(it) }
        HostList("우회에서 뺄 사이트", "쪼갠 패킷 때문에 안 열리는 사이트가 있으면 여기 등록해요.", prefs.bypassOff, "예: example.com",
            { prefs.putBypassOff(it, true) }, { prefs.putBypassOff(it, false) })
    }
}

@Composable
private fun BoostSection(b: Browser, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun checkShizukuStatus(): String = when {
        !Booster.isShizukuRunning() -> "미실행"
        !Booster.hasShizukuPermission() -> "권한 없음"
        Booster.shizukuUid() == 0 -> "연결됨 (루트)"
        else -> "연결됨 (ADB)"
    }
    var shizukuStatus by remember { mutableStateOf(checkShizukuStatus()) }
    var rootStatus by remember { mutableStateOf("미확인") }
    var running by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuStatus = checkShizukuStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                statusMsg = "Shizuku 권한이 허용되었습니다."
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    Card("권한 모드", "Shizuku 또는 루트(su) 권한을 사용하면 시스템 레벨 최적화를 실행할 수 있어요.") {
        SwitchRow("Shizuku 사용", prefs.boostShizuku) { prefs.putBoostShizuku(it) }
        Text("Shizuku 상태: $shizukuStatus", fontSize = 13.sp, color = C.Sub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (Booster.isShizukuRunning() && !Booster.hasShizukuPermission()) {
                    Booster.requestShizukuPermission()
                }
                shizukuStatus = checkShizukuStatus()
            }) { Text("권한 요청", color = C.Accent) }
            TextButton(onClick = {
                shizukuStatus = checkShizukuStatus()
            }) { Text("상태 새로고침", color = C.Accent) }
        }
        SwitchRow("루트(su) 사용", prefs.boostRoot) { prefs.putBoostRoot(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("루트 상태: $rootStatus", fontSize = 13.sp, color = C.Sub)
            TextButton(enabled = !running, onClick = {
                scope.launch {
                    running = true
                    val ok = withContext(Dispatchers.IO) { Booster.hasRoot() }
                    rootStatus = if (ok) "루트 허용됨" else "루트 불가"
                    running = false
                }
            }) { Text("루트 확인", color = C.Accent) }
        }
    }

    Card("즉시 실행 최적화 (Shizuku / 루트)", "앱과 WebView를 AOT 컴파일하거나 절전 제한을 해제해 반응 속도를 높여요.") {
        if (running) {
            Text("실행 중...", fontSize = 13.sp, color = C.Accent)
        }
        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, fontSize = 13.sp, color = C.Sub)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !running, onClick = {
                scope.launch {
                    running = true
                    statusMsg = withContext(Dispatchers.IO) {
                        Booster.compileAot(ctx, prefs.boostRoot, prefs.boostShizuku)
                    }
                    running = false
                }
            }) { Text("앱 + WebView AOT 컴파일", color = C.Accent) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !running, onClick = {
                scope.launch {
                    running = true
                    statusMsg = withContext(Dispatchers.IO) {
                        Booster.disableBatteryLimits(ctx, prefs.boostRoot, prefs.boostShizuku)
                    }
                    running = false
                }
            }) { Text("절전·백그라운드 제한 완전 해제", color = C.Accent) }
        }
    }

    Card("루트 전용 상시 가속", "루트 권한으로 프로세스 우선순위와 RAM 캐시 마운트를 적용해요.") {
        SwitchRow("OOM 방어 & 우선순위 상향 (시작 시 자동)", prefs.boostOom) { prefs.putBoostOom(it) }
        TextButton(enabled = !running, onClick = {
            scope.launch {
                running = true
                statusMsg = withContext(Dispatchers.IO) {
                    Booster.boostOomAndPriority(ctx, prefs.boostRoot, prefs.boostShizuku)
                }
                running = false
            }
        }) { Text("지금 적용", color = C.Accent) }
        SwitchRow("WebView 캐시 RAM 디스크(tmpfs)", prefs.boostRamCache) { prefs.putBoostRamCache(it) }
        TextButton(enabled = !running, onClick = {
            scope.launch {
                running = true
                statusMsg = withContext(Dispatchers.IO) {
                    Booster.mountRamCache(ctx, prefs.boostRoot, prefs.boostShizuku)
                }
                running = false
            }
        }) { Text("지금 마운트", color = C.Accent) }
    }

    Card("앱 내부 가속 (권한 불필요)", "브라우저 내부 WebView 풀링과 렌더러 유지 옵션을 설정해요.") {
        SwitchRow("새 탭 WebView 미리 준비 (풀링)", prefs.boostWebViewPool) { prefs.putBoostWebViewPool(it) }
        SwitchRow("렌더러 우선순위 항상 최상 유지", prefs.boostKeepAlive) { prefs.putBoostKeepAlive(it) }
    }
}
