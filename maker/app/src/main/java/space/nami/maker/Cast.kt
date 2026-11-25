package space.nami.maker

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** 등장인물(주연·조연) 설정: 이름·역할·설명·걷기 그림·스탠딩 일러스트·얼굴 영역·표정. */
@Composable
fun CastScreen(doc: Doc, onClose: () -> Unit) {
    remember { doc.checkpoint(); 0 }
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    fun close() { doc.dropIfSame(); doc.changed(); onClose() }
    val cur = open?.let { id -> doc.p.cast.firstOrNull { it.id == id } }
    androidx.activity.compose.BackHandler(enabled = cur != null) { open = null }

    Dialog(onDismissRequest = { if (cur != null) open = null else close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding().imePadding()) {
            tick
            @Suppress("UNUSED_VARIABLE") val sr = Stands.rev
            if (cur != null) CastDetail(doc, cur, onBack = { open = null; tick++ }, onDeleted = { open = null; tick++ }, onChange = { tick++ })
            else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.Close, "닫기") { close() }
                    Text("등장인물", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("주연·조연을 정해 두면 AI가 이 인물들을 그대로 써요. 엑스트라는 AI가 알아서 만들어요.", fontSize = 13.sp, color = K.sub)
                    doc.p.cast.forEach { c ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.panel).clickable { open = c.id }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(40.dp)) { drawIntoCanvas { cv -> Art.sprite(cv.art(), c.sprite.ifBlank { "villager" }, 0, 0f, 0f, size.width) } }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(c.name, fontSize = 15.sp, color = K.text)
                                Text((if (c.role == "support") "조연" else "주연") + " · " +
                                    (if (Stands.hasBase(c.id)) "스탠딩 있음" + (if (c.faces.isNotEmpty()) " · 표정 ${c.faces.size}개" else "") else "스탠딩 없음"),
                                    fontSize = 12.sp, color = K.sub)
                            }
                            Text("›", color = K.sub)
                        }
                    }
                    Text("+ 등장인물 추가", color = K.accent, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        val c = CastMember("c" + System.currentTimeMillis().toString(36), "인물 ${doc.p.cast.size + 1}")
                        c.role = if (doc.p.cast.isEmpty()) "main" else "support"
                        doc.p.cast.add(c); doc.changed(); open = c.id; tick++
                    }.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun CastDetail(doc: Doc, c: CastMember, onBack: () -> Unit, onDeleted: () -> Unit, onChange: () -> Unit) {
    var name by remember(c.id) { mutableStateOf(c.name) }
    var desc by remember(c.id) { mutableStateOf(c.desc) }
    var tick by remember { mutableIntStateOf(0) }
    var spritePick by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var newFace by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun bump() { tick++; doc.changed(); onChange() }
    fun commit() { c.name = name.trim().ifBlank { c.name }; c.desc = desc }
    if (spritePick) GraphicPicker(c.sprite.ifBlank { "villager" }, 0, onPick = { g -> c.sprite = if (g == "none") "" else g; spritePick = false; bump() }) { spritePick = false }

    val pickBase = rememberFilePicker { _, bytes ->
        if (Stands.setBase(c.id, bytes)) {
            Stands.base(c.id)?.let { b -> if (!c.hasFace() || c.face[0] + c.face[2] > b.width || c.face[1] + c.face[3] > b.height)
                c.face = intArrayOf(b.width * 3 / 8, b.height / 20, b.width / 4, b.width / 4) }
            msg = "스탠딩을 넣었어요. 그림 위를 끌어서 얼굴 영역을 정해 주세요."; bump()
        } else msg = "그림 파일이 아니에요."
    }
    val pickFace = rememberFilePicker { fname, bytes ->
        val n = newFace.trim().ifBlank { fname.substringBeforeLast('.') }.take(20)
        if (n.isBlank()) return@rememberFilePicker
        if (Stands.addFace(c, n, bytes)) { if (n !in c.faces) c.faces.add(n); newFace = ""; preview = n; msg = "표정 '$n'을 넣었어요."; bump() }
        else msg = "그림 파일이 아니에요."
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") { commit(); bump(); onBack() }
            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (!confirmDel) { confirmDel = true; return@TextButton }
                doc.p.cast.remove(c); Stands.delete(c.id); doc.changed(); onDeleted()
            }) { Text(if (confirmDel) "정말 지울까요?" else "삭제", color = K.danger) }
        }
        tick
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(name, { name = it; commit() }, "이름 (대사의 말하는 사람과 같게)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("주연", on = c.role == "main") { c.role = "main"; bump() }
                Pill("조연", on = c.role == "support") { c.role = "support"; bump() }
            }
            Field(desc, { desc = it; commit() }, "설명 (성격·말투·관계 — AI가 읽어요)", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.panel).clickable { spritePick = true }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(40.dp)) { drawIntoCanvas { cv -> Art.sprite(cv.art(), c.sprite.ifBlank { "villager" }, 0, 0f, 0f, size.width) } }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("걷기 그림", fontSize = 15.sp, color = K.text)
                    Text(if (c.sprite.isBlank()) "아직 안 정함 (AI가 이 인물을 만들 때 씀)" else Sprites.name(c.sprite), fontSize = 12.sp, color = K.sub)
                }
                Text("바꾸기 ›", fontSize = 13.sp, color = K.sub)
            }

            Text("스탠딩 일러스트", fontSize = 13.sp, color = K.sub, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text("서 있는 전신 그림을 넣고, 그림 위를 끌어서 얼굴 영역(네모)을 정해요. 표정 그림은 그 영역에 덮어 그려져요.", fontSize = 12.sp, color = K.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (Stands.hasBase(c.id)) "스탠딩 바꾸기" else "스탠딩 넣기") { pickBase() }
            }
            val base = Stands.base(c.id)
            if (base != null) {
                FaceArea(c, base, preview) { bump() }
                Text("얼굴 영역: ${c.face[0]}, ${c.face[1]} · ${c.face[2]}×${c.face[3]}", fontSize = 12.sp, color = K.sub)

                Text("표정", fontSize = 13.sp, color = K.sub, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("스탠딩과 같은 크기의 그림(표정만 바꾼 것)을 넣으면 얼굴 영역만 잘라 써요. 얼굴만 있는 그림이면 그대로 얼굴 영역에 맞춰요.", fontSize = 12.sp, color = K.sub)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("기본", on = preview.isBlank()) { preview = "" }
                    c.faces.forEach { f -> Pill(f, on = preview == f) { preview = f } }
                }
                if (preview.isNotBlank()) Text("'$preview' 지우기", color = K.danger, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    Stands.removeFace(c.id, preview); c.faces.remove(preview); preview = ""; bump()
                }.padding(6.dp))
                Field(newFace, { newFace = it }, "새 표정 이름 (예: 웃음, 화남, 놀람, 슬픔)", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("그림 골라 넣기") { pickFace() }
                    Pill(if (busy) "만드는 중…" else "AI로 만들기") {
                        val n = newFace.trim()
                        if (busy) return@Pill
                        if (n.isBlank()) { msg = "표정 이름을 먼저 써 주세요."; return@Pill }
                        if (!c.hasFace()) { msg = "얼굴 영역을 먼저 정해 주세요."; return@Pill }
                        busy = true; msg = "표정 그리는 중… (30초쯤 걸려요)"
                        scope.launch {
                            msg = runCatching {
                                val crop = faceCrop(base, c.face)
                                val out = Api.image(listOf(crop), FACE_PROMPT.replace("{{face}}", n), Api.imageModel().ifBlank { "gemini-3.1-flash-image" })
                                if (!Stands.addFace(c, n, out)) error("받은 그림을 읽지 못했어요.")
                                if (n !in c.faces) c.faces.add(n)
                                newFace = ""; preview = n; bump()
                                "표정 '$n'을 만들었어요."
                            }.getOrElse { "못 만들었어요: " + (it.message ?: it.toString()) }
                            busy = false
                        }
                    }
                }
            }
            if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = K.sub)
            Spacer(Modifier.size(40.dp))
        }
    }
}

