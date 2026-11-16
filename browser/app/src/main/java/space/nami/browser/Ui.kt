@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package space.nami.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object C {
    val Bg = Color(0xFFFAF9F7)
    val Drawer = Color(0xFFF3F2EE)
    val Ink = Color(0xFF141413)
    val Sub = Color(0xFF73726C)
    val Accent = Color(0xFFD97757)
    val AccentSoft = Color(0xFFF7E3DA)
    val Line = Color(0xFFE6E5E0)
}

val Pretendard = FontFamily(
    Font(R.font.pretendardgov_regular, FontWeight.Normal),
    Font(R.font.pretendardgov_medium, FontWeight.Medium),
    Font(R.font.pretendardgov_semibold, FontWeight.SemiBold),
    Font(R.font.pretendardgov_bold, FontWeight.Bold),
    Font(R.font.pretendardgov_extrabold, FontWeight.ExtraBold),
)

@Composable
fun NamiTheme(content: @Composable () -> Unit) {
    val b = Typography()
    fun TextStyle.p() = copy(fontFamily = Pretendard)
    val t = Typography(
        displayLarge = b.displayLarge.p(), displayMedium = b.displayMedium.p(), displaySmall = b.displaySmall.p(),
        headlineLarge = b.headlineLarge.p(), headlineMedium = b.headlineMedium.p(), headlineSmall = b.headlineSmall.p(),
        titleLarge = b.titleLarge.p(), titleMedium = b.titleMedium.p(), titleSmall = b.titleSmall.p(),
        bodyLarge = b.bodyLarge.p(), bodyMedium = b.bodyMedium.p(), bodySmall = b.bodySmall.p(),
        labelLarge = b.labelLarge.p(), labelMedium = b.labelMedium.p(), labelSmall = b.labelSmall.p(),
    )
    val scheme = lightColorScheme(
        primary = C.Accent, onPrimary = Color.White, secondary = C.Accent, background = C.Bg, onBackground = C.Ink,
        surface = Color.White, onSurface = C.Ink, surfaceVariant = C.Drawer, onSurfaceVariant = C.Sub, outline = C.Line,
        surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = C.Drawer,
    )
    MaterialTheme(colorScheme = scheme, typography = t, content = content)
}

fun copyText(ctx: Context, s: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s))
    Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
}

fun shareText(ctx: Context, s: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), null))
}

@Composable
fun NamiApp(b: Browser, prefs: Prefs, act: Activity) = NamiTheme {
    val ctx = LocalContext.current
    val view = LocalView.current
    var menuOpen by remember { mutableStateOf(false) }
    val tab = b.tab

    LaunchedEffect(prefs.hideStatusBar, b.customView) {
        val c = WindowCompat.getInsetsController(act.window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when {
            b.customView != null -> c.hide(WindowInsetsCompat.Type.systemBars())
            prefs.hideStatusBar -> { c.show(WindowInsetsCompat.Type.navigationBars()); c.hide(WindowInsetsCompat.Type.statusBars()) }
            else -> c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 120Hz: ask for the fastest mode at the current resolution (recomputed when the phone folds/unfolds).
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(prefs.highRefresh, config.screenWidthDp, config.screenHeightDp) {
        runCatching {
            val d = act.display ?: return@runCatching
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            val lp = act.window.attributes
            lp.preferredDisplayModeId = if (prefs.highRefresh && best != null) best.modeId else 0
            act.window.attributes = lp
        }
    }

    LaunchedEffect(prefs.dnsMode, prefs.dpiBypass, prefs.proxySites) { b.applyNetwork() }
    LaunchedEffect(prefs.textZoom) { b.tabs.forEach { it.web.settings.textZoom = prefs.textZoom } }

    fun run(a: Action) {
        val t = b.tab
        when (a) {
            Action.NONE -> {}
            Action.ADDRESS -> { b.toolbarVisible = true; b.editing = true }
            Action.TABS -> b.openTabs()
            Action.MENU -> { b.toolbarVisible = true; menuOpen = true }
            Action.BACK -> if (t != null && t.web.canGoBack()) t.web.goBack() else b.showPill("더 뒤로 갈 페이지가 없어요", 1200)
            Action.FORWARD -> if (t != null && t.web.canGoForward()) t.web.goForward()
            Action.RELOAD -> { t?.web?.reload(); b.showPill("새로고침", 900) }
            Action.TRANSLATE -> t?.let { b.toggleTranslate(it) }
            Action.NEW_TAB -> { b.newTab(prefs.homeUrl); b.toolbarVisible = true; b.editing = true }
            Action.HOME -> t?.let { b.load(it, prefs.homeUrl) }
            Action.SHARE -> t?.let { shareText(ctx, it.url) }
            Action.TOP -> t?.web?.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
            Action.TOOLBAR -> b.toolbarVisible = !b.toolbarVisible
            Action.SETTINGS -> b.showSettings = true
        }
    }
    SideEffect { b.runAction = { run(it) } }

    BackHandler {
        when {
            b.customView != null -> b.exitCustomView()
            b.longPress != null -> b.longPress = null
            b.showSettings -> b.showSettings = false
            b.showTabs -> b.showTabs = false
            b.editing -> b.editing = false
            menuOpen -> menuOpen = false
            tab != null && tab.web.canGoBack() -> tab.web.goBack()
            else -> act.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (!prefs.hideStatusBar) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { b.container }, modifier = Modifier.fillMaxSize(),
                    update = { b.current; b.tabs.size; b.attachCurrent() })
                val p = tab?.progress ?: 100
                if (p < 100) Box(Modifier.fillMaxWidth(p / 100f).height(2.dp).background(C.Accent))
                PullIndicator(b.pull, prefs.pullAction, Modifier.align(Alignment.TopCenter))
            }
            // Like Chrome: when the keyboard opens, shrink the page to the space above it
            // (edge-to-edge apps don't get this from adjustResize automatically).
            Spacer(Modifier.windowInsetsBottomHeight(if (b.editing) WindowInsets.navigationBars else WindowInsets.ime.union(WindowInsets.navigationBars)))
        }

        // Bottom overlay: status pill + toolbar / address editor
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            b.pill?.let { Pill(it, tab, b) }
            AnimatedVisibility(b.toolbarVisible || b.editing,
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                if (b.editing) AddressEditor(b) else Toolbar(b, prefs, menuOpen, { menuOpen = it }, ::run)
            }
        }

        // The small dot that calls the toolbar
        if (!b.toolbarVisible && !b.editing) {
            val on = tab?.translateOn == true
            Box(
                Modifier.align(if (prefs.dotOnLeft) Alignment.BottomStart else Alignment.BottomEnd)
                    .navigationBarsPadding().padding(14.dp).size(30.dp).clip(CircleShape)
                    .background(if (on) C.Accent.copy(alpha = 0.55f) else C.Ink.copy(alpha = 0.16f))
                    .clickable { b.toolbarVisible = true },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f))) }
        }

        if (b.showTabs) TabsScreen(b)
        if (b.showSettings) SettingsScreen(b, prefs)
        b.longPress?.let { LongPressDialog(b, it) }
        b.pendingInstall?.let { InstallDialog(b, it) }
        b.scriptPanel?.let { ScriptPanel(b, it) }
        b.customView?.let { v ->
            AndroidView(factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                modifier = Modifier.fillMaxSize(),
                update = { fl ->
                    if (fl.childCount == 0 || fl.getChildAt(0) !== v) {
                        fl.removeAllViews(); (v.parent as? ViewGroup)?.removeView(v)
                        fl.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                })
        }
    }
}

@Composable
private fun PullIndicator(p: Float, action: Action, modifier: Modifier) {
    if (p <= 0f || action == Action.NONE) return
    val ready = p >= 1f
    Surface(modifier.padding(top = (8 + 48 * p.coerceAtMost(1f)).dp).size(40.dp).alpha((p * 1.6f).coerceAtMost(1f)),
        shape = CircleShape, color = if (ready) C.Accent else Color.White, shadowElevation = 4.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(action.icon ?: Icons.Filled.Refresh, action.label,
                tint = if (ready) Color.White else C.Sub, modifier = Modifier.size(22.dp).rotate(if (action == Action.RELOAD) p * 300f else 0f))
        }
    }
}

@Composable
private fun Pill(text: String, tab: BrowserTab?, b: Browser) {
    val translated = tab != null && tab.translateOn && !tab.translating
    Surface(Modifier.padding(bottom = 8.dp), shape = CircleShape, color = C.Ink.copy(alpha = 0.88f)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (translated) {
                Spacer(Modifier.width(12.dp))
                Text("원문", color = Color(0xFFF2B8A0), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { b.toggleTranslate(tab!!) })
            }
        }
    }
}

