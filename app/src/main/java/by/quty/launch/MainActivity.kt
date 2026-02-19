// *** MainActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import by.quty.launch.api.ApiInitializer
import by.quty.launch.core.Core
import by.quty.launch.core.webview.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация API методов
        ApiInitializer.init(this)

        val core = Core()
        val webView = LauncherWebView(this)

        webView.addJavascriptInterface(
            JsBridge(core),
            "Android"
        )

        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView }
            )
        }

        webView.loadUrl("file:///android_asset/themes/default/index.html")
    }
}
