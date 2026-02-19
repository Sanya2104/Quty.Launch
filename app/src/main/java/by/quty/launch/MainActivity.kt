// *** MainActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import by.quty.launch.core.Core
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView

class MainActivity : ComponentActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация Core и WebView
        core = Core(this)
        webView = LauncherWebView(this)

        webView.addJavascriptInterface(
            JsBridge(core),
            "Android"
        )

        // Устанавливаем контент
        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView }
            )
        }

        webView.loadUrl("file:///android_asset/themes/default/index.html")

        // Устанавливаем полноэкранный режим
        setFullScreen()
    }

    override fun onResume() {
        super.onResume()
        // Восстанавливаем полноэкранный режим при возвращении в активность
        setFullScreen()
    }

    private fun setFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