@Composable
private fun Toolbar(b: Browser, prefs: Prefs, menuOpen: Boolean, setMenu: (Boolean) -> Unit, run: (Action) -> Unit) {
    val tab = b.tab
    val hasAddress = Action.ADDRESS in prefs.toolbar
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 760.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 6.dp) {
        Row(Modifier.height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (hasAddress) Arrangement.spacedBy(2.dp) else Arrangement.SpaceEvenly) {
            for (a in prefs.toolbar) when (a) {
                Action.ADDRESS -> AddressPill(tab, Modifier.weight(1f), onSwipe = { b.switchBy(it) }) { run(Action.ADDRESS) }
                Action.TABS -> Box(Modifier.size(44.dp).clip(CircleShape).clickable { run(Action.TABS) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).border(2.dp, C.Ink, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text("${b.tabs.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    }
                }
                Action.MENU -> Box {
                    IconButton({ setMenu(true) }) { Icon(Icons.Filled.MoreVert, "메뉴", tint = C.Ink) }
                    MainMenu(b, prefs, menuOpen, { setMenu(false) }, run)
                }
                else -> {
                    val enabled = when (a) {
                        Action.BACK -> tab?.canBack == true
                        Action.FORWARD -> tab?.canForward == true
                        else -> true
                    }
                    val tint = when {
                        !enabled -> C.Line
                        a == Action.TRANSLATE && tab?.translateOn == true -> C.Accent
                        else -> C.Ink
                    }
                    IconButton({ run(a) }, enabled = enabled) { Icon(a.icon ?: Icons.Filled.MoreVert, a.label, tint = tint) }
                }
            }
        }
    }
}

@Composable
private fun AddressPill(tab: BrowserTab?, modifier: Modifier, onSwipe: (Int) -> Unit, onClick: () -> Unit) {
    val url = tab?.url ?: ""
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
    // Swipe sideways on the address bar: left → next tab, right → previous tab (like Chrome).
    var dx by remember { mutableStateOf(0f) }
    val swipe = Modifier.pointerInput(Unit) {
        val limit = 56.dp.toPx()
        detectHorizontalDragGestures(
            onDragEnd = { if (dx <= -limit) onSwipe(1) else if (dx >= limit) onSwipe(-1); dx = 0f },
            onDragCancel = { dx = 0f },
        ) { change, d -> change.consume(); dx += d }
    }
    Row(modifier.height(42.dp).then(swipe).graphicsLayer { translationX = dx * 0.35f }
        .clip(CircleShape).background(C.Drawer).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (url.startsWith("https://")) { Icon(Icons.Filled.Lock, null, tint = C.Sub, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
        Text(host.ifEmpty { "주소 입력" }, color = if (host.isEmpty()) C.Sub else C.Ink, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (tab?.translateOn == true) Icon(Icons.Filled.Translate, "번역 중", tint = C.Accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun MainMenu(b: Browser, prefs: Prefs, open: Boolean, close: () -> Unit, run: (Action) -> Unit) {
    val tab = b.tab
    val ctx = LocalContext.current
    DropdownMenu(open, close, shape = RoundedCornerShape(18.dp), containerColor = Color.White) {
        @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, enabled: Boolean = true, checked: Boolean = false, onClick: () -> Unit) {
            val lead: @Composable () -> Unit = { if (icon != null) Icon(icon, null, tint = C.Ink) }
            val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, null, tint = C.Accent) }
            DropdownMenuItem(text = { Text(label, fontSize = 15.sp) }, onClick = { close(); onClick() }, enabled = enabled,
                leadingIcon = lead, trailingIcon = if (checked) check else null)
        }
        item("새 탭", Action.NEW_TAB.icon) { run(Action.NEW_TAB) }
        item("뒤로", Action.BACK.icon, tab?.canBack == true) { run(Action.BACK) }
        item("앞으로", Action.FORWARD.icon, tab?.canForward == true) { run(Action.FORWARD) }
        item("새로고침", Action.RELOAD.icon) { run(Action.RELOAD) }
        item(if (tab?.translateOn == true) "원문 보기" else "한국어로 번역", Action.TRANSLATE.icon) { run(Action.TRANSLATE) }
        val host = tab?.host ?: ""
        if (host.isNotEmpty()) {
            val always = host in prefs.alwaysHosts
            item("이 사이트는 항상 번역", Icons.Filled.Translate, checked = always) {
                prefs.setAlways(host, !always)
                if (!always && tab != null && !tab.translateOn) b.toggleTranslate(tab)
            }
        }
        if (host.isNotEmpty() && prefs.adblock) {
            val on = !prefs.adblockOffFor(host)
            val n = tab?.blocked?.get() ?: 0
            item(if (on && n > 0) "광고 차단 · ${n}개 막음" else "이 사이트 광고 차단", Icons.Filled.Block, checked = on) {
                prefs.putAdblockOff(host, on); tab?.web?.reload()
            }
        }
        if (b.scripts.list.isNotEmpty()) item("유저스크립트", Icons.Filled.Code) { b.loadScriptPanel() }
        item("공유", Action.SHARE.icon) { run(Action.SHARE) }
        item("맨 위로", Action.TOP.icon) { run(Action.TOP) }
        item("받은 파일", Icons.Filled.Download) {
            runCatching { ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        item("설정", Action.SETTINGS.icon) { run(Action.SETTINGS) }
    }
}

@Composable
private fun AddressEditor(b: Browser) {
    val tab = b.tab
    val ctx = LocalContext.current
    val start = tab?.url ?: ""
    var text by remember(tab?.id) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go() { tab?.let { b.load(it, text.text) }; b.editing = false; b.toolbarVisible = false }
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 760.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 6.dp) {
        Row(Modifier.height(56.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (text.text.isEmpty()) Text("검색어나 주소", color = C.Sub, fontSize = 16.sp)
                BasicTextField(text, { text = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Pretendard, fontSize = 16.sp, color = C.Ink),
                    cursorBrush = SolidColor(C.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            if (text.text.isNotEmpty()) IconButton({ text = TextFieldValue("") }) { Icon(Icons.Filled.Close, "지우기", tint = C.Sub) }
            IconButton({ copyText(ctx, start) }) { Icon(Icons.Filled.ContentCopy, "주소 복사", tint = C.Sub, modifier = Modifier.size(20.dp)) }
            TextButton({ b.editing = false }) { Text("취소", color = C.Ink) }
        }
    }
}

@Composable
private fun TabsScreen(b: Browser) {
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragId by remember { mutableStateOf<Long?>(null) }
    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Surface(Modifier.fillMaxSize(), color = C.Bg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("탭 ${b.tabs.size}개", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
                TextButton({ b.newTab(b.prefs.homeUrl); b.showTabs = false; b.toolbarVisible = true; b.editing = true }) {
                    Icon(Icons.Filled.Add, null, tint = C.Accent); Spacer(Modifier.width(4.dp)); Text("새 탭", color = C.Accent, fontWeight = FontWeight.SemiBold)
                }
                IconButton({ b.showTabs = false }) { Icon(Icons.Filled.Close, "닫기", tint = C.Ink) }
            }
            Text("꾹 눌러서 끌면 순서를 바꿀 수 있어요", fontSize = 12.sp, color = C.Sub, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
            LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp), state = grid,
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(b.tabs, key = { _, t -> t.id }) { i, t ->
                    val sel = i == b.current
                    val dragging = dragId == t.id
                    val moveMod = if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationX = drag.x; translationY = drag.y; scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 24f
                    } else Modifier.animateItem()
                    Surface(moveMod.fillMaxWidth().height(250.dp)
                        .pointerInput(t.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); dragId = t.id; drag = androidx.compose.ui.geometry.Offset.Zero },
                                onDragEnd = { dragId = null; drag = androidx.compose.ui.geometry.Offset.Zero },
                                onDragCancel = { dragId = null; drag = androidx.compose.ui.geometry.Offset.Zero },
                            ) { change, amount ->
                                change.consume()
                                drag += amount
                                val info = grid.layoutInfo.visibleItemsInfo
                                val me = info.firstOrNull { it.key == t.id }
                                if (me != null) {
                                    val cx = me.offset.x + drag.x + me.size.width / 2f
                                    val cy = me.offset.y + drag.y + me.size.height / 2f
                                    val target = info.firstOrNull {
                                        it.key != t.id && cx >= it.offset.x && cx <= it.offset.x + it.size.width &&
                                            cy >= it.offset.y && cy <= it.offset.y + it.size.height
                                    }
                                    if (target != null) {
                                        val from = b.tabs.indexOfFirst { it.id == t.id }
                                        b.moveTab(from, target.index)
                                        drag -= androidx.compose.ui.geometry.Offset((target.offset.x - me.offset.x).toFloat(), (target.offset.y - me.offset.y).toFloat())
                                    }
                                }
                            }
                        }
                        .clickable { b.select(b.tabs.indexOfFirst { it.id == t.id }); b.showTabs = false; b.toolbarVisible = false },
                        shape = RoundedCornerShape(22.dp), color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(if (sel) 2.dp else 1.dp, if (sel) C.Accent else C.Line)) {
                        Column {
                            Row(Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title.ifEmpty { Uri.parse(t.url).host ?: t.url }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton({ b.closeTab(b.tabs.indexOfFirst { it.id == t.id }) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "탭 닫기", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                            }
                            Box(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(C.Drawer),
                                contentAlignment = Alignment.Center) {
                                val th = t.thumb
                                if (th != null) androidx.compose.foundation.Image(th.asImageBitmap(), null, Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.TopCenter)
                                else Text(Uri.parse(t.url).host?.removePrefix("www.") ?: "", fontSize = 13.sp, color = C.Sub, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LongPressDialog(b: Browser, lp: LongPress) {
    val ctx = LocalContext.current
    fun close() { b.longPress = null }
    AlertDialog(onDismissRequest = ::close, confirmButton = {}, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text(lp.link ?: lp.image ?: "", fontSize = 13.sp, color = C.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                @Composable fun row(label: String, onClick: () -> Unit) {
                    Text(label, fontSize = 16.sp, color = C.Ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { close(); onClick() }.padding(horizontal = 8.dp, vertical = 13.dp))
                }
                lp.image?.let { img ->
                    row("이미지 저장") { b.download(img) }
                    row("이미지를 새 탭에서 열기") { b.newTab(img) }
                    row("이미지 주소 복사") { copyText(ctx, img) }
                }
                lp.link?.let { link ->
                    row("새 탭에서 열기") { b.newTab(link, select = false); b.showPill("새 탭에서 열었어요", 1500) }
                    row("링크 복사") { copyText(ctx, link) }
                    row("링크 공유") { shareText(ctx, link) }
                }
            }
        })
}

@Composable
private fun InstallDialog(b: Browser, s: UScript) {
    val existing = b.scripts.list.firstOrNull { it.name == s.name && it.namespace == s.namespace }
    AlertDialog(onDismissRequest = { b.pendingInstall = null }, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text(if (existing != null) "유저스크립트 업데이트" else "유저스크립트 설치", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.name + if (s.version.isNotEmpty()) "  ${s.version}" else "", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                if (existing != null) Text("지금 설치된 버전: ${existing.version}", fontSize = 13.sp, color = C.Sub)
                if (s.description.isNotEmpty()) Text(s.description, fontSize = 14.sp, color = C.Ink)
                val m = s.matches.ifEmpty { listOf("모든 사이트") }
                Text("실행되는 곳: " + m.take(5).joinToString(", ") + if (m.size > 5) " 외 ${m.size - 5}곳" else "", fontSize = 13.sp, color = C.Sub)
                Text("믿을 수 있는 곳에서 받은 스크립트만 설치해 주세요. 스크립트는 그 사이트의 내용을 읽고 바꿀 수 있어요.", fontSize = 12.sp, color = C.Sub)
            }
        },
        confirmButton = { TextButton({ b.installPending() }) { Text(if (existing != null) "업데이트" else "설치", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ b.pendingInstall = null }) { Text("취소", color = C.Ink) } })
}

@Composable
private fun ScriptPanel(b: Browser, list: List<PageScriptInfo>) {
    AlertDialog(onDismissRequest = { b.scriptPanel = null }, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        confirmButton = { TextButton({ b.scriptPanel = null }) { Text("닫기", color = C.Ink) } },
        title = { Text("이 페이지의 유저스크립트", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("이 페이지에서 실행 중인 스크립트가 없어요. 스크립트가 적용되는 사이트에서 열어 주세요.", fontSize = 14.sp, color = C.Sub)
                list.forEach { p ->
                    val sc = b.scripts.list.firstOrNull { it.id == p.sid }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                        if (sc != null) androidx.compose.material3.Switch(sc.enabled, { on ->
                            b.scripts.setEnabled(sc, on); b.showPill("새로고침하면 적용돼요", 1800)
                        }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White,
                            uncheckedTrackColor = C.Drawer, uncheckedBorderColor = C.Line))
                    }
                    if (p.commands.isEmpty()) Text("등록한 명령이 없어요", fontSize = 13.sp, color = C.Sub, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    p.commands.forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { b.runScriptCommand(id) }
                            .padding(horizontal = 6.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Settings, null, tint = C.Sub, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(name, fontSize = 15.sp, color = C.Ink)
                        }
                    }
                }
            }
        })
}
