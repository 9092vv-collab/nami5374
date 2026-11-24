package space.nami.maker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Set by the PC build's main(). */
object Plat { var desktop = false }

// Claude-like warm palette
object K {
    val bg = Color(0xFFFAF9F5)
    val panel = Color(0xFFF3F1EA)
    val card = Color(0xFFFFFFFF)
    val line = Color(0xFFE5E2D9)
    val text = Color(0xFF1F1E1D)
    val sub = Color(0xFF75736C)
    val accent = Color(0xFFD97757)
    val accentSoft = Color(0xFFF6E3DA)
    val bubble = Color(0xFFEFEBE1)
    val danger = Color(0xFFC0473F)
}

@Composable
fun MakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = K.accent, onPrimary = Color.White, background = K.bg, surface = K.card,
            onSurface = K.text, onBackground = K.text, secondaryContainer = K.accentSoft, outline = K.line,
        ),
        content = content,
    )
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (on) K.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = when { !enabled -> K.line; on -> K.accent; else -> K.text }, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, color = if (on) K.accent else K.text,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (on) K.accentSoft else K.card)
            .border(1.dp, if (on) K.accent else K.line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun Seg(options: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(K.panel).padding(3.dp)) {
        options.forEachIndexed { i, s ->
            Text(
                s, fontSize = 14.sp, color = if (i == sel) K.text else K.sub,
                fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (i == sel) K.card else Color.Transparent)
                    .clickable { onSel(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.accent, unfocusedBorderColor = K.line, focusedLabelColor = K.accent),
    )
}
