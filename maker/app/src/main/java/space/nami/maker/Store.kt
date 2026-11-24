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
        tmp.writeText(snapshot()); tmp.renameTo(file)
    }
}

object Store {
    fun dir(ctx: Context) = File(ctx.filesDir, "games").also { it.mkdirs() }

    class Entry(val file: File, val name: String, val modified: Long)

    fun list(ctx: Context): List<Entry> = (dir(ctx).listFiles() ?: emptyArray())
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
