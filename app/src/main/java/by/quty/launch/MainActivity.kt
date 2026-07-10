// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        private const val DELAY_BEFORE_RECREATE = 300L // Задержка перед перезапуском (мс)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация
        applyOrientation() // Применяем сохраненную ориентацию из BaseActivity
        core = Core(this)
        webView = LauncherWebView(this)
        themeManager = ThemeManager(this, configManager)
        webView.addJavascriptInterface(JsBridge(core), "Android")
        loadTheme()
        setContentView(webView)

        // Включаем иммерсивный режим ПОСЛЕ того, как View создан
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    /**
     * Загрузка активной темы в WebView
     * Получает тему из ThemeManager и загружает соответствующий index.html
     */
    private fun loadTheme() {
        val themeToActivate = themeManager.getThemeToActivate()
        themeManager.setActiveTheme(themeToActivate)

        // Загружаем тему через новый единый метод
        webView.loadTheme(
            themeName = themeToActivate.name,
            isAsset = themeToActivate.isAsset
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Обрабатываем только изменение темы
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == SettingsActivity.RESULT_THEME_CHANGED) {
            // Получаем имя выбранной темы из Intent
            val selectedTheme = data?.getStringExtra(SettingsActivity.EXTRA_SELECTED_THEME)

            // Перезагружаем тему с задержкой, чтобы избежать мерцания
            Handler(Looper.getMainLooper()).postDelayed({
                loadTheme()
            }, DELAY_BEFORE_RECREATE)
        }
    }

    override fun onResume() {
        super.onResume()

        // Проверяем, не изменилась ли ориентация в настройках
        val savedOrientation = configManager.getOrientation()
        val currentOrientation = getCurrentOrientationString()

        // Получаем принудительную ориентацию из SharedPreferences
        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        val forcedOrientation = prefs.getString("forced_orientation", null)

        // Определяем, какая ориентация должна быть применена
        val expectedOrientation = when (forcedOrientation) {
            "portrait" -> "portrait"
            "landscape" -> "landscape"
            else -> savedOrientation
        }

        // Если ориентация изменилась - перезапускаем активность с задержкой
        if (expectedOrientation != currentOrientation) {
            Handler(Looper.getMainLooper()).postDelayed({
                recreate()
            }, DELAY_BEFORE_RECREATE)
        }
    }
}