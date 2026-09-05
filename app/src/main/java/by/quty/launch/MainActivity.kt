// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.ShellManager
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
    private lateinit var jsBridge: JsBridge

    companion object {
        // Код запроса для ParametersActivity (из конфига)
        const val REQUEST_CODE_PARAMETERS = CoreConfig.PARAMETERS_REQUEST_CODE

        // Задержка перед пересозданием активности (из конфига)
        private const val DELAY_BEFORE_RELOAD = CoreConfig.DELAY_BEFORE_RECREATE_MS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, нужно ли перезапустить приложение для применения ориентации
        if (configManager.needRestartForOrientation()) {
            configManager.clearRestartForOrientationFlag()
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
            return
        }

        LoggerManager.init(this)

        shellManager = ShellManager(this, configManager)
        applyOrientation(shellManager)

        core = Core(this)
        // Используем applicationContext для WebView (предотвращает утечки памяти)
        webView = LauncherWebView(applicationContext)

        jsBridge = JsBridge(core, this)
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
     */
    private fun loadShell() {
        lifecycleScope.launch {
            val shellToActivate = shellManager.getShellToActivate()
            // Вызов suspend функции в фоновом потоке
            shellManager.setActiveShell(shellToActivate)

            // После установки оболочки повторно применяем ориентацию
            applyOrientation(shellManager)

            webView.loadShell(
                shellName = shellToActivate.name,
                isAsset = shellToActivate.isAsset
            )

            // После загрузки оболочки отправляем цвета в WebView
            webView.post {
                val primary = configManager.getSchemePrimaryColor()
                val accent = configManager.getSchemeAccentColor()
                jsBridge.applyColorScheme(primary, accent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Обрабатываем только изменение оболочки
        if (requestCode == REQUEST_CODE_PARAMETERS && resultCode == ParametersActivity.RESULT_SHELL_CHANGED) {
            // Перезагружаем оболочку с задержкой, чтобы избежать мерцания
            Handler(Looper.getMainLooper()).postDelayed({
                loadShell()
            }, DELAY_BEFORE_RELOAD)
        }
    }

    override fun onResume() {
        super.onResume()

        // При возврате в активность ПРИНУДИТЕЛЬНО применяем ориентацию
        if (::shellManager.isInitialized) {
            applyOrientation(shellManager)
        } else {
            applyOrientation()
        }

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }
}