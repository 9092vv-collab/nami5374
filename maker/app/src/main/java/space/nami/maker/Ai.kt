package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** The map as text, so the AI can "see" exact positions. */
object MapText {
    fun grid(p: Project, m: GameMap): String {
        val sb = StringBuilder()
        val pad = "    "
        if (m.w > 10) {
            sb.append(pad); for (x in 0 until m.w) sb.append(if (x % 10 == 0) ((x / 10) % 10).toString() else " "); sb.append('\n')
        }
        sb.append(pad); for (x in 0 until m.w) sb.append(x % 10); sb.append('\n')
        for (y in 0 until m.h) {
            sb.append(y.toString().padStart(3)).append(' ')
            for (x in 0 until m.w) {
                val k = m.i(x, y)
                val ch = when {
                    m.eventAt(x, y) != null -> '@'
                    p.startMap == m.id && p.startX == x && p.startY == y -> 'P'
                    m.upper[k] != 0 -> Tiles.get(m.upper[k]).ch
                    else -> Tiles.get(m.ground[k]).ch
                }
                sb.append(ch)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun eventLine(p: Project, e: GameEvent): String {
        val pages = e.pages.mapIndexed { i, pg ->
            val cond = if (pg.condSwitch.isNotBlank()) "조건 [${pg.condSwitch}] 켜짐, " else ""
            val first = pg.commands.take(2).joinToString(" / ") { it.summary(p) }.take(80)
            "페이지${i + 1}(${cond}${pg.graphic}, ${pg.trigger}, 명령 ${pg.commands.size}개${if (first.isNotEmpty()) ": $first" else ""})"
        }
        return "#${e.id} \"${e.name}\" (${e.x},${e.y}) " + pages.joinToString(" ")
    }

    fun describe(p: Project, mapId: Int): String {
        val m = p.map(mapId) ?: p.maps[0]
        val sb = StringBuilder()
        sb.append("맵 #${m.id} \"${m.name}\" ${m.w}x${m.h} (x: 왼→오, y: 위→아래, 0부터. @=이벤트, P=시작 위치)\n")
        sb.append(grid(p, m))
        sb.append("이벤트:\n")
        if (m.events.isEmpty()) sb.append(" (없음)\n")
        m.events.forEach { sb.append(" ").append(eventLine(p, it)).append('\n') }
        sb.append("시작 위치: 맵 #${p.startMap} (${p.startX},${p.startY})\n")
        sb.append("모든 맵: " + p.maps.joinToString(", ") { "#${it.id} \"${it.name}\" ${it.w}x${it.h}" } + "\n")
        val sw = p.switches()
        sb.append("쓰인 스위치: " + (if (sw.isEmpty()) "없음" else sw.joinToString(", ")))
        return sb.toString()
    }
}

class ToolResult(val text: String, val summary: String, val image: String? = null, val error: Boolean = false, val mapId: Int? = null)

object Tools {
    val defs: JSONArray by lazy { JSONArray(DEFS) }

    fun run(doc: Doc, name: String, a: JSONObject): ToolResult = try { exec(doc, name, a) } catch (e: Exception) {
        ToolResult("오류: ${e.message}", "실패: $name", error = true)
    }

    private fun mapOf(doc: Doc, a: JSONObject): GameMap =
        doc.p.map(a.optInt("map_id", -1)) ?: throw IllegalArgumentException("map_id ${a.opt("map_id")} 맵이 없어요")

    private fun tileOf(s: String): Int {
        val t = s.trim()
        if (t.length == 1) Tiles.byChar(t[0])?.let { return it.id }
        Tiles.list.firstOrNull { it.name == t }?.let { return it.id }
        t.toIntOrNull()?.let { if (it in Tiles.list.indices) return it }
        throw IllegalArgumentException("모르는 타일: $s")
    }

    private fun pages(v: Any?): MutableList<EventPage>? {
        val a = when (v) {
            is JSONArray -> v
            is String -> runCatching { JSONArray(v.trim()) }.getOrElse { throw IllegalArgumentException("pages JSON을 읽지 못했어요: ${it.message}") }
            else -> null
        }
        if (a == null || a.length() == 0) return null
        return MutableList(a.length()) { EventPage.from(a.getJSONObject(it)) }
    }

    private fun exec(doc: Doc, name: String, a: JSONObject): ToolResult {
        val p = doc.p
        when (name) {
            "view_map" -> { val m = mapOf(doc, a); return ToolResult(MapText.describe(p, m.id), "맵 보기: ${m.name}", mapId = m.id) }
            "screenshot" -> { val m = mapOf(doc, a); return ToolResult("맵 #${m.id} 그림 (한 칸 32px)", "맵 사진 보기: ${m.name}", image = Shot.base64(p, m), mapId = m.id) }
            "get_event" -> {
                val m = mapOf(doc, a); val e = m.event(a.getInt("event_id")) ?: throw IllegalArgumentException("이벤트가 없어요")
                return ToolResult(e.toJson().toString(1), "이벤트 보기: ${e.name}", mapId = m.id)
            }
            "paint" -> {
                val m = mapOf(doc, a); val x0 = a.getInt("x"); val y0 = a.getInt("y"); val rows = a.getJSONArray("rows")
                var n = 0; val bad = sortedSetOf<Char>()
                for (r in 0 until rows.length()) {
                    val line = rows.getString(r)
                    for ((c, ch) in line.withIndex()) {
                        if (ch == '?' || ch == ' ') continue
                        val t = Tiles.byChar(ch)
                        if (t == null) { bad.add(ch); continue }
                        val x = x0 + c; val y = y0 + r
                        if (!m.inside(x, y)) continue
                        m.paint(x, y, t.id); n++
                    }
                }
                doc.changed()
                val warn = if (bad.isNotEmpty()) " (모르는 글자 무시: ${bad.joinToString("")})" else ""
                return ToolResult("${n}칸 칠함$warn", "타일 ${n}칸 칠함", mapId = m.id)
            }
            "fill_rect" -> {
                val m = mapOf(doc, a); val x0 = a.getInt("x"); val y0 = a.getInt("y")
                val w = a.getInt("width"); val h = a.getInt("height"); val ts = a.getString("tile")
                val clear = ts == "none"; val t = if (clear) 0 else tileOf(ts)
                var n = 0
                for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (m.inside(x, y)) {
                    if (clear) m.upper[m.i(x, y)] = 0 else m.paint(x, y, t); n++
                }
                doc.changed()
                return ToolResult("${n}칸 처리", if (clear) "위층 ${n}칸 지움" else "${Tiles.get(t).name} ${n}칸 채움", mapId = m.id)
            }
            "add_event" -> {
                val m = mapOf(doc, a); val x = a.getInt("x"); val y = a.getInt("y")
                if (!m.inside(x, y)) throw IllegalArgumentException("맵 밖 좌표예요")
                m.eventAt(x, y)?.let { throw IllegalArgumentException("(${x},${y})에 이미 이벤트 #${it.id}이 있어요") }
                val e = GameEvent(m.nextEventId(), a.optString("name", "이벤트"), x, y)
                pages(a.opt("pages"))?.let { e.pages = it }
                m.events.add(e); doc.changed()
                return ToolResult("이벤트 #${e.id} 만듦", "이벤트 추가: ${e.name}", mapId = m.id)
            }
            "update_event" -> {
                val m = mapOf(doc, a); val e = m.event(a.getInt("event_id")) ?: throw IllegalArgumentException("이벤트가 없어요")
                if (a.has("name")) e.name = a.getString("name")
                if (a.has("x") || a.has("y")) {
                    val nx = a.optInt("x", e.x); val ny = a.optInt("y", e.y)
                    if (!m.inside(nx, ny)) throw IllegalArgumentException("맵 밖 좌표예요")
                    m.eventAt(nx, ny)?.let { if (it !== e) throw IllegalArgumentException("그 자리에 이벤트 #${it.id}이 있어요") }
                    e.x = nx; e.y = ny
                }
                pages(a.opt("pages"))?.let { e.pages = it }
                doc.changed()
                return ToolResult("이벤트 #${e.id} 수정함", "이벤트 수정: ${e.name}", mapId = m.id)
            }
            "delete_event" -> {
                val m = mapOf(doc, a); val e = m.event(a.getInt("event_id")) ?: throw IllegalArgumentException("이벤트가 없어요")
                m.events.remove(e); doc.changed()
                return ToolResult("삭제함", "이벤트 삭제: ${e.name}", mapId = m.id)
            }
            "create_map" -> {
                val w = a.getInt("width").coerceIn(5, MAX_MAP); val h = a.getInt("height").coerceIn(5, MAX_MAP)
                val m = GameMap(p.nextMapId(), a.optString("name", "새 맵"), w, h)
                val fill = tileOf(a.optString("fill", "."))
                for (k in m.ground.indices) m.ground[k] = if (Tiles.get(fill).upper) 1 else fill
                p.maps.add(m); doc.changed()
                return ToolResult("맵 #${m.id} 만듦 (${w}x${h})", "새 맵: ${m.name}", mapId = m.id)
            }
            "edit_map" -> {
                val m = mapOf(doc, a)
                if (a.has("name")) m.name = a.getString("name")
                if (a.has("width") || a.has("height")) m.resize(a.optInt("width", m.w).coerceIn(5, MAX_MAP), a.optInt("height", m.h).coerceIn(5, MAX_MAP))
                doc.changed()
                return ToolResult("맵 #${m.id}: ${m.name} ${m.w}x${m.h}", "맵 설정: ${m.name}", mapId = m.id)
            }
            "set_start" -> {
                val m = mapOf(doc, a); val x = a.getInt("x"); val y = a.getInt("y")
                if (!m.inside(x, y)) throw IllegalArgumentException("맵 밖 좌표예요")
                p.startMap = m.id; p.startX = x; p.startY = y; doc.changed()
                return ToolResult("시작 위치 바꿈", "시작 위치: ${m.name} ($x,$y)", mapId = m.id)
            }
        }
        throw IllegalArgumentException("모르는 도구: $name")
    }

    private const val DEFS = """[
{"name":"view_map","description":"맵을 글자 지도로 다시 본다(좌표 눈금, 이벤트 목록 포함). 편집 후 결과 확인에 써라.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"}},"required":["map_id"]}},
{"name":"screenshot","description":"맵을 실제 그림으로 본다(이벤트 그래픽, 시작 위치 포함). 분위기·모양을 눈으로 판단할 때 써라.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"}},"required":["map_id"]}},
{"name":"get_event","description":"이벤트 하나의 전체 내용(JSON: 페이지, 명령)을 본다. 수정 전에 꼭 확인.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"event_id":{"type":"integer"}},"required":["map_id","event_id"]}},
{"name":"paint","description":"(x,y)를 왼쪽 위로 하여 글자 줄들을 타일로 칠한다. 한 줄 = 한 행, 한 글자 = 한 칸. 범례 글자를 쓰고, '?'나 공백은 그대로 둔다. 바닥 타일은 그 칸의 위층을 지우고, 위층 타일은 바닥 위에 올린다. '@'와 'P'는 타일이 아니니 쓰지 마라.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"rows":{"type":"array","items":{"type":"string"}}},"required":["map_id","x","y","rows"]}},
{"name":"fill_rect","description":"사각형을 한 타일로 채운다. tile은 범례 글자 하나. tile을 \"none\"으로 하면 그 범위의 위층(나무, 가구 등)만 지운다.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"width":{"type":"integer"},"height":{"type":"integer"},"tile":{"type":"string"}},"required":["map_id","x","y","width","height","tile"]}},
{"name":"add_event","description":"이벤트(NPC, 상자, 문 이동 칸 등)를 만든다. pages를 주면 그 내용으로, 없으면 기본 마을 사람.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"name":{"type":"string"},"pages":{"type":"array","items":{"type":"object"}}},"required":["map_id","x","y","name"]}},
{"name":"update_event","description":"이벤트의 이름, 위치, 페이지를 바꾼다. pages를 주면 페이지 전체를 교체하니 get_event로 기존 내용을 먼저 보고 필요한 부분만 고쳐서 넣어라.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"event_id":{"type":"integer"},"name":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"},"pages":{"type":"array","items":{"type":"object"}}},"required":["map_id","event_id"]}},
{"name":"delete_event","description":"이벤트를 지운다.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"event_id":{"type":"integer"}},"required":["map_id","event_id"]}},
{"name":"create_map","description":"새 맵을 만든다(가로·세로 5~64). fill은 바닥 타일 글자.","input_schema":{"type":"object","properties":{"name":{"type":"string"},"width":{"type":"integer"},"height":{"type":"integer"},"fill":{"type":"string"}},"required":["name","width","height"]}},
{"name":"edit_map","description":"맵 이름이나 크기를 바꾼다. 줄이면 바깥 칸과 이벤트는 사라진다.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"name":{"type":"string"},"width":{"type":"integer"},"height":{"type":"integer"}},"required":["map_id"]}},
{"name":"set_start","description":"게임 시작 위치(주인공이 처음 서는 곳)를 정한다.","input_schema":{"type":"object","properties":{"map_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["map_id","x","y"]}}
]"""

    fun system(): String = """너는 모바일 게임 제작 앱 "Nami Maker"(쯔꾸르 같은 2D 탑다운 게임 제작 도구) 안의 AI 조수다.
사용자와 함께 대화·탐색 위주의 게임(어드벤처, 호러 쯔꾸르 같은)을 만든다. 전투는 아직 없다.
항상 한국어로, 상냥하고 짧게 답한다. 이모지는 거의 쓰지 않는다.

## 보는 법
사용자의 메시지마다 지금 보고 있는 맵의 글자 지도와 그림이 붙어 온다. 글자 지도는 한 글자가 한 칸이다.
좌표는 (x, y)이고 x는 왼쪽에서 오른쪽, y는 위에서 아래, 0부터 센다. 위쪽 눈금이 x, 왼쪽 숫자가 y다.
'@'는 이벤트, 'P'는 시작 위치(실제 타일은 그 아래에 있다).

## 타일 범례
${Tiles.legend()}
위층 타일(나무, 가구, 문 등)은 바닥 위에 올라간다. 위층이 있으면 지나갈 수 있는지는 위층이 정한다(벽 위의 문은 지나갈 수 있다).

## 이벤트
이벤트는 페이지 목록을 가진다. 게임은 조건을 만족하는 페이지 중 가장 뒤의 것을 쓴다.
페이지: {"condition_switch": "" 또는 스위치 이름, "graphic": 그래픽, "dir": "down|left|right|up", "trigger": "action(말 걸기·조사)|touch(밟으면)|auto(조건이 맞으면 자동, 맵마다 한 번)", "commands": [...]}
그래픽: ${Sprites.ids.joinToString(", ")} ("none"은 투명하고 지나갈 수 있다 — 문 이동 칸이나 숨은 이벤트에 써라. 그래픽이 있으면 지나갈 수 없다.)
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
문으로 다른 맵에 가려면 문 칸에 graphic "none", trigger "touch", transfer 명령 이벤트를 둔다. 도착 위치는 상대 맵의 문 바로 앞(문 위가 아닌 곳)으로 해서 바로 되돌아가지 않게 한다.

## 일하는 법
- 맵을 바꿀 땐 도구를 써서 직접 바꿔라. 말로만 설명하지 마라.
- 먼저 붙어 온 지도를 읽고 정확한 좌표를 계산한 뒤 칠해라. 건물은 paint로 여러 줄을 한 번에 그리는 게 좋다.
- 편집이 끝나면 view_map이나 screenshot으로 결과를 확인하고, 이상하면 고쳐라.
- 길이 막혀서 못 가는 곳이 생기지 않게, NPC가 문이나 길을 막지 않게 신경 써라.
- 사용자가 맵에 대해 물으면 지도와 그림을 보고 구체적으로 답해라.
- 다 하고 나면 무엇을 했는지 1~3문장으로 알려라. 사용자는 되돌리기 버튼 한 번으로 네 작업 전체를 취소할 수 있다."""
}

class ChatItem(val role: String, val text: String) // user, ai, tool, error

/** Talks to Claude with the map attached and lets it edit through tools. */
class AiChat(private val ctx: Context, private val doc: Doc) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
    var onFocusMap: (Int) -> Unit = {}
    private var messages = JSONArray()
    private val file = Store.chatFile(doc)

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                messages = o.optJSONArray("messages") ?: JSONArray()
                o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { items.add(ChatItem(it.getString("r"), it.getString("t"))) } }
            }
        }
    }

    private fun save() = runCatching {
        val a = JSONArray(); items.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        file.writeText(JSONObject().put("messages", messages).put("items", a).toString())
    }

    fun clear() { items.clear(); messages = JSONArray(); save() }

    suspend fun send(text: String, viewMapId: Int) {
        if (busy) return
        if (Api.current() == null) { items.add(ChatItem("error", "설정 › API에서 프로바이더와 모델을 먼저 추가해 주세요")); return }
        busy = true
        items.add(ChatItem("user", text))
        val mark = messages.length()
        val before = doc.snapshot()
        var edited = false
        try {
            val m = doc.p.map(viewMapId) ?: doc.p.maps[0]
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", "[지금 보고 있는 맵]\n" + MapText.describe(doc.p, m.id) + "\n\n[사용자]\n" + text))
                .put(image(Shot.base64(doc.p, m)))
            messages.put(JSONObject().put("role", "user").put("content", content))
            for (round in 0 until 40) {
                val res = Api.turn(Tools.system(), Tools.defs, outgoing())
                val blocks = res.blocks
                messages.put(JSONObject().put("role", "assistant").put("content", blocks).also { m -> res.raw?.let { m.put("gparts", it) } })
                val results = JSONArray()
                for (i in 0 until blocks.length()) {
                    val b = blocks.getJSONObject(i)
                    when (b.optString("type")) {
                        "text" -> b.optString("text").trim().takeIf { it.isNotEmpty() }?.let { items.add(ChatItem("ai", it)) }
                        "tool_use" -> {
                            val name = b.getString("name")
                            val r = Tools.run(doc, name, b.optJSONObject("input") ?: JSONObject())
                            if (!name.startsWith("view") && name != "screenshot" && name != "get_event" && !r.error) edited = true
                            r.mapId?.let { onFocusMap(it) }
                            items.add(ChatItem(if (r.error) "error" else "tool", r.summary))
                            val rc = JSONArray().put(JSONObject().put("type", "text").put("text", r.text))
                            r.image?.let { rc.put(image(it)) }
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id"))
                                .put("content", rc).put("is_error", r.error))
                        }
                    }
                }
                if (results.length() == 0 || !res.wantsTools) break
                messages.put(JSONObject().put("role", "user").put("content", results))
            }
        } catch (e: Exception) {
            while (messages.length() > mark) messages.remove(messages.length() - 1)
            items.add(ChatItem("error", "오류: " + (e.message ?: e.javaClass.simpleName)))
        }
        if (edited) doc.pushUndo(before)
        busy = false
        save()
    }

    private fun image(b64: String) = JSONObject().put("type", "image")
        .put("source", JSONObject().put("type", "base64").put("media_type", "image/png").put("data", b64))

    private fun isPlainUser(o: JSONObject): Boolean {
        if (o.optString("role") != "user") return false
        val c = o.optJSONArray("content") ?: return true
        return c.length() > 0 && c.getJSONObject(0).optString("type") != "tool_result"
    }

    /** History to send: last ~40 messages, starting at a real user turn, with old pictures replaced by a note. */
    private fun outgoing(): JSONArray {
        val n = messages.length()
        var start = maxOf(0, n - 40)
        while (start < n && !isPlainUser(messages.getJSONObject(start))) start++
        if (start >= n) start = 0
        var lastUser = start
        for (i in start until n) if (isPlainUser(messages.getJSONObject(i))) lastUser = i
        val out = JSONArray()
        for (i in start until n) {
            val o = messages.getJSONObject(i)
            out.put(if (i < lastUser) strip(JSONObject(o.toString())) else o)
        }
        return out
    }

    private fun strip(o: JSONObject): JSONObject {
        val c = o.optJSONArray("content") ?: return o
        fun fix(a: JSONArray): JSONArray {
            val r = JSONArray()
            for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                when (b.optString("type")) {
                    "image" -> r.put(JSONObject().put("type", "text").put("text", "[예전 그림 생략]"))
                    "tool_result" -> { b.optJSONArray("content")?.let { b.put("content", fix(it)) }; r.put(b) }
                    "text" -> {
                        val t = b.optString("text")
                        // old map dumps are long; keep only the user's words
                        val k = t.indexOf("\n\n[사용자]\n")
                        r.put(if (t.startsWith("[지금 보고 있는 맵]") && k > 0) JSONObject().put("type", "text").put("text", t.substring(k + 2)) else b)
                    }
                    else -> r.put(b)
                }
            }
            return r
        }
        o.put("content", fix(c))
        return o
    }
}