private const val FACE_PROMPT = """This image is the face area cropped from a character's standing illustration.
Redraw exactly the same crop with only the facial expression changed to: {{face}}.
Keep the same art style, line weight, colors, hair, lighting, framing, size and position so it can be pasted back over the original seamlessly. Do not change anything outside the face. Output only the image."""

private fun faceCrop(b: Bitmap, f: IntArray): ByteArray {
    val x = f[0].coerceIn(0, b.width - 1); val y = f[1].coerceIn(0, b.height - 1)
    val cut = Bitmap.createBitmap(b, x, y, f[2].coerceAtMost(b.width - x).coerceAtLeast(1), f[3].coerceAtMost(b.height - y).coerceAtLeast(1))
    return java.io.ByteArrayOutputStream().also { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

/** 스탠딩 미리보기 + 얼굴 영역 끌어서 정하기. */
@Composable
private fun FaceArea(c: CastMember, base: Bitmap, preview: String, onSet: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val sr = Stands.rev
    val shown = Stands.composed(c, preview) ?: base
    val ib = remember(shown, sr) { shown.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val ratio = base.width.toFloat() / base.height.coerceAtLeast(1)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, 460.dp * ratio)
        Box(Modifier.size(w, w / ratio)
            .border(1.dp, K.line).background(Color(0xFFEDEAE2))
            .onSizeChanged { box = it }
            .pointerInput(c.id) {
                detectDragGestures(
                    onDragStart = { o -> drag = o to o },
                    onDrag = { ch, d -> ch.consume(); drag = drag?.let { it.first to (it.second + d) } },
                    onDragEnd = {
                        val d = drag; drag = null
                        if (d != null && box.width > 0) {
                            val s = base.width.toFloat() / box.width
                            val x0 = (min(d.first.x, d.second.x) * s).toInt().coerceIn(0, base.width - 1)
                            val y0 = (min(d.first.y, d.second.y) * s).toInt().coerceIn(0, base.height - 1)
                            val x1 = (max(d.first.x, d.second.x) * s).toInt().coerceIn(x0 + 1, base.width)
                            val y1 = (max(d.first.y, d.second.y) * s).toInt().coerceIn(y0 + 1, base.height)
                            if (x1 - x0 >= 4 && y1 - y0 >= 4) { c.face = intArrayOf(x0, y0, x1 - x0, y1 - y0); onSet() }
                        }
                    },
                    onDragCancel = { drag = null },
                )
            }) {
            Image(ib, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / base.width
                val d = drag
                if (d != null) {
                    val tl = Offset(min(d.first.x, d.second.x), min(d.first.y, d.second.y))
                    drawRect(K.accent, tl, Size(kotlin.math.abs(d.second.x - d.first.x), kotlin.math.abs(d.second.y - d.first.y)), style = Stroke(2.dp.toPx()))
                } else if (c.hasFace()) {
                    drawRect(K.accent, Offset(c.face[0] * s, c.face[1] * s), Size(c.face[2] * s, c.face[3] * s), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}
