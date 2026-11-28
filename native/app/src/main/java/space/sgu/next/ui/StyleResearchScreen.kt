package space.sgu.next.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import space.sgu.next.data.GenOps
import space.sgu.next.data.ImageGenStore
import space.sgu.next.data.StyleLibrary
import space.sgu.next.data.StyleResearch
import space.sgu.next.data.arr
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull

@Composable
fun StyleResearchScreen(cid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = remember(cid, ImageGenStore.version) { ImageGenStore.load(cid) }
    val data = remember(cid, StyleResearch.version) { StyleResearch.load(cid) }
    val chars = g.arr("characters")
    val ch = chars.firstOrNull { it.s("id") == g.sOrNull("selectedCharacterId") } ?: chars.firstOrNull()
    var artists by remember(cid) { mutableStateOf(data.s("artists")) }
    var count by remember { mutableStateOf("10") }
    var asking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }   // 저장할 조합
    var clearAsk by remember { mutableStateOf(0) }
    val run = StyleResearch.progress[cid]
    val items = data.arr("items")
    KeepScreenOn(run != null)
    val n = count.toIntOrNull()?.coerceIn(1, StyleResearch.MAX) ?: 10
    val medium = GenOps.noNegatives(null)

    Page("그림체 연구", ch?.let { "${it.s("name")} 기준" + if (items.isNotEmpty()) " · ${items.size}장" else "" }, onBack, actions = {
        if (items.isNotEmpty() && run == null) TextButton(onClick = { clearAsk = 1 }) { Text("전체 삭제", color = Sgu.Danger) }
    }) {
        Hint("작가 순서를 섞고 작가마다 0.2~1.0(0.05 단위) 가중치를 랜덤으로 붙여 한 장씩 뽑아요. 지금 열려 있는 캐릭터의 켜진 블록과 자유 프롬프트로, 한 번 연구하는 동안 같은 시드로 그리고, 퀄리티 태그·부정 프롬프트·모델은 그림체 설정 값을 써요. 마음에 드는 사진을 눌러 저장하면 그림체 라이브러리에 들어가요.")
        FieldLabel("작가 태그 (쉼표·줄바꿈으로 구분)")
        InputBox(artists, { artists = it }, placeholder = "artist:ebora, artist:fishine …", singleLine = false)
        FieldLabel("장수 (최대 ${StyleResearch.MAX})")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputBox(count, { count = it.filter(Char::isDigit).take(3) }, number = true, modifier = Modifier.width(110.dp))
            if (run != null) InkButton("중지 ${run.done}/${run.total}", { StyleResearch.stop(cid) }, Modifier.weight(1f), icon = Icons.Outlined.Stop)
            else InkButton("연구 시작", { if (ch == null) Toast.makeText(ctx, "이미지 캐릭터가 없어요", Toast.LENGTH_SHORT).show() else asking = true },
                Modifier.weight(1f), icon = Icons.Filled.AutoAwesome)
        }
        StyleResearch.error[cid]?.let { e ->
            Spacer(Modifier.height(8.dp))
            LineCard(onClick = { StyleResearch.error.remove(cid) }) { Text(e, style = MaterialTheme.typography.bodyMedium, color = Sgu.Danger) }
        }
        if (items.isNotEmpty()) FieldLabel("결과 (최근 순)")
        items.chunked(3).forEach { row ->
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { it2 ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { open = it2 }) {
                        FileImage(ImageGenStore.imageFile(cid, it2.s("file")), Modifier.fillMaxWidth().aspectRatio(832f / 1216f).clip(RoundedCornerShape(12.dp)), 384)
                        Text(it2.s("combo"), style = MaterialTheme.typography.labelSmall, color = Sgu.Sub, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("${n}장을 뽑을까요?") },
        text = { Text("작가 ${StyleResearch.artists(artists).size}명 · ${ch?.s("name").orEmpty()} 기준이에요." + if (medium) "" else "\nV5M이 아니면 Anlas가 들 수 있어요.") },
        confirmButton = { TextButton(onClick = { asking = false; ch?.let { StyleResearch.start(cid, it.s("id"), artists, n) } }) { Text("시작", color = Sgu.Ink) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("취소", color = Sgu.Sub) } },
        containerColor = Sgu.Bg,
    )
    open?.let { it2 ->
        AlertDialog(
            onDismissRequest = { open = null },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    FileImage(ImageGenStore.imageFile(cid, it2.s("file")), Modifier.fillMaxWidth().aspectRatio(832f / 1216f).clip(RoundedCornerShape(12.dp)), 1024)
                    Text(it2.s("combo"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { naming = it2.s("combo"); open = null }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { StyleResearch.delete(cid, setOf(it2.s("file"))); open = null }) { Text("삭제", color = Sgu.Danger) }
                    TextButton(onClick = { open = null }) { Text("닫기", color = Sgu.Sub) }
                }
            },
            containerColor = Sgu.Bg,
        )
    }
    naming?.let { combo ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("저장할 이름") },
            text = { InputBox(name, { name = it }, placeholder = "그림체 이름") },
            confirmButton = { TextButton(onClick = {
                val nm = name.trim().ifBlank { "그림체" }
                StyleLibrary.saveAs(nm, StyleResearch.styleData(cid, combo))
                Toast.makeText(ctx, "'$nm'을(를) 그림체 라이브러리에 저장했어요", Toast.LENGTH_SHORT).show()
                naming = null
            }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Bg,
        )
    }
    if (clearAsk > 0) AlertDialog(
        onDismissRequest = { clearAsk = 0 },
        title = { Text(if (clearAsk == 1) "${items.size}장을 정말 삭제할까요?" else "${items.size}장을 정말정말 삭제할까요?") },
        confirmButton = { TextButton(onClick = {
            if (clearAsk == 1) clearAsk = 2 else { clearAsk = 0; StyleResearch.delete(cid, items.map { it.s("file") }.toSet()) }
        }) { Text("삭제", color = Sgu.Danger) } },
        dismissButton = { TextButton(onClick = { clearAsk = 0 }) { Text("취소", color = Sgu.Ink) } },
        containerColor = Sgu.Bg,
    )
}
