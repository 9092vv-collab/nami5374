package space.nami.maker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class Tool(val label: String, val icon: ImageVector) {
    PEN("펜", Icons.Filled.Brush),
    RECT("사각형", Icons.Filled.CropSquare),
    FILL("채우기", Icons.Filled.FormatColorFill),
    ERASE("위층 지우개", Icons.Filled.AutoFixNormal),
    EVENT("이벤트", Icons.Filled.Person),
    START("시작 위치", Icons.Filled.Flag),
    HAND("화면 이동", Icons.Filled.PanTool),
}

@Composable
fun EditorScreen(doc: Doc, chat: AiChat, onBack: () -> Unit, onPlay: () -> Unit) {
    var mapId by remember { mutableIntStateOf(doc.p.startMap) }
    @Suppress("UNUSED_VARIABLE") val rev = doc.rev
    val map = doc.p.map(mapId) ?: doc.p.maps[0]
    SideEffect { chat.onFocusMap = { mapId = it } }
    var showChat by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<Int?>(null) }
    var mapSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    // AI chat: full screen in both layouts, closed only with the back key (or its ← button)
    androidx.activity.compose.BackHandler(enabled = showChat) { showChat = false }
    // next처럼 탭 3개: 이벤트 목록 · 맵·작업실(가운데, 기본) · 이미지. 맵 그리기와 겹치지 않게 밀어서 넘기기는 없음.
    var tab by remember { mutableIntStateOf(1) }
    androidx.activity.compose.BackHandler(enabled = tab != 1 && !showChat) { tab = 1 }
    Column(Modifier.fillMaxSize().background(K.bg)) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) { Seg(listOf("이벤트", "맵·작업실", "이미지"), tab) { tab = it } }
    if (tab == 0) EventsPage(doc, map, Modifier.weight(1f).fillMaxWidth(), onPickMap = { mapId = it }, onEditEvent = { editEvent = it })
    else if (tab == 2) ImagePage(Modifier.weight(1f).fillMaxWidth())
    else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wide = maxWidth >= 700.dp
        EditorPane(
            doc, map, wide, Modifier.fillMaxSize(),
            onBack = onBack, onPlay = onPlay, onChat = { showChat = true },
            onPickMap = { mapId = it }, onEditEvent = { editEvent = it },
            onMapSettings = { mapSettings = true }, onSettings = { settings = true },
        )
        if (showChat) {
            ChatPane(chat, map.id, Modifier.fillMaxSize().background(K.bg).pointerInput(Unit) {}, onSettings = { settings = true }, onClose = { showChat = false })
        }
    }
    }

    val ee = editEvent?.let { map.event(it) }
    if (ee != null) EventEditor(doc, map, ee, onClose = { editEvent = null })
    if (mapSettings) MapSettingsDialog(doc, map, onClose = { mapSettings = false }, onDeleted = { mapId = doc.p.maps[0].id; mapSettings = false })
    if (settings) SettingsDialog(onClose = { settings = false })
}

