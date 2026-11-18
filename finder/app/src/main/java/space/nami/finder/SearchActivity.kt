package space.nami.finder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLEncoder

private const val NAMI = "space.nami.browser"
private val Ink = Color(0xFF141413)
private val Gray = Color(0xFF8A8984)
private val Accent = Color(0xFFD97757)

/** Search window: type, press search → opens in a new Nami tab. Tap outside or back to close. */
class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SearchBar(onGo = ::go, onClose = ::finish) }
    }

    // Leaving the window (home button, another app) closes it, so the next tap starts empty.
    override fun onStop() { super.onStop(); finish() }

    private fun go(input: String) {
        val url = toUrl(input) ?: return
        val uri = Uri.parse(url)
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(Intent(view).setPackage(NAMI))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nami가 없어서 다른 브라우저로 열어요", Toast.LENGTH_SHORT).show()
            runCatching { startActivity(view) }
        }
        finish()
    }
}

/** Same rule as Nami's address bar: address → open it, anything else → Google search. */
fun toUrl(input: String): String? {
    val s = input.trim()
    if (s.isEmpty()) return null
    return when {
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) -> s
        !s.contains(' ') && Regex("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) -> "https://$s"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }
}

@Composable
private fun SearchBar(onGo: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x59000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                // swallow taps so tapping the bar itself doesn't close the window
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    focus.requestFocus(); keyboard?.show()
                }
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = Ink),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onGo(text) }, onGo = { onGo(text) }),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("검색어나 주소", color = Gray, fontSize = 17.sp)
                        inner()
                    }
                },
            )
            if (text.isNotEmpty()) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { text = "" },
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.ic_clear), "지우기", Modifier.size(20.dp)) }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
}
