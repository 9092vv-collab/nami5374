package space.nami.maker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private sealed class Line {
    class C(val list: MutableList<Cmd>, val i: Int, val depth: Int) : Line()
    class Add(val list: MutableList<Cmd>, val depth: Int) : Line()
    class Label(val text: String, val depth: Int) : Line()
}

private fun flatten(list: MutableList<Cmd>, depth: Int, out: MutableList<Line>) {
    list.forEachIndexed { i, c ->
        out.add(Line.C(list, i, depth))
        when (c.type) {
            "choice" -> c.options.forEachIndexed { bi, o ->
                out.add(Line.Label("[$o] 고르면", depth + 1))
                while (c.branches.size <= bi) c.branches.add(mutableListOf())
                flatten(c.branches[bi], depth + 2, out)
            }
            "if" -> {
                out.add(Line.Label("그렇다면", depth + 1)); flatten(c.then, depth + 2, out)
                out.add(Line.Label("아니면", depth + 1)); flatten(c.other, depth + 2, out)
            }
        }
    }
    out.add(Line.Add(list, depth))
}

@Composable
fun EventEditor(doc: Doc, map: GameMap, ev: GameEvent, onClose: () -> Unit) {
    remember(ev.id) { doc.checkpoint(); 0 }
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(ev.name) }
    var editing by remember { mutableStateOf<Pair<MutableList<Cmd>, Int>?>(null) }
    var adding by remember { mutableStateOf<MutableList<Cmd>?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    fun close() { ev.name = name.ifBlank { ev.name }; doc.dropIfSame(); doc.changed(); onClose() }
    if (page >= ev.pages.size) page = ev.pages.size - 1
    val pg = ev.pages[page]
    if (picking) GraphicPicker(pg.graphic, pg.dir, onPick = { pg.graphic = it; picking = false; tick++ }) { picking = false }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding().imePadding()) {
            tick
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.Close, "닫기") { close() }
                    Text("이벤트 (${ev.x}, ${ev.y})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (!confirmDel) { confirmDel = true; return@TextButton }
                        map.events.remove(ev); doc.changed(); onClose()
                    }) { Text(if (confirmDel) "정말 지울까요?" else "삭제", color = K.danger) }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    item {
                        tick // lazy items skip recomposition unless they read the state that changed
                        Field(name, { name = it }, "이름", Modifier.fillMaxWidth())
                        Spacer(Modifier.padding(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ev.pages.size) { i -> Pill("페이지 ${i + 1}", on = i == page) { page = i } }
                                item { Pill("+") { ev.pages.add(EventPage().also { it.graphic = pg.graphic }); page = ev.pages.size - 1; tick++ } }
                            }
                            if (ev.pages.size > 1) IconBtn(Icons.Filled.Delete, "페이지 지우기") { ev.pages.removeAt(page); page = 0; tick++ }
                        }
                        Spacer(Modifier.padding(6.dp))
                        Section("그래픽")
                        // 그래픽: 지금 것만 보여 주고, 누르면 검색되는 고르기 창
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.panel).clickable { picking = true }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            tick
                            Canvas(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(K.bg)) {
                                drawIntoCanvas { c -> Art.sprite(c.art(), pg.graphic, pg.dir, 0f, 0f, size.width) }
                            }
                            Text(Sprites.name(pg.graphic), fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            Text("바꾸기 ›", fontSize = 13.sp, color = K.sub)
                        }
                        Spacer(Modifier.padding(4.dp))
                        Section("방향")
                        Seg(listOf("아래", "왼쪽", "오른쪽", "위"), pg.dir) { pg.dir = it; tick++ }
                        Spacer(Modifier.padding(4.dp))
                        Section("언제 실행?")
                        Seg(listOf("말 걸기", "밟으면", "자동"), TRIGGERS.indexOf(pg.trigger).coerceAtLeast(0)) { pg.trigger = TRIGGERS[it]; tick++ }
                        Text(
                            when (pg.trigger) {
                                "touch" -> "주인공이 이 칸을 밟으면 실행. 문 이동 칸은 그래픽 '없음'으로."
                                "auto" -> "이 페이지가 켜지면 맵에 들어올 때 한 번 자동 실행."
                                else -> "주인공이 바라보고 A 버튼을 누르면 실행."
                            }, fontSize = 12.sp, color = K.sub, modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.padding(4.dp))
                        Section("조건 (이 스위치가 켜져 있을 때만 이 페이지)")
                        var cond by remember(page, ev.id) { mutableStateOf(pg.condSwitch) }
                        Field(cond, { cond = it; pg.condSwitch = it.trim() }, "스위치 이름 (비우면 항상)", Modifier.fillMaxWidth())
                        Spacer(Modifier.padding(6.dp))
                        Section("명령")
                    }
                    val lines = mutableListOf<Line>().also { flatten(pg.commands, 0, it) }
                    items(lines.size) { k ->
                        tick
                        when (val l = lines[k]) {
                            is Line.C -> {
                                val c = l.list[l.i]
                                Row(
                                    Modifier.fillMaxWidth().padding(start = (l.depth * 16).dp, top = 3.dp, bottom = 3.dp)
                                        .clip(RoundedCornerShape(10.dp)).background(K.card).border(1.dp, K.line, RoundedCornerShape(10.dp))
                                        .clickable { editing = l.list to l.i }.padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(Cmd.label(c.type), fontSize = 11.sp, color = K.accent, fontWeight = FontWeight.SemiBold)
                                        Text(c.summary(doc.p), fontSize = 14.sp, color = K.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (l.i > 0) IconBtn(Icons.Filled.KeyboardArrowUp, "위로") { val t = l.list.removeAt(l.i); l.list.add(l.i - 1, t); tick++ }
                                    if (l.i < l.list.size - 1) IconBtn(Icons.Filled.KeyboardArrowDown, "아래로") { val t = l.list.removeAt(l.i); l.list.add(l.i + 1, t); tick++ }
                                }
                            }
                            is Line.Add -> Row(
                                Modifier.padding(start = (l.depth * 16).dp, top = 2.dp, bottom = 6.dp).clip(RoundedCornerShape(50))
                                    .clickable { adding = l.list }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Icon(Icons.Filled.Add, null, tint = K.accent, modifier = Modifier.size(18.dp))
                                Text(" 명령 추가", fontSize = 14.sp, color = K.accent)
                            }
                            is Line.Label -> Text(l.text, fontSize = 12.sp, color = K.sub, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = (l.depth * 16).dp, top = 4.dp))
                        }
                    }
                    item { Spacer(Modifier.padding(30.dp)) }
                }
            }
        }
    }

    adding?.let { list ->
        AlertDialog(
            onDismissRequest = { adding = null }, containerColor = K.bg,
            title = { Text("어떤 명령?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Cmd.TYPES.forEach { t ->
                        Text(Cmd.label(t), fontSize = 16.sp, color = K.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable {
                                list.add(Cmd.make(t)); adding = null; tick++
                                if (t != "end") editing = list to list.size - 1
                            }.padding(horizontal = 8.dp, vertical = 12.dp))
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { adding = null }) { Text("취소", color = K.sub) } },
        )
    }

    editing?.let { (list, i) ->
        val c = list.getOrNull(i)
        if (c != null) {
            CmdDialog(doc.p, c, onDone = { editing = null; tick++ }, onDelete = { list.removeAt(i); editing = null; tick++ })
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, fontSize = 13.sp, color = K.sub, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun CmdDialog(p: Project, c: Cmd, onDone: () -> Unit, onDelete: () -> Unit) {
    var speaker by remember { mutableStateOf(c.speaker) }
    var text by remember { mutableStateOf(c.text) }
    var name by remember { mutableStateOf(c.name) }
    var on by remember { mutableStateOf(c.on) }
    var cond by remember { mutableStateOf(c.cond) }
    var options by remember { mutableStateOf(c.options.toList()) }
    var mapId by remember { mutableIntStateOf(if (p.map(c.map) != null) c.map else p.maps[0].id) }
    var x by remember { mutableStateOf(c.x.toString()) }
    var y by remember { mutableStateOf(c.y.toString()) }
    var ms by remember { mutableStateOf(c.ms.toString()) }
    var amount by remember { mutableStateOf(c.amount.toString()) }
    var out by remember { mutableStateOf(c.out) }
    var target by remember { mutableStateOf(c.target) }
    var route by remember { mutableStateOf(c.route) }
    var toPos by remember { mutableStateOf(c.toPos) }
    val switches = remember { p.switches() }

    AlertDialog(
        onDismissRequest = onDone, containerColor = K.bg,
        title = { Text(Cmd.label(c.type)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (c.type) {
                    "text" -> {
                        Field(speaker, { speaker = it }, "말하는 사람 (비워도 돼요)", Modifier.fillMaxWidth())
                        Field(text, { text = it }, "대사", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                    }
                    "choice" -> {
                        options.forEachIndexed { k, o ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field(o, { v -> options = options.toMutableList().also { it[k] = v } }, "선택지 ${k + 1}", Modifier.weight(1f))
                                if (options.size > 2) IconBtn(Icons.Filled.Close, "빼기") { options = options.toMutableList().also { it.removeAt(k) } }
                            }
                        }
                        if (options.size < 4) Pill("+ 선택지") { options = options + "선택 ${options.size + 1}" }
                    }
                    "switch", "if" -> {
                        if (c.type == "if") Seg(listOf("스위치", "아이템"), if (cond == "item") 1 else 0) { cond = if (it == 1) "item" else "switch" }
                        Field(name, { name = it }, if (c.type == "if" && cond == "item") "아이템 이름" else "스위치 이름", Modifier.fillMaxWidth())
                        if (switches.isNotEmpty() && !(c.type == "if" && cond == "item")) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(switches) { s -> Pill(s, on = s == name) { name = s } } }
                        }
                        if (c.type == "switch" || cond == "switch") Seg(if (c.type == "switch") listOf("켜기", "끄기") else listOf("켜져 있으면", "꺼져 있으면"), if (on) 0 else 1) { on = it == 0 }
                    }
                    "transfer" -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(p.maps) { m -> Pill(m.name, on = m.id == mapId) { mapId = m.id } } }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(x, { x = it.filter(Char::isDigit).take(2) }, "x", Modifier.weight(1f))
                            Field(y, { y = it.filter(Char::isDigit).take(2) }, "y", Modifier.weight(1f))
                        }
                        Text("도착할 칸의 좌표 (왼쪽 위가 0, 0)", fontSize = 12.sp, color = K.sub)
                    }
                    "goal" -> {
                        val off = target.toIntOrNull() == null
                        Seg(listOf("목적지 정하기", "화살표 끄기"), if (off) 1 else 0) { target = if (it == 1) "off" else (p.map(mapId)?.events?.firstOrNull()?.id?.toString() ?: "1") }
                        if (!off) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(p.maps) { m -> Pill(m.name, on = m.id == mapId) { mapId = m.id; target = m.events.firstOrNull()?.id?.toString() ?: "1" } } }
                            val evs = p.map(mapId)?.events.orEmpty()
                            if (evs.isEmpty()) Text("이 맵에 이벤트가 없어요", fontSize = 12.sp, color = K.sub)
                            else LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(evs) { e -> Pill(e.name.ifBlank { "#${e.id}" }, on = target == e.id.toString()) { target = e.id.toString() } } }
                        }
                        Text("플레이 중 그 이벤트 위에 화살표가 떠요. 다른 맵이면 그쪽으로 가는 문을 가리키고, 그 이벤트를 실행하면 저절로 꺼져요.", fontSize = 12.sp, color = K.sub)
                    }
                    "item" -> {
                        Field(name, { name = it }, "아이템 이름", Modifier.fillMaxWidth())
                        Field(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) }, "개수 (잃으면 -1)", Modifier.fillMaxWidth())
                    }
                    "move" -> {
                        val sel = when (target) { "player" -> 0; "this" -> 1; else -> 2 }
                        Seg(listOf("주인공", "이 이벤트", "다른 이벤트"), sel) { target = when (it) { 0 -> "player"; 1 -> "this"; else -> if (target.toIntOrNull() != null) target else "1" } }
                        if (sel == 2) Field(target, { target = it.filter(Char::isDigit).take(4).ifEmpty { "" } }, "이벤트 번호 (#)", Modifier.fillMaxWidth())
                        Seg(listOf("좌표로", "경로로"), if (toPos) 0 else 1) { toPos = it == 0 }
                        if (toPos) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(x, { x = it.filter(Char::isDigit).take(3) }, "X", Modifier.weight(1f))
                                Field(y, { y = it.filter(Char::isDigit).take(3) }, "Y", Modifier.weight(1f))
                            }
                            Text("그 칸까지 알아서 길을 찾아 걸어가요. 가는 방향을 보면서 걸어요.", fontSize = 12.sp, color = K.sub)
                        } else {
                        Field(route, { route = it.filter { ch -> ch in "UDLRudlr." } }, "경로", Modifier.fillMaxWidth())
                        Text(routeText(route).ifEmpty { "(비어 있음)" }, fontSize = 18.sp, color = K.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("↑" to "U", "↓" to "D", "←" to "L", "→" to "R", "쉬기" to ".").forEach { (l, ch) -> Pill(l) { route += ch } }
                            Pill("⌫") { route = route.dropLast(1) }
                        }
                        Text("U·D·L·R = 한 칸 걷기, u·d·l·r = 그쪽 보기, . = 잠깐 쉬기. 막힌 칸은 건너뛰어요.", fontSize = 12.sp, color = K.sub)
                        }
                    }
                    "wait" -> Field(ms, { ms = it.filter(Char::isDigit).take(5) }, "밀리초 (1000 = 1초)", Modifier.fillMaxWidth())
                    "fade" -> Seg(listOf("어둡게", "밝게"), if (out) 0 else 1) { out = it == 0 }
                    else -> Text("설정할 게 없어요", color = K.sub)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.speaker = speaker.trim(); c.text = text; c.name = name.trim(); c.on = on; c.cond = cond
                if (c.type == "choice") {
                    c.options = options.map { it.ifBlank { "…" } }.toMutableList()
                    while (c.branches.size < c.options.size) c.branches.add(mutableListOf())
                    while (c.branches.size > c.options.size) c.branches.removeAt(c.branches.size - 1)
                }
                c.map = mapId; c.x = x.toIntOrNull() ?: 0; c.y = y.toIntOrNull() ?: 0
                c.ms = ms.toIntOrNull() ?: 500; c.amount = amount.toIntOrNull() ?: 1; c.out = out
                c.target = target.ifBlank { "player" }; c.route = if (toPos) "" else route; c.toPos = toPos
                if (c.type == "goal") c.map = mapId
                onDone()
            }) { Text("확인") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("삭제", color = K.danger) }
                TextButton(onClick = onDone) { Text("취소", color = K.sub) }
            }
        },
    )
}

/** 그래픽 고르기 창: 이름으로 검색, 격자로 보기. */
@Composable
fun GraphicPicker(current: String, dir: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val crev = Chars.rev
    val all = Sprites.ids
    val list = if (q.isBlank()) all else all.filter { Sprites.name(it).contains(q.trim(), ignoreCase = true) || it.contains(q.trim(), ignoreCase = true) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f).clip(RoundedCornerShape(18.dp)).background(K.bg).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("그래픽 고르기", fontSize = 17.sp, color = K.text, modifier = Modifier.weight(1f))
                Text("${list.size}개", fontSize = 12.sp, color = K.sub)
            }
            Spacer(Modifier.padding(4.dp))
            Field(q, { q = it }, "검색 (예: cat, 고양이, Male)", Modifier.fillMaxWidth())
            Spacer(Modifier.padding(4.dp))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(76.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list.size) { i ->
                    val g = list[i]
                    val sel = g == current
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onPick(g) }.padding(2.dp)) {
                        Canvas(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(K.panel)
                            .border(if (sel) 2.5.dp else 1.dp, if (sel) K.accent else K.line, RoundedCornerShape(10.dp))) {
                            drawIntoCanvas { c -> Art.sprite(c.art(), g, dir, size.width * 0.1f, size.height * 0.1f, size.width * 0.8f) }
                        }
                        Text(Sprites.name(g), fontSize = 11.sp, color = K.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("닫기", color = K.sub) }
            }
        }
    }
}
