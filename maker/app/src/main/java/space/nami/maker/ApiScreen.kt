package space.nami.maker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp

/** API settings like Nami next: providers (Vertex AI, Anthropic) → models, plus which model the AI uses. */
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    // route: "" home · "p:<provider>" · "m:<model>" · "ps:<preset>" · "b:<preset>:<block>"
    val stack = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    fun go(r: String) { stack.add(r) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else onClose() }
    Dialog(onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding().imePadding()) {
            @Suppress("UNUSED_VARIABLE") val rev = Api.rev + Prompts.rev
            val r = stack.lastOrNull() ?: ""
            val parts = r.split(":")
            when {
                parts[0] == "p" && Api.provider(parts[1]) != null -> ProviderPage(parts[1], onBack = { back() }, onModel = { go("m:$it") })
                parts[0] == "m" && Api.model(parts[1]) != null -> ModelPage(parts[1], onBack = { back() })
                parts[0] == "ps" && Prompts.preset(parts[1]) != null -> PresetPage(parts[1], onBack = { back() }, onBlock = { go("b:${parts[1]}:$it") })
                parts[0] == "b" && parts.size == 3 && Prompts.preset(parts[1]) != null -> BlockPage(parts[1], parts[2], onBack = { back() })
                else -> ApiHome(onClose = onClose, onOpen = { go("p:$it") }, onModel = { go("m:$it") }, onPreset = { go("ps:$it") })
            }
        }
    }
}

@Composable
private fun Header(title: String, back: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(if (back) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, if (back) "뒤로" else "닫기") { onClick() }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = K.text)
    }
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 13.sp, color = K.sub, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = K.sub, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(K.card).border(1.dp, K.line, RoundedCornerShape(14.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
}

@Composable
private fun Btn(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = if (!enabled) K.sub else if (filled) Color.White else K.text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filled && enabled) K.accent else if (filled) K.line else K.card)
            .border(1.dp, if (filled) Color.Transparent else K.line, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
private fun Input(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, single: Boolean = true) {
    OutlinedTextField(value, onChange, placeholder = { Text(placeholder, color = K.sub) }, singleLine = single, minLines = if (single) 1 else 3,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.accent, unfocusedBorderColor = K.line, focusedContainerColor = K.card, unfocusedContainerColor = K.card))
}

@Composable
private fun Result(ok: Boolean?, text: String) {
    if (text.isBlank()) return
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = when (ok) { true -> Color(0xFF2E7D4F); false -> K.danger; null -> K.sub },
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(when (ok) { true -> Color(0xFFE6F3EA); false -> Color(0xFFFBE9E7); null -> K.panel }).padding(horizontal = 12.dp, vertical = 9.dp))
}

@Composable
private fun Radio(on: Boolean) {
    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) K.accent else K.line, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(K.accent))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiHome(onClose: () -> Unit, onOpen: (String) -> Unit, onModel: (String) -> Unit, onPreset: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Api.rev + Prompts.rev
    val clip = LocalClipboardManager.current
    var presetMsg by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("API 설정", back = false, onClick = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Label("AI가 쓸 모델")
                val models = Api.models()
                if (models.isEmpty()) Hint("아직 모델이 없어요. 아래에서 프로바이더를 추가하고 모델을 넣어주세요.")
                val cur = Api.current()?.optString("id")
                models.forEach { m ->
                    val mid = m.optString("id")
                    Card(onClick = { onModel(mid) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(CircleShape).clickable { Api.select(mid) }.padding(4.dp)) { Radio(mid == cur) }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(Api.label(m), fontSize = 15.sp, color = K.text)
                                Text(modelSummary(m), fontSize = 12.sp, color = K.sub)
                            }
                            Text("설정 ›", fontSize = 13.sp, color = K.sub)
                        }
                    }
                }
                if (models.isNotEmpty()) Hint("동그라미 = AI가 쓸 모델. 카드를 누르면 스트리밍·추론 설정이에요.")
                Label("프롬프트 프리셋")
                val active = Prompts.active().optString("id")
                Prompts.presets().forEach { ps ->
                    val id = ps.optString("id")
                    Card(onClick = { onPreset(id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(CircleShape).clickable { Prompts.setActive(id) }.padding(4.dp)) { Radio(id == active) }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(ps.optString("name"), fontSize = 15.sp, color = K.text)
                                val bl = Prompts.blocks(ps)
                                Text("블록 ${bl.size}개 · 켜짐 ${bl.count { it.optBoolean("enabled", true) }}개", fontSize = 12.sp, color = K.sub)
                            }
                            Text("편집 ›", fontSize = 13.sp, color = K.sub)
                        }
                    }
                }
                val presetPick = rememberFilePicker { _, bytes ->
                    val n = Prompts.import(String(bytes, Charsets.UTF_8))
                    presetMsg = if (n > 0) true to "프리셋 ${n}개를 가져왔어요" else false to "가져올 프리셋을 찾지 못했어요. 내보내기로 저장한 JSON 파일인지 확인해 주세요."
                }
                val allSave = rememberFileSaver { ok -> presetMsg = if (ok) true to "프리셋 ${Prompts.presets().size}개를 파일로 저장했어요" else false to "저장하지 못했어요" }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("+ 새 프리셋") { onPreset(Prompts.add("프리셋 ${Prompts.presets().size + 1}")) }
                    Btn("파일에서 불러오기") { presetPick() }
                    Btn("전체 파일로 내보내기") { allSave("Nami Maker 프리셋.json", Prompts.exportAll().toByteArray(Charsets.UTF_8)) }
                    Btn("클립보드에서 가져오기") {
                        val n = Prompts.import(clip.getText()?.text ?: "")
                        presetMsg = if (n > 0) true to "프리셋 ${n}개를 가져왔어요" else false to "가져올 프리셋을 찾지 못했어요. 내보내기로 복사한 JSON인지 확인해 주세요."
                    }
                }
                Result(presetMsg.first, presetMsg.second)
                Hint("동그라미 = 지금 쓰는 프리셋. 블록을 위에서 아래로 이어 붙여 AI에게 보내요.")
                Label("프로바이더")
                Api.providers().forEach { p ->
                    val pid = p.optString("id")
                    Card(onClick = { onOpen(pid) }) {
                        Column {
                            Text(p.optString("name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                            val keyText = if (p.optString("preset") == "vertex") (if (Api.hasKey(pid)) "서비스 계정 저장됨" else "서비스 계정 없음") else (if (Api.hasKey(pid)) "키 저장됨" else "키 없음")
                            Text("${Api.preset(p.optString("preset")).name} · $keyText · 모델 ${Api.modelsOf(pid).size}개", fontSize = 12.sp, color = K.sub)
                        }
                    }
                }
                if (!adding) Btn("+ 프로바이더 추가", filled = true) { adding = true }
                else {
                    Api.PRESETS.forEach { pr ->
                        Card(onClick = { adding = false; onOpen(Api.addProvider(pr.id)) }) {
                            Column {
                                Text(pr.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                                Text(pr.desc, fontSize = 12.sp, color = K.sub)
                            }
                        }
                    }
                    Btn("취소") { adding = false }
                }
                Hint("키는 이 폰의 보안 저장소에 암호화해서 보관해요. 화면에 다시 보이지 않아요.")
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderPage(pid: String, onBack: () -> Unit, onModel: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Api.rev
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val p = Api.provider(pid) ?: return
    val isVertex = p.optString("preset") == "vertex"
    val preset = Api.preset(p.optString("preset"))
    var name by remember(pid) { mutableStateOf(p.optString("name")) }
    var projectId by remember(pid) { mutableStateOf(p.optString("projectId")) }
    var email by remember(pid) { mutableStateOf(p.optString("clientEmail")) }
    var location by remember(pid) { mutableStateOf(p.optString("location")) }
    var keyInput by remember(pid) { mutableStateOf("") }
    var saJson by remember(pid) { mutableStateOf("") }
    var newModel by remember(pid) { mutableStateOf("") }
    var check by remember(pid) { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var busy by remember(pid) { mutableStateOf(false) }
    var atPaste by remember(pid) { mutableStateOf(false) }
    var tests by remember(pid) { mutableStateOf(mapOf<String, Pair<Boolean?, String>>()) }
    var confirmDel by remember(pid) { mutableStateOf(false) }

    fun runCheck(lead: String = "") {
        busy = true; atPaste = lead.isNotEmpty(); check = null to (lead + "연결 확인하는 중…")
        scope.launch {
            val t0 = System.currentTimeMillis()
            check = try { true to (lead + Api.check(pid) + secs(t0)) } catch (e: Exception) { false to (lead + (e.message ?: e.javaClass.simpleName)) }
            busy = false
        }
    }

    fun useJson(s: String): Boolean {
        val sa = Api.Vertex.parseServiceAccount(s) ?: return false
        val (pj, mail, pk) = sa
        projectId = pj; email = mail
        Api.patchProvider(pid, "projectId", pj); Api.patchProvider(pid, "clientEmail", mail)
        if (location.isBlank()) { location = "global"; Api.patchProvider(pid, "location", "global") }
        Api.setKey(pid, pk)
        saJson = ""
        runCheck("서비스 계정을 저장했어요 ($mail)\n")
        return true
    }

    Column(Modifier.fillMaxSize()) {
        Header(p.optString("name"), back = true, onClick = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(preset.desc, fontSize = 13.sp, color = K.sub, lineHeight = 18.sp)
                Label("이름")
                Input(name, { name = it; Api.patchProvider(pid, "name", it) }, preset.name)

                if (isVertex) {
                    if (Api.hasKey(pid) && projectId.isNotBlank()) Text("저장됨 · $projectId · ${email.ifBlank { "?" }}", fontSize = 13.sp, color = Color(0xFF2E7D4F), modifier = Modifier.padding(top = 8.dp))
                    Label("서비스 계정 JSON (제일 쉬워요)")
                    Btn("클립보드에서 붙여넣기", filled = true) {
                        val t = clip.getText()?.text ?: ""
                        atPaste = true
                        if (t.isBlank()) check = false to "클립보드가 비어 있어요. JSON 파일 내용을 복사한 뒤 눌러주세요."
                        else if (!useJson(t)) check = false to "서비스 계정 JSON으로 읽지 못했어요. project_id · client_email · private_key가 들어 있는 파일 전체를 복사했는지 확인해 주세요."
                    }
                    Spacer(Modifier.height(8.dp))
                    Input(saJson, { s ->
                        saJson = s
                        if (!useJson(s) && s.trim().startsWith("{") && s.trim().endsWith("}")) {
                            atPaste = true
                            check = false to "서비스 계정 JSON으로 읽지 못했어요. project_id · client_email · private_key가 있는지 확인해 주세요."
                        }
                    }, "또는 여기에 직접 붙여넣기", single = false)
                    if (atPaste) Result(check.first, check.second)
                    Label("또는 직접 입력")
                    Input(projectId, { projectId = it; Api.patchProvider(pid, "projectId", it.trim()) }, "Project ID")
                    Input(email, { email = it; Api.patchProvider(pid, "clientEmail", it.trim()) }, "Client Email")
                    Input(location, { location = it; Api.patchProvider(pid, "location", it.trim()) }, "지역: global 또는 us-central1 등")
                    Label("Private Key" + if (Api.hasKey(pid)) " · 저장됨" else "")
                    Input(keyInput, { keyInput = it }, "-----BEGIN PRIVATE KEY-----", single = false)
                    Hint("서비스 계정에 'Vertex AI User' 역할이 있어야 하고, 프로젝트에서 Vertex AI API가 켜져 있어야 해요. 지역은 global이 기본이에요.")
                } else {
                    Label("API 키" + if (Api.hasKey(pid)) " · 저장됨" else "")
                    Input(keyInput, { keyInput = it }, "sk-ant-...", password = true)
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("키 저장", filled = true, enabled = keyInput.isNotBlank()) {
                        Api.setKey(pid, if (isVertex) Api.Vertex.normalizeKey(keyInput) else Api.cleanKey(keyInput))
                        keyInput = ""; runCheck()
                    }
                    if (Api.hasKey(pid)) Btn("키 삭제") { Api.removeKey(pid); check = null to "" }
                    Btn(if (busy) "확인 중…" else "연결 확인", enabled = !busy) { runCheck() }
                }
                if (!atPaste) Result(check.first, check.second)

                Label("모델")
                val cur = Api.current()?.optString("id")
                Api.modelsOf(pid).forEach { m ->
                    val mid = m.optString("id")
                    Card(onClick = { onModel(mid) }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clip(CircleShape).clickable { Api.select(mid) }.padding(4.dp)) { Radio(mid == cur) }
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(m.optString("modelId"), fontSize = 15.sp, color = K.text)
                                    Text(modelSummary(m), fontSize = 12.sp, color = K.sub)
                                }
                                Text("테스트", fontSize = 13.sp, color = K.accent, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                        tests = tests + (mid to (null to "보내는 중…"))
                                        scope.launch {
                                            val t0 = System.currentTimeMillis()
                                            val r = try { true to ("응답: " + Api.testModel(mid) + secs(t0)) } catch (e: Exception) { false to ((e.message ?: "실패") + secs(t0)) }
                                            tests = tests + (mid to r)
                                        }
                                    }.padding(horizontal = 10.dp, vertical = 6.dp))
                                IconBtn(Icons.Filled.Delete, "모델 지우기") { Api.removeModel(mid) }
                            }
                            tests[mid]?.let { (ok, t) -> Result(ok, t) }
                        }
                    }
                }
                Input(newModel, { newModel = it }, "모델 ID (예: ${preset.models.first()})")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("추가", filled = true, enabled = newModel.isNotBlank()) { Api.addModel(pid, newModel.trim()); newModel = "" }
                    preset.models.filter { s -> Api.modelsOf(pid).none { it.optString("modelId") == s } }.forEach { s ->
                        Pill(s) { Api.addModel(pid, s) }
                    }
                }
                Hint("동그라미 = AI가 쓸 모델. 카드를 누르면 스트리밍·추론 설정이에요.")

                Spacer(Modifier.height(28.dp))
                Text(if (confirmDel) "정말 지울까요? 한 번 더 누르세요" else "이 프로바이더 지우기", fontSize = 14.sp, color = K.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        if (!confirmDel) confirmDel = true else { Api.removeProvider(pid); onBack() }
                    }.padding(horizontal = 6.dp, vertical = 8.dp))
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

private fun secs(t0: Long) = " · %.1f초".format((System.currentTimeMillis() - t0) / 1000f)

private fun modelSummary(m: JSONObject): String {
    val th = Api.thinking(m)
    val a = mutableListOf<String>()
    a += if (!Api.stream(m)) "한 번에 받기(스트리밍 끔)" else if (Api.atOnce(m)) "스트리밍 · 한 번에 불러오기" else "스트리밍"
    a += if (th.optBoolean("enabled")) "추론 " + (th.optString("effort").ifBlank { th.optInt("budget", 0).takeIf { it > 0 }?.toString() ?: "켬" }) else "추론 끔"
    return a.joinToString(" · ")
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Card(onClick = if (enabled) ({ onChange(!on) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = if (enabled) K.text else K.sub)
                Text(sub, fontSize = 12.sp, color = K.sub, lineHeight = 17.sp)
            }
            androidx.compose.material3.Switch(on, onChange, enabled = enabled,
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = K.accent, checkedThumbColor = Color.White))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPage(mid: String, onBack: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Api.rev
    val scope = rememberCoroutineScope()
    val m = Api.model(mid) ?: return
    val p = Api.provider(m.optString("providerId"))
    val gemini = p?.optString("preset") == "vertex"
    val th = Api.thinking(m)
    var modelId by remember(mid) { mutableStateOf(m.optString("modelId")) }
    var maxTok by remember(mid) { mutableStateOf(m.optInt("maxTokens", 0).takeIf { it > 0 }?.toString() ?: "") }
    var budget by remember(mid) { mutableStateOf(th.optInt("budget", 0).takeIf { it > 0 }?.toString() ?: "") }
    var test by remember(mid) { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var confirmDel by remember(mid) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header(m.optString("modelId"), back = true, onClick = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(p?.optString("name") ?: "프로바이더 없음", fontSize = 13.sp, color = K.sub)
                val cur = Api.current()?.optString("id") == mid
                Spacer(Modifier.height(8.dp))
                if (cur) Text("지금 AI가 쓰는 모델이에요", fontSize = 13.sp, color = Color(0xFF2E7D4F))
                else Btn("이 모델 쓰기", filled = true) { Api.select(mid) }

                Label("모델 ID")
                Input(modelId, { modelId = it; if (it.isNotBlank()) Api.patchModel(mid, "modelId", it.trim()) }, "모델 ID")
                Label("최대 출력 토큰")
                Input(maxTok, { v -> maxTok = v.filter(Char::isDigit).take(6); Api.patchModel(mid, "maxTokens", maxTok.toIntOrNull()) },
                    if (gemini) "비우면 32768" else "비우면 8000")

                Label("동작")
                SwitchRow("스트리밍", "답을 받는 대로 화면에 바로 보여줘요.", Api.stream(m)) { Api.patchModel(mid, "stream", it) }
                SwitchRow("한 번에 불러오기", "서버에선 스트리밍으로 받되, 화면에는 끝난 뒤 한 번에 보여줘요. 폰 부하가 줄어요.",
                    Api.atOnce(m), enabled = Api.stream(m)) { Api.patchModel(mid, "atOnce", it) }
                Hint("스트리밍을 끄면 한 번에 받기(답이 다 만들어질 때까지 기다렸다가 받음)예요.")
                SwitchRow("추론 (thinking)", if (gemini) "Gemini thinkingConfig — 2.x는 토큰 예산, 3 이상은 강도(레벨)" else "Claude thinking — 토큰 예산",
                    th.optBoolean("enabled")) { Api.patchThinking(mid, "enabled", it) }
                if (th.optBoolean("enabled")) {
                    if (gemini) {
                        Label("추론 강도 (Gemini 3 이상)")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("미지정", on = th.optString("effort").isBlank()) { Api.patchThinking(mid, "effort", null) }
                            Api.EFFORTS.forEach { e -> Pill(e, on = th.optString("effort") == e) { Api.patchThinking(mid, "effort", e) } }
                        }
                        Hint("미지정이면 모델 기본값(보통 high)이에요. 하이드로겐이 느리면 low나 medium으로 낮춰 보세요. xhigh는 하이드로겐 전용이에요.")
                    }
                    Label(if (gemini) "추론 토큰 예산 (Gemini 2.x)" else "추론 토큰 예산")
                    Input(budget, { v -> budget = v.filter(Char::isDigit).take(6); Api.patchThinking(mid, "budget", budget.toIntOrNull()) },
                        if (gemini) "비우면 모델 기본값" else "비우면 4000 (최소 1024)")
                    Hint("추론 과정은 채팅에서 '생각 보기'로 펼쳐 볼 수 있어요.")
                } else if (gemini) Hint("끄면 생각 설정을 보내지 않아요(모델 기본값). Gemini 3 이상은 완전히 끌 수 없어요.")

                Label("테스트")
                Btn("연결 테스트") {
                    test = null to "보내는 중…"
                    scope.launch {
                        val t0 = System.currentTimeMillis()
                        test = try { true to ("응답: " + Api.testModel(mid) + secs(t0)) } catch (e: Exception) { false to ((e.message ?: "실패") + secs(t0)) }
                    }
                }
                Result(test.first, test.second)

                Spacer(Modifier.height(28.dp))
                Text(if (confirmDel) "정말 지울까요? 한 번 더 누르세요" else "이 모델 지우기", fontSize = 14.sp, color = K.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (!confirmDel) confirmDel = true else { Api.removeModel(mid); onBack() } }
                        .padding(horizontal = 6.dp, vertical = 8.dp))
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetPage(pid: String, onBack: () -> Unit, onBlock: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Prompts.rev
    val clip = LocalClipboardManager.current
    val ps = Prompts.preset(pid) ?: return
    var name by remember(pid) { mutableStateOf(ps.optString("name")) }
    var adding by remember(pid) { mutableStateOf(false) }
    var msg by remember(pid) { mutableStateOf<Pair<Boolean?, String>>(null to "") }
    var confirm by remember(pid) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header(ps.optString("name"), back = true, onClick = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (Prompts.active().optString("id") == pid) Text("지금 쓰는 프리셋이에요", fontSize = 13.sp, color = Color(0xFF2E7D4F))
                else Btn("이 프리셋 쓰기", filled = true) { Prompts.setActive(pid) }
                Label("이름")
                Input(name, { name = it; if (it.isNotBlank()) Prompts.rename(pid, it.trim()) }, "프리셋 이름")
                Label("블록 (위에서 아래 순서로 보내요)")
                val bl = Prompts.blocks(ps)
                bl.forEachIndexed { i, b ->
                    val bid = b.optString("id")
                    Card(onClick = { onBlock(bid) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(b.optBoolean("enabled", true), { Prompts.patchBlock(pid, bid, "enabled", it) },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = K.accent))
                            Column(Modifier.weight(1f)) {
                                Text(b.optString("name").ifBlank { Prompts.TYPE_LABELS[b.optString("type")] ?: "블록" }, fontSize = 15.sp,
                                    color = if (b.optBoolean("enabled", true)) K.text else K.sub)
                                Text((Prompts.TYPE_LABELS[b.optString("type")] ?: "") + " · " + (when { b.optString("type") == "prefill" -> "AI 답 첫머리"; b.optString("role") == "user" -> "사용자 메시지에"; else -> "시스템 프롬프트에" }) +
                                    (if (b.optString("type") == "map" && b.optBoolean("image", false)) " · 그림 첨부" else ""), fontSize = 12.sp, color = K.sub)
                            }
                            if (i > 0) IconBtn(androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp, "위로") { Prompts.move(pid, bid, -1) }
                            if (i < bl.size - 1) IconBtn(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, "아래로") { Prompts.move(pid, bid, 1) }
                        }
                    }
                }
                if (!adding) Btn("+ 블록 추가") { adding = true }
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Prompts.TYPE_LABELS.forEach { (t, l) -> Pill(l) { adding = false; onBlock(Prompts.addBlock(pid, t)) } }
                    Pill("취소") { adding = false }
                }
                Label("관리")
                val oneSave = rememberFileSaver { ok -> msg = if (ok) true to "프리셋을 파일로 저장했어요" else false to "저장하지 못했어요" }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("파일로 내보내기") { oneSave(Prompts.fileName(ps.optString("name")), Prompts.export(ps).toByteArray(Charsets.UTF_8)) }
                    Btn("클립보드로 내보내기") { clip.setText(androidx.compose.ui.text.AnnotatedString(Prompts.export(ps))); msg = true to "프리셋을 클립보드에 복사했어요" }
                    Btn("복제") { Prompts.copy(pid)?.let { msg = true to "사본을 만들었어요" } }
                    Btn(if (confirm == "reset") "정말 되돌릴까요?" else "기본값으로 되돌리기") {
                        if (confirm != "reset") confirm = "reset" else { Prompts.reset(pid); confirm = ""; msg = true to "기본 블록으로 되돌렸어요" }
                    }
                    if (Prompts.presets().size > 1) Btn(if (confirm == "del") "정말 지울까요?" else "프리셋 지우기") {
                        if (confirm != "del") confirm = "del" else { Prompts.remove(pid); onBack() }
                    }
                }
                Result(msg.first, msg.second)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun BlockPage(pid: String, bid: String, onBack: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Prompts.rev
    val ps = Prompts.preset(pid) ?: return
    val b = Prompts.blocks(ps).firstOrNull { it.optString("id") == bid } ?: return
    val type = b.optString("type")
    var name by remember(bid) { mutableStateOf(b.optString("name")) }
    var text by remember(bid) { mutableStateOf(b.optString("text")) }
    var confirmDel by remember(bid) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header(b.optString("name").ifBlank { "블록" }, back = true, onClick = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text((Prompts.TYPE_LABELS[type] ?: type) + " — " + (Prompts.TYPE_DESC[type] ?: ""), fontSize = 13.sp, color = K.sub, lineHeight = 18.sp)
                SwitchRow("켜기", "끄면 이 블록은 보내지 않아요.", b.optBoolean("enabled", true)) { Prompts.patchBlock(pid, bid, "enabled", it) }
                Label("이름")
                Input(name, { name = it; Prompts.patchBlock(pid, bid, "name", it) }, "블록 이름")
                if (type == "prefill") Hint("AI 답의 첫머리로 들어가요. 블록 순서와 상관없이 맨 끝(AI 차례)에 붙어요. 프리필을 쓰면 제미니의 JSON 강제 모드는 꺼져요. 클로드는 추론(thinking)을 켜면 프리필을 받지 않아요.")
                else {
                Label("어디에 넣을까")
                Seg(listOf("시스템 프롬프트", "사용자 메시지"), if (b.optString("role") == "user") 1 else 0) { Prompts.patchBlock(pid, bid, "role", if (it == 1) "user" else "system") }
                Hint("시스템 프롬프트 블록은 이어 붙여 AI의 기본 지시가 되고, 사용자 메시지 블록은 매번 보내는 메시지에 순서대로 들어가요.")
                }
                if (type == "map") SwitchRow("맵 그림 첨부", "글자 지도와 함께 맵 그림(PNG)도 보내요. 끄면 토큰이 줄어요.", b.optBoolean("image", false)) { Prompts.patchBlock(pid, bid, "image", it) }
                Label("내용")
                OutlinedTextField(text, { text = it; Prompts.patchBlock(pid, bid, "text", it) }, minLines = 8,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = K.text, lineHeight = 20.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.accent, unfocusedBorderColor = K.line, focusedContainerColor = K.card, unfocusedContainerColor = K.card))
                if (type != "text" && type != "prefill") Hint("{{slot}} 자리에 내용이 들어가요. 없으면 글 뒤에 붙여요.")
                else Hint("{{tiles}} = 타일 범례, {{graphics}} = 그래픽 목록")
                Spacer(Modifier.height(28.dp))
                Text(if (confirmDel) "정말 지울까요? 한 번 더 누르세요" else "이 블록 지우기", fontSize = 14.sp, color = K.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (!confirmDel) confirmDel = true else { Prompts.removeBlock(pid, bid); onBack() } }
                        .padding(horizontal = 6.dp, vertical = 8.dp))
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

private const val SHEET_PROMPT = "Turn the character in this image into an RPG Maker MV style pixel-art walking sprite sheet. " +
    "Layout: exactly 3 columns and 4 rows of equal square cells on a plain pure white background, no grid lines, no text, no shadows. " +
    "Rows from top to bottom: facing down (front view), facing left, facing right, facing up (back view). " +
    "Columns: walking frame with left foot forward, standing, walking frame with right foot forward. " +
    "Chibi proportions about 2.5 heads tall, full body inside every cell, same size and position in each cell, clean dark outlines. " +
    "Keep the hair style, hair color, eye color and outfit of the reference."

// 예시 시트가 있으면 글로 길게 설명하지 않음 — 그림이 형식을 다 알려 줘서, 긴 지시는 오히려 방해됨
private const val SHEET_PROMPT_EXAMPLE = "첫 번째 그림(레퍼런스)처럼 두 번째 그림의 캐릭터를 만들어주세요. 배경은 투명으로요."

/** 고를 수 있는 구글 이미지 모델 (Vertex). 맨 위가 최신. */
private val IMAGE_MODELS = listOf(
    "gemini-nano-banana-2.1" to "Nano Banana 2.1 (최신)",
    "gemini-3-pro-image" to "Nano Banana Pro · 고품질",
    "gemini-3.1-flash-image" to "Nano Banana 2",
    "gemini-3.1-flash-lite-image" to "Nano Banana 2 Lite · 빠름",
    "gemini-2.5-flash-image" to "Nano Banana (구버전)",
)

/** 오른쪽 '이미지' 탭: 그림 받기 링크, 캐릭터 그림 불러오기·다듬기·목록. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagePage(modifier: Modifier = Modifier) {
    var tidy by remember { mutableStateOf(false) }
    var oneBack by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Label("그림 받기")
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        listOf(
            Triple("Pipoya 캐릭터 (32×32)", "RPG Maker풍 걷기 캐릭터 64종 · 4방향", "https://pipoya.itch.io/pipoya-free-rpg-character-sprites-32x32"),
            Triple("Pipoya 타일셋 (32×32)", "RPG Maker풍 마을·실내·자연 타일", "https://pipoya.itch.io/pipoya-rpg-tileset-32x32"),
        ).forEach { (t, d, u) ->
            Card(onClick = { runCatching { uri.openUri(u) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t, fontSize = 15.sp, color = K.text)
                        Text(d, fontSize = 12.sp, color = K.sub)
                    }
                    Text("열기 ›", fontSize = 13.sp, color = K.sub)
                }
            }
        }
        Hint("무료예요(Download Now → 'No thanks, just take me to the downloads'). 그림을 다시 배포하거나 팔면 안 되고, 게임에 쓰는 건 자유예요.")
        Label("캐릭터 그림")
        @Suppress("UNUSED_VARIABLE") val crev = Chars.rev
        var charMsg by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
        var delSel by remember { mutableStateOf<String?>(null) }
        val pick = rememberFilePicker { name, bytes ->
            val n = runCatching { Chars.import(name, bytes, tidy, oneBack) }.getOrDefault(0)
            charMsg = if (n > 0) true to "캐릭터 ${n}명을 넣었어요" else false to "넣을 캐릭터 그림을 못 찾았어요. 걷기 시트(3칸 × 4줄) PNG나 그걸 담은 zip인지 확인해 주세요."
        }
        Label("레퍼런스로 만들기")
        Hint("NAI 등에서 뽑은 캐릭터 그림 한 장을 고르면, 제미니 이미지 모델이 걷기 시트(3칸 × 4줄)로 바꿔 줘요. 다듬기와 뒷모습 하나로를 거쳐 바로 캐릭터로 들어가요.")
        val scope = rememberCoroutineScope()
        var refName by remember { mutableStateOf("") }
        var imgModel by remember { mutableStateOf(Api.imageModel()) }
        var genBusy by remember { mutableStateOf(false) }
        var genMsg by remember { mutableStateOf<Pair<Boolean?, String>>(null to "") }
        Input(refName, { refName = it }, "캐릭터 이름 (예: 나, 클로드)")
        Spacer(Modifier.height(6.dp))
        Label("이미지 모델")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IMAGE_MODELS.forEach { (id, nm) -> Pill(nm, on = imgModel == id) { imgModel = id; Api.setImageModel(id) } }
        }
        Spacer(Modifier.height(6.dp))
        Input(imgModel, { imgModel = it; Api.setImageModel(it) }, "모델 ID 직접 입력")
        Spacer(Modifier.height(6.dp))
        val refPick = rememberFilePicker { fname, bytes ->
            val nm = refName.trim().ifEmpty { fname.substringBeforeLast('.') }
            genBusy = true; genMsg = null to "만드는 중… (30초~1분쯤 걸려요)"
            scope.launch {
                genMsg = runCatching {
                    val ex = Chars.example()?.let { Chars.shrinkRef(it) }
                    val ref = Chars.shrinkRef(bytes)
                    val out = if (ex != null) Api.image(listOf(ex, ref), SHEET_PROMPT_EXAMPLE, imgModel.ifBlank { "gemini-3.1-flash-image" })
                        else Api.image(listOf(ref), SHEET_PROMPT, imgModel.ifBlank { "gemini-3.1-flash-image" })
                    val n = Chars.import("$nm.png", out, tidy = true, oneBack = true)
                    if (n > 0) true to "'$nm' 캐릭터를 만들었어요. 마음에 안 들면 지우고 다시 눌러 보세요." else false to "그림은 받았는데 걷기 시트 모양이 아니었어요. 다시 눌러 보세요."
                }.getOrElse { false to (it.message ?: "실패했어요") }
                genBusy = false
            }
        }
        // 예시 시트: 이 모양(칸 배치·크기·도트 느낌) 그대로 만들어 달라고 같이 보냄
        @Suppress("UNUSED_VARIABLE") val erev = Chars.rev
        val hasEx = Chars.example() != null
        val exPick = rememberFilePicker { _, bytes -> Chars.setExample(bytes) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hasEx) "예시 시트: 있음 (이 모양대로 만들어요)" else "예시 시트: 없음", fontSize = 13.sp, color = if (hasEx) K.text else K.sub, modifier = Modifier.weight(1f))
            Btn(if (hasEx) "바꾸기" else "예시 시트 고르기") { exPick() }
            if (hasEx) { Spacer(Modifier.width(6.dp)); Btn("지우기") { Chars.setExample(null) } }
        }
        Hint("마음에 드는 걷기 시트 한 장을 예시로 넣어 두면, 칸 배치·크기·도트 느낌을 그대로 따라 그려요.")
        Spacer(Modifier.height(6.dp))
        Btn(if (genBusy) "만드는 중…" else "레퍼런스 고르고 만들기", filled = true, enabled = !genBusy) { refPick() }
        Result(genMsg.first, genMsg.second)
        Label("시트 불러오기")
        SwitchRow("AI 그림 다듬기", "흰 배경을 투명하게 지우고, 큰 그림은 한 칸 48픽셀 도트로 줄여요. 제미니 같은 AI가 만든 시트에 켜세요.", tidy) { tidy = it }
        SwitchRow("뒷모습 하나로", "맨 아랫줄(뒷모습) 가운데 칸을 세 칸 모두에 써요. 뒷모습이 들쭉날쭉할 때.", oneBack) { oneBack = it }
        Btn("zip·PNG 불러오기", filled = true) { pick() }
        Result(charMsg.first, charMsg.second)
        val chars = Chars.ids
        if (chars.isEmpty()) Hint("받은 zip을 그대로 고르면 안에 든 캐릭터를 한 번에 넣어요. 넣은 캐릭터는 이벤트 그래픽에서 고르고, AI도 이름으로 써요.")
        else {
            Hint("${chars.size}명 · 누르면 지우기")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chars.forEach { id ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).clickable { delSel = if (delSel == id) null else id }.padding(2.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(K.panel)) {
                            drawIntoCanvas { c -> Art.sprite(c.art(), id, 0, 0f, 0f, size.width) }
                        }
                        if (delSel == id) Text("지우기", fontSize = 11.sp, color = K.danger, modifier = Modifier.clickable { Chars.delete(id); delSel = null })
                        else Text(Sprites.name(id), fontSize = 10.sp, color = K.sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
      }
    }
}
