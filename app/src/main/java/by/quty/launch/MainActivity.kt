// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView
import kotlinx.coroutines.launch

/**
 * Главная активность Quty.Launch
 * Отвечает за отображение WebView с оболочками и обработку API вызовов
 */
class MainActivity : BaseActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var shellManager: ShellManager

    companion object {
        // Код запроса для SettingsActivity (из конфига)
        const val REQUEST_CODE_SETTINGS = CoreConfig.SETTINGS_REQUEST_CODE

        // Задержка перед пересозданием активности (из конфига)
        private const val DELAY_BEFORE_RECREATE = CoreConfig.DELAY_BEFORE_RECREATE_MS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация логгера
        LoggerManager.init(this)

        // Инициализация
        applyOrientation() // Применяем сохраненную ориентацию из BaseActivity
        core = Core(this)
        // Используем applicationContext для WebView (предотвращает утечки памяти)
        webView = LauncherWebView(applicationContext)
        shellManager = ShellManager(this, configManager)

        val jsBridge = JsBridge(core, this)
        jsBridge.setWebView(webView)
        webView.addJavascriptInterface(jsBridge, "Android")

        // Загружаем оболочку
        loadShell()

        setContentView(webView)

        // Включаем иммерсивный режим ПОСЛЕ того, как View создан
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    /**
     * Загрузка активной оболочки в WebView
     * Получает оболочку из ShellManager и загружает соответствующий index.html
     * - Загрузка оболочки в корутине
     * - setActiveShell() - suspend функция, не блокирует UI
     */
    private fun loadShell() {
        lifecycleScope.launch {
            val shellToActivate = shellManager.getShellToActivate()
            // Вызов suspend функции в фоновом потоке
            shellManager.setActiveShell(shellToActivate)

            // Загрузка WebView в UI потоке
            webView.loadShell(
                shellName = shellToActivate.name,
                isAsset = shellToActivate.isAsset
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Обрабатываем только изменение оболочки
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == SettingsActivity.RESULT_SHELL_CHANGED) {
            // Перезагружаем оболочку с задержкой, чтобы избежать мерцания
            Handler(Looper.getMainLooper()).postDelayed({
                loadShell()
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