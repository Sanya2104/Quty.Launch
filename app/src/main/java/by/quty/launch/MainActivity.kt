// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import by.quty.launch.core.Core
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

    // Последнее применённое состояние — чтобы не дёргать WebView зря
    private var lastAppliedLanguage: String? = null

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

        shellManager = ShellManager(this, configManager)
        applyOrientation(shellManager)

        core = Core(this)
        // Используем applicationContext для WebView (предотвращает утечки памяти)
        webView = LauncherWebView(applicationContext)

        jsBridge = JsBridge(core, this)
        jsBridge.setWebView(webView)
        webView.addJavascriptInterface(jsBridge, "Android")

        lastAppliedLanguage = configManager.getLanguageCode()

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

        // Проверяем смену языка → пушим в WebView, если изменилось
        if (::jsBridge.isInitialized && ::webView.isInitialized) {
            val currentLang = configManager.getLanguageCode()
            if (lastAppliedLanguage != null && lastAppliedLanguage != currentLang) {
                lastAppliedLanguage = currentLang
                jsBridge.notifyCoreStateChanged()
            }
        }

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Пушим оболочке, что ориентация/конфигурация изменилась
        if (::jsBridge.isInitialized && ::webView.isInitialized) {
            jsBridge.notifyCoreStateChanged()
        }
    }
}