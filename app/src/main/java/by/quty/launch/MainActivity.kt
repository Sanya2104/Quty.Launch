// *** MainActivity.kt *** //
package by.quty.launch

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import by.quty.launch.core.Core
import by.quty.launch.core.ConfigManager
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView

class MainActivity : AppCompatActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var themeManager: ThemeManager
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        configManager = ConfigManager(this)  // сначала конфиг
        core = Core(this)
        webView = LauncherWebView(this)
        themeManager = ThemeManager(this, configManager)  // передаем конфиг в ThemeManager

        webView.addJavascriptInterface(JsBridge(core), "Android")

        // Получаем тему для активации из конфига и активируем её
        val themeToActivate = themeManager.getThemeToActivate()
        themeManager.setActiveTheme(themeToActivate)

        // Устанавливаем WebView как контент
        setContentView(webView)

        // Загружаем index.html выбранной темы
        webView.loadUrl(themeManager.getActiveThemeIndexHtml())

        // Устанавливаем полноэкранный режим
        setFullScreen()
    }

    override fun onResume() {
        super.onResume()
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