// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import by.quty.launch.core.Core
import by.quty.launch.core.ShellManager
import by.quty.launch.core.logger.Logger
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView
import java.io.File

/**
 * Главная активность лаунчера
 * Отвечает за отображение WebView с оболочками и обработку API вызовов
 */
class MainActivity : BaseActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var shellManager: ShellManager

    companion object {
        const val REQUEST_CODE_SETTINGS = 1001
        private const val DELAY_BEFORE_RECREATE = 300L // Задержка перед перезапуском (мс)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация логгера
        Logger.init(this)

        // Инициализация
        applyOrientation() // Применяем сохраненную ориентацию из BaseActivity
        core = Core(this)
        webView = LauncherWebView(this)
        shellManager = ShellManager(this, configManager)
        webView.addJavascriptInterface(JsBridge(core), "Android")

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
     */
    private fun loadShell() {
        val shellToActivate = shellManager.getShellToActivate()
        shellManager.setActiveShell(shellToActivate)

        // Для кастомных оболочек проверяем, распакована ли она
        if (!shellToActivate.isAsset) {
            val extractDir = File(filesDir, "shells/active/${shellToActivate.name}")
            if (!extractDir.exists() || !File(extractDir, "index.html").exists()) {
                // Если не распакована — распаковываем
                shellManager.setActiveShell(shellToActivate)
            }
        }

        webView.loadShell(
            shellName = shellToActivate.name,
            isAsset = shellToActivate.isAsset
        )
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