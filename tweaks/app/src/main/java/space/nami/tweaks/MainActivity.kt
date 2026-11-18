package space.nami.tweaks

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val Bg = Color(0xFFFAF9F7)
private val Ink = Color(0xFF141413)
private val Gray = Color(0xFF6B6A66)
private val Line = Color(0xFFE6E5E0)
private val Accent = Color(0xFFD97757)
private val AccentSoft = Color(0xFFF7E3DA)

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val permResult = Shizuku.OnRequestPermissionResultListener { _, _ -> tick++ }
    private val binderUp = Shizuku.OnBinderReceivedListener { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Bg)) {
                Screen(tick)
            }
        }
    }

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
        tick++
    }
}

private fun connectShizuku(c: Context) {
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    if (running) runCatching { Shizuku.requestPermission(1) }
    else {
        val open = c.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (open != null) c.startActivity(open)
        else Toast.makeText(c, "Shizuku 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}

private fun toast(c: Context, err: String?, ok: String) =
    Toast.makeText(c, err?.let { "안 됐어요: $it" } ?: ok, Toast.LENGTH_SHORT).show()

private fun scaleText(v: Float) = if (v == 0f) "끔" else (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "배"

@Composable
private fun Screen(tick: Int) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = remember(tick) { Sh.ready() }
    var anim by remember { mutableStateOf(Anim.read(c)) }
    var original by remember { mutableStateOf(Anim.original(c)) }
    var screenMode by remember { mutableStateOf<Int?>(null) }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 추천, 1 삼성, 2 전체, 3 꺼 둔 앱
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AppRow?>(null) }
    var info by remember { mutableStateOf<AppRow?>(null) }

    LaunchedEffect(tick) {
        anim = Anim.read(c)
        original = Anim.original(c)
        screenMode = withContext(Dispatchers.IO) { ScreenMode.read() }
        apps = withContext(Dispatchers.IO) { Apps.load(c) }
    }

    fun setApp(row: AppRow, on: Boolean) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { Apps.setOn(row.pkg, on) }
            val now = Apps.isOn(c.packageManager, row.pkg)
            apps = apps?.map { if (it.pkg == row.pkg) it.copy(enabled = now) else it }
            toast(c, err, if (on) "${row.label} 켰어요" else "${row.label} 껐어요")
        }
    }

    fun setAnim(v: Float?) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { if (v == null) Anim.restore(c) else Anim.set(c, v) }
            anim = Anim.read(c)
            original = Anim.original(c)
            if (err != null) toast(c, err, "")
        }
    }

    fun setScreen(v: Int) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { ScreenMode.set(v) }
            screenMode = withContext(Dispatchers.IO) { ScreenMode.read() }
            if (err != null) toast(c, err, "")
        }
    }

    val shown = remember(apps, filter, query) {
        apps.orEmpty().filter {
            when (filter) {
                0 -> it.level == 0 && !it.essential
                1 -> it.samsung
                3 -> !it.enabled
                else -> true
            } && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
        }
    }

    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 640.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            item {
                Text("숨은 설정", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
            }

            if (!ready) item {
                Card {
                    Text("Shizuku 연결이 필요해요", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    PillButton("연결하기", Modifier.fillMaxWidth()) { connectShizuku(c) }
                    Spacer(Modifier.height(8.dp))
                    Text("Shizuku가 꺼져 있으면 Shizuku 앱이 열려요.", color = Gray, fontSize = 13.sp)
                }
            }

            item {
                Card {
                    Text("애니메이션 속도", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val same = anim.distinct().size == 1
                    Text("지금 " + if (same) scaleText(anim[0]) else anim.joinToString(" · ") { scaleText(it) },
                        color = Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { v ->
                            Choice(scaleText(v), same && anim[0] == v,
                                Modifier.weight(1f), enabled = ready) { setAnim(v) }
                        }
                    }
                    if (original != null && original != anim) {
                        Spacer(Modifier.height(10.dp))
                        Text("원래대로 (" + original!!.joinToString(" · ") { scaleText(it) } + ")",
                            color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = ready) { setAnim(null) }
                                .padding(horizontal = 4.dp, vertical = 6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("작을수록 화면 전환이 빨라져요. '끔'은 애니메이션이 아예 없어져요.", color = Gray, fontSize = 13.sp)
                }
            }

            item {
                Card {
                    Text("화면 모드", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("지금 " + (ScreenMode.MODES.firstOrNull { it.first == screenMode }?.second ?: "알 수 없음"),
                        color = Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    ScreenMode.MODES.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (v, name) ->
                                Choice(name, screenMode == v, Modifier.weight(1f), enabled = ready) { setScreen(v) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("바로 안 바뀌면 설정 › 디스플레이 › 화면 모드에 들어갔다 나오면 적용돼요. 아래 버튼으로 바로 갈 수 있어요.",
                        color = Gray, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("디스플레이 설정 열기", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                            runCatching { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) }
                        }.padding(horizontal = 4.dp, vertical = 6.dp))
                }
            }

            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("앱 끄기", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp))
                    Text("끈 앱은 안 열리고 알림도 안 와요. 지우는 게 아니라서 언제든 다시 켤 수 있어요. 등급은 UAD(Universal Android Debloater) 목록 기준이고, 앱을 누르면 설명이 나와요. 꼭 필요한 앱과 '위험' 등급은 끌 수 없게 막아 뒀어요.",
                        color = Gray, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Text("설명용 목록 복사", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 0.dp, top = 6.dp).clip(RoundedCornerShape(50)).clickable {
                            // Apps with a UAD description (to translate) + system apps nobody described yet.
                            val rows = apps.orEmpty().filter { (it.desc != null && !it.byClaude) || (it.desc == null && it.system) }
                            val list = rows.joinToString("\n") {
                                it.pkg + "\t" + it.label + "\t" + (if (it.desc == null) "-" else "RAEU"[it.level ?: 3].toString())
                            }
                            val cm = c.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("apps", list))
                            Toast.makeText(c, "${rows.size}개 복사했어요", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 4.dp, vertical = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    SearchBox(query) { query = it }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val off = apps.orEmpty().count { !it.enabled }
                        listOf("추천", "삼성", "전체", "꺼 둔 앱 $off").forEachIndexed { i, t ->
                            Choice(t, filter == i, Modifier.weight(1f)) { filter = i }
                        }
                    }
                }
            }

            if (apps == null) item {
                Text("앱 목록 불러오는 중…", color = Gray, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            } else if (shown.isEmpty()) item {
                Text("해당하는 앱이 없어요", color = Gray, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }

            items(shown, key = { it.pkg }) { row ->
                AppItem(row, ready, onInfo = { info = row }) { on -> if (on) setApp(row, true) else confirm = row }
            }
        }
    }

    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${row.label} 끌까요?") },
            text = { Text(warning(row) + "이 앱이 안 열리고 알림도 안 와요. 여기서 언제든 다시 켤 수 있어요.\n\n${row.pkg}") },
            confirmButton = { TextButton(onClick = { confirm = null; setApp(row, false) }) { Text("끄기", color = Accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소", color = Gray) } },
            containerColor = Color.White,
        )
    }

    info?.let { row ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(row.label) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(row.pkg, color = Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    val lv = if (row.essential) null else row.level
                    Text(
                        when {
                            row.essential -> "필수 · 휴대폰에 꼭 필요한 앱이라 끌 수 없어요."
                            lv != null -> Uad.NAMES[lv] + " · " + Uad.MEANING[lv]
                            else -> "정보 없음 · 목록에 없는 앱이에요. 무슨 앱인지 알 때만 끄세요."
                        },
                        color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (row.byClaude) {
                        Spacer(Modifier.height(6.dp))
                        Text("UAD 목록에 없는 앱이라 등급과 설명은 Claude가 붙였어요.", color = Gray, fontSize = 12.sp)
                    }
                    if (row.payment) {
                        Spacer(Modifier.height(8.dp))
                        Text("삼성 페이·월렛·패스, 보안 폴더, 생체 인증 쪽과 관련된 앱이에요. 이런 걸 쓰고 있다면 끄지 마세요.",
                            color = Color(0xFFC0392B), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    if (row.byClaude) row.desc?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("설명", color = Gray, fontSize = 12.sp)
                        Text(it, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                    } else row.desc?.takeIf { it.isNotBlank() }?.let { en ->
                        var showEn by remember(row.pkg) { mutableStateOf(false) }
                        val ko by produceState(Ko.cached(c, row.pkg) ?: "", row.pkg) {
                            if (value.isEmpty()) value = Ko.translate(c, row.pkg, en) ?: "!"
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("설명", color = Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (ko.isNotEmpty() && ko != "!") Text(if (showEn) "한국어로 보기" else "원문 보기",
                                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showEn = !showEn }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                        Text(
                            when {
                                showEn || ko == "!" -> en
                                ko.isEmpty() -> "번역 중…"
                                else -> ko
                            },
                            color = if (ko.isEmpty() && !showEn) Gray else Ink, fontSize = 13.sp, lineHeight = 19.sp,
                        )
                        if (ko == "!") Text("번역을 못 했어요(인터넷 확인). 원문을 보여 드려요.", color = Gray, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("닫기", color = Accent) } },
            containerColor = Color.White,
        )
    }
}

private fun warning(row: AppRow): String {
    val parts = mutableListOf<String>()
    when (row.level) {
        1 -> parts += "'고급' 등급이에요. 관련된 기능이 같이 안 될 수 있어요."
        2 -> parts += "'전문가' 등급이에요. 무슨 앱인지 모르면 끄지 마세요. 다른 기능이 망가질 수 있어요."
        null -> parts += "목록에 없는 앱이라 꺼도 되는지 몰라요."
    }
    if (row.payment) parts += "삼성 페이·월렛·패스, 보안 폴더, 생체 인증을 쓰고 있다면 끄지 마세요."
    return if (parts.isEmpty()) "" else parts.joinToString("\n") + "\n\n"
}

@Composable
private fun AppItem(row: AppRow, ready: Boolean, onInfo: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, row.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { c.packageManager.getApplicationIcon(row.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable(onClick = onInfo)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) {
            icon?.let { Image(it, null, Modifier.size(40.dp), alpha = if (row.enabled) 1f else 0.35f) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, color = if (row.enabled) Ink else Gray, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (tag, color) = when {
                    row.essential -> "필수" to Gray
                    row.level != null -> Uad.NAMES[row.level] to Color(Uad.COLORS[row.level])
                    else -> null to Gray
                }
                if (tag != null) {
                    Text(tag, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (row.payment) Text(" · 결제·보안", color = Color(0xFFC0392B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(" · ", color = Gray, fontSize = 12.sp)
                } else if (row.payment) Text("결제·보안 · ", color = Color(0xFFC0392B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(row.pkg, color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(
            checked = row.enabled,
            onCheckedChange = onToggle,
            enabled = ready && !(row.locked && row.enabled),
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
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
private fun Choice(text: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else if (enabled) Ink else Gray,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Color(0xFFF3F2EE))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
private fun PillButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Accent).clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) Text("앱 이름이나 패키지 검색", color = Gray, fontSize = 15.sp)
                inner()
            }
        },
    )
}
