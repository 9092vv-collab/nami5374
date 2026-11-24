package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The map as short JSON for the AI (Nami next 방식): ground/upper rows with one letter per cell
 * ([G] 풀 …), events by id·kind·position, the start, and the list of maps.
 */
object MapJson {
    private fun q(s: String) = JSONObject.quote(s)

    fun kind(e: GameEvent) = if ((e.pages.firstOrNull()?.graphic ?: "none") == "none") "point" else "npc"

    fun eventBrief(p: Project, e: GameEvent): String {
        val pg = e.pages.firstOrNull() ?: EventPage()
        val o = StringBuilder("{\"id\":${e.id},\"kind\":\"${kind(e)}\",\"name\":${q(e.name)},\"x\":${e.x},\"y\":${e.y}")
        if (kind(e) == "npc") o.append(",\"graphic\":\"${pg.graphic}\"")
        o.append(",\"run\":\"${pg.trigger}\"")
        if (e.pages.size > 1) o.append(",\"pages\":${e.pages.size}")
        val first = pg.commands.firstOrNull()?.summary(p)?.take(40)
        if (!first.isNullOrBlank()) o.append(",\"first\":${q(first)}")
        return o.append("}").toString()
    }

    fun map(p: Project, m: GameMap): String {
        val sb = StringBuilder()
        sb.append("{\"map\":${m.id},\"name\":${q(m.name)},\"w\":${m.w},\"h\":${m.h},\n")
        fun rows(key: String, upper: Boolean) {
            sb.append(" \"$key\":[\n")
            for (y in 0 until m.h) {
                val r = StringBuilder()
                for (x in 0 until m.w) {
                    val k = m.i(x, y)
                    r.append(if (upper) (if (m.upper[k] == 0) '.' else Tiles.get(m.upper[k]).ch) else Tiles.get(m.ground[k]).ch)
                }
                sb.append("  \"").append(r).append('"').append(if (y < m.h - 1) ",\n" else "\n")
            }
            sb.append(" ],\n")
        }
        rows("ground", false); rows("upper", true)
        sb.append(" \"events\":[")
        m.events.forEachIndexed { i, e -> sb.append(if (i == 0) "\n  " else ",\n  ").append(eventBrief(p, e)) }
        sb.append(if (m.events.isEmpty()) "]" else "\n ]").append("}")
        return sb.toString()
    }

    /** What goes in the "지금 맵" block. */
    fun describe(p: Project, mapId: Int): String {
        val m = p.map(mapId) ?: p.maps[0]
        val maps = p.maps.joinToString(",") { "{\"id\":${it.id},\"name\":${q(it.name)},\"w\":${it.w},\"h\":${it.h}}" }
        val sw = p.switches().joinToString(",") { q(it) }
        return map(p, m) + "\n" +
            "{\"start\":{\"map\":${p.startMap},\"x\":${p.startX},\"y\":${p.startY}},\"maps\":[$maps],\"switches\":[$sw]}"
    }

    /** Full event (pages + commands) for an "open" request. */
    fun eventFull(e: GameEvent): String = e.toJson().put("kind", kind(e)).toString()
}

class ChatItem(val role: String, val text: String) // user, ai, think, tool, warn, error

/** Turns the AI's JSON answer into changes (one undo step for the whole answer). */
object Edits {
    class Result(val done: MutableList<String> = mutableListOf(), val failed: MutableList<String> = mutableListOf(), var focus: Int? = null)

    private fun tile(c: Char, upper: Boolean): Int? {
        if (upper && c == '.') return 0
        val t = Tiles.byChar(c) ?: return null
        return if (t.upper == upper) t.id else null
    }

