// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.pm.ActivityInfo
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

    companion object {
        const val REQUEST_CODE_SETTINGS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        configManager = ConfigManager(this)
        applyOrientation()
        core = Core(this)
        webView = LauncherWebView(this)
        themeManager = ThemeManager(this, configManager)
        webView.addJavascriptInterface(JsBridge(core), "Android")
        loadTheme()
        setContentView(webView)
        setFullScreen()
    }

    /**
     * Применение сохраненной ориентации экрана
     */
    private fun applyOrientation() {
        val orientation = configManager.getOrientation()

        requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    /**
     * Загрузка активной темы в WebView
     */
    private fun loadTheme() {
        val themeToActivate = themeManager.getThemeToActivate()
        themeManager.setActiveTheme(themeToActivate)
        webView.loadUrl(themeManager.getActiveThemeIndexHtml())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Обрабатываем только изменение темы (ориентация проверяется в onResume)
        if (requestCode == REQUEST_CODE_SETTINGS &&
            resultCode == SettingsActivity.RESULT_THEME_CHANGED) {
            loadTheme()
        }
    }

    override fun onResume() {
        super.onResume()
        setFullScreen()

        // Проверяем, не изменилась ли ориентация в настройках
        val savedOrientation = configManager.getOrientation()
        val currentOrientation = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "portrait"
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
            else -> "sensor"
        }

        // Если ориентация изменилась - перезапускаем активность
        if (savedOrientation != currentOrientation) {
            recreate()
        }
    }

    /**
     * Установка полноэкранного режима
     */
    private fun setFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}