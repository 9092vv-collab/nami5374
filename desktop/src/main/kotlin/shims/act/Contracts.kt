package androidx.activity.result.contract

import android.net.Uri
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** 파일 고르기·저장 창: 윈도우 기본 파일 창(AWT FileDialog). 결과는 화면 스레드로 돌려줌. */
abstract class ActivityResultContract<I, O> {
    internal abstract fun pick(input: I): O
    fun run(input: I, done: (O) -> Unit) {
        Thread {
            val r = runCatching { pick(input) }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            javax.swing.SwingUtilities.invokeLater { done(r ?: empty()) }
        }.start()
    }
    @Suppress("UNCHECKED_CAST")
    internal open fun empty(): O = null as O
}

private fun frame(): Frame? = space.sgu.next.DesktopApp.frame

private fun extOf(mimes: Array<String>): List<String> = mimes.flatMap { m ->
    when {
        m.startsWith("image/") -> listOf("png", "jpg", "jpeg", "webp", "gif", "avif")
        m == "application/json" -> listOf("json")
        m.contains("zip") -> listOf("zip", "charx")
        m == "text/plain" -> listOf("txt", "md")
        else -> emptyList()
    }
}

private fun open(title: String, exts: List<String>, multi: Boolean): List<File> {
    val d = FileDialog(frame(), title, FileDialog.LOAD)
    d.isMultipleMode = multi
    if (exts.isNotEmpty()) d.setFilenameFilter { _, n -> exts.any { n.lowercase().endsWith(".$it") } }
    d.isVisible = true
    return d.files?.toList().orEmpty()
}

private fun save(name: String): File? {
    val d = FileDialog(frame(), "저장", FileDialog.SAVE)
    d.file = name
    d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

private fun mimeList(m: String) = if (m == "*/*") emptyList() else extOf(arrayOf(m))

object ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun pick(input: Array<String>): Uri? = open("열기", if ("*/*" in input) emptyList() else extOf(input), false).firstOrNull()?.let { Uri.fromFile(it) }
    }
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun pick(input: Array<String>): List<Uri> = open("열기", if ("*/*" in input) emptyList() else extOf(input), true).map { Uri.fromFile(it) }
        override fun empty(): List<Uri> = emptyList()
    }
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun pick(input: String): Uri? = open("열기", mimeList(input), false).firstOrNull()?.let { Uri.fromFile(it) }
    }
    class GetMultipleContents : ActivityResultContract<String, List<Uri>>() {
        override fun pick(input: String): List<Uri> = open("열기", mimeList(input), true).map { Uri.fromFile(it) }
        override fun empty(): List<Uri> = emptyList()
    }
    class CreateDocument(private val mime: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun pick(input: String): Uri? = save(input)?.let { Uri.fromFile(it) }
    }
}