@Composable
private fun EditorPane(
    doc: Doc, map: GameMap, wide: Boolean, modifier: Modifier,
    onBack: () -> Unit, onPlay: () -> Unit, onChat: () -> Unit, onPickMap: (Int) -> Unit,
    onEditEvent: (Int) -> Unit, onMapSettings: () -> Unit, onSettings: () -> Unit,
) {
    var tool by remember { mutableStateOf(Tool.PEN) }
    var tile by remember { mutableIntStateOf(1) }
    var menu by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") { onBack() }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(map.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
                    Icon(Icons.Filled.ArrowDropDown, null, tint = K.sub)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    doc.p.maps.forEach { m ->
                        DropdownMenuItem(text = { Text("${m.name}  ·  ${m.w}×${m.h}", fontWeight = if (m.id == map.id) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { menu = false; onPickMap(m.id) })
                    }
                    DropdownMenuItem(text = { Text("+ 새 맵", color = K.accent) }, onClick = {
                        menu = false
                        doc.checkpoint()
                        val m = GameMap(doc.p.nextMapId(), "맵 ${doc.p.maps.size + 1}", 20, 15)
                        doc.p.maps.add(m); doc.changed(); onPickMap(m.id)
                    })
                    DropdownMenuItem(text = { Text("이 맵 설정…") }, onClick = { menu = false; onMapSettings() })
                }
            }
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.AutoMirrored.Filled.Undo, "되돌리기", enabled = doc.canUndo) { doc.undo() }
            IconBtn(Icons.AutoMirrored.Filled.Redo, "다시 하기", enabled = doc.canRedo) { doc.redo() }
            IconBtn(Icons.Filled.PlayArrow, "테스트 플레이") { onPlay() }
            IconBtn(Icons.Filled.AutoAwesome, "AI") { onChat() }
            IconBtn(Icons.Filled.Settings, "설정") { onSettings() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(K.panel)) {
            MapCanvas(doc, map, tool, tile, onEditEvent)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tool.entries.forEach { t -> IconBtn(t.icon, t.label, on = tool == t) { tool = t } }
            Spacer(Modifier.width(8.dp))
            Text(
                when (tool) {
                    Tool.EVENT -> "누르면 만들기·편집, 끌면 옮기기"
                    Tool.START -> "주인공이 처음 설 곳"
                    Tool.HAND -> "두 손가락·마우스 오른쪽 끌기·휠로도 돼요"
                    Tool.ERASE -> "나무·가구 같은 위층만 지워요"
                    else -> Tiles.get(tile).name
                },
                fontSize = 13.sp, color = K.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) {
            items(Tiles.list.drop(1)) { t ->
                @Suppress("UNUSED_VARIABLE") val sr = TileSkin.rev
                val sel = t.id == tile
                Canvas(
                    Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                        .border(if (sel) 2.5.dp else 1.dp, if (sel) K.accent else K.line, RoundedCornerShape(10.dp))
                        .clickable { tile = t.id; if (tool !in listOf(Tool.PEN, Tool.RECT, Tool.FILL)) tool = Tool.PEN }
                ) {
                    drawIntoCanvas { c ->
                        if (t.upper) Art.tile(c.art(), 1, 0f, 0f, size.width, 0, 0)
                        Art.tile(c.art(), t.id, 0f, 0f, size.width, 3, 5)
                    }
                }
            }
        }
    }
}

private class StrokeState {
    var snap: String? = null
    var dirty = false
    var sx = 0; var sy = 0
    var lx = -1; var ly = -1
    var drag: GameEvent? = null
    var moved = false
}

@Composable
private fun MapCanvas(doc: Doc, map: GameMap, tool: Tool, tile: Int, onEditEvent: (Int) -> Unit) {
    val rev = doc.rev
    val skinRev = TileSkin.rev
    val img = remember(map.id, map.w, map.h, skinRev) { MapImage(map, TileSkin.mapTs()) }
    img.map = map
    remember(rev, map, img) { img.all(); 0 }
    val mapNow by rememberUpdatedState(map)
    val imgNow by rememberUpdatedState(img)
    var tick by remember { mutableIntStateOf(0) }
    // phone: wraps the same pixels (cheap). PC: a fresh snapshot, so every stroke/AI edit shows right away.
    val ib = remember(img, tick, rev, skinRev) { img.bmp.asImageBitmap() }
    var scale by remember(map.id) { mutableFloatStateOf(0f) }
    var off by remember(map.id) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var rect by remember { mutableStateOf<IntArray?>(null) }
    val st = remember { StrokeState() }

    if (scale == 0f && box.width > 0) {
        // 맵을 화면에 꽉 채우지 않음: 작은 맵도 플레이 화면과 같은 타일 크기까지만 키움 (큰 맵은 전체가 보이게 줄임)
        val fit = min(box.width / (map.w * 32f), box.height / (map.h * 32f)) * 0.94f
        val play = min(kotlin.math.sqrt(box.width * box.height / 150f), box.width / 8f) / 32f
        val s = min(fit, play)
        scale = s
        off = Offset((box.width - map.w * 32f * s) / 2f, (box.height - map.h * 32f * s) / 2f)
    }

    fun cellAt(p: Offset): Pair<Int, Int> {
        val s = if (scale == 0f) 1f else scale
        return floor((p.x - off.x) / s / 32f).toInt() to floor((p.y - off.y) / s / 32f).toInt()
    }

    fun paintAt(x: Int, y: Int) {
        val m = mapNow
        if (!m.inside(x, y)) return
        val k = m.i(x, y)
        if (tool == Tool.ERASE) { if (m.upper[k] != 0) { m.upper[k] = 0; st.dirty = true } }
        else { val g = m.ground[k]; val u = m.upper[k]; m.paint(x, y, tile); if (g != m.ground[k] || u != m.upper[k]) st.dirty = true }
        imgNow.cell(x, y)
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            paintAt(x, y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    fun start(x: Int, y: Int) {
        st.snap = doc.snapshot(); st.dirty = false; st.sx = x; st.sy = y; st.lx = x; st.ly = y; st.moved = false; st.drag = null
        when (tool) {
            Tool.PEN, Tool.ERASE -> { paintAt(x, y); tick++ }
            Tool.RECT -> rect = intArrayOf(x, y, x, y)
            Tool.EVENT -> st.drag = mapNow.eventAt(x, y)
            else -> {}
        }
    }

    fun move(x: Int, y: Int) {
        if (x == st.lx && y == st.ly) return
        when (tool) {
            Tool.PEN, Tool.ERASE -> { line(st.lx, st.ly, x, y); tick++ }
            Tool.RECT -> rect = intArrayOf(st.sx, st.sy, x, y)
            Tool.EVENT -> st.drag?.let { e ->
                if (mapNow.inside(x, y) && mapNow.eventAt(x, y) == null) { e.x = x; e.y = y; st.moved = true; st.dirty = true; tick++ }
            }
            else -> {}
        }
        st.lx = x; st.ly = y
    }

    fun cancel() {
        rect = null
        if (st.dirty) st.snap?.let { doc.restore(it) }
        st.snap = null; st.dirty = false; st.drag = null
    }

    fun end() {
        val snap = st.snap ?: return
        val m = mapNow
        when (tool) {
            Tool.PEN, Tool.ERASE -> if (st.dirty) { doc.pushUndo(snap); doc.changed() }
            Tool.RECT -> {
                rect?.let { r ->
                    for (y in min(r[1], r[3])..max(r[1], r[3])) for (x in min(r[0], r[2])..max(r[0], r[2])) if (m.inside(x, y)) m.paint(x, y, tile)
                    doc.pushUndo(snap); doc.changed()
                }
                rect = null
            }
            Tool.FILL -> {
                val x = st.sx; val y = st.sy
                if (m.inside(x, y)) {
                    val g0 = m.ground[m.i(x, y)]; val u0 = m.upper[m.i(x, y)]
                    val seen = BooleanArray(m.w * m.h); val q = ArrayDeque<Int>(); q.add(m.i(x, y)); seen[m.i(x, y)] = true
                    while (q.isNotEmpty()) {
                        val k = q.removeFirst(); val cx = k % m.w; val cy = k / m.w
                        m.paint(cx, cy, tile)
                        for ((nx, ny) in listOf(cx + 1 to cy, cx - 1 to cy, cx to cy + 1, cx to cy - 1)) {
                            if (!m.inside(nx, ny)) continue
                            val nk = m.i(nx, ny)
                            if (!seen[nk] && m.ground[nk] == g0 && m.upper[nk] == u0) { seen[nk] = true; q.add(nk) }
                        }
                    }
                    doc.pushUndo(snap); doc.changed()
                }
            }
            Tool.EVENT -> {
                val e = st.drag
                if (e != null) {
                    if (st.moved) { doc.pushUndo(snap); doc.changed() } else onEditEvent(e.id)
                } else if (m.inside(st.sx, st.sy)) {
                    doc.pushUndo(snap)
                    val ne = GameEvent(m.nextEventId(), "이벤트 ${m.nextEventId()}", st.sx, st.sy)
                    m.events.add(ne); doc.changed(); onEditEvent(ne.id)
                }
            }
            Tool.START -> if (m.inside(st.sx, st.sy)) {
                doc.pushUndo(snap); doc.p.startMap = m.id; doc.p.startX = st.sx; doc.p.startY = st.sy; doc.changed()
            }
            Tool.HAND -> {}
        }
        st.snap = null; st.drag = null
    }

    Canvas(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            // mouse wheel = zoom around the pointer (PC)
            .pointerInput(map.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Scroll) {
                            val ch = e.changes.first()
                            val dy = ch.scrollDelta.y
                            if (dy != 0f && scale > 0f) {
                                val ns = (scale * (if (dy > 0) 0.88f else 1.14f)).coerceIn(0.15f, 8f)
                                off = ch.position - (ch.position - off) * (ns / scale); scale = ns
                            }
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(map.id, tool, tile) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // right / middle mouse button drags the view (PC)
                    val panButton = currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed
                    var multi = tool == Tool.HAND || panButton
                    if (!multi) { val (x, y) = cellAt(down.position); start(x, y) }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 || tool == Tool.HAND || panButton) {
                            if (!multi) { multi = true; cancel() }
                            val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid(useCurrent = true)
                            val ns = (scale * z).coerceIn(0.15f, 8f)
                            if (c != Offset.Unspecified) off = c - (c - off) * (ns / scale) + pan
                            scale = ns
                        } else if (!multi) {
                            val (x, y) = cellAt(pressed[0].position); move(x, y)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    if (!multi) end()
                }
            }
    ) {
        tick
        if (scale == 0f) return@Canvas
        val m = map
        withTransform({ translate(off.x, off.y); scale(scale, scale, Offset.Zero) }) {
            drawImage(ib, IntOffset.Zero, IntSize(img.bmp.width, img.bmp.height), IntOffset.Zero, IntSize(map.w * 32, map.h * 32), filterQuality = FilterQuality.None)
            if (scale * 32f > 16f) {
                val gc = Color(0x1A000000); val sw = 1f / scale
                for (x in 0..m.w) drawLine(gc, Offset(x * 32f, 0f), Offset(x * 32f, m.h * 32f), sw)
                for (y in 0..m.h) drawLine(gc, Offset(0f, y * 32f), Offset(m.w * 32f, y * 32f), sw)
            }
            drawIntoCanvas { c ->
                m.events.sortedBy { it.y }.forEach { e ->
                    val pg = e.pages.firstOrNull() ?: return@forEach
                    Art.sprite(c.art(), pg.graphic, pg.dir, e.x * 32f, e.y * 32f, 32f)
                }
                if (doc.p.startMap == m.id) Art.sprite(c.art(), doc.p.hero, 0, doc.p.startX * 32f, doc.p.startY * 32f, 32f)
            }
            val sw = max(2f, 2f / scale)
            m.events.forEach { e ->
                drawRoundRect(K.accent, Offset(e.x * 32f + 1.5f, e.y * 32f + 1.5f), Size(29f, 29f),
                    androidx.compose.ui.geometry.CornerRadius(5f), style = Stroke(sw))
            }
            if (doc.p.startMap == m.id) {
                drawCircle(Color(0xFF3F6FB8), 5f, Offset(doc.p.startX * 32f + 27f, doc.p.startY * 32f + 5f))
            }
            rect?.let { r ->
                val l = min(r[0], r[2]); val t = min(r[1], r[3]); val rr = max(r[0], r[2]) + 1; val b = max(r[1], r[3]) + 1
                drawRect(K.accent.copy(alpha = 0.25f), Offset(l * 32f, t * 32f), Size((rr - l) * 32f, (b - t) * 32f))
                drawRect(K.accent, Offset(l * 32f, t * 32f), Size((rr - l) * 32f, (b - t) * 32f), style = Stroke(sw))
            }
        }
    }
}

@Composable
private fun MapSettingsDialog(doc: Doc, map: GameMap, onClose: () -> Unit, onDeleted: () -> Unit) {
    var name by remember { mutableStateOf(map.name) }
    var w by remember { mutableStateOf(map.w.toString()) }
    var h by remember { mutableStateOf(map.h.toString()) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = K.bg,
        title = { Text("맵 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it }, "이름", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(w, { w = it.filter(Char::isDigit).take(2) }, "가로", Modifier.weight(1f))
                    Field(h, { h = it.filter(Char::isDigit).take(2) }, "세로", Modifier.weight(1f))
                }
                Text("크기는 5~$MAX_MAP. 줄이면 바깥 칸이 사라져요.", fontSize = 12.sp, color = K.sub)
                if (doc.p.maps.size > 1) {
                    TextButton(onClick = {
                        if (!confirm) { confirm = true; return@TextButton }
                        doc.checkpoint(); doc.p.maps.remove(map)
                        if (doc.p.startMap == map.id) { doc.p.startMap = doc.p.maps[0].id; doc.p.startX = 1; doc.p.startY = 1 }
                        doc.changed(); onDeleted()
                    }) { Text(if (confirm) "정말 지울까요? 한 번 더 누르세요" else "이 맵 지우기", color = K.danger) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nw = (w.toIntOrNull() ?: map.w).coerceIn(5, MAX_MAP); val nh = (h.toIntOrNull() ?: map.h).coerceIn(5, MAX_MAP)
                doc.checkpoint()
                map.name = name.ifBlank { map.name }
                if (nw != map.w || nh != map.h) map.resize(nw, nh)
                doc.dropIfSame(); doc.changed(); onClose()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("취소", color = K.sub) } },
    )
}

/** 왼쪽 '이벤트' 탭: 맵을 고르고 그 맵의 이벤트를 목록으로. 누르면 이벤트 편집. */
@Composable
private fun EventsPage(doc: Doc, map: GameMap, modifier: Modifier, onPickMap: (Int) -> Unit, onEditEvent: (Int) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = doc.rev
    Column(modifier) {
        androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(doc.p.maps.size) { i -> val m = doc.p.maps[i]; Pill(m.name, on = m.id == map.id) { onPickMap(m.id) } }
        }
        var heroPick by remember { mutableStateOf(false) }
        if (heroPick) GraphicPicker(doc.p.hero, 0, onPick = { g -> if (g != "none") { doc.checkpoint(); doc.p.hero = g; doc.changed() }; heroPick = false }) { heroPick = false }
        // 조작하는 캐릭터(주인공): 게임 전체에서 하나
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(K.panel).clickable { heroPick = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(40.dp)) { drawIntoCanvas { c -> Art.sprite(c.art(), doc.p.hero, 0, 0f, 0f, size.width) } }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("주인공 (조작하는 캐릭터)", fontSize = 15.sp, color = K.text)
                Text(Sprites.name(doc.p.hero) + " · 시작: " + (doc.p.map(doc.p.startMap)?.name ?: "") + " (${doc.p.startX}, ${doc.p.startY})", fontSize = 12.sp, color = K.sub)
            }
            Text("바꾸기 ›", fontSize = 13.sp, color = K.sub)
        }
        if (map.events.isEmpty()) Text("이 맵에는 이벤트가 없어요. 맵·작업실 탭에서 이벤트 도구로 칸을 누르거나 AI에게 부탁해 보세요.",
            fontSize = 13.sp, color = K.sub, modifier = Modifier.padding(18.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(map.events.size) { i ->
                val e = map.events[i]
                val pg = e.pages.firstOrNull() ?: EventPage()
                Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(K.panel).clickable { onEditEvent(e.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
                        drawIntoCanvas { c -> if (pg.graphic != "none") Art.sprite(c.art(), pg.graphic, pg.dir, 0f, 0f, size.width) }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(e.name.ifBlank { "이벤트 #${e.id}" }, fontSize = 15.sp, color = K.text)
                        val run = when (pg.trigger) { "touch" -> "밟으면"; "auto" -> "자동"; else -> "말 걸기" }
                        Text("#${e.id} · (${e.x}, ${e.y}) · $run" + (if (e.pages.size > 1) " · 페이지 ${e.pages.size}개" else ""), fontSize = 12.sp, color = K.sub)
                        pg.commands.firstOrNull()?.summary(doc.p)?.let { Text(it, fontSize = 12.sp, color = K.sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                    Text("›", color = K.sub)
                }
            }
        }
    }
}
