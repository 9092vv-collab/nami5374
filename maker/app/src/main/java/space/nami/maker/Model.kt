package space.nami.maker

import org.json.JSONArray
import org.json.JSONObject

// ---------- event commands ----------
// One class for every command type; only the fields of its type are used.
class Cmd(var type: String) {
    var speaker = ""
    var text = ""
    var options = mutableListOf<String>()
    var branches = mutableListOf<MutableList<Cmd>>()
    var name = ""          // switch / item name
    var on = true          // switch value
    var cond = "switch"    // if: "switch" or "item"
    var then = mutableListOf<Cmd>()
    var other = mutableListOf<Cmd>()
    var map = 0
    var x = 0
    var y = 0
    var ms = 500
    var amount = 1
    var out = true         // fade out (true) or in (false)

    fun toJson(): JSONObject {
        val o = JSONObject().put("type", type)
        when (type) {
            "text" -> o.put("speaker", speaker).put("text", text)
            "choice" -> {
                o.put("options", JSONArray(options))
                o.put("branches", JSONArray().also { a -> branches.forEach { a.put(arr(it)) } })
            }
            "switch" -> o.put("name", name).put("on", on)
            "if" -> {
                o.put("cond", cond).put("name", name)
                if (cond == "switch") o.put("on", on)
                o.put("then", arr(then)).put("else", arr(other))
            }
            "transfer" -> o.put("map", map).put("x", x).put("y", y)
            "wait" -> o.put("ms", ms)
            "fade" -> o.put("out", out)
            "item" -> o.put("name", name).put("amount", amount)
        }
        return o
    }

    fun copy(): Cmd = from(toJson())

    companion object {
        val TYPES = listOf("text", "choice", "switch", "if", "transfer", "item", "wait", "fade", "end")

        fun label(type: String) = when (type) {
            "text" -> "대사"
            "choice" -> "선택지"
            "switch" -> "스위치"
            "if" -> "조건 분기"
            "transfer" -> "장소 이동"
            "item" -> "아이템"
            "wait" -> "기다리기"
            "fade" -> "화면 어둡게/밝게"
            "end" -> "이벤트 끝"
            else -> type
        }

        fun make(type: String): Cmd = Cmd(type).also {
            if (type == "choice") {
                it.options = mutableListOf("네", "아니요")
                it.branches = mutableListOf(mutableListOf(), mutableListOf())
            }
        }

        fun from(o: JSONObject): Cmd {
            val c = Cmd(o.optString("type", "text"))
            c.speaker = o.optString("speaker", "")
            c.text = o.optString("text", "")
            o.optJSONArray("options")?.let { a -> c.options = MutableList(a.length()) { a.optString(it) } }
            val br = o.optJSONArray("branches")
            c.branches = MutableList(c.options.size) { i -> list(br?.optJSONArray(i)) }
            c.name = o.optString("name", o.optString("switch", o.optString("item", "")))
            c.on = if (o.has("on")) o.optBoolean("on", true) else o.optBoolean("value", true)
            c.cond = o.optString("cond", if (o.has("item") && !o.has("switch")) "item" else "switch")
            c.then = list(o.optJSONArray("then"))
            c.other = list(o.optJSONArray("else"))
            c.map = o.optInt("map", 0)
            c.x = o.optInt("x", 0)
            c.y = o.optInt("y", 0)
            c.ms = o.optInt("ms", 500)
            c.amount = o.optInt("amount", 1)
            c.out = o.optBoolean("out", true)
            return c
        }

        fun list(a: JSONArray?): MutableList<Cmd> {
            if (a == null) return mutableListOf()
            return MutableList(a.length()) { from(a.getJSONObject(it)) }
        }

        fun arr(l: List<Cmd>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }
    }

