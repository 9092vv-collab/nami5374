package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/** One open game: the project, its undo history and saving. */
class Doc(val file: File, var p: Project) {
    var rev by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    fun snapshot(): String = p.toJson().toString()

    /** Call BEFORE a change: remembers the current state as one undo step. */
    fun checkpoint() = pushUndo(snapshot())

    fun pushUndo(s: String) {
        undo.addLast(s); if (undo.size > 80) undo.removeFirst()
        redo.clear(); flags()
    }

    /** Drops the last checkpoint if nothing really changed since. */
    fun dropIfSame() {
        if (undo.isNotEmpty() && undo.last() == snapshot()) { undo.removeLast(); flags() }
    }

    /** Call AFTER a change. */
    fun changed() { rev++; save() }

    /** Puts the game back to a saved state without touching history (cancelled strokes). */
    fun restore(s: String) { p = Project.from(JSONObject(s)); rev++ }

    fun undo() {
        val s = undo.removeLastOrNull() ?: return
        redo.addLast(snapshot()); p = Project.from(JSONObject(s)); flags(); changed()
    }

    fun redo() {
        val s = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot()); p = Project.from(JSONObject(s)); flags(); changed()
    }

    private fun flags() { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }

    fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(snapshot())
        // 윈도우에서는 File.renameTo가 있는 파일을 덮어쓰지 못해서(실패해도 조용히 false) 저장이 안 되던 문제 → 덮어쓰기로 옮김
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

object Store {
    fun dir(ctx: Context) = File(ctx.filesDir, "games").also { it.mkdirs() }

    class Entry(val file: File, val name: String, val modified: Long)

    /** 예전 PC판에서 못 옮기고 남은 .tmp(마지막으로 저장하려던 내용)가 더 새것이면 되살림. */
    private fun recover(d: File) {
        d.listFiles { f -> f.name.endsWith(".json.tmp") }?.forEach { t ->
            val f = File(t.path.removeSuffix(".tmp"))
            if (runCatching { JSONObject(t.readText()) }.isSuccess && (!f.exists() || t.lastModified() > f.lastModified()))
                runCatching { java.nio.file.Files.move(t.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            else t.delete()
        }
    }

    fun list(ctx: Context): List<Entry> = (dir(ctx).also { recover(it) }.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") && !it.name.endsWith(".chat.json") }
        .mapNotNull { f ->
            runCatching { Entry(f, JSONObject(f.readText()).optString("name", "게임"), f.lastModified()) }.getOrNull()
        }
        .sortedByDescending { it.modified }

    fun create(ctx: Context, name: String): Doc {
        val f = File(dir(ctx), "g" + System.currentTimeMillis() + ".json")
        return Doc(f, Project.sample(name)).also { it.save() }
    }

    fun open(f: File) = Doc(f, Project.from(JSONObject(f.readText())))

    fun chatFile(doc: Doc) = File(doc.file.path.removeSuffix(".json") + ".chat.json")

    fun delete(e: Entry) {
        e.file.delete(); File(e.file.path.removeSuffix(".json") + ".chat.json").delete()
    }
}
