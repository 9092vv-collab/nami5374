package space.nami.finder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.concurrent.thread

private const val NAMI = "space.nami.browser"
private val Ink = Color(0xFF141413)
private val Gray = Color(0xFF8A8984)
private val Accent = Color(0xFFD97757)
private val AccentSoft = Color(0xFFF7E3DA)
private val Line = Color(0xFFE6E5E0)

/** Background behind the search window: transparency (0 = solid, 100 = see-through) + none / own photo / Danbooru. */
object Look {
    fun prefs(c: Context) = c.getSharedPreferences("finder", Context.MODE_PRIVATE)
    fun transparency(c: Context) = prefs(c).getInt("transparency", 65)
    fun putTransparency(c: Context, v: Int) = prefs(c).edit().putInt("transparency", v).apply()
    fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")

    /** "none" | "photo" | "booru". Older versions only had the photo. */
    fun source(c: Context): String = prefs(c).getString("source", null) ?: if (bgFile(c).exists()) "photo" else "none"
    fun putSource(c: Context, v: String) = prefs(c).edit().putString("source", v).apply()

    /** Decode scaled so the long side is at most 2560px (EXIF rotation applied), save as JPEG. */
    fun writeScaled(src: ImageDecoder.Source, dest: File): Boolean = runCatching {
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            // exact size (power-of-two sampling alone left pictures up to ~5000px → slow to open)
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, 2560f / maxOf(w, h))
            if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(dest)
    }.getOrDefault(false)

    fun saveBg(c: Context, uri: Uri): Boolean = writeScaled(ImageDecoder.createSource(c.contentResolver, uri), bgFile(c))

    private fun bgFileFor(c: Context): File? = when (source(c)) { "photo" -> bgFile(c); "booru", "pixiv" -> Booru.cur(c); else -> null }

    /** Last decoded background, kept while the app is alive so reopening the window shows it at once. */
    private var memo: Triple<String, Long, ImageBitmap>? = null

    /** The background from memory if the file hasn't changed. Cheap, main thread OK. */
    fun cachedBg(c: Context): ImageBitmap? {
        val f = bgFileFor(c) ?: return null
        val m = memo ?: return null
        return if (m.first == f.path && m.second == f.lastModified() && f.exists()) m.third else null
    }

    fun loadBg(c: Context): ImageBitmap? {
        val f = bgFileFor(c) ?: return null
        if (!f.exists()) return null
        cachedBg(c)?.let { return it }
        val stamp = f.lastModified()
        val img = runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() ?: return null
        memo = Triple(f.path, stamp, img)
        return img
    }
}

/** Search window: type, press search → opens in a new Nami tab. Tap outside or back to close. */
class SearchActivity : ComponentActivity() {
    private var settingsOpen by mutableStateOf(false)
    private var bgVersion by mutableIntStateOf(0)
    private var booruStatus by mutableStateOf("")
    private var picking = false
    private var accounts by mutableStateOf(listOf<Accounts.Acc>())
    private var activeUid by mutableStateOf<String?>(null)

    private fun refreshAccounts() {
        accounts = Accounts.list(this)
        activeUid = Accounts.activeUid(this)
        // fill in names we don't know yet
        accounts.filter { it.name.isEmpty() }.forEach { a ->
            thread { Accounts.fetchName(this, a); runOnUiThread { accounts = Accounts.list(this) } }
        }
    }

    /** Danbooru: swap if it's time, then make sure the next picture is downloading. */
    private fun booruTick(force: Boolean) {
        if (Look.source(this) !in setOf("booru", "pixiv")) return
        val cookie = Accounts.cookie(this)
        thread {
            val swapped = Booru.rotate(this, force)
            if (swapped) runOnUiThread { bgVersion++ }
            if (swapped) prefetchCurPage()
            if (Booru.queueFull(this)) { runOnUiThread { booruStatus = "다음 그림 준비됨" }; return@thread }
            runOnUiThread { booruStatus = "다음 그림 받는 중…" }
            var err = Booru.fetchNext(this, cookie)
            // first picture, or "다른 그림" with nothing ready yet: show it right away and prepare more
            val showNow = err == null && !swapped && (force || !Booru.cur(this).exists()) && Booru.rotate(this, true)
            if (showNow) runOnUiThread { bgVersion++ }
            prefetchCurPage()
            // keep two in line so several "다음 그림" in a row stay instant
            repeat(2) { if (err == null && !Booru.queueFull(this)) err = Booru.fetchNext(this, cookie) }
            runOnUiThread {
                booruStatus = err ?: if (Booru.hasNext(this)) "다음 그림 준비됨" else ""
            }
        }
    }