    /** One-line Korean summary (editor rows and AI listings). */
    fun summary(p: Project?): String = when (type) {
        "text" -> (if (speaker.isNotBlank()) "$speaker: " else "") + text.replace('\n', ' ')
        "choice" -> "선택지: " + options.joinToString(" / ")
        "switch" -> "스위치 [$name] = ${if (on) "켜기" else "끄기"}"
        "if" -> if (cond == "item") "만약 아이템 [$name] 있으면" else "만약 스위치 [$name] ${if (on) "켜짐" else "꺼짐"}"
        "transfer" -> "이동: ${p?.map(map)?.name ?: "맵 $map"} ($x, $y)"
        "item" -> "아이템 [$name] ${if (amount >= 0) "+$amount" else "$amount"}"
        "wait" -> "기다리기 ${ms}ms"
        "fade" -> if (out) "화면 어둡게" else "화면 밝게"
        "end" -> "이벤트 끝"
        else -> type
    }
}

// ---------- events ----------
val DIRS = listOf("down", "left", "right", "up")
val TRIGGERS = listOf("action", "touch", "auto")
fun triggerLabel(t: String) = when (t) { "touch" -> "밟으면"; "auto" -> "자동"; else -> "말 걸기" }
fun dirLabel(d: Int) = when (d) { 1 -> "왼쪽"; 2 -> "오른쪽"; 3 -> "위"; else -> "아래" }

class EventPage {
    var condSwitch = ""
    var graphic = "villager"
    var dir = 0
    var trigger = "action"
    var commands = mutableListOf<Cmd>()

    fun toJson(): JSONObject = JSONObject()
        .put("condition_switch", condSwitch)
        .put("graphic", graphic)
        .put("dir", DIRS[dir.coerceIn(0, 3)])
        .put("trigger", trigger)
        .put("commands", Cmd.arr(commands))

    companion object {
        fun from(o: JSONObject) = EventPage().also {
            it.condSwitch = o.optString("condition_switch", "")
            it.graphic = o.optString("graphic", "villager").let { g -> if (Sprites.ids.contains(g)) g else "villager" }
            it.dir = DIRS.indexOf(o.optString("dir", "down")).coerceAtLeast(0)
            it.trigger = o.optString("trigger", "action").let { t -> if (TRIGGERS.contains(t)) t else "action" }
            it.commands = Cmd.list(o.optJSONArray("commands"))
        }
    }
}

class GameEvent(var id: Int, var name: String, var x: Int, var y: Int) {
    var pages = mutableListOf(EventPage())

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("x", x).put("y", y)
        .put("pages", JSONArray().also { a -> pages.forEach { a.put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject) = GameEvent(o.getInt("id"), o.optString("name", "이벤트"), o.optInt("x"), o.optInt("y")).also { e ->
            val a = o.optJSONArray("pages")
            if (a != null && a.length() > 0) e.pages = MutableList(a.length()) { EventPage.from(a.getJSONObject(it)) }
        }
    }
}

// ---------- maps ----------
const val MAX_MAP = 64

class GameMap(var id: Int, var name: String, var w: Int, var h: Int) {
    var ground = IntArray(w * h) { 1 }
    var upper = IntArray(w * h)
    var events = mutableListOf<GameEvent>()

    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h
    fun i(x: Int, y: Int) = y * w + x
    fun eventAt(x: Int, y: Int) = events.firstOrNull { it.x == x && it.y == y }
    fun event(id: Int) = events.firstOrNull { it.id == id }
    fun nextEventId() = (events.maxOfOrNull { it.id } ?: 0) + 1

    /** Paints what the tile is: ground tiles replace the floor (and clear anything on top), upper tiles go on top. */
    fun paint(x: Int, y: Int, tile: Int) {
        if (!inside(x, y)) return
        val k = i(x, y)
        if (Tiles.get(tile).upper) upper[k] = tile else { ground[k] = tile; upper[k] = 0 }
    }

