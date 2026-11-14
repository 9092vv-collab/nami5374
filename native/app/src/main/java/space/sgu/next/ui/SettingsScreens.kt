package space.sgu.next.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import space.sgu.next.data.DriveBackup
import space.sgu.next.data.Llm
import space.sgu.next.data.Presets
import space.sgu.next.data.SettingsStore
import space.sgu.next.data.Vertex
import space.sgu.next.data.arr
import space.sgu.next.data.b
import space.sgu.next.data.d
import space.sgu.next.data.num
import space.sgu.next.data.o
import space.sgu.next.data.s
import space.sgu.next.data.sOrNull
import space.sgu.next.data.str
import space.sgu.next.data.with

// ---------- shared inputs ----------

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Sgu.Sub,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
}

/** Grows with its text (no inner scrolling). */
@Composable
fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, color = Sgu.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Decimal; password -> KeyboardType.Password; else -> KeyboardType.Text }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Sgu.Card, focusedContainerColor = Sgu.Card,
            unfocusedBorderColor = Sgu.Line, focusedBorderColor = Sgu.Ink,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun fmt(v: Double?): String = when {
    v == null -> ""
    v == Math.floor(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
    else -> v.toString()
}

/** Number box; blank = null ("비우면 전송하지 않음"). */
@Composable
fun NumberBox(key: String, value: Double?, onChange: (Double?) -> Unit, placeholder: String = "비움") {
    var t by remember(key) { mutableStateOf(fmt(value)) }
    InputBox(t, { s ->
        t = s
        if (s.isBlank()) onChange(null) else s.toDoubleOrNull()?.let(onChange)
    }, placeholder = placeholder, number = true)
}

@Composable
private fun PickerDialog(title: String, options: List<Pair<String?, String>>, selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (id == selected) Sgu.Bubble else Color.Transparent)
                            .clickable { onPick(id); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Sgu.Sub) } },
        containerColor = Sgu.Card,
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Sgu.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Sgu.Sub) } },
        containerColor = Sgu.Card,
    )
}

@Composable
private fun ResultLine(ok: Boolean?, text: String) {
    if (text.isEmpty()) return
    Row(Modifier.padding(start = 6.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        when (ok) {
            true -> Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF3F8F5A))
            false -> Icon(Icons.Outlined.ErrorOutline, null, tint = Sgu.Danger)
            null -> CircularProgressIndicator(Modifier.padding(2.dp).width(18.dp).height(18.dp), color = Sgu.Ink, strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok == false) Sgu.Danger else Sgu.Ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(containerColor = Sgu.Bg, topBar = { SguTopBar(title, subtitle, onBack = onBack, actions = actions) }) { pad ->
        androidx.compose.runtime.CompositionLocalProvider(LocalGroupInset provides 0.dp) {
            Column(
                Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
            ) { content() }
        }
    }
}

private fun presetName(p: JsonObject?) = Presets.byId[p.s("preset")]?.name ?: "커스텀"

// ---------- 프로바이더 목록 ----------

@Composable
fun ProvidersScreen(open: (String) -> Unit, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val list = SettingsStore.providers
    Page("프로바이더", "엔드포인트 · 인증 · 요청 포맷", onBack, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "프로바이더 추가") }
    }) {
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            LineCard {
                Column {
                    Text("프로바이더가 없어요", style = MaterialTheme.typography.titleMedium)
                    Text("OpenAI(GPT), Anthropic(Claude), Google AI Studio, Vertex AI 중에서 추가하세요. 기존 앱의 설정 백업을 가져와도 돼요.",
                        style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("추가", onClick = { adding = true }, icon = Icons.Outlined.Add)
                        LineButton("백업 가져오기", onClick = { open("backup") }, icon = Icons.Outlined.FileUpload)
                    }
                }
            }
        }
        list.forEachIndexed { i, p ->
            val hint = SettingsStore.keyHint(p)
            val keyText = when {
                p.s("preset") == "vertex" -> if (hint != null) "서비스 계정 저장됨" else "서비스 계정 없음"
                p.s("auth") == "none" -> "인증 없음"
                hint != null -> "키 ····$hint"
                else -> "키 없음"
            }
            GroupRow(i, list.size, p.s("name"), subtitle = "${presetName(p)} · $keyText", icon = Icons.Outlined.Key,
                onClick = { open("provider/${p.s("id")}") })
        }
        Hint("프로바이더를 추가한 뒤 그 화면에서 API 키와 모델을 함께 설정합니다.")
    }
    if (adding) {
        PickerDialog(
            "프리셋 선택",
            Presets.all.filterNot { it.hidden }.map { it.id to it.name },
            null,
            onPick = { id -> Presets.byId[id]?.let { open("provider/" + SettingsStore.addProvider(it)) } },
            onDismiss = { adding = false },
        )
    }
}

