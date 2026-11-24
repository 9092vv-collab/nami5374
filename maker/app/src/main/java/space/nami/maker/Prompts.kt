package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Prompt presets like Nami next: a preset is a list of blocks sent top to bottom.
 * - text: fixed text ({{tiles}}, {{graphics}} are filled in)
 * - map: the map the user is looking at ({{slot}} = text map), can attach the picture
 * - command: what the user typed ({{slot}})
 * role "system" blocks become the system prompt; role "user" blocks become the user's message, in order.
 */
object Prompts {
    const val SLOT = "{{slot}}"
    val TYPE_LABELS = mapOf("text" to "텍스트", "map" to "지금 맵", "command" to "사용자 메시지")
    val TYPE_DESC = mapOf(
        "text" to "직접 쓰는 고정 글. {{tiles}} = 타일 범례, {{graphics}} = 그래픽 목록으로 바뀌어요.",
        "map" to "지금 보고 있는 맵의 글자 지도·이벤트·맵 목록이 {{slot}} 자리에 들어가요. 그림 첨부를 켜면 맵 그림도 같이 가요.",
        "command" to "사용자가 채팅에 쓴 말이 {{slot}} 자리에 들어가요.",
    )

    var rev by mutableIntStateOf(0)
    private var file: File? = null
    private var root = JSONObject()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "presets.json"); file = f
        root = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        if ((root.optJSONArray("presets")?.length() ?: 0) == 0) {
            root.put("presets", JSONArray().put(newPreset("기본", defaults())))
            save()
        }
    }

    private fun save() { file?.writeText(root.toString()); rev++ }
    private fun id(p: String) = p + System.nanoTime().toString(36)

    fun block(type: String, role: String, name: String, text: String, image: Boolean = false): JSONObject =
        JSONObject().put("id", id("b")).put("type", type).put("enabled", true).put("role", role).put("name", name).put("text", text)
            .also { if (type == "map") it.put("image", image) }

    private fun newPreset(name: String, blocks: List<JSONObject>) = JSONObject().put("id", id("ps")).put("name", name).put("blocks", JSONArray(blocks))

    fun defaults(): List<JSONObject> = listOf(
        block("text", "system", "역할", """너는 모바일 게임 제작 앱 "Nami Maker"(쯔꾸르 같은 2D 탑다운 게임 제작 도구) 안의 AI 조수다.
사용자와 함께 대화·탐색 위주의 게임(어드벤처, 호러 쯔꾸르 같은)을 만든다. 전투는 아직 없다.
항상 한국어로, 상냥하고 짧게 답한다. 이모지는 거의 쓰지 않는다."""),
        block("text", "system", "보는 법", """## 보는 법
사용자의 메시지마다 지금 보고 있는 맵의 글자 지도와 그림이 붙어 온다. 글자 지도는 한 글자가 한 칸이다.
좌표는 (x, y)이고 x는 왼쪽에서 오른쪽, y는 위에서 아래, 0부터 센다. 위쪽 눈금이 x, 왼쪽 숫자가 y다.
'@'는 이벤트, 'P'는 시작 위치(실제 타일은 그 아래에 있다)."""),
        block("text", "system", "타일 범례", """## 타일 범례
{{tiles}}
위층 타일(나무, 가구, 문 등)은 바닥 위에 올라간다. 위층이 있으면 지나갈 수 있는지는 위층이 정한다(벽 위의 문은 지나갈 수 있다)."""),
        block("text", "system", "이벤트 규칙", """## 이벤트
이벤트는 페이지 목록을 가진다. 게임은 조건을 만족하는 페이지 중 가장 뒤의 것을 쓴다.
페이지: {"condition_switch": "" 또는 스위치 이름, "graphic": 그래픽, "dir": "down|left|right|up", "trigger": "action(말 걸기·조사)|touch(밟으면)|auto(조건이 맞으면 자동, 맵마다 한 번)", "commands": [...]}
그래픽: {{graphics}} ("none"은 투명하고 지나갈 수 있다 — 문 이동 칸이나 숨은 이벤트에 써라. 그래픽이 있으면 지나갈 수 없다.)
명령:
- {"type":"text","speaker":"이름(없으면 빈칸)","text":"대사. 줄바꿈은 \n, 한 번에 2~3줄"}
- {"type":"choice","options":["네","아니요"],"branches":[[...네일 때 명령],[...아니요일 때]]}  (선택지 2~4개)
- {"type":"switch","name":"스위치 이름","on":true}
- {"type":"if","cond":"switch","name":"스위치 이름","on":true,"then":[...],"else":[...]}  /  {"type":"if","cond":"item","name":"아이템 이름","then":[...],"else":[...]}
- {"type":"transfer","map":맵 번호,"x":0,"y":0}  (장소 이동, 화면이 자동으로 어두워졌다 밝아진다)
- {"type":"item","name":"열쇠","amount":1}  (음수면 잃음)
- {"type":"wait","ms":500}
- {"type":"fade","out":true}  (true 어둡게, false 밝게)
- {"type":"end"}  (이벤트 즉시 끝)
한 번만 일어나야 하는 일은 스위치를 켜고, 그 스위치를 조건으로 하는 다음 페이지를 만들어라(예: 상자 열면 [상자1 열림] 켜고, 페이지2는 빈 상자).
문으로 다른 맵에 가려면 문 칸에 graphic "none", trigger "touch", transfer 명령 이벤트를 둔다. 도착 위치는 상대 맵의 문 바로 앞(문 위가 아닌 곳)으로 해서 바로 되돌아가지 않게 한다."""),
        block("text", "system", "일하는 법", """## 일하는 법
- 맵을 바꿀 땐 도구를 써서 직접 바꿔라. 말로만 설명하지 마라.
- 먼저 붙어 온 지도를 읽고 정확한 좌표를 계산한 뒤 칠해라. 건물은 paint로 여러 줄을 한 번에 그리는 게 좋다.
- 편집이 끝나면 view_map이나 screenshot으로 결과를 확인하고, 이상하면 고쳐라.
- 길이 막혀서 못 가는 곳이 생기지 않게, NPC가 문이나 길을 막지 않게 신경 써라.
- 사용자가 맵에 대해 물으면 지도와 그림을 보고 구체적으로 답해라.
- 다 하고 나면 무엇을 했는지 1~3문장으로 알려라. 사용자는 되돌리기 버튼 한 번으로 네 작업 전체를 취소할 수 있다."""),
        block("map", "user", "지금 맵", "[지금 보고 있는 맵]\n{{slot}}", image = true),
        block("command", "user", "사용자 메시지", "[사용자]\n{{slot}}"),
    )

    fun newBlock(type: String) = block(type, if (type == "text") "system" else "user", TYPE_LABELS[type] ?: type, if (type == "text") "" else SLOT, image = type == "map")

    // ---------- presets ----------
    fun presets(): List<JSONObject> { val a = root.getJSONArray("presets"); return List(a.length()) { a.getJSONObject(it) } }
    fun preset(id: String?) = presets().firstOrNull { it.optString("id") == id }
    fun active(): JSONObject = preset(root.optString("active")) ?: presets().first()
    fun setActive(id: String) { root.put("active", id); save() }
    fun blocks(p: JSONObject): List<JSONObject> { val a = p.optJSONArray("blocks") ?: JSONArray(); return List(a.length()) { a.getJSONObject(it) } }

    fun add(name: String, blocks: List<JSONObject> = defaults()): String {
        val p = newPreset(name, blocks.map { JSONObject(it.toString()).put("id", id("b")) })
        root.put("presets", JSONArray(presets() + p)); save(); return p.getString("id")
    }
    fun copy(id: String): String? { val p = preset(id) ?: return null; return add(p.optString("name") + " 사본", blocks(p)) }
    fun remove(id: String) {
        if (presets().size <= 1) return
        root.put("presets", JSONArray(presets().filter { it.optString("id") != id }))
        if (root.optString("active") == id) root.remove("active")
        save()
    }
    fun rename(id: String, name: String) { preset(id)?.put("name", name); save() }
    fun reset(id: String) { preset(id)?.put("blocks", JSONArray(defaults())); save() }

    fun setBlocks(pid: String, l: List<JSONObject>) { preset(pid)?.put("blocks", JSONArray(l)); save() }
    fun patchBlock(pid: String, bid: String, k: String, v: Any) {
        preset(pid)?.let { p -> blocks(p).firstOrNull { it.optString("id") == bid }?.put(k, v) }; save()
    }
    fun move(pid: String, bid: String, d: Int) {
        val p = preset(pid) ?: return
        val l = blocks(p).toMutableList(); val i = l.indexOfFirst { it.optString("id") == bid }; val j = i + d
        if (i < 0 || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t; setBlocks(pid, l)
    }
    fun removeBlock(pid: String, bid: String) { val p = preset(pid) ?: return; setBlocks(pid, blocks(p).filter { it.optString("id") != bid }) }
    fun addBlock(pid: String, type: String): String { val p = preset(pid)!!; val b = newBlock(type); setBlocks(pid, blocks(p) + b); return b.getString("id") }

    // ---------- export / import (clipboard JSON) ----------
    fun export(p: JSONObject): String = JSONObject().put("nami_maker_preset", 1).put("name", p.optString("name")).put("blocks", p.optJSONArray("blocks")).toString(2)

    /** Returns the new preset id, or null if the text isn't a preset. Accepts one preset or a list. */
    fun import(text: String): Int {
        val found = mutableListOf<JSONObject>()
        runCatching {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { found.add(it) } }
            else JSONObject(t).let { o -> o.optJSONArray("presets")?.let { a -> for (i in 0 until a.length()) found.add(a.getJSONObject(i)) } ?: found.add(o) }
        }
        var n = 0
        found.forEach { o ->
            val a = o.optJSONArray("blocks") ?: return@forEach
            val bl = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") in TYPE_LABELS.keys }
            if (bl.isEmpty()) return@forEach
            add(o.optString("name").ifBlank { "가져온 프리셋" }, bl); n++
        }
        return n
    }

    // ---------- building a request ----------
    private fun fill(t: String) = t.replace("{{tiles}}", Tiles.legend()).replace("{{graphics}}", Sprites.ids.joinToString(", "))
    private fun slot(t: String, v: String) = if (SLOT in t) t.replace(SLOT, v) else if (t.isBlank()) v else "$t\n\n$v"

    class Built(val system: String, val content: JSONArray, val mapIdx: JSONArray)

    /** System prompt + the user's message content (text/image blocks). mapIdx = content positions holding the map (shrunk later). */
    fun build(p: Project, mapId: Int, userText: String): Built {
        val bl = blocks(active()).filter { it.optBoolean("enabled", true) }
        val sys = StringBuilder(); val content = JSONArray(); val mapIdx = JSONArray()
        val m = p.map(mapId) ?: p.maps[0]
        var hasCommand = false
        for (b in bl) {
            val type = b.optString("type"); val t = b.optString("text")
            val text = when (type) {
                "map" -> slot(t, MapText.describe(p, m.id))
                "command" -> { hasCommand = true; slot(t, userText) }
                else -> fill(t)
            }
            if (text.isBlank()) continue
            if (b.optString("role") == "system") { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(text); continue }
            if (type == "map") mapIdx.put(content.length())
            content.put(JSONObject().put("type", "text").put("text", text))
            if (type == "map" && b.optBoolean("image", true)) {
                mapIdx.put(content.length())
                content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/png").put("data", Shot.base64(p, m))))
            }
        }
        if (!hasCommand) content.put(JSONObject().put("type", "text").put("text", userText))
        return Built(sys.toString(), content, mapIdx)
    }
}
