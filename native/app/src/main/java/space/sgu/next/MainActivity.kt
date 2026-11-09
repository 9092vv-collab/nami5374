package space.sgu.next

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import space.sgu.next.data.CharacterStore
import space.sgu.next.data.SettingsStore
import space.sgu.next.ui.SguApp
import space.sgu.next.ui.SguTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light only: dark status/navigation bar icons on the light background.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        CharacterStore.init(applicationContext)
        SettingsStore.init(applicationContext)
        space.sgu.next.data.Templates.init(applicationContext)
        space.sgu.next.data.TemplateLibrary.init(applicationContext)
        space.sgu.next.data.StyleLibrary.init(applicationContext)
        setContent { SguTheme { SguApp() } }
    }
}
