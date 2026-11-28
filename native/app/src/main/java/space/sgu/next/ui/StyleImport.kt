package space.sgu.next.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.NaiMeta
import space.sgu.next.data.NaiModels
import space.sgu.next.data.StyleLibrary
import space.sgu.next.data.d
import space.sgu.next.data.s
import space.sgu.next.data.with
import java.io.File

/** 그림체 설정 › 가져오기: NovelAI 이미지에서 읽은 그림체 (편집 화면으로 넘겨줌). */
object StyleImport {
    var pending by mutableStateOf<JsonObject?>(null)
}

/** 이미지를 고르면 메타데이터를 읽어 편집 화면(styleimport)을 엶. */
@Composable
fun rememberStyleImporter(open: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(ctx.cacheDir, "style-import.png")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    NaiMeta.styleData(tmp).also { tmp.delete() }
                }.getOrNull()
            }
            if (data == null) Toast.makeText(ctx, "이 이미지에는 NovelAI 생성 정보가 없어요. NovelAI에서 받은 원본 PNG를 골라 주세요.", Toast.LENGTH_LONG).show()
            else { StyleImport.pending = data; open() }
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

@Composable
fun StyleImportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(StyleImport.pending) }
    val d = data ?: run { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }
    var editing by remember { mutableStateOf<String?>(null) }   // 고치는 칸의 키
    var naming by remember { mutableStateOf(false) }
    val summary = listOfNotNull(
        d.s("model name").takeIf { it.isNotBlank() }?.let { NaiModels.label(it) } ?: "모델 정보 없음",
        d.s("Sampler").takeIf { it.isNotBlank() }?.let { s -> NaiModels.samplers.firstOrNull { it.first == s }?.second ?: s },
        d.d("Steps")?.let { "스텝 ${it.toInt()}" },
    ).joinToString(" · ")

    Page("그림체 가져오기", summary, { StyleImport.pending = null; onBack() }) {
        Hint("이미지에서 캐릭터 프롬프트를 뺀 긍정·부정 프롬프트와 모델·샘플러·스텝을 가져왔어요. 칸을 누르면 고칠 수 있어요.")
        listOf("Art style prompt" to "긍정 프롬프트", "Art style negative" to "부정 프롬프트").forEach { (k, label) ->
            FieldLabel(label)
            LineCard(onClick = { editing = k }) {
                Text(d.s(k).ifBlank { "(비어 있음)" }, style = MaterialTheme.typography.bodyMedium, color = if (d.s(k).isBlank()) Sgu.Sub else Sgu.Ink)
            }
        }
        Spacer(Modifier.height(20.dp))
        InkButton("저장", { naming = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Check)
    }

    editing?.let { k ->
        var text by remember(k) { mutableStateOf(d.s(k)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (k == "Art style prompt") "긍정 프롬프트" else "부정 프롬프트") },
            text = { InputBox(text, { text = it }, singleLine = false) },
            confirmButton = { TextButton(onClick = { data = d.with(k to JsonPrimitive(text)); editing = null }) { Text("확인", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Bg,
        )
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("저장할 이름") },
            text = { InputBox(name, { name = it }, placeholder = "그림체 이름") },
            confirmButton = { TextButton(onClick = {
                val n = name.trim().ifBlank { "그림체" }
                StyleLibrary.saveAs(n, d)
                Toast.makeText(ctx, "'$n'을(를) 그림체 라이브러리에 저장했어요", Toast.LENGTH_SHORT).show()
                naming = false; StyleImport.pending = null; onBack()
            }) { Text("저장", color = Sgu.Ink) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Bg,
        )
    }
}