    private fun page(o: JSONObject, kind: String, base: EventPage?): EventPage {
        val pg = base ?: EventPage().also { if (kind == "point") { it.graphic = "none"; it.trigger = "touch" } }
        if (o.has("graphic")) pg.graphic = o.optString("graphic").let { if (Sprites.ids.contains(it)) it else pg.graphic }
        if (kind == "point" && !o.has("graphic")) pg.graphic = "none"
        if (o.has("dir")) pg.dir = DIRS.indexOf(o.optString("dir")).coerceAtLeast(0)
        (o.optString("run").ifBlank { o.optString("trigger") }).takeIf { it in TRIGGERS }?.let { pg.trigger = it }
        if (o.has("condition_switch")) pg.condSwitch = o.optString("condition_switch")
        o.optJSONArray("commands")?.let { pg.commands = Cmd.list(it) }
        return pg
    }

    fun apply(doc: Doc, viewMap: Int, edits: JSONArray): Result {
        val p = doc.p
        val r = Result()
        val refs = mutableMapOf<String, Int>()
        fun mapOf(o: JSONObject): GameMap? {
            val v = o.opt("map") ?: return p.map(viewMap)
            if (v is Number) return p.map(v.toInt())
            val s = v.toString()
            refs[s]?.let { return p.map(it) }
            s.toIntOrNull()?.let { return p.map(it) }
            return p.maps.firstOrNull { it.name == s }
        }
        for (i in 0 until edits.length()) {
            val o = edits.optJSONObject(i) ?: continue
            val op = o.optString("op")
            try {
                if (op == "add_map") {
                    val w = o.optInt("w", o.optInt("width", 20)).coerceIn(5, MAX_MAP); val h = o.optInt("h", o.optInt("height", 15)).coerceIn(5, MAX_MAP)
                    val m = GameMap(p.nextMapId(), o.optString("name", "새 맵"), w, h)
                    val fill = o.optString("fill", "G").firstOrNull()?.let { tile(it, false) } ?: 1
                    for (k in m.ground.indices) m.ground[k] = fill
                    p.maps.add(m)
                    o.optString("ref").takeIf { it.isNotBlank() }?.let { refs[it] = m.id }
                    refs[m.name] = m.id
                    r.done.add("새 맵 #${m.id} ${m.name} (${w}×${h})"); r.focus = m.id
                    continue
                }
                val m = mapOf(o) ?: throw IllegalArgumentException("맵 ${o.opt("map")}이(가) 없어요")
                when (op) {
                    "paint" -> {
                        val upper = o.optString("layer") == "upper"
                        val x0 = o.getInt("x"); val y0 = o.getInt("y"); val rows = o.getJSONArray("rows")
                        var n = 0; val bad = sortedSetOf<Char>()
                        for (ry in 0 until rows.length()) for ((cx, ch) in rows.getString(ry).withIndex()) {
                            if (ch == '?' || ch == ' ') continue
                            val t = tile(ch, upper); if (t == null) { bad.add(ch); continue }
                            val x = x0 + cx; val y = y0 + ry
                            if (!m.inside(x, y)) continue
                            if (upper) m.setUpper(x, y, t) else m.setGround(x, y, t); n++
                        }
                        r.done.add("${m.name} ${if (upper) "위층" else "바닥"} ${n}칸 칠함")
                        if (bad.isNotEmpty()) r.failed.add("${if (upper) "위층" else "바닥"}에 쓸 수 없는 기호 무시: ${bad.joinToString("")}")
                    }
                    "fill" -> {
                        val upper = o.optString("layer") == "upper"
                        val c = o.optString("tile").firstOrNull() ?: throw IllegalArgumentException("tile이 없어요")
                        val t = tile(c, upper) ?: throw IllegalArgumentException("${if (upper) "위층" else "바닥"} 기호가 아니에요: $c")
                        val x0 = o.getInt("x"); val y0 = o.getInt("y"); var n = 0
                        for (y in y0 until y0 + o.optInt("h", 1)) for (x in x0 until x0 + o.optInt("w", 1)) if (m.inside(x, y)) {
                            if (upper) m.setUpper(x, y, t) else m.setGround(x, y, t); n++
                        }
                        r.done.add("${m.name} ${if (upper) "위층" else "바닥"} [$c] ${n}칸 채움")
                    }
                    "add_event" -> {
                        val x = o.getInt("x"); val y = o.getInt("y")
                        if (!m.inside(x, y)) throw IllegalArgumentException("(${x},${y})은 맵 밖이에요")
                        m.eventAt(x, y)?.let { throw IllegalArgumentException("(${x},${y})에 이미 이벤트 #${it.id}이 있어요") }
                        val kind = o.optString("kind", "npc")
                        val e = GameEvent(m.nextEventId(), o.optString("name", if (kind == "point") "이벤트 지점" else "이벤트"), x, y)
                        val pages = o.optJSONArray("pages")
                        e.pages = if (pages != null && pages.length() > 0) MutableList(pages.length()) { page(pages.getJSONObject(it), kind, null) }
                            else mutableListOf(page(o, kind, null))
                        m.events.add(e)
                        r.done.add("${if (kind == "point") "이벤트 지점" else "이벤트"} 추가: ${e.name} #${e.id}")
                    }
                    "edit_event" -> {
                        val e = m.event(o.getInt("id")) ?: throw IllegalArgumentException("${m.name}에 이벤트 #${o.opt("id")}이 없어요")
                        if (o.has("name")) e.name = o.getString("name")
                        if (o.has("x") || o.has("y")) {
                            val nx = o.optInt("x", e.x); val ny = o.optInt("y", e.y)
                            if (!m.inside(nx, ny)) throw IllegalArgumentException("(${nx},${ny})은 맵 밖이에요")
                            m.eventAt(nx, ny)?.let { if (it !== e) throw IllegalArgumentException("(${nx},${ny})에 이벤트 #${it.id}이 있어요") }
                            e.x = nx; e.y = ny
                        }
                        val kind = o.optString("kind", MapJson.kind(e))
                        val pages = o.optJSONArray("pages")
                        if (pages != null && pages.length() > 0) e.pages = MutableList(pages.length()) { page(pages.getJSONObject(it), kind, null) }
                        else if (listOf("graphic", "dir", "run", "trigger", "condition_switch", "commands", "kind").any { o.has(it) }) page(o, kind, e.pages[0])
                        r.done.add("이벤트 수정: ${e.name} #${e.id}")
                    }
                    "delete_event" -> {
                        val e = m.event(o.getInt("id")) ?: throw IllegalArgumentException("${m.name}에 이벤트 #${o.opt("id")}이 없어요")
                        m.events.remove(e); r.done.add("이벤트 삭제: ${e.name}")
                    }
                    "edit_map" -> {
                        if (o.has("name")) m.name = o.getString("name")
                        if (o.has("w") || o.has("h")) m.resize(o.optInt("w", m.w).coerceIn(5, MAX_MAP), o.optInt("h", m.h).coerceIn(5, MAX_MAP))
                        r.done.add("맵 설정: ${m.name} ${m.w}×${m.h}")
                    }
                    "set_start" -> {
                        val x = o.getInt("x"); val y = o.getInt("y")
                        if (!m.inside(x, y)) throw IllegalArgumentException("(${x},${y})은 맵 밖이에요")
                        p.startMap = m.id; p.startX = x; p.startY = y; r.done.add("시작 위치: ${m.name} ($x,$y)")
                    }
                    else -> throw IllegalArgumentException("모르는 op: $op")
                }
                if (r.focus == null) r.focus = m.id
            } catch (e: Exception) {
                r.failed.add("$op: ${e.message}")
            }
        }
        return r
    }

    /** Problems the AI should hear about next time (blocked start, doors to nowhere, …). */
    fun check(p: Project): List<String> {
        val out = mutableListOf<String>()
        fun blocked(m: GameMap, x: Int, y: Int): Boolean {
            if (!m.inside(x, y)) return true
            val k = m.i(x, y)
            return !Tiles.passable(m.ground[k], m.upper[k]) || m.events.any { it.x == x && it.y == y && MapJson.kind(it) == "npc" }
        }
        val sm = p.map(p.startMap)
        if (sm == null) out.add("시작 맵이 없어요")
        else if (blocked(sm, p.startX, p.startY)) out.add("시작 위치 ${sm.name} (${p.startX},${p.startY})가 막힌 칸이에요")
        for (m in p.maps) for (e in m.events) for (pg in e.pages) {
            fun walk(l: List<Cmd>) {
                for (c in l) {
                    if (c.type == "transfer") {
                        val t = p.map(c.map)
                        if (t == null) out.add("${m.name} #${e.id} ${e.name}: 이동할 맵 ${c.map}이 없어요")
                        else if (blocked(t, c.x, c.y)) out.add("${m.name} #${e.id} ${e.name}: 도착 칸 ${t.name} (${c.x},${c.y})가 막혀 있어요")
                        else if (t.events.any { it.x == c.x && it.y == c.y && it.pages.any { pp -> pp.trigger == "touch" } }) out.add("${m.name} #${e.id} ${e.name}: 도착 칸이 다른 이동 지점 위예요(바로 되돌아가요)")
                    }
                    c.branches.forEach { walk(it) }; walk(c.then); walk(c.other)
                }
            }
            walk(pg.commands)
        }
        for (m in p.maps) for (e in m.events) if (MapJson.kind(e) == "point" && !Tiles.passable(m.ground[m.i(e.x, e.y)], m.upper[m.i(e.x, e.y)]))
            out.add("${m.name} #${e.id} ${e.name}: 이벤트 지점이 막힌 칸 위라 밟을 수 없어요")
        return out.take(12)
    }
}

/** Talks to the AI with the map JSON attached; the AI answers with JSON edits (Nami next 작업실 방식). */
class AiChat(private val ctx: Context, private val doc: Doc) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
    /** Streaming reply/thinking of the current request (empty when "한 번에 불러오기"). */
    var live by mutableStateOf("")
    var liveThink by mutableStateOf("")
    var onFocusMap: (Int) -> Unit = {}
    private var messages = JSONArray()
    private var note = ""   // problems found after the last edits, sent with the next message
    private val file = Store.chatFile(doc)

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                // version 2 = JSON edits; older chats used tool calls, which this history can't replay
                if (o.optInt("v") >= 2) { messages = o.optJSONArray("messages") ?: JSONArray(); note = o.optString("note") }
                o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { items.add(ChatItem(it.getString("r"), it.getString("t"))) } }
            }
        }
    }

    private fun save() = runCatching {
        val a = JSONArray(); items.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        file.writeText(JSONObject().put("v", 2).put("messages", messages).put("items", a).put("note", note).toString())
    }

    fun clear() { items.clear(); messages = JSONArray(); note = ""; save() }

    /** The "reply" so far, pulled out of a JSON answer that is still streaming. */
    private fun replySoFar(raw: String): String {
        val k = raw.indexOf("\"reply\""); if (k < 0) return ""
        val colon = raw.indexOf(':', k); if (colon < 0) return ""
        var i = raw.indexOf('"', colon + 1); if (i < 0) return ""
        val sb = StringBuilder(); i++
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) { val n = raw[i + 1]; sb.append(when (n) { 'n' -> '\n'; 't' -> ' '; else -> n }); i += 2; continue }
            if (c == '"') break
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** The JSON object in the answer (code fences and stray words around it are ignored). */
    private fun parse(raw: String): JSONObject? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull()
    }

    private fun opens(o: JSONObject, viewMap: Int): List<Pair<Int, Int>> {
        val v = o.opt("open") ?: return emptyList()
        val a = when (v) { is JSONArray -> v; is JSONObject -> v.optJSONArray("events") ?: JSONArray(); else -> JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            when (val x = a.opt(i)) {
                is Number -> viewMap to x.toInt()
                is JSONObject -> x.optInt("map", viewMap) to x.optInt("id", -1)
                else -> x?.toString()?.toIntOrNull()?.let { viewMap to it }
            }
        }
    }

    suspend fun send(text: String, viewMapId: Int) {
        if (busy) return
        if (Api.current() == null) { items.add(ChatItem("error", "설정 › API에서 프로바이더와 모델을 먼저 추가해 주세요")); return }
        busy = true
        items.add(ChatItem("user", text))
        val mark = messages.length()
        val keepNote = note
        val before = doc.snapshot()
        var edited = false
        try {
            val userText = if (note.isNotBlank()) "[지난 작업 뒤 앱이 찾은 문제]\n$note\n\n$text" else text
            val built = Prompts.build(doc.p, viewMapId, userText)
            val system = built.system.ifBlank { "너는 게임 제작 앱 Nami Maker의 AI 조수다. JSON으로 답하라." }
            messages.put(JSONObject().put("role", "user").put("content", built.content).put("mapIdx", built.mapIdx))
            note = ""
            var opened = 0
            while (true) {
                live = ""; liveThink = ""
                val raw = StringBuilder()
                val res = Api.turn(system, null, outgoing(), Api.Live(
                    onText = { t -> raw.append(t); live = replySoFar(raw.toString()) },
                    onThink = { t -> liveThink += t }), json = true)
                live = ""; liveThink = ""
                if (res.reasoning.isNotBlank()) items.add(ChatItem("think", res.reasoning.trim()))
                val answer = (0 until res.blocks.length()).map { res.blocks.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                messages.put(JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", answer.ifBlank { "{}" })))
                    .also { m -> res.raw?.let { m.put("gparts", it) } })
                val o = parse(answer)
                if (o == null) { if (answer.isNotBlank()) items.add(ChatItem("ai", answer.trim())); break }
                val want = opens(o, viewMapId)
                if (want.isNotEmpty() && opened < 2 && (o.optJSONArray("edits")?.length() ?: 0) == 0) {
                    opened++
                    val sb = StringBuilder("[요청한 이벤트 내용]\n")
                    want.forEach { (mid, eid) ->
                        val e = doc.p.map(mid)?.event(eid)
                        sb.append(if (e == null) "맵 $mid 이벤트 $eid: 없음\n" else "맵 $mid: " + MapJson.eventFull(e) + "\n")
                    }
                    sb.append("이제 이 내용을 보고 원래 요청대로 답하라.")
                    items.add(ChatItem("tool", "이벤트 ${want.size}개 열어봄"))
                    messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", sb.toString()))))
                    continue
                }
                o.optString("reply").trim().takeIf { it.isNotEmpty() }?.let { items.add(ChatItem("ai", it)) }
                val edits = o.optJSONArray("edits")
                if (edits != null && edits.length() > 0) {
                    val r = Edits.apply(doc, viewMapId, edits)
                    if (r.done.isNotEmpty()) { edited = true; doc.changed() }
                    r.done.forEach { items.add(ChatItem("tool", it)) }
                    r.failed.forEach { items.add(ChatItem("warn", it)) }
                    r.focus?.let { onFocusMap(it) }
                    val problems = r.failed.map { "적용 못 함: $it" } + Edits.check(doc.p)
                    if (problems.isNotEmpty()) {
                        note = problems.joinToString("\n")
                        items.add(ChatItem("warn", "확인할 것: " + problems.joinToString(" / ")))
                    }
                }
                break
            }
        } catch (e: Exception) {
            live = ""; liveThink = ""
            note = keepNote
            while (messages.length() > mark) messages.remove(messages.length() - 1)
            items.add(ChatItem("error", "오류: " + (e.message ?: e.javaClass.simpleName)))
        }
        if (edited) doc.pushUndo(before)
        busy = false
        save()
    }

    private fun isPlainUser(o: JSONObject) = o.optString("role") == "user" && o.has("mapIdx")

    /** History to send: last ~30 messages, starting at a real user turn; old maps/pictures shrunk to a note. */
    private fun outgoing(): JSONArray {
        val n = messages.length()
        var start = maxOf(0, n - 30)
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
        val idx = o.optJSONArray("mapIdx")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()
        val out = JSONArray()
        for (i in 0 until c.length()) {
            val b = c.getJSONObject(i)
            out.put(when {
                i in idx -> JSONObject().put("type", "text").put("text", "[예전 맵 생략]")
                b.optString("type") == "image" -> JSONObject().put("type", "text").put("text", "[예전 그림 생략]")
                else -> b
            })
        }
        return o.put("content", out)
    }
}