    /** Multi-page pixiv work: show the next/previous page in place. */
    private fun turnPage(d: Int) {
        val list = Booru.pages(this)
        if (list.size <= 1 || Look.source(this) != "pixiv") return
        val i = Booru.page(this) + d
        if (i !in list.indices) { booruStatus = if (d > 0) "마지막 장이에요" else "첫 장이에요"; return }
        booruStatus = "${i + 1} / ${list.size} 불러오는 중…"
        thread {
            val err = Booru.showPage(this, i)
            runOnUiThread { bgVersion++; booruStatus = err ?: "" }
            if (err == null) Booru.prefetchPage(this, i)
        }
    }

    /** Multi-page work on screen: get page 2 ready. Call from a background thread. */
    private fun prefetchCurPage() {
        if (Look.source(this) == "pixiv" && Booru.pages(this).size > 1) Booru.prefetchPage(this, Booru.page(this))
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false
        if (uri != null) thread {
            val ok = Look.saveBg(this, uri)
            if (ok) Look.putSource(this, "photo")
            runOnUiThread {
                if (ok) bgVersion++ else Toast.makeText(this, "사진을 못 불러왔어요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SearchScreen(
                onGo = ::go,
                onClose = ::finish,
                settingsOpen = settingsOpen,
                onSettings = { settingsOpen = it },
                bgVersion = bgVersion,
                onPick = {
                    picking = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveBg = { Look.bgFile(this).delete(); Look.putSource(this, "none"); bgVersion++ },
                onSource = { v ->
                    val wasNet = Look.source(this) in setOf("booru", "pixiv")
                    Look.putSource(this, v)
                    if (wasNet && v in setOf("booru", "pixiv")) Booru.dropAll(this)
                    booruStatus = ""
                    bgVersion++
                    booruTick(false)
                },
                accounts = accounts,
                activeUid = activeUid,
                onPixivLogin = { picking = true; startActivity(Intent(this, PixivLogin::class.java)) },
                onPixivSwitch = { uid ->
                    if (uid != activeUid) {
                        Accounts.setActive(this, uid); activeUid = uid
                        Booru.dropNext(this); booruTick(true)
                    }
                },
                onPixivLogout = {
                    activeUid?.let { Accounts.remove(this, it) }
                    refreshAccounts(); Booru.dropNext(this); booruTick(true)
                },
                booruStatus = booruStatus,
                onBooruChanged = { Booru.dropNext(this); booruTick(true) },
                onBooruNext = { booruTick(true) },
                onPage = ::turnPage,
                onOpenPost = { Booru.curUrl(this).takeIf { it.isNotEmpty() }?.let { go(it) } },
            )
        }
        // the search window opens and closes without animation
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        prewarm(true)
        Accounts.migrate(this)
        refreshAccounts()
        booruTick(false)
        // Back closes the window in one press, even while the keyboard is up.
        // PRIORITY_OVERLAY runs before the keyboard's own "hide" back handler.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY) {
                if (settingsOpen) settingsOpen = false else finish()
            }
        }
    }

