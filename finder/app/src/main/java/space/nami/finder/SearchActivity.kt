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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
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
            val long = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (long / (sample * 2) >= 2560) sample *= 2
            d.setTargetSampleSize(sample)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(dest)
    }.getOrDefault(false)

    fun saveBg(c: Context, uri: Uri): Boolean = writeScaled(ImageDecoder.createSource(c.contentResolver, uri), bgFile(c))

    fun loadBg(c: Context): ImageBitmap? {
        val f = when (source(c)) { "photo" -> bgFile(c); "booru" -> Booru.cur(c); else -> return null }
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }
}

/** Search window: type, press search → opens in a new Nami tab. Tap outside or back to close. */
class SearchActivity : ComponentActivity() {
    private var settingsOpen by mutableStateOf(false)
    private var bgVersion by mutableIntStateOf(0)
    private var booruStatus by mutableStateOf("")
    private var picking = false

    /** Danbooru: swap if it's time, then make sure the next picture is downloading. */
    private fun booruTick(force: Boolean) {
        if (Look.source(this) != "booru") return
        thread {
            val swapped = Booru.rotate(this, force)
            if (swapped) runOnUiThread { bgVersion++ }
            if (Booru.hasNext(this)) { runOnUiThread { booruStatus = "다음 그림 준비됨" }; return@thread }
            runOnUiThread { booruStatus = "다음 그림 받는 중…" }
            var err = Booru.fetchNext(this)
            // first picture, or "다른 그림" with nothing ready yet: show it right away and prepare another
            val showNow = err == null && !swapped && (force || !Booru.cur(this).exists()) && Booru.rotate(this, true)
            if (showNow) err = Booru.fetchNext(this)
            runOnUiThread {
                if (showNow) bgVersion++
                booruStatus = err ?: if (Booru.hasNext(this)) "다음 그림 준비됨" else ""
            }
        }
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
                onSource = { v -> Look.putSource(this, v); bgVersion++; booruTick(false) },
                booruStatus = booruStatus,
                onBooruChanged = { Booru.dropNext(this); booruTick(true) },
                onBooruNext = { booruTick(true) },
                onOpenPost = { Booru.curId(this).takeIf { it > 0 }?.let { go("https://danbooru.donmai.us/posts/$it") } },
            )
        }
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

    private fun go(input: String) {
        val url = toUrl(input) ?: return
        val uri = Uri.parse(url)
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(Intent(view).setPackage(NAMI))
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
    onOpenPost: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var transparency by remember { mutableIntStateOf(Look.transparency(ctx)) }
    var source by remember { mutableStateOf(Look.source(ctx)) }
    var tags by remember { mutableStateOf(Booru.tags(ctx)) }
    var sensitive by remember { mutableStateOf(Booru.sensitive(ctx)) }
    var minutes by remember { mutableStateOf(Booru.minutes(ctx).toString()) }
    val bg by produceState<ImageBitmap?>(null, bgVersion) { value = withContext(Dispatchers.IO) { Look.loadBg(ctx) } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val opacity = (100 - transparency) / 100f
    val swallow = remember { MutableInteractionSource() }
    val imeUp = WindowInsets.isImeVisible

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                // keyboard up → just put it away; otherwise close settings, then the window
                when {
                    imeUp -> { keyboard?.hide(); focusManager.clearFocus() }
                    settingsOpen -> onSettings(false)
                    else -> onClose()
                }
            },
    ) {
        val img = bg
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = opacity)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = opacity)))
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
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
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
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .shadow(10.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .clickable(interactionSource = swallow, indication = null) {}
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("배경", color = Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("투명도", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                    Spacer(Modifier.height(6.dp))
                    Text("배경 그림", color = Ink, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("none" to "없음", "photo" to "내 사진", "booru" to "단부루").forEach { (v, name) ->
                            Seg(name, source == v) { source = v; onSource(v) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (source) {
                        "photo" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(if (bg == null) "사진 고르기" else "사진 바꾸기", AccentSoft, Accent, onPick)
                            if (bg != null) Pill("사진 빼기", Color(0xFFF3F2EE), Ink, onRemoveBg)
                        }
                        "booru" -> Column {
                            Field(tags, "태그 (예: hatsune_miku scenery)", KeyboardType.Text, {
                                tags = it; Look.prefs(ctx).edit().putString("booruTags", it).apply()
                            }) { onBooruChanged() }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Seg("일반만", !sensitive) {
                                    if (sensitive) { sensitive = false; Look.prefs(ctx).edit().putBoolean("booruSensitive", false).apply(); onBooruChanged() }
                                }
                                Seg("센시티브까지", sensitive) {
                                    if (!sensitive) { sensitive = true; Look.prefs(ctx).edit().putBoolean("booruSensitive", true).apply(); onBooruChanged() }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.widthIn(max = 110.dp)) {
                                    Field(minutes, "30", KeyboardType.Number, { v ->
                                        minutes = v.filter { it.isDigit() }.take(5)
                                        minutes.toIntOrNull()?.takeIf { it >= 1 }?.let { Look.prefs(ctx).edit().putInt("booruMinutes", it).apply() }
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
                            Text("태그는 띄어쓰기로 구분(단부루 검색과 같아요). 시간이 지나면 다음에 열 때 바뀌어요.",
                                color = Gray, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        else -> {}
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
