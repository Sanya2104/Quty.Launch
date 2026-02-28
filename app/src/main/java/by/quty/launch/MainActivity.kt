// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import by.quty.launch.core.Core
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView

/**
 * Главная активность лаунчера
 * Отвечает за отображение WebView с темами и обработку API вызовов
 */
class MainActivity : BaseActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var themeManager: ThemeManager

    companion object {
        const val REQUEST_CODE_SETTINGS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        applyOrientation() // Применяем сохраненную ориентацию из BaseActivity
        core = Core(this)
        webView = LauncherWebView(this)
        themeManager = ThemeManager(this, configManager) // configManager из BaseActivity
        webView.addJavascriptInterface(JsBridge(core), "Android")
        loadTheme()
        setContentView(webView)
        setFullScreen() // Метод из BaseActivity
    }

    /**
     * Загрузка активной темы в WebView
     * Получает тему из ThemeManager и загружает соответствующий index.html
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
        val currentOrientation = getCurrentOrientationString() // Метод из BaseActivity

        // Если ориентация изменилась - перезапускаем активность
        if (savedOrientation != currentOrientation) {
            recreate()
        }
    }
}