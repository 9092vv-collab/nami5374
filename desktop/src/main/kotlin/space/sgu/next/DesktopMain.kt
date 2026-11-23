package space.sgu.next

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.SettingsStore
import space.sgu.next.ui.SguApp
import space.sgu.next.ui.SguTheme
import java.io.File

object DesktopApp {
    val dataDir: File = (System.getenv("APPDATA")?.let { File(it, "Nami next") } ?: File(System.getProperty("user.home"), ".nami-next")).apply { mkdirs() }
    val ctx = android.content.Context(dataDir)
    var frame: java.awt.Frame? = null
}

/** 안드로이드 Toast 대신 창 아래에 잠깐 뜨는 알림. */
object DesktopToast {
    var text by mutableStateOf<String?>(null)
    private var seq by mutableStateOf(0)
    private var long = false
    fun show(t: String, isLong: Boolean) { javax.swing.SwingUtilities.invokeLater { text = t; long = isLong; seq++ } }
    @androidx.compose.runtime.Composable
    fun Host() {
        val s = seq
        LaunchedEffect(s) { if (text != null) { delay(if (long) 3500 else 2000); if (seq == s) text = null } }
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(text != null, enter = fadeIn(), exit = fadeOut()) {
                Text(text ?: "", color = Color.White, modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6222222)).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

/** 뒤로 가기 처리기 목록 (Esc 키). */
object DesktopBack {
    class Handler(val enabled: () -> Boolean, val onBack: () -> Unit)
    private val list = mutableListOf<Handler>()
    fun add(h: Handler) { list.add(h) }
    fun remove(h: Handler) { list.remove(h) }
    /** 처리했으면 true. */
    fun back(): Boolean { val h = list.lastOrNull { it.enabled() } ?: return false; h.onBack(); return true }
}

fun main() {
    space.sgu.next.ui.Platform.desktop = true
    val c = DesktopApp.ctx
    CharacterStore.init(c)
    SettingsStore.init(c)
    space.sgu.next.data.Templates.init(c)
    space.sgu.next.data.TemplateLibrary.init(c)
    space.sgu.next.data.StyleLibrary.init(c)
    space.sgu.next.data.Workshop.init(c)
    space.sgu.next.data.References.init(c)
    application {
        val state = rememberWindowState(width = 1280.dp, height = 860.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = "Nami next",
            onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) DesktopBack.back() else false }) {
            DesktopApp.frame = window
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                    SguTheme { SguApp() }
                    DesktopToast.Host()
                }
            }
        }
    }
}
