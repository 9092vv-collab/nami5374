package space.sgu.next.ui

import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.sgu.next.data.Thumbs
import java.io.File

// ---------- Top bar ----------

/** Claude-style top bar: no background, centred bold title (+ optional grey subtitle). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SguTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                if (subtitle != null) {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Sgu.Sub)
                }
            }
        },
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
                navigation != null -> navigation()
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Sgu.Bg, scrolledContainerColor = Sgu.Bg),
    )
}

// ---------- Pills ----------

/** Separated rounded tabs. Selected = ink pill with a soft shadow; others = plain grey text. */
@Composable
fun PillTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val scroll = rememberScrollState()
    val lefts = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    // Keep the selected pill on screen when the row is wider than the screen (only this row scrolls).
    LaunchedEffect(selected, lefts[selected]) {
        lefts[selected]?.let { x -> scroll.animateScrollTo((x - 40).coerceAtLeast(0)) }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(contentPadding).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            Box(Modifier.onGloballyPositioned { lefts[i] = it.positionInParent().x.toInt() }) { PillChip(label, i == selected) { onSelect(i) } }
        }
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Sgu.Ink else Color.Transparent, tween(220), label = "pillBg")
    val fg by animateColorAsState(if (selected) Color.White else Sgu.Sub, tween(220), label = "pillFg")
    val lift by animateDpAsState(if (selected) 6.dp else 0.dp, tween(220), label = "pillLift")
    Row(
        Modifier
            .shadow(lift, CircleShape, ambientColor = Sgu.Ink.copy(alpha = 0.2f), spotColor = Sgu.Ink.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Main action: ink pill, white bold text, soft shadow (like "+ 새 세션"). */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        modifier
            .shadow(if (enabled) 10.dp else 0.dp, CircleShape, ambientColor = Sgu.Ink.copy(alpha = 0.25f), spotColor = Sgu.Ink.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(if (enabled) Sgu.Ink else Sgu.Line)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** Secondary action: white pill with a thin line. */
@Composable
fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Sgu.Card)
            .border(1.dp, Sgu.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Sgu.Ink, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Sgu.Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun Badge(text: String, container: Color = Sgu.Ink, content: Color = Color.White) {
    Text(
        text,
        modifier = Modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 3.dp),
        color = content, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
    )
}

// ---------- Cards ----------

/** Side inset of grouped rows; pages that already pad their content provide 0.dp. */
val LocalGroupInset = compositionLocalOf { 16.dp }

private val Big = 24.dp
private val Small = 6.dp

/** Shape for row [index] of [count] inside a grouped card: only the outer corners are big. */
fun groupShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(Big)
    index == 0 -> RoundedCornerShape(topStart = Big, topEnd = Big, bottomStart = Small, bottomEnd = Small)
    index == count - 1 -> RoundedCornerShape(topStart = Small, topEnd = Small, bottomStart = Big, bottomEnd = Big)
    else -> RoundedCornerShape(Small)
}

/** One row of a Claude-style grouped list (rows are separated by a thin gap of background). */
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LocalGroupInset.current)
            .padding(top = if (index == 0) 0.dp else 3.dp)
            .clip(groupShape(index, count))
            .background(Sgu.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Sgu.Ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Sgu.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = Sgu.Sub,
    )
}

/** White card with a thin line (stand-alone card). */
@Composable
fun LineCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Big))
            .background(Sgu.Card)
            .border(1.dp, Sgu.Line, RoundedCornerShape(Big))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) { content() }
}

// ---------- Misc ----------

@Composable
fun EmptyPane(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = Sgu.Sub)
    }
}

/** Character icon from file (downsampled + cached), else a soft initial. */
@Composable
fun CharIcon(file: File?, cacheKey: String, name: String, size: Dp = 52.dp, corner: Dp = 16.dp) {
    val bmp by produceState<ImageBitmap?>(null, cacheKey) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Thumbs.load(file, cacheKey, 256) }
    }
    val shape = RoundedCornerShape(corner)
    val b = bmp
    if (b != null) {
        Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(Sgu.Bubble), contentAlignment = Alignment.Center) {
            Text(name.take(1).ifBlank { "?" }, color = Sgu.Sub, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp, fontFamily = Pretendard)
        }
    }
}