// ---------- 프로바이더 상세 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(id: String, open: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = SettingsStore.provider(id)
    if (p == null) { Page("프로바이더", null, onBack) { EmptyPane("프로바이더를 찾을 수 없습니다.") }; return }
    val preset = Presets.byId[p.s("preset")]
    var keyInput by remember(id) { mutableStateOf("") }
    var check by remember(id) { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var serverModels by remember(id) { mutableStateOf<List<String>?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var manualModel by remember(id) { mutableStateOf("") }
    var saJson by remember(id) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isVertex = p.s("preset") == "vertex"
    val isJev = p.s("format") == "jev"
    val isImage = p.s("format") == "novelai-image"
    val editableUrl = preset?.editableUrl ?: true
    val myModels = SettingsStore.models.filter { it.s("providerId") == id }
    fun set(k: String, v: String) = SettingsStore.patch("providers", id, k to JsonPrimitive(v))
    fun runCheck() {
        check = null to "확인 중…"
        scope.launch { val (ok, msg) = Llm.checkConnection(SettingsStore.provider(id) ?: return@launch); check = ok to msg }
    }

    Page(p.s("name"), preset?.name ?: "커스텀", onBack, actions = {
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "프로바이더 삭제") }
    }) {
        if (p.s("preset") == "novelai" && p.s("format") != "novelai-image") {
            Spacer(Modifier.height(8.dp))
            LineCard {
                Column {
                    Text("이미지 생성용으로 쓰실 건가요?", style = MaterialTheme.typography.titleMedium)
                    Text("이 프로바이더는 NovelAI 텍스트(GLM)용이에요. 이미지 생성에는 image.novelai.net 주소와 NovelAI Image 포맷이 필요해요. 키는 그대로 유지돼요.",
                        style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    InkButton("이미지 생성용으로 바꾸기", onClick = {
                        SettingsStore.patch("providers", id,
                            "preset" to JsonPrimitive("novelai-image"), "format" to JsonPrimitive("novelai-image"),
                            "baseUrl" to JsonPrimitive(Presets.NAI_IMAGE_URL), "auth" to JsonPrimitive("bearer"))
                        SettingsStore.models.filter { it.s("providerId") == id }.forEach { SettingsStore.removeModel(it.s("id")) }
                        runCheck()
                    })
                }
            }
        }
        FieldLabel("이름")
        InputBox(p.s("name"), { set("name", it) })

        FieldLabel("Base URL")
        if (editableUrl && !isVertex) InputBox(p.s("baseUrl"), { set("baseUrl", it) }, placeholder = "https://…")
        else Text(p.s("baseUrl"), style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, modifier = Modifier.padding(start = 6.dp))
        if (!editableUrl) Hint("프리셋 기본 URL입니다. 프록시를 쓰면 고급에서 포맷을 '커스텀'처럼 바꿀 수 있어요.")

        if (isVertex) {
            val v = p.o("vertex")
            fun setV(k: String, value: String) = SettingsStore.patch("providers", id, "vertex" to JsonObject(v.orEmpty() + (k to JsonPrimitive(value))))
            FieldLabel("Vertex AI 서비스 계정")
            InputBox(v.s("projectId"), { setV("projectId", it) }, placeholder = "Project ID")
            Spacer(Modifier.height(8.dp))
            InputBox(v.s("clientEmail"), { setV("clientEmail", it) }, placeholder = "Client Email")
            Spacer(Modifier.height(8.dp))
            InputBox(v.s("location"), { setV("location", it) }, placeholder = "global 또는 us-central1 등")
            FieldLabel("Private Key " + if (SettingsStore.keyHint(p) != null) "· 저장됨" else "")
            InputBox(keyInput, { keyInput = it }, placeholder = "-----BEGIN PRIVATE KEY-----", singleLine = false)
            FieldLabel("또는 서비스 계정 JSON 붙여넣기")
            InputBox(saJson, { s ->
                saJson = s
                Vertex.parseServiceAccount(s)?.let { (pid, mail, pk) ->
                    SettingsStore.patch("providers", id, "vertex" to JsonObject(v.orEmpty() + mapOf(
                        "projectId" to JsonPrimitive(pid), "clientEmail" to JsonPrimitive(mail),
                        "location" to JsonPrimitive(v.s("location").ifBlank { "global" }),
                    )))
                    SettingsStore.setProviderKey(id, pk)
                    saJson = ""
                    Toast.makeText(ctx, "서비스 계정을 저장했어요", Toast.LENGTH_SHORT).show()
                    runCheck()
                }
            }, placeholder = "{ \"type\": \"service_account\", … }", singleLine = false)
        } else if (p.s("auth") != "none") {
            val hint = SettingsStore.keyHint(p)
            FieldLabel(if (p.s("preset") == "copilot") "GitHub 토큰" else "API 키" + if (hint != null) " · 저장됨 ····$hint" else "")
            InputBox(keyInput, { keyInput = it }, placeholder = preset?.keyPlaceholder ?: "API key", password = true)
            Hint("기기 보안 저장소에 암호화해 저장되며 화면에 다시 표시되지 않습니다.")
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.s("auth") != "none") {
                InkButton("키 저장", onClick = {
                    if (keyInput.isNotBlank()) {
                        SettingsStore.setProviderKey(id, if (isVertex) Vertex.normalizeKey(keyInput) else keyInput)
                        keyInput = ""
                        Toast.makeText(ctx, "저장했어요", Toast.LENGTH_SHORT).show()
                        runCheck()
                    }
                }, enabled = keyInput.isNotBlank())
                if (SettingsStore.keyHint(p) != null) LineButton("키 삭제", onClick = { SettingsStore.removeProviderKey(id); check = null to "" })
            }
            LineButton("연결 확인", onClick = { runCheck() }, icon = Icons.Outlined.Sync)
        }
        ResultLine(check.first, check.second)

        if (isImage) {
            FieldLabel("이미지 모델")
            LineCard {
                Column {
                    space.sgu.next.data.NaiModels.list.forEach { (mid, label) ->
                        Text("• $label", style = MaterialTheme.typography.bodyMedium)
                        Text("   $mid", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                    }
                    Text("모델 등록은 필요 없어요. 캐릭터 › 이미지 › 그림체 설정에서 고릅니다.", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (!isImage) {
            FieldLabel("모델 ${myModels.size}개")
            myModels.forEachIndexed { i, m ->
                GroupRow(i, myModels.size, m.s("name"), subtitle = m.s("modelId").takeIf { it != m.s("name") },
                    icon = Icons.Outlined.Memory, onClick = { open("model/${m.s("id")}") })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InputBox(manualModel, { manualModel = it }, placeholder = "모델 ID 직접 추가", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                LineButton("추가", onClick = {
                    val m = manualModel.trim()
                    if (m.isNotEmpty() && myModels.none { it.s("modelId") == m }) SettingsStore.addModel(id, m)
                    manualModel = ""
                })
            }
            Spacer(Modifier.height(10.dp))
            LineButton(if (loadingModels) "불러오는 중…" else "서버 모델 불러오기", onClick = {
                loadingModels = true
                scope.launch {
                    runCatching { Llm.listModels(SettingsStore.provider(id)!!) }
                        .onSuccess { serverModels = it }
                        .onFailure { check = false to (it.message ?: "실패"); serverModels = null }
                    loadingModels = false
                }
            }, icon = Icons.Outlined.FileDownload)
            serverModels?.let { list ->
                Hint("서버 모델 ${list.size}개 · 탭하여 추가")
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { mid ->
                        val added = myModels.any { it.s("modelId") == mid }
                        PillChip(mid, selected = added) { if (!added) SettingsStore.addModel(id, mid) }
                    }
                }
            }
        }

        if (preset != null) {
            FieldLabel("특징 · 캐싱 방식")
            LineCard {
                Column {
                    Text(preset.description, style = MaterialTheme.typography.bodyMedium)
                    preset.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 6.dp)) }
                    Text("캐싱: ${preset.cacheNote}", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PillChip(if (advanced) "고급 닫기" else "고급", selected = advanced) { advanced = !advanced }
        AnimatedVisibility(advanced) {
            Column {
                FieldLabel("요청 포맷")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Presets.formatLabels.forEach { (k, label) ->
                        PillChip(label, p.s("format") == k) {
                            set("format", k)
                            if (k == "novelai-image" && (p.s("baseUrl").isBlank() || "text.novelai.net" in p.s("baseUrl"))) set("baseUrl", Presets.NAI_IMAGE_URL)
                        }
                    }
                }
                FieldLabel("인증 방식")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Presets.authLabels.forEach { (k, label) -> PillChip(label, p.s("auth") == k) { set("auth", k) } }
                }
                if (!isJev && !isImage) {
                    FieldLabel("출력 길이 파라미터")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cur = Llm.defaultTokenParam(p)
                        listOf("max_tokens", "max_completion_tokens").forEach { k -> PillChip(k, cur == k) { set("tokenParam", k) } }
                    }
                    Hint("OpenAI 기본값은 max_completion_tokens입니다(추론 모델은 max_tokens를 거부). 다른 프로바이더의 기본값은 max_tokens입니다.")
                }
                FieldLabel("추가 헤더 (JSON)")
                InputBox(p.s("headersJson"), { set("headersJson", it) }, placeholder = "{\"HTTP-Referer\": \"…\"}", singleLine = false)
                FieldLabel("추가 바디 필드 (JSON)")
                InputBox(p.s("bodyJson"), { set("bodyJson", it) }, placeholder = "{\"provider\": {\"order\": [\"…\"]}}", singleLine = false)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("프로바이더 삭제", "이 프로바이더에 속한 모델도 함께 제거됩니다.", "삭제",
            onConfirm = { SettingsStore.removeProvider(id); onBack() }, onDismiss = { confirmDelete = false })
    }
}

// ---------- 모델 목록 ----------

@Composable
fun ModelsScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val procs = SettingsStore.root.o("processors")
    val roles = mapOf(
        procs.o("main").sOrNull("modelId") to "메인", procs.o("main").sOrNull("fallbackModelId") to "메인 폴백",
        procs.o("sub").sOrNull("modelId") to "서브", procs.o("sub").sOrNull("fallbackModelId") to "서브 폴백",
    )
    Page("모델", "프로바이더에 등록된 모델과 파라미터", onBack) {
        val models = SettingsStore.models
        if (models.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            LineCard {
                Column {
                    Text("등록된 모델이 없습니다", style = MaterialTheme.typography.titleMedium)
                    Text("프로바이더 상세 화면에서 모델을 추가하세요.", style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub)
                    Spacer(Modifier.height(12.dp))
                    InkButton("프로바이더로", onClick = { open("providers") })
                }
            }
        }
        SettingsStore.providers.forEach { p ->
            val list = models.filter { it.s("providerId") == p.s("id") }
            if (list.isEmpty()) return@forEach
            FieldLabel(p.s("name"))
            list.forEachIndexed { i, m ->
                val role = roles[m.s("id")]
                GroupRow(i, list.size, m.s("name"), subtitle = m.s("modelId").takeIf { it != m.s("name") },
                    icon = if (m.b("favorite")) Icons.Outlined.Star else Icons.Outlined.Memory,
                    onClick = { open("model/${m.s("id")}") },
                    trailing = { if (role != null) Badge(role) })
            }
        }
    }
}

// ---------- 모델 상세 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = SettingsStore.model(id)
    if (m == null) { Page("모델", null, onBack) { EmptyPane("모델을 찾을 수 없습니다.") }; return }
    val provider = SettingsStore.provider(m.s("providerId"))
    var test by remember(id) { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var confirmDelete by remember { mutableStateOf(false) }
    fun set(k: String, v: kotlinx.serialization.json.JsonElement) = SettingsStore.patch("models", id, k to v)
    fun setParam(k: String, v: Double?) = set("params", JsonObject(m.o("params").orEmpty() + (k to num(v))))
    val thinking = m.o("thinking")
    fun setThinking(k: String, v: kotlinx.serialization.json.JsonElement) = set("thinking", JsonObject(thinking.orEmpty() + (k to v)))
    val isMain = SettingsStore.root.o("processors").o("main").sOrNull("modelId") == id
    val vision = if (m.b("visionSet")) m.b("vision") else (m.b("vision") || Presets.guessVision(m.s("modelId")))

    Page(m.s("name"), provider?.s("name") ?: "프로바이더 없음", onBack, actions = {
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "모델 삭제") }
    }) {
        FieldLabel("표시 이름")
        InputBox(m.s("name"), { set("name", JsonPrimitive(it)) })
        FieldLabel("모델 ID")
        InputBox(m.s("modelId"), { set("modelId", JsonPrimitive(it)) })

        FieldLabel("파라미터")
        LineCard {
            Column {
                val params = m.o("params")
                Text("최대 컨텍스트 토큰", style = MaterialTheme.typography.bodyMedium)
                NumberBox("$id-ctx", params.d("maxContext"), { setParam("maxContext", it) }, "150000")
                Hint("입력 프롬프트의 상한. 넘으면 오래된 대화 기록부터 줄입니다.")
                Spacer(Modifier.height(10.dp))
                Text("최대 출력 토큰", style = MaterialTheme.typography.bodyMedium)
                NumberBox("$id-out", params.d("maxTokens"), { setParam("maxTokens", it) }, "50000")
                Hint("모델 한도보다 크면 오류가 날 수 있습니다.")
                listOf("temperature" to "Temperature", "topP" to "Top P", "topK" to "Top K",
                    "frequencyPenalty" to "Frequency penalty", "presencePenalty" to "Presence penalty").forEach { (k, label) ->
                    Spacer(Modifier.height(10.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    NumberBox("$id-$k", params.d(k), { setParam(k, it) }, "비움(모델 기본값)")
                }
                Hint("비우면 전송하지 않고 모델 기본값을 씁니다.")
            }
        }

        FieldLabel("동작")
        GroupRow(0, 5, "스트리밍", subtitle = "진행 중인 원문을 실시간으로 표시합니다.",
            trailing = { SguSwitch(m.b("stream", true)) { set("stream", JsonPrimitive(it)) } })
        GroupRow(1, 5, "한 번에 불러오기", subtitle = "서버에서는 스트리밍으로 받되, 화면에는 끝난 뒤 한 번에 보여줍니다. 폰 부하가 줄어요. (작업실 메인 응답)",
            trailing = { SguSwitch(m.b("streamAtOnce")) { set("streamAtOnce", JsonPrimitive(it)) } })
        GroupRow(2, 5, "비전 (이미지 입력)", subtitle = "켜면 활성화된 이미지 참고 자료를 함께 보냅니다." + if (!m.b("visionSet")) " 지금은 모델 이름으로 자동 판별했어요." else "",
            trailing = { SguSwitch(vision) { SettingsStore.patch("models", id, "vision" to JsonPrimitive(it), "visionSet" to JsonPrimitive(true)) } })
        GroupRow(3, 5, "추론 (thinking / reasoning)", subtitle = "Anthropic thinking, Gemini thinkingConfig(2.x 예산 / 3 이상 레벨), OpenAI reasoning_effort",
            trailing = { SguSwitch(thinking.b("enabled")) { setThinking("enabled", JsonPrimitive(it)) } })
        GroupRow(4, 5, "즐겨찾기", subtitle = if (isMain) "기본값 모델은 즐겨찾기가 항상 켜져 있습니다" else "기본 모델 외 최대 2개 · 모델 목록 앞쪽에 표시",
            trailing = {
                SguSwitch(isMain || m.b("favorite")) { on ->
                    if (isMain) return@SguSwitch
                    if (on && SettingsStore.favoriteCount() >= 2) Toast.makeText(ctx, "즐겨찾기는 최대 2개까지 지정할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    else set("favorite", JsonPrimitive(on))
                }
            })
        AnimatedVisibility(thinking.b("enabled")) {
            Column {
                FieldLabel("추론 토큰 예산 (Anthropic · Gemini 2.x)")
                NumberBox("$id-budget", thinking.d("budget"), { setThinking("budget", num(it?.toInt())) }, "미지정")
                FieldLabel("추론 강도 (OpenAI · Gemini 3 이상)")
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "미지정", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high").forEach { (k, label) ->
                        PillChip(label, thinking.sOrNull("effort") == k) { setThinking("effort", str(k)) }
                    }
                }
            }
        }
        FieldLabel("구조화 출력")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto", "none").forEach { k -> PillChip(k, (m.sOrNull("structuredOutput") ?: "auto") == k) { set("structuredOutput", JsonPrimitive(k)) } }
        }
        Hint("auto: 프로바이더가 지원하면 JSON 스키마 사용. none: 프롬프트 지시만으로 JSON을 받습니다(호환성 최고).")

        FieldLabel("테스트")
        LineButton("연결 테스트", onClick = {
            test = null to "보내는 중…"
            val t0 = System.currentTimeMillis()
            scope.launch {
                runCatching { Llm.testModel(SettingsStore.model(id)!!) }
                    .onSuccess { test = true to "응답 \"${it.ifBlank { "(빈 응답)" }}\" · ${System.currentTimeMillis() - t0}ms" }
                    .onFailure { test = false to (it.message ?: "실패") }
            }
        }, icon = Icons.Outlined.Sync)
        ResultLine(test.first, test.second)
    }
    if (confirmDelete) {
        ConfirmDialog("모델 삭제", "프로세서 배정에서도 해제됩니다.", "삭제",
            onConfirm = { SettingsStore.removeModel(id); onBack() }, onDismiss = { confirmDelete = false })
    }
}

// ---------- 프로세서 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProcessorsScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val procs = SettingsStore.root.o("processors")
    val main = procs.o("main"); val sub = procs.o("sub")
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // role to key
    var pickingJev by remember { mutableStateOf(false) }
    val chatModels = SettingsStore.models.filter { m -> SettingsStore.provider(m.s("providerId")).s("format").let { it != "jev" && it != "novelai-image" } }
    fun modelLabel(id: String?): String = SettingsStore.model(id)?.let { "${it.s("name")} · ${SettingsStore.provider(it.s("providerId")).s("name")}" } ?: "미지정"

    Page("프로세서", "서브: 판단 · 메인: 작성", onBack) {
        FieldLabel("메인 프로세서")
        GroupRow(0, 2, "메인 모델 (기본값)", subtitle = modelLabel(main.sOrNull("modelId")), icon = Icons.Outlined.Memory, onClick = { picking = "main" to "modelId" })
        GroupRow(1, 2, "폴백 모델 (선택)", subtitle = modelLabel(main.sOrNull("fallbackModelId")) + " · 메인 실패 시 자동 재시도", onClick = { picking = "main" to "fallbackModelId" })

        FieldLabel("서브 프로세서")
        GroupRow(0, 1, "서브 프로세서 비활성화", subtitle = "서브 단계를 건너뛰고 메인 프로세서가 바로 작업합니다.",
            trailing = { SguSwitch(sub.b("disabled")) { SettingsStore.setProcessor("sub", "disabled", JsonPrimitive(it)) } })
        if (!sub.b("disabled")) {
            Hint("빠른 경량 모델이 명령을 읽고 어떤 필드를 열람·편집해야 하는지 판단합니다.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("llm" to "경량 LLM") + if (sub.s("strategy") == "jev" || SettingsStore.providers.any { it.s("format") == "jev" }) listOf("jev" to "Jev") else emptyList()).forEach { (k, label) ->
                    PillChip(label, (sub.sOrNull("strategy") ?: "llm") == k) { SettingsStore.setProcessor("sub", "strategy", JsonPrimitive(k)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if ((sub.sOrNull("strategy") ?: "llm") == "llm") {
                GroupRow(0, 2, "서브 모델", subtitle = modelLabel(sub.sOrNull("modelId")), icon = Icons.Outlined.Memory, onClick = { picking = "sub" to "modelId" })
                GroupRow(1, 2, "서브 폴백 모델", subtitle = modelLabel(sub.sOrNull("fallbackModelId")) + " · 서브 실패 시 자동 재시도", onClick = { picking = "sub" to "fallbackModelId" })
            } else {
                val jevP = SettingsStore.provider(sub.sOrNull("jevProviderId"))
                GroupRow(0, 1, "Jev 프로바이더", subtitle = jevP?.s("name") ?: "Jev 프로바이더를 추가하세요", onClick = { pickingJev = true })
                FieldLabel("Jev 모델")
                InputBox(sub.s("jevModel").ifBlank { "jev-latest" }, { SettingsStore.setProcessor("sub", "jevModel", JsonPrimitive(it)) })
                FieldLabel("Yes 판정 임계값 (0~1)")
                NumberBox("jevThreshold", sub.d("jevThreshold") ?: 0.5, { it?.let { v -> SettingsStore.setProcessor("sub", "jevThreshold", JsonPrimitive(v.coerceIn(0.0, 1.0))) } })
                Hint("필드마다 '열람이 필요한가 / 편집이 필요한가'를 질문으로 보내고 확률이 임계값 이상이면 필요로 봅니다.")
            }
            FieldLabel("채팅 열람 범위 (메시지 수)")
            NumberBox("chatRange", sub.d("chatRange") ?: 2.0, { it?.let { v -> SettingsStore.setProcessor("sub", "chatRange", JsonPrimitive(v.toInt().coerceAtLeast(0))) } })
        }

        FieldLabel("네트워크")
        LineCard {
            Column {
                Text("요청 타임아웃 (초)", style = MaterialTheme.typography.bodyMedium)
                NumberBox("timeout", (SettingsStore.root.d("requestTimeoutMs") ?: 180000.0) / 1000, {
                    it?.let { v -> SettingsStore.update { m -> m["requestTimeoutMs"] = JsonPrimitive((v * 1000).toLong().coerceAtLeast(5000)) } }
                })
                Spacer(Modifier.height(10.dp))
                Text("재시도 횟수", style = MaterialTheme.typography.bodyMedium)
                NumberBox("retries", SettingsStore.root.d("retries") ?: 2.0, {
                    it?.let { v -> SettingsStore.update { m -> m["retries"] = JsonPrimitive(v.toInt().coerceIn(0, 5)) } }
                })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("모델 관리", onClick = { open("models") })
            LineButton("프로바이더", onClick = { open("providers") })
        }
    }
    picking?.let { (role, key) ->
        val current = procs.o(role).sOrNull(key)
        PickerDialog(
            "모델 선택",
            listOf<Pair<String?, String>>(null to "해제") + chatModels.map { it.s("id") to modelLabel(it.s("id")) },
            current,
            onPick = { SettingsStore.setProcessor(role, key, str(it)) },
            onDismiss = { picking = null },
        )
    }
    if (pickingJev) {
        PickerDialog(
            "Jev 프로바이더",
            listOf<Pair<String?, String>>(null to "해제") + SettingsStore.providers.filter { it.s("format") == "jev" }.map { it.s("id") to it.s("name") },
            sub.sOrNull("jevProviderId"),
            onPick = { SettingsStore.setProcessor("sub", "jevProviderId", str(it)) },
            onDismiss = { pickingJev = false },
        )
    }
}

// ---------- 백업 · 복원 ----------

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withKeys by remember { mutableStateOf(DriveBackup.withKeys(ctx)) }
    var target by remember { mutableStateOf(DriveBackup.target(ctx)) }
    var msg by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var confirmImport by remember { mutableStateOf<String?>(null) }
    val keyNote = { if (withKeys) "API 키 포함 · 파일이 유출되지 않도록 주의하세요." else "API 키는 넣지 않았어요." }

    fun runDriveBackup() {
        msg = null to "드라이브에 백업하는 중…"
        scope.launch {
            runCatching { DriveBackup.backup(ctx) }
                .onSuccess { target = it; msg = true to "드라이브에 백업했어요 · ${keyNote()}" }
                .onFailure { msg = false to "백업하지 못했어요. 파일이 지워졌거나 접근 권한이 풀렸을 수 있어요. '위치 바꾸기'로 다시 골라주세요. (${it.message})" }
        }
    }

    val driveTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            DriveBackup.setTarget(ctx, uri)
            target = DriveBackup.target(ctx)
            runDriveBackup()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } } }
                .onSuccess { confirmImport = it }
                .onFailure { msg = false to "파일을 읽지 못했어요: ${it.message}" }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = SettingsStore.exportBackup(withKeys)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            }.onSuccess { msg = true to "설정을 내보냈어요 · ${keyNote()}" }
                .onFailure { msg = false to "실패: ${it.message}" }
        }
    }
    Page("백업 · 복원", "프로바이더 · 모델 · 프로세서 · 권한 · 템플릿", onBack) {
        FieldLabel("드라이브 백업")
        val t = target
        if (t == null) {
            LineCard {
                Column {
                    Text("처음 한 번만 저장 위치를 고르면, 다음부터는 버튼 한 번으로 같은 파일에 덮어써요.", style = MaterialTheme.typography.bodyMedium)
                    Text("파일 선택 창 왼쪽 메뉴(☰)에서 Drive를 고르고 원하는 폴더에서 저장을 누르세요.",
                        style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(14.dp))
                    InkButton("백업 위치 고르기", onClick = { driveTarget.launch("sgu-settings.json") }, icon = Icons.Outlined.CloudUpload)
                }
            }
        } else {
            val last = if (t.lastAt == 0L) "아직 백업하지 않았어요"
                else "마지막 백업 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(t.lastAt))
            GroupRow(0, 1, t.name, subtitle = last, icon = Icons.Outlined.Cloud)
            Spacer(Modifier.height(12.dp))
            InkButton("지금 백업", onClick = { runDriveBackup() }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.CloudUpload)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("여기서 복원", onClick = {
                    scope.launch {
                        runCatching { DriveBackup.read(ctx) }
                            .onSuccess { confirmImport = it }
                            .onFailure { msg = false to "백업 파일을 읽지 못했어요. 파일이 지워졌거나 접근 권한이 풀렸을 수 있어요. (${it.message})" }
                    }
                }, modifier = Modifier.weight(1f), icon = Icons.Outlined.CloudDownload)
                LineButton("위치 바꾸기", onClick = { driveTarget.launch("sgu-settings.json") }, modifier = Modifier.weight(1f), icon = Icons.Outlined.Sync)
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupRow(0, 1, "API 키 · 서비스 계정 키 포함", subtitle = if (withKeys) "경고: 해당 파일이 유출되지 않도록 주의하십시오" else "드라이브 백업 · 내보내기 모두에 적용",
            trailing = { SguSwitch(withKeys) { withKeys = it; DriveBackup.setWithKeys(ctx, it) } })
        ResultLine(msg.first, msg.second)

        FieldLabel("파일로 옮기기")
        LineCard {
            Column {
                Text("기존 SGU 앱의 '설정 JSON 내보내기' 파일을 그대로 가져올 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                Text("키가 포함된 파일이면 API 키까지 한 번에 옮겨져요. 키가 없는 파일은 가져온 뒤 각 프로바이더에서 키를 다시 입력해야 합니다. 캐릭터는 CHARX로 따로 옮기세요.",
                    style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                InkButton("설정 JSON 가져오기", onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, icon = Icons.Outlined.FileUpload)
            }
        }
        Spacer(Modifier.height(12.dp))
        LineButton("설정 JSON 내보내기", onClick = {
            exporter.launch("sgu-settings-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json")
        }, icon = Icons.Outlined.FileDownload)
    }
    confirmImport?.let { text ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("설정 복원") },
            text = { Text("지금 설정(프로바이더·모델·프로세서 등)을 파일 내용으로 바꿀까요? 캐릭터는 그대로예요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    runCatching { SettingsStore.importBackup(text) }
                        .onSuccess { r -> msg = true to "설정을 복원했습니다 · 프로바이더 ${r.providers}개, 모델 ${r.models}개, 키 ${r.keys}개" }
                        .onFailure { msg = false to "가져오지 못했어요: ${it.message}" }
                }) { Text("복원", color = Sgu.Ink) }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("취소", color = Sgu.Sub) } },
            containerColor = Sgu.Card,
        )
    }
}
