package space.nami.maker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val DX = intArrayOf(0, -1, 1, 0)
private val DY = intArrayOf(1, 0, 0, -1)
private fun opposite(d: Int) = when (d) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }

/** The running game: position, switches, items and the event interpreter. */
class Game(val p: Project) {
    var mapId by mutableIntStateOf(p.startMap)
    var px by mutableIntStateOf(p.startX)
    var py by mutableIntStateOf(p.startY)
    var fx = p.startX
    var fy = p.startY
    var dir by mutableIntStateOf(0)
    var steps = 0
    val prog = Animatable(1f)
    val fade = Animatable(0f)
    val switches = mutableStateMapOf<String, Boolean>()
    val items = mutableStateMapOf<String, Int>()
    val face = mutableStateMapOf<Int, Int>()
    /** 이동 경로 명령: 움직이는 중인 이벤트의 그려질 위치, 돌아선 방향. */
    val evPos = mutableStateMapOf<Int, Pair<Float, Float>>()
    val turned = mutableStateMapOf<Int, Int>()
    /** 목적지 화살표: 맵 id·이벤트 id (없으면 -1). 그 이벤트를 실행하면 저절로 꺼짐. */
    var goalMap by mutableStateOf(-1)
    var goalEv by mutableStateOf(-1)

    /** 화살표가 가리킬 칸: 목적지가 이 맵이면 그 이벤트, 다른 맵이면 그 맵으로 가는 이동 이벤트. */
    fun goalTile(): Pair<Float, Float>? {
        if (goalMap < 0) return null
        if (goalMap == mapId) {
            val e = map.events.firstOrNull { it.id == goalEv } ?: return null
            return evPos[e.id] ?: (e.x.toFloat() to e.y.toFloat())
        }
        fun leads(l: List<Cmd>): Boolean = l.any { c -> (c.type == "transfer" && c.map == goalMap) || leads(c.then) || leads(c.other) || c.branches.any { leads(it) } }
        val door = map.events.firstOrNull { e -> e.pages.any { leads(it.commands) } } ?: return null
        return door.x.toFloat() to door.y.toFloat()
    }
    var msg by mutableStateOf<Pair<String, String>?>(null)
    var choices by mutableStateOf<List<String>?>(null)
    var busy by mutableStateOf(false)
    private var msgWait: CompletableDeferred<Unit>? = null
    private var choiceWait: CompletableDeferred<Int>? = null
    private val autoDone = mutableSetOf<String>()

    val map: GameMap get() = p.map(mapId) ?: p.maps[0]

    fun page(e: GameEvent) = e.pages.lastOrNull { it.condSwitch.isBlank() || switches[it.condSwitch] == true }

    fun blocked(x: Int, y: Int): Boolean {
        val m = map
        if (!m.inside(x, y)) return true
        val k = m.i(x, y)
        if (!Tiles.passable(m.ground[k], m.upper[k])) return true
        return m.events.any { it.x == x && it.y == y && (page(it)?.graphic ?: "none") != "none" }
    }

    /** 걷는 중인지, 그리고 지난 걸음에서 남은 진행분(이어 걸을 때 씀). */
    var walking by mutableStateOf(false)
    var carry = 0f

