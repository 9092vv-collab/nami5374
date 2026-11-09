package space.sgu.next.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.sgu.next.data.CardIO
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.Exporter
import space.sgu.next.data.ImageGenStore
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 내보내기 (original module 1651). */
@Composable
fun ExportScreen(cid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = CardIO.str(CharacterStore.readCard(cid)["data"] as? JsonObject, "name").ifBlank { "이름 없는 캐릭터" }
    var format by remember { mutableStateOf(Exporter.Format.Charx) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    var asking by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(Exporter.summary(cid)) }
    var built by remember { mutableStateOf<File?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val f = built
        if (uri == null || f == null) { saved = false; return@rememberLauncherForActivityResult }
        scope.launch {
            saved = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false) }
            if (saved == false) error = "저장할 곳을 열 수 없어요"
        }
    }

    fun run(compress: Boolean?) {
        val targets = ImageGenStore.countCompressTargets(cid, true)
        if (targets > 0 && compress == null) { asking = true; return }
        busy = true; error = null; result = null; saved = null
        scope.launch {
            try {
                if (targets > 0 && compress == true) {
                    ImageGenStore.compress(cid, true, 80, true) { d, t -> status = "이미지 압축 중 ($d/$t)" }
                }
                status = "내보내는 중"
                val fileName = Exporter.fileName(cid, format)
                val out = File(ctx.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }.let { File(it, fileName) }
                withContext(Dispatchers.IO) { Exporter.build(cid, format, out) }
                built = out
                result = fileName to out.length()
                summary = Exporter.summary(cid)
                saver.launch(fileName)
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally { status = null; busy = false }
        }
    }

    Page("내보내기", name, onBack) {
        Spacer(Modifier.height(8.dp))
        LineCard {
            Column {
                Exporter.Format.entries.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { format = f }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == f, onClick = { format = f }, enabled = !busy, colors = RadioButtonDefaults.colors(selectedColor = Sgu.Ink))
                        Column(Modifier.weight(1f)) {
                            Text(f.label, style = MaterialTheme.typography.bodyLarge)
                            Text(f.hint, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                        }
                    }
                }
            }
        }
        SectionLabel("포함 내용")
        LineCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val s = summary
                Text("아이콘 ${s.icons} · 감정 이미지 ${s.emotions} · 추가 에셋 ${s.extra}", style = MaterialTheme.typography.bodyMedium)
                Text("로어북 ${s.lore} · 정규식 ${s.regex} · 트리거 ${s.trigger ?: "없음"}", style = MaterialTheme.typography.bodyMedium)
                Text("디스크립션 ${s.desc}자 · 첫 메시지 ${s.first}자 · 추가 첫 메시지 ${s.alt}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        InkButton(if (busy) (status ?: "내보내는 중") else if (result != null) "다시 내보내기" else "파일 내보내기",
            { run(null) }, Modifier.fillMaxWidth(), icon = if (result != null) Icons.Outlined.Refresh else Icons.Outlined.IosShare, enabled = !busy)
        result?.let { (fn, size) ->
            val sz = if (size < 1048576) "%.1f KB".format(size / 1024.0) else "%.2f MB".format(size / 1024.0 / 1024.0)
            Text("$fn · $sz " + when (saved) { true -> "기기에 저장됨"; false -> "· 저장 안 함"; null -> "" },
                style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 10.dp))
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
        Text("RisuAI에서 가져오기 › 파일 선택으로 불러옵니다. CHARX는 트리거/정규식/로어북이 card.json에 포함되어 그대로 복원됩니다.",
            style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 16.dp))
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false }, containerColor = Sgu.Card,
        title = { Text("이미지 압축 안내") },
        text = { Text("현재 이미지 데이터가 압축되지 않았습니다. 캐릭터 카드의 용량이 매우 클 수 있습니다") },
        confirmButton = { TextButton(onClick = { asking = false; run(true) }) { Text("이미지 압축하기", color = Sgu.Ink) } },
        dismissButton = { TextButton(onClick = { asking = false; run(false) }) { Text("그대로 내보내기", color = Sgu.Sub) } },
    )
}
