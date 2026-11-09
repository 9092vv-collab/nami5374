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
        space.sgu.next.data.Workshop.init(applicationContext)
        space.sgu.next.data.KeepAlive.watch(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { SguTheme { SguApp() } }
    }

    override fun onResume() { super.onResume(); space.sgu.next.data.KeepAlive.appVisible = true }
    override fun onPause() { super.onPause(); space.sgu.next.data.KeepAlive.appVisible = false }
}