    suspend fun step(d: Int) {
        dir = d
        val nx = px + DX[d]; val ny = py + DY[d]
        if (blocked(nx, ny)) { carry = 0f; delay(60); return }
        fx = px; fy = py; px = nx; py = ny; steps++
        // 프레임마다 직접 움직이고, 남은 몫(carry)은 다음 칸으로 넘김 → 칸 사이에서 멈칫하지 않음
        walking = true
        var t = carry
        var last = -1L
        prog.snapTo(t.coerceIn(0f, 1f))
        while (t < 1f) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last >= 0) t += (now - last) / 170_000_000f
                last = now
            }
            prog.snapTo(t.coerceAtMost(1f))
        }
        carry = (t - 1f).coerceIn(0f, 0.5f)
        walking = false
        map.events.firstOrNull { it.x == px && it.y == py && page(it)?.trigger == "touch" }?.let { run(it); autoCheck() }
    }

    suspend fun interact() {
        if (busy || walking) return
        val tx = px + DX[dir]; val ty = py + DY[dir]
        val m = map
        val e = m.events.firstOrNull { it.x == tx && it.y == ty && page(it)?.trigger == "action" }
            ?: m.events.firstOrNull { it.x == px && it.y == py && page(it)?.trigger == "action" } ?: return
        run(e, faceMe = true); autoCheck()
    }

    private fun key(e: GameEvent, pg: EventPage) = "$mapId:${e.id}:${e.pages.indexOf(pg)}"

    suspend fun autoCheck() {
        for (guard in 0 until 50) {
            val e = map.events.firstOrNull { ev -> val pg = page(ev); pg != null && pg.trigger == "auto" && !autoDone.contains(key(ev, pg)) } ?: return
            autoDone.add(key(e, page(e)!!))
            run(e)
        }
    }

    private suspend fun run(e: GameEvent, faceMe: Boolean = false) {
        val pg = page(e) ?: return
        busy = true
        if (faceMe && pg.graphic != "none") face[e.id] = opposite(dir)
        if (goalMap == mapId && goalEv == e.id) { goalMap = -1; goalEv = -1 }
        try { exec(pg.commands, e) } finally { face.remove(e.id); msg = null; choices = null; busy = false }
    }

    private suspend fun exec(list: List<Cmd>, self: GameEvent?): Boolean {
        for ((n, c) in list.withIndex()) {
            when (c.type) {
                "text" -> say(c.speaker, c.text, keep = list.getOrNull(n + 1)?.type == "choice")
                "choice" -> if (!exec(c.branches.getOrElse(choose(c.options)) { mutableListOf() }, self)) return false
                "switch" -> if (c.name.isNotBlank()) switches[c.name] = c.on
                "if" -> {
                    val ok = if (c.cond == "item") (items[c.name] ?: 0) > 0 else (switches[c.name] == true) == c.on
                    if (!exec(if (ok) c.then else c.other, self)) return false
                }
                "transfer" -> {
                    val dark = fade.value >= 1f
                    if (!dark) fade.animateTo(1f, tween(260))
                    p.map(c.map)?.let { m -> mapId = m.id; px = c.x.coerceIn(0, m.w - 1); py = c.y.coerceIn(0, m.h - 1); fx = px; fy = py; prog.snapTo(1f) }
                    delay(120)
                    if (!dark) fade.animateTo(0f, tween(260))
                }
                "move" -> if (c.toPos) moveTo(c.target, c.x, c.y, self) else moveRoute(c.target, c.route, self)
                "goal" -> { val id = c.target.toIntOrNull(); if (id == null) { goalMap = -1; goalEv = -1 } else { goalMap = if (c.map > 0) c.map else mapId; goalEv = id } }
                "item" -> if (c.name.isNotBlank()) items[c.name] = max(0, (items[c.name] ?: 0) + c.amount)
                "wait" -> delay(c.ms.toLong().coerceIn(0, 60000))
                "fade" -> fade.animateTo(if (c.out) 1f else 0f, tween(450))
                "end" -> return false
            }
        }
        return true
    }

    /** 이동 경로: U D L R = 한 칸 걷기, u d l r = 그 방향 보기, . = 잠깐 쉬기. 막히면 그 걸음만 건너뜀. */
    private suspend fun moveRoute(target: String, route: String, self: GameEvent?) {
        val ev = moveTarget(target, self)
        if (target != "player" && ev == null) return
        // 움직이는 동안은 '말 걸 때 돌아보기'보다 가는 방향이 먼저
        ev?.let { face.remove(it.id) }
        var carry = 0f
        for (ch in route) {
            val d = when (ch.uppercaseChar()) { 'D' -> 0; 'L' -> 1; 'R' -> 2; 'U' -> 3; else -> -1 }
            if (d < 0) { carry = 0f; delay(250); continue }
            if (!ch.isUpperCase()) { carry = 0f; turn(ev, d); delay(120); continue }
            carry = walkOne(ev, d, carry) ?: run { delay(150); 0f }
        }
        ev?.let { evPos.remove(it.id) }
    }

    private fun moveTarget(target: String, self: GameEvent?): GameEvent? = when (target) {
        "player" -> null
        "this" -> self
        else -> target.toIntOrNull()?.let { id -> map.events.firstOrNull { it.id == id } }
    }

    private fun turn(ev: GameEvent?, d: Int) { if (ev == null) dir = d else turned[ev.id] = d }

    /** 좌표 이동: (tx, ty)까지 길을 찾아 걸어감. 길이 막히면 다시 찾고, 그래도 없으면 갈 수 있는 데까지. */
    private suspend fun moveTo(target: String, tx: Int, ty: Int, self: GameEvent?) {
        val ev = moveTarget(target, self)
        if (target != "player" && ev == null) return
        ev?.let { face.remove(it.id) }
        var carry = 0f
        var tries = 0
        while (tries < 200) {
            val sx = ev?.x ?: px; val sy = ev?.y ?: py
            if (sx == tx && sy == ty) break
            val path = findPath(ev, sx, sy, tx, ty) ?: break
            val d = path.firstOrNull() ?: break
            val c = walkOne(ev, d, carry)
            if (c == null) { carry = 0f; tries += 10; delay(200); continue }  // 누가 막고 있음: 잠깐 기다렸다 다시
            carry = c; tries++
        }
        ev?.let { evPos.remove(it.id) }
    }

    private fun canStand(ev: GameEvent?, x: Int, y: Int): Boolean {
        val m = map
        if (!m.inside(x, y)) return false
        if (ev == null) return !blocked(x, y)
        val solid = (page(ev)?.graphic ?: "none") != "none"
        if (map.events.any { it !== ev && it.x == x && it.y == y }) return false
        return !(solid && (blocked(x, y) || (x == px && y == py)))
    }

    /** 가장 짧은 길의 방향 목록 (BFS). 도착 칸이 막혀 있으면 그 옆까지. */
    private fun findPath(ev: GameEvent?, sx: Int, sy: Int, tx: Int, ty: Int): List<Int>? {
        val m = map
        val w = m.w; val h = m.h
        val prev = IntArray(w * h) { -1 }; val how = IntArray(w * h) { -1 }
        val q = ArrayDeque<Int>(); val start = sy * w + sx
        prev[start] = start; q.add(start)
        var best = start; var bestD = Math.abs(sx - tx) + Math.abs(sy - ty)
        while (q.isNotEmpty()) {
            val i = q.removeFirst(); val x = i % w; val y = i / w
            val dist = Math.abs(x - tx) + Math.abs(y - ty)
            if (dist < bestD) { best = i; bestD = dist }
            if (dist == 0) break
            for (d in 0..3) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (prev[j] >= 0) continue
                if (!canStand(ev, nx, ny)) continue
                prev[j] = i; how[j] = d; q.add(j)
            }
        }
        if (best == start) return null
        val out = ArrayList<Int>(); var k = best
        while (k != start) { out.add(how[k]); k = prev[k] }
        return out.reversed()
    }

    /**
     * 한 칸 걷기 (가는 방향으로 돌아서). 화면 프레임에 맞춰 움직여서 끊기지 않고, 남은 몫은 다음 칸으로 넘김.
     * 막혔으면 null (방향만 돌림).
     */
    private suspend fun walkOne(ev: GameEvent?, d: Int, carry: Float): Float? {
        turn(ev, d)
        val sx = ev?.x ?: px; val sy = ev?.y ?: py
        val nx = sx + DX[d]; val ny = sy + DY[d]
        if (!canStand(ev, nx, ny)) return null
        if (ev == null) { fx = px; fy = py; px = nx; py = ny; steps++; walking = true }
        var t = carry; var last = -1L
        while (true) {
            if (ev == null) prog.snapTo(t.coerceAtMost(1f)) else evPos[ev.id] = (sx + DX[d] * t.coerceAtMost(1f)) to (sy + DY[d] * t.coerceAtMost(1f))
            if (t >= 1f) break
            androidx.compose.runtime.withFrameNanos { now -> if (last >= 0) t += (now - last) / 200_000_000f; last = now }
        }
        if (ev == null) walking = false
        else { ev.x = nx; ev.y = ny; evPos[ev.id] = nx.toFloat() to ny.toFloat() }
        return (t - 1f).coerceIn(0f, 0.5f)
    }

    private suspend fun say(s: String, t: String, keep: Boolean) {
        val d = CompletableDeferred<Unit>(); msgWait = d
        msg = s to t
        d.await()
        if (!keep) msg = null
    }

    fun advance() { msgWait?.complete(Unit) }

    private suspend fun choose(o: List<String>): Int {
        if (o.isEmpty()) return 0
        val d = CompletableDeferred<Int>(); choiceWait = d
        choices = o
        val r = d.await()
        choices = null; msg = null
        return r
    }

    fun pick(i: Int) { choiceWait?.complete(i) }
}

