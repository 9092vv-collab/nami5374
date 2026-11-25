package space.nami.maker

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

object DesktopApp {
    val dataDir: File = (System.getenv("APPDATA")?.let { File(it, "Nami Maker") } ?: File(System.getProperty("user.home"), ".nami-maker")).apply { mkdirs() }
    val ctx = android.content.Context(dataDir)
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
    Plat.desktop = true
    Api.init(DesktopApp.ctx)
    Prompts.init(DesktopApp.ctx)
    Chars.init(DesktopApp.ctx)
    TileSkin.init(DesktopApp.ctx)
    Stands.init(DesktopApp.ctx)
    Logs.init(DesktopApp.ctx)
    application {
        val state = rememberWindowState(width = 1440.dp, height = 920.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = "Nami Maker",
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && DesktopBack.back() }) {
            androidx.compose.runtime.LaunchedEffect(Unit) { Plat.gfx = runCatching { window.renderApi.toString() }.getOrDefault("") }
            MakerTheme { App() }
        }
    }
}