    // Leaving the window (home button, another app) closes it, so the next tap starts empty.
    // Not while the photo picker is open — that's us.
    override fun onStop() { super.onStop(); if (!picking) finish() }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        picking = false   // back from the photo picker or the pixiv login page
        val before = activeUid
        refreshAccounts()
        if (activeUid != before) { Booru.dropNext(this); booruTick(true) }
    }

    /** Tell Nami to get ready (process + Chromium + one waiting tab) or to throw the waiting tab away. */
    private var searched = false
    private fun prewarm(on: Boolean) = runCatching {
        sendBroadcast(Intent(if (on) "space.nami.browser.PREWARM" else "space.nami.browser.PREWARM_DROP")
            .setClassName(NAMI, "$NAMI.PrewarmReceiver"))
    }

    override fun onDestroy() {
        if (!searched && isFinishing) prewarm(false)
        super.onDestroy()
    }

    private fun go(input: String) {
        val url = toUrl(input) ?: return
        searched = true
        val uri = Uri.parse(url)
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // no transition animation: Nami just appears
            startActivity(Intent(view).setPackage(NAMI), android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nami가 없어서 다른 브라우저로 열어요", Toast.LENGTH_SHORT).show()
            runCatching { startActivity(view) }
        }
        finish()
    }
}

