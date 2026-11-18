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

/** Background behind the search window: transparency (0 = solid, 100 = see-through) + optional photo. */
object Look {
    private fun prefs(c: Context) = c.getSharedPreferences("finder", Context.MODE_PRIVATE)
    fun transparency(c: Context) = prefs(c).getInt("transparency", 65)
    fun putTransparency(c: Context, v: Int) = prefs(c).edit().putInt("transparency", v).apply()
    fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")

    /** Copy the picked photo in, scaled so the long side is at most 2560px (EXIF rotation applied). */
    fun saveBg(c: Context, uri: Uri): Boolean = runCatching {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (long / (sample * 2) >= 2560) sample *= 2
            d.setTargetSampleSize(sample)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(c.filesDir, "bg.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(bgFile(c))
    }.getOrDefault(false)

    fun loadBg(c: Context): ImageBitmap? {
        val f = bgFile(c)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }
}

/** Search window: type, press search → opens in a new Nami tab. Tap outside or back to close. */
class SearchActivity : ComponentActivity() {
    private var settingsOpen by mutableStateOf(false)
    private var bgVersion by mutableIntStateOf(0)
    private var picking = false

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false
        if (uri != null) thread {
            val ok = Look.saveBg(this, uri)
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
                onRemoveBg = { Look.bgFile(this).delete(); bgVersion++ },
            )
        }
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

@Composable
private fun SearchScreen(
    onGo: (String) -> Unit,
    onClose: () -> Unit,
    settingsOpen: Boolean,
    onSettings: (Boolean) -> Unit,
    bgVersion: Int,
    onPick: () -> Unit,
    onRemoveBg: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var transparency by remember { mutableIntStateOf(Look.transparency(ctx)) }
    val bg by produceState<ImageBitmap?>(null, bgVersion) { value = withContext(Dispatchers.IO) { Look.loadBg(ctx) } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val opacity = (100 - transparency) / 100f
    val swallow = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (settingsOpen) onSettings(false) else onClose()
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (bg == null) "사진 고르기" else "사진 바꾸기", AccentSoft, Accent, onPick)
                        if (bg != null) Pill("사진 빼기", Color(0xFFF3F2EE), Ink, onRemoveBg)
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
