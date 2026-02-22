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
import by.quty.launch.core.Theme
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView

class MainActivity : ComponentActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация Core, WebView, themeManager
        core = Core(this)
        webView = LauncherWebView(this)
        themeManager = ThemeManager(this)

        webView.addJavascriptInterface(JsBridge(core), "Android")

        // Устанавливаем тему (по умолчанию дефолтная)
        val themes = themeManager.getAvailableThemes()
        val activeTheme = themes.find { !it.isDefault } ?: themes.firstOrNull()
        if (activeTheme != null) {
            themeManager.setActiveTheme(activeTheme)
        } else {
            // Обработка ошибки - нет доступных тем
            // Если нет тем (что маловероятно), используем дефолтную
            themeManager.setActiveTheme(Theme("Default", true, "file:///android_asset/themes/default/"))
        }

        // Устанавливаем контент
        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView }
            )
        }

        // Загружаем index.html выбранной темы
        webView.loadUrl(themeManager.getActiveThemeIndexHtml())

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
