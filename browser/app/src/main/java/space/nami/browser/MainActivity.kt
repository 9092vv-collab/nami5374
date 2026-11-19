package space.nami.browser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), Host {
    private lateinit var prefs: Prefs
    private lateinit var browser: Browser
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        browser = Browser(this, prefs, lifecycleScope, this)
        handleOpenFile(intent)
        val incoming = urlFrom(intent)
        if (!browser.restore(incoming)) browser.newTab(incoming ?: prefs.homeUrl).fromLink = incoming != null
        setContent { NamiApp(browser, prefs, this) }; if ((prefs.boostRamCache || prefs.boostOom) && (prefs.boostRoot || prefs.boostShizuku)) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (prefs.boostRamCache) Booster.mountRamCache(applicationContext, prefs.boostRoot, prefs.boostShizuku)
                if (prefs.boostOom) Booster.boostOomAndPriority(applicationContext, prefs.boostRoot, prefs.boostShizuku); } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleOpenFile(intent)) return
        urlFrom(intent)?.let { browser.newTab(it).fromLink = true; browser.toolbarVisible = false }
    }

    private fun urlFrom(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_VIEW -> i.dataString?.takeIf { val s = Uri.parse(it).scheme?.lowercase(); s == "http" || s == "https" }
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
            Regex("https?://\\S+").find(t)?.value ?: "https://www.google.com/search?q=" + Uri.encode(t)
        }
        else -> null
    }

    /** Tap on the "다 받았어요" notification → open the file now. */
    private fun handleOpenFile(i: Intent?): Boolean {
        if (i?.action != Downloads.ACTION_OPEN_FILE) return false
        val uri = i.data ?: return true
        Downloads.open(this, uri, i.getStringExtra("mime"), i.getStringExtra("name") ?: "")
        return true
    }

    override fun onResume() { super.onResume(); Downloads.resumePending(this) }

    override fun onStop() { super.onStop(); browser.save() }
    override fun onDestroy() { if (::browser.isInitialized) { browser.tabs.forEach { t -> t.frame.removeAllViews(); t.web.destroy(); t.thumb = null }; browser.tabs.clear() }; super.onDestroy() }
    
    /**
     * While a page is streaming a download (kio.ac), leaving Nami turns it into a small picture-in-picture window,
     * like the user's own "pop-up window in a corner" trick: a page that stays on screen isn't frozen.
     */
    private var pipWanted = false
    override fun relaying(on: Boolean) {
        if (on == pipWanted) return
        pipWanted = on
        runCatching {
            setPictureInPictureParams(android.app.PictureInPictureParams.Builder()
                .setAutoEnterEnabled(on && prefs.pipWhileRelay).setAspectRatio(android.util.Rational(3, 4)).build())
        }
        if (!on && isInPictureInPictureMode) browser.showPill("다 받았어요", 3000)
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        browser.inPip = inPip
    }

    override fun chooseFile(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = cb
        return try { pickFile.launch(params.createIntent()); true } catch (_: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
    }
}
