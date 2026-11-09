package space.sgu.next.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import space.sgu.next.data.SettingsStore

/** 첫 실행 안내 (original module 1701). */
private val intro = listOf(
    Triple(Icons.Outlined.AccountTree, "두 개의 프로세서", "서브 프로세서(경량 모델)가 명령을 읽고 어떤 필드를 열람·편집해야 하는지 판단합니다. 메인 프로세서(대형 모델)는 허용된 데이터만 받아 캐릭터를 작성합니다. 서브 프로세서는 토큰 절약용이며, 필요하면 설정에서 비활성화할 수 있습니다."),
    Triple(Icons.Outlined.Shield, "필드별 권한", "모든 필드에 열람(V0~V3)과 편집(E0~E2) 등급이 있습니다. Lv1 필드는 AI가 필요하다고 판단하면 먼저 묻고, 승인하면 이번 세션 동안만 Lv2로 올라갑니다. 언제든 직접 조정할 수 있습니다."),
    Triple(Icons.Outlined.IosShare, "RisuAI로 내보내기", "결과물은 CHARX, CHARX-JPEG, JSON, PNG 카드로 내보내 RisuAI에서 바로 가져올 수 있습니다. 모든 데이터는 이 기기에만 저장됩니다."),
)

@Composable
fun WelcomeScreen(done: (goProviders: Boolean) -> Unit) {
    fun finish(go: Boolean) { SettingsStore.update { it["onboardingDone"] = JsonPrimitive(true) }; done(go) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("SUPERGEN UNITED", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, color = Sgu.Sub)
            Text("SGU", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = Sgu.Ink, modifier = Modifier.padding(top = 8.dp))
            Text("명령 한 줄로 캐릭터를 만들고 편집하는 봇", style = MaterialTheme.typography.bodyLarge, color = Sgu.Sub, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                intro.forEachIndexed { i, (icon, title, body) -> IntroRow(i, icon, title, body) }
            }
            Spacer(Modifier.height(36.dp))
            InkButton("프로바이더 설정하러 가기", { finish(true) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.ArrowForward)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) { Text("건너뛰기", color = Sgu.Sub) }
        }
    }
}

@Composable
private fun IntroRow(i: Int, icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).border(1.dp, Sgu.Ink, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Sgu.Ink)
        }
        Column(Modifier.weight(1f)) {
            Text("0${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = Sgu.Sub)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sgu.Ink, modifier = Modifier.padding(top = 2.dp))
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = Sgu.Sub, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