@Composable
fun PlayScreen(p: Project, onExit: () -> Unit) {
    val game = remember { Game(Project.from(p.toJson())) }
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf<Int?>(null) }
    var bag by remember { mutableStateOf(false) }
    val bounce by androidx.compose.animation.core.rememberInfiniteTransition(label = "goal").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(520), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    // F3: 초당 프레임 보기 (PC)
    var showFps by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(showFps) {
        if (!showFps) return@LaunchedEffect
        var n = 0; var t0 = -1L
        while (true) androidx.compose.runtime.withFrameNanos { t ->
            if (t0 < 0) t0 = t
            n++
            if (t - t0 >= 1_000_000_000L) { fps = n; n = 0; t0 = t }
        }
    }
    BackHandler { onExit() }

    LaunchedEffect(game) {
        game.autoCheck()
        while (true) {
            val h = held
            if (h != null && !game.busy && game.msg == null) game.step(h) else { game.carry = 0f; delay(16) }
        }
    }

    val img = remember(game.mapId) { MapImage(game.map).also { it.all() } }
    val ib = remember(img) { img.bmp.asImageBitmap() }

    // keyboard (PC): arrows/WASD move, Z/Enter/Space = A
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var sel by remember { mutableIntStateOf(0) }
    LaunchedEffect(game.choices) { sel = 0 }
    fun pressA() { if (game.msg != null && game.choices == null) game.advance() else if (game.msg == null) scope.launch { game.interact() } }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(focus).focusable()
        .onKeyEvent { e ->
            val d = when (e.key) {
                Key.DirectionDown, Key.S -> 0; Key.DirectionLeft, Key.A -> 1
                Key.DirectionRight, Key.D -> 2; Key.DirectionUp, Key.W -> 3; else -> -1
            }
            val ch = game.choices
            when {
                ch != null && (d == 0 || d == 3) -> { if (e.type == KeyEventType.KeyDown) sel = (sel + (if (d == 0) 1 else -1) + ch.size) % ch.size; true }
                d >= 0 && e.type == KeyEventType.KeyDown -> { held = d; true }
                d >= 0 && e.type == KeyEventType.KeyUp -> { if (held == d) held = null; true }
                e.type == KeyEventType.KeyDown && e.key == Key.F3 -> { showFps = !showFps; true }
                e.type == KeyEventType.KeyDown && (e.key == Key.Z || e.key == Key.Enter || e.key == Key.Spacebar) -> {
                    if (ch != null) game.pick(sel.coerceIn(0, ch.size - 1)) else pressA(); true
                }
                else -> false
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val m = game.map
            val ts = min(sqrt(size.width * size.height / 150f), size.width / 8f)
            val t = game.prog.value
            val cx = game.fx + (game.px - game.fx) * t
            val cy = game.fy + (game.py - game.fy) * t
            val mw = m.w * ts; val mh = m.h * ts
            val camX = if (mw <= size.width) (mw - size.width) / 2f else (cx * ts + ts / 2 - size.width / 2).coerceIn(0f, mw - size.width)
            val camY = if (mh <= size.height) (mh - size.height) / 2f else (cy * ts + ts / 2 - size.height / 2).coerceIn(0f, mh - size.height)
            // 카메라를 정수 픽셀에 맞춤(도트가 반 픽셀씩 흔들려 끊겨 보이는 것 방지)
            translate(-kotlin.math.round(camX), -kotlin.math.round(camY)) {
                drawImage(ib, IntOffset.Zero, IntSize(img.bmp.width, img.bmp.height), IntOffset.Zero, IntSize(mw.toInt() + 1, mh.toInt() + 1), filterQuality = FilterQuality.None)
                drawIntoCanvas { c ->
                    val nc = c.art()
                    val sprites = mutableListOf<Triple<Float, Float, () -> Unit>>()
                    m.events.forEach { e ->
                        val pg = game.page(e) ?: return@forEach
                        if (pg.graphic == "none") return@forEach
                        val (ex, ey) = game.evPos[e.id] ?: (e.x.toFloat() to e.y.toFloat())
                        val mv = game.evPos[e.id]
                        // 걷는 중: 칸 앞쪽 절반은 발 내딛기(번갈아 왼발·오른발), 뒤쪽 절반은 서 있기
                        val fr = if (mv == null) 1 else {
                            val f = ((mv.first + mv.second) % 1f + 1f) % 1f
                            if (f > 0.001f && f < 0.5f) (if ((kotlin.math.floor(mv.first + mv.second).toInt() and 1) == 0) 0 else 2) else 1
                        }
                        sprites.add(Triple(ey, 0f) { Art.sprite(nc, pg.graphic, game.face[e.id] ?: game.turned[e.id] ?: pg.dir, ex * ts, ey * ts, ts, frame = fr) })
                    }
                    val bob = if (game.walking && game.steps % 2 == 0) 1 else 0
                    val hf = if (game.walking && game.prog.value < 0.5f) (if (game.steps % 2 == 0) 0 else 2) else 1
                    sprites.add(Triple(cy, 1f) { Art.sprite(nc, game.p.hero, game.dir, cx * ts, cy * ts, ts, bob, hf) })
                    sprites.sortedWith(compareBy<Triple<Float, Float, () -> Unit>>({ it.first }, { it.second })).forEach { it.third() }
                }
            }
            game.goalTile()?.let { (gx, gy) -> drawGoal(gx * ts - kotlin.math.round(camX), gy * ts - kotlin.math.round(camY), ts, bounce) }
            if (game.fade.value > 0f) drawRect(Color.Black.copy(alpha = game.fade.value))
        }
        if (showFps) Text("${fps} fps" + (if (Plat.gfx.isNotEmpty()) " · ${Plat.gfx}" else ""), color = Color.Yellow, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp))

        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundBtn { Icon(Icons.Filled.Close, "끝내기", tint = Color.White, modifier = Modifier.size(22.dp).clickable { onExit() }) }
            Spacer(Modifier.weight(1f))
            RoundBtn { Icon(Icons.Filled.Work, "가방", tint = Color.White, modifier = Modifier.size(22.dp).clickable { bag = !bag }) }
        }
        if (bag) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp).widthIn(min = 160.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6202024)).padding(14.dp),
            ) {
                Text("가방", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val have = game.items.filter { it.value > 0 }
                if (have.isEmpty()) Text("비어 있어요", color = Color(0xFFAAAAAA), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                have.forEach { (k, v) -> Text("$k × $v", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            game.choices?.let { opts ->
                Column(
                    Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp).widthIn(min = 160.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xEE202024)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(14.dp)).padding(6.dp),
                ) {
                    opts.forEachIndexed { i, o ->
                        Text(o, color = Color.White, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (i == sel) Color(0x33FFFFFF) else Color.Transparent)
                            .clickable { game.pick(i) }.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            game.msg?.let { m -> MessageBox(m.first, m.second, choosing = game.choices != null) { game.advance() } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                DPad { held = it }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(78.dp).clip(CircleShape).background(Color(0x66FFFFFF)).border(2.dp, Color(0x88FFFFFF), CircleShape)
                        .clickable { pressA() },
                    contentAlignment = Alignment.Center,
                ) { Text("A", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun RoundBtn(content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun MessageBox(speaker: String, text: String, choosing: Boolean, onNext: () -> Unit) {
    var shown by remember(speaker, text) { mutableIntStateOf(0) }
    LaunchedEffect(speaker, text) { while (shown < text.length) { delay(24); shown++ } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 110.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1C1C22)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp))
            .clickable(enabled = !choosing) { if (shown < text.length) shown = text.length else onNext() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (speaker.isNotBlank()) Text(speaker, color = Color(0xFFF2B89E), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(text.take(shown), color = Color.White, fontSize = 17.sp, lineHeight = 25.sp)
    }
}

@Composable
private fun DPad(onDir: (Int?) -> Unit) {
    var cur by remember { mutableStateOf<Int?>(null) }
    Box(
        Modifier.size(150.dp).clip(CircleShape).background(Color(0x44FFFFFF)).border(2.dp, Color(0x66FFFFFF), CircleShape)
            .pointerInput(Unit) {
                fun pick(o: Offset): Int? {
                    val dx = o.x - size.width / 2f; val dy = o.y - size.height / 2f
                    if (dx * dx + dy * dy < (size.width * 0.12f) * (size.width * 0.12f)) return null
                    return if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 2) else (if (dy < 0) 3 else 0)
                }
                awaitEachGesture {
                    val d = awaitFirstDown()
                    cur = pick(d.position); onDir(cur)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                        val n = pick(ch.position)
                        if (n != cur) { cur = n; onDir(n) }
                        ch.consume()
                    }
                    cur = null; onDir(null)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width / 2f
            val c = Offset(r, r)
            listOf(0 to Offset(0f, 1f), 1 to Offset(-1f, 0f), 2 to Offset(1f, 0f), 3 to Offset(0f, -1f)).forEach { (d, v) ->
                drawCircle(if (cur == d) Color(0xCCFFFFFF) else Color(0x77FFFFFF), r * 0.17f, c + v * (r * 0.62f))
            }
        }
    }
}

/** 목적지 화살표. 칸이 화면 안이면 그 위에서 통통 튀는 ▼, 밖이면 화면 가장자리에서 그쪽을 가리킴. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGoal(x: Float, y: Float, ts: Float, b: Float) {
    val r = ts * 0.32f
    val tx = x + ts / 2; val ty = y - ts * 0.15f - b * ts * 0.18f
    val m = r * 1.6f
    fun tri(cx: Float, cy: Float, ang: Float) {
        // ang: 화살 끝이 향하는 방향(라디안), 기본 아래
        val path = androidx.compose.ui.graphics.Path()
        fun pt(dx: Float, dy: Float): Offset {
            val c = kotlin.math.cos(ang - (Math.PI / 2).toFloat()); val s = kotlin.math.sin(ang - (Math.PI / 2).toFloat())
            return Offset(cx + dx * c - dy * s, cy + dx * s + dy * c)
        }
        val a = pt(0f, r); val l = pt(-r, -r * 0.6f); val rr = pt(r, -r * 0.6f)
        path.moveTo(a.x, a.y); path.lineTo(l.x, l.y); path.lineTo(rr.x, rr.y); path.close()
        drawPath(path, Color(0xFFFFD54F))
        drawPath(path, Color(0xFF5D4037), style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(2f, ts * 0.05f)))
    }
    if (tx in m..size.width - m && ty in m..size.height - m) { tri(tx, ty, (Math.PI / 2).toFloat()); return }
    val cx = size.width / 2; val cy = size.height / 2
    val ang = kotlin.math.atan2(ty + ts * 0.5f - cy, tx - cx)
    val ex = tx.coerceIn(m, size.width - m); val ey = (ty + ts * 0.5f).coerceIn(m, size.height - m)
    tri(ex - kotlin.math.cos(ang) * b * r * 0.5f, ey - kotlin.math.sin(ang) * b * r * 0.5f, ang)
}
