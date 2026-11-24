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

/** API settings like Nami next: providers (Vertex AI, Anthropic) → models, plus which model the AI uses. */
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (open != null) open = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(K.bg).safeDrawingPadding().imePadding()) {
            @Suppress("UNUSED_VARIABLE") val rev = Api.rev
            val pid = open
            if (pid != null && Api.provider(pid) != null) ProviderPage(pid, onBack = { open = null })
            else ApiHome(onClose = onClose, onOpen = { open = it })
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

@Composable
private fun ApiHome(onClose: () -> Unit, onOpen: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = Api.rev
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
                    Card(onClick = { Api.select(m.optString("id")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Radio(m.optString("id") == cur)
                            Text("  " + Api.label(m), fontSize = 15.sp, color = K.text)
                        }
                    }
                }
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
private fun ProviderPage(pid: String, onBack: () -> Unit) {
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
                    Card(onClick = { Api.select(mid) }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Radio(mid == cur)
                                Text("  " + m.optString("modelId"), fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f))
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
                Hint("동그라미를 누르면 AI가 그 모델을 써요.")

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