    fun resize(nw: Int, nh: Int, fill: Int = 1) {
        val g = IntArray(nw * nh) { fill }
        val u = IntArray(nw * nh)
        for (y in 0 until minOf(h, nh)) for (x in 0 until minOf(w, nw)) {
            g[y * nw + x] = ground[i(x, y)]; u[y * nw + x] = upper[i(x, y)]
        }
        w = nw; h = nh; ground = g; upper = u
        events.removeAll { !inside(it.x, it.y) }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("width", w).put("height", h)
        .put("ground", JSONArray(ground.toList())).put("upper", JSONArray(upper.toList()))
        .put("events", JSONArray().also { a -> events.forEach { a.put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): GameMap {
            val m = GameMap(o.getInt("id"), o.optString("name", "맵"), o.getInt("width"), o.getInt("height"))
            o.optJSONArray("ground")?.let { a -> for (k in 0 until minOf(a.length(), m.ground.size)) m.ground[k] = a.optInt(k) }
            o.optJSONArray("upper")?.let { a -> for (k in 0 until minOf(a.length(), m.upper.size)) m.upper[k] = a.optInt(k) }
            o.optJSONArray("events")?.let { a -> m.events = MutableList(a.length()) { GameEvent.from(a.getJSONObject(it)) } }
            return m
        }
    }
}

// ---------- project ----------
class Project(var name: String) {
    var maps = mutableListOf<GameMap>()
    var startMap = 1
    var startX = 1
    var startY = 1

    fun map(id: Int) = maps.firstOrNull { it.id == id }
    fun nextMapId() = (maps.maxOfOrNull { it.id } ?: 0) + 1

    /** Every switch name used anywhere (for pickers and the AI). */
    fun switches(): List<String> {
        val s = sortedSetOf<String>()
        fun walk(l: List<Cmd>) { l.forEach { c ->
            if (c.type == "switch" || (c.type == "if" && c.cond == "switch")) if (c.name.isNotBlank()) s.add(c.name)
            c.branches.forEach { walk(it) }; walk(c.then); walk(c.other)
        } }
        maps.forEach { m -> m.events.forEach { e -> e.pages.forEach { pg -> if (pg.condSwitch.isNotBlank()) s.add(pg.condSwitch); walk(pg.commands) } } }
        return s.toList()
    }

    fun toJson(): JSONObject = JSONObject().put("name", name)
        .put("start_map", startMap).put("start_x", startX).put("start_y", startY)
        .put("maps", JSONArray().also { a -> maps.forEach { a.put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): Project {
            val p = Project(o.optString("name", "새 게임"))
            p.startMap = o.optInt("start_map", 1)
            p.startX = o.optInt("start_x", 1)
            p.startY = o.optInt("start_y", 1)
            o.optJSONArray("maps")?.let { a -> p.maps = MutableList(a.length()) { GameMap.from(a.getJSONObject(it)) } }
            if (p.maps.isEmpty()) p.maps.add(GameMap(1, "맵 1", 20, 15))
            if (p.map(p.startMap) == null) p.startMap = p.maps[0].id
            return p
        }

        /** A small starter village so a new game isn't empty. */
        fun sample(name: String): Project {
            val p = Project(name)
            val m = GameMap(1, "시작 마을", 20, 15)
            for (y in 0 until m.h) for (x in 0 until m.w) {
                if (x == 0 || y == 0 || x == m.w - 1 || y == m.h - 1) m.paint(x, y, 13) // trees around
            }
            for (x in 1 until m.w - 1) m.paint(x, 9, 2) // a path
            for (y in 3..8) m.paint(10, y, 2)
            // a small house
            for (y in 3..5) for (x in 7..12) m.paint(x, y, if (y == 3) 11 else 9)
            m.paint(9, 5, 10); m.paint(11, 5, 10)
            m.paint(10, 5, 9); m.paint(10, 5, 16)
            m.paint(3, 11, 14); m.paint(4, 12, 14); m.paint(16, 11, 14)
            m.paint(15, 3, 15); m.paint(16, 4, 15)
            val e = GameEvent(1, "촌장", 12, 8)
            e.pages[0].graphic = "elder"
            e.pages[0].commands = mutableListOf(
                Cmd("text").also { it.speaker = "촌장"; it.text = "어서 오게, 여행자여." },
                Cmd("text").also { it.speaker = "촌장"; it.text = "이 마을은 아직 텅 비어 있다네.\n자네가 채워주겠나?" },
            )
            m.events.add(e)
            p.maps.add(m)
            p.startMap = 1; p.startX = 5; p.startY = 9
            return p
        }
    }
}