/** Same rule as Nami's address bar: address → open it, anything else → Google search. */
fun toUrl(input: String): String? {
    val s = input.trim()
    if (s.isEmpty()) return null
    return when {
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) -> s
        !s.contains(' ') && Regex("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) -> "https://$s"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    onGo: (String) -> Unit,
    onClose: () -> Unit,
    settingsOpen: Boolean,
    onSettings: (Boolean) -> Unit,
    bgVersion: Int,
    onPick: () -> Unit,
    onRemoveBg: () -> Unit,
    onSource: (String) -> Unit,
    booruStatus: String,
    onBooruChanged: () -> Unit,
    onBooruNext: () -> Unit,
    onPage: (Int) -> Unit,
    onOpenPost: () -> Unit,
    accounts: List<Accounts.Acc>,
    activeUid: String?,
    onPixivLogin: () -> Unit,
    onPixivSwitch: (String) -> Unit,
    onPixivLogout: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var transparency by remember { mutableIntStateOf(Look.transparency(ctx)) }
    var source by remember { mutableStateOf(Look.source(ctx)) }
    var tags by remember { mutableStateOf(Booru.tags(ctx)) }
    var sensitive by remember { mutableStateOf(Booru.sensitive(ctx)) }
    var minutes by remember { mutableStateOf(Booru.minutes(ctx).toString()) }
    var pMode by remember { mutableStateOf(Pixiv.mode(ctx)) }
    var pPeriod by remember { mutableStateOf(Pixiv.period(ctx)) }
    var pR18 by remember { mutableStateOf(Pixiv.r18(ctx)) }
    var pLight by remember { mutableStateOf(Pixiv.light(ctx)) }
    var pTop by remember { mutableStateOf(Pixiv.top(ctx).toString()) }
    var pTags by remember { mutableStateOf(Pixiv.tags(ctx)) }
    val bg by produceState(Look.cachedBg(ctx), bgVersion) { value = withContext(Dispatchers.IO) { Look.loadBg(ctx) } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val opacity = (100 - transparency) / 100f
    var barTransparency by remember { mutableIntStateOf(Look.prefs(ctx).getInt("barTransparency", 0)) }
    val barAlpha = (100 - barTransparency) / 100f
    val swallow = remember { MutableInteractionSource() }
    val imeUp = WindowInsets.isImeVisible

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(source) {
                // swipe sideways on empty space = next / previous page of a multi-page pixiv work
                if (source != "pixiv") return@pointerInput
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val min = 80.dp.toPx()
                        if (dx < -min) onPage(1) else if (dx > min) onPage(-1)
                    },
                ) { change, d -> dx += d; change.consume() }
            }
            .pointerInput(source, settingsOpen, imeUp) {
                val net = source == "booru" || source == "pixiv"
                detectTapGestures(
                    // double tap on empty space = next picture (only for Danbooru/pixiv, so other taps aren't delayed)
                    onDoubleTap = if (net) { _ -> onBooruNext() } else null,
                ) {
                    // keyboard up → just put it away; otherwise close settings, then the window
                    when {
                        imeUp -> { keyboard?.hide(); focusManager.clearFocus() }
                        settingsOpen -> onSettings(false)
                        else -> onClose()
                    }
                }
            },
    ) {
        val img = bg
        if (img != null) {
            // whole picture, never cropped; the empty sides are the same picture, blurred and filled
            Image(img, null, Modifier.fillMaxSize().blur(36.dp), contentScale = ContentScale.Crop, alpha = opacity)
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = opacity)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = opacity)))
        }
        val pageInfo = remember(bgVersion, source) { if (source == "pixiv") Booru.pages(ctx).size to Booru.page(ctx) else 0 to 0 }
        if (pageInfo.first > 1) {
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("${pageInfo.second + 1} / ${pageInfo.first}", color = Color.White, fontSize = 13.sp) }
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(10.dp * barAlpha, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = barAlpha))
                    // swallow taps so tapping the bar itself doesn't close the window
                    .clickable(interactionSource = swallow, indication = null) {
                        onSettings(false); focus.requestFocus(); keyboard?.show()
                    }
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = Ink),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onGo(text) }, onGo = { onGo(text) }),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Text("검색어나 주소", color = Gray, fontSize = 17.sp)
                            inner()
                        }
                    },
                )
                if (text.isNotEmpty()) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { text = "" },
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(R.drawable.ic_clear), "지우기", Modifier.size(20.dp)) }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable {
                        val open = !settingsOpen
                        onSettings(open)
                        if (open) { keyboard?.hide(); focusManager.clearFocus() }
                    },
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.ic_more), "설정", Modifier.size(22.dp)) }
            }

            if (settingsOpen) {
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .weight(1f, fill = false)   // never taller than the space left; scrolls inside
                        .shadow(10.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .clickable(interactionSource = swallow, indication = null) {}
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("배경", color = Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("배경 투명도", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("$transparency%", color = Gray, fontSize = 15.sp)
                    }
                    Slider(
                        value = transparency.toFloat(),
                        onValueChange = { transparency = it.toInt() },
                        onValueChangeFinished = { Look.putTransparency(ctx, transparency) },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("검색창 투명도", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("$barTransparency%", color = Gray, fontSize = 15.sp)
                    }
                    Slider(
                        value = barTransparency.toFloat(),
                        onValueChange = { barTransparency = it.toInt() },
                        onValueChangeFinished = { Look.prefs(ctx).edit().putInt("barTransparency", barTransparency).apply() },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("배경 그림", color = Ink, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to "없음", "photo" to "내 사진", "booru" to "단부루", "pixiv" to "픽시브").forEach { (v, name) ->
                            Seg(name, source == v) { if (source != v) { source = v; onSource(v) } }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val put = { k: String, v: Any ->
                        Look.prefs(ctx).edit().apply {
                            when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); else -> putString(k, v.toString()) }
                        }.apply()
                    }
                    if (source == "photo") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (bg == null) "사진 고르기" else "사진 바꾸기", AccentSoft, Accent, onPick)
                        if (bg != null) Pill("사진 빼기", Color(0xFFF3F2EE), Ink, onRemoveBg)
                    }
                    if (source == "booru") Column {
                        Field(tags, "태그 (예: hatsune_miku scenery)", KeyboardType.Text, {
                            tags = it; put("booruTags", it)
                        }) { onBooruChanged() }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seg("일반만", !sensitive) { if (sensitive) { sensitive = false; put("booruSensitive", false); onBooruChanged() } }
                            Seg("센시티브까지", sensitive) { if (!sensitive) { sensitive = true; put("booruSensitive", true); onBooruChanged() } }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (source == "pixiv") Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seg("랭킹", pMode == "ranking") { if (pMode != "ranking") { pMode = "ranking"; put("pixivMode", "ranking"); onBooruChanged() } }
                            Seg("태그", pMode == "tag") { if (pMode != "tag") { pMode = "tag"; put("pixivMode", "tag"); onBooruChanged() } }
                            Seg("북마크", pMode == "bookmark") { if (pMode != "bookmark") { pMode = "bookmark"; put("pixivMode", "bookmark"); onBooruChanged() } }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (pMode == "ranking") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val periods = if (pR18) listOf("daily" to "일간", "weekly" to "주간")
                                    else listOf("daily" to "일간", "weekly" to "주간", "monthly" to "월간")
                                periods.forEach { (v, name) ->
                                    val sel = pPeriod == v || (pR18 && v == "weekly" && pPeriod == "monthly")
                                    Seg(name, sel) { if (!sel) { pPeriod = v; put("pixivPeriod", v); onBooruChanged() } }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("상위  ", color = Ink, fontSize = 15.sp)
                                Box(Modifier.widthIn(max = 90.dp)) {
                                    Field(pTop, "100", KeyboardType.Number, { v ->
                                        pTop = v.filter { it.isDigit() }.take(3)
                                        pTop.toIntOrNull()?.takeIf { it in 1..500 }?.let { put("pixivTop", it) }
                                    }) { onBooruChanged() }
                                }
                                Text("  위 중에서 아무거나", color = Ink, fontSize = 15.sp)
                            }
                        } else if (pMode == "tag") {
                            Field(pTags, "태그 (예: 初音ミク 風景)", KeyboardType.Text, {
                                pTags = it; put("pixivTags", it)
                            }) { onBooruChanged() }
                        } else {
                            Text("내 북마크(공개 + 비공개)에서 아무거나 골라요. 로그인이 필요해요.",
                                color = Gray, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seg("전체 연령", !pR18) { if (pR18) { pR18 = false; put("pixivR18", false); onBooruChanged() } }
                            Seg("R-18", pR18) { if (!pR18) { pR18 = true; put("pixivR18", true); onBooruChanged() } }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seg("원본", !pLight) { if (pLight) { pLight = false; put("pixivLight", false); onBooruChanged() } }
                            Seg("가볍게", pLight) { if (!pLight) { pLight = true; put("pixivLight", true); onBooruChanged() } }
                        }
                        Text(if (pLight) "긴 변 1200px로 받아요. 빠르지만 펼친 화면에선 조금 흐려요."
                            else "원본 그대로 받아요. 선명하지만 큰 그림은 오래 걸려요.",
                            color = Gray, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        if (accounts.isEmpty()) {
                            Pill("픽시브 로그인", AccentSoft, Accent, onPixivLogin)
                        } else {
                            Text("계정", color = Gray, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                accounts.forEach { acc ->
                                    Seg(acc.name.ifEmpty { acc.uid }, acc.uid == activeUid) { onPixivSwitch(acc.uid) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("+ 계정 추가", AccentSoft, Accent, onPixivLogin)
                                Pill("이 계정 로그아웃", Color(0xFFF3F2EE), Ink, onPixivLogout)
                            }
                        }
                        if (pR18 && accounts.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("R-18은 로그인하고, 픽시브 설정에서 R-18 보기를 켜 둬야 나와요.", color = Gray, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (source == "booru" || source == "pixiv") Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.widthIn(max = 110.dp)) {
                                Field(minutes, "30", KeyboardType.Number, { v ->
                                    minutes = v.filter { it.isDigit() }.take(5)
                                    minutes.toIntOrNull()?.takeIf { it >= 1 }?.let { put("booruMinutes", it) }
                                }) {}
                            }
                            Text("  분마다 바꾸기", color = Ink, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("다른 그림", AccentSoft, Accent, onBooruNext)
                            if (bg != null) Pill("이 그림 열기", Color(0xFFF3F2EE), Ink, onOpenPost)
                        }
                        if (booruStatus.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(booruStatus, color = Gray, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("태그·숫자는 키보드의 완료를 눌러야 적용돼요. 시간이 지나면 다음에 열 때 바뀌고, 빈 곳을 두 번 누르면 바로 바뀌어요. 여러 장짜리 작품은 빈 곳을 옆으로 밀면 다음 장이 나와요.",
                            color = Gray, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Seg(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (selected) Accent else Color(0xFFF3F2EE))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (selected) Color.White else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Field(value: String, hint: String, type: KeyboardType, onChange: (String) -> Unit, onDone: () -> Unit) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = Ink),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onDone() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.clip(CircleShape).background(Color(0xFFF3F2EE)).padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(hint, color = Gray, fontSize = 15.sp)
                inner()
            }
        },
    )
}
