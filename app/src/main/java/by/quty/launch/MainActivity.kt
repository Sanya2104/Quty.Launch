// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.SystemUpdateManager
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.webview.JsBridge
import by.quty.launch.core.webview.LauncherWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Главная активность Quty.Launch.
 *
 * Одновременно:
 * - HOME-лаунчер по умолчанию (если юзер его назначил) — на неё
 *   система присылает Intent.ACTION_MAIN + CATEGORY_HOME при нажатии
 *   кнопки Home;
 * - Хост WebView с активной оболочкой.
 *
 * launchMode="singleTask" в манифесте — при повторном запуске Home
 * система шлёт onNewIntent() в существующий экземпляр, а не создаёт
 * новый. Мы в onNewIntent() ничего не делаем: WebView уже загружен,
 * повторно грузить оболочку нет смысла.
 */
class MainActivity : BaseActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var shellManager: ShellManager
    private lateinit var jsBridge: JsBridge
    private lateinit var updateManager: SystemUpdateManager

    // Последнее применённое состояние — чтобы не дёргать WebView зря
    private var lastAppliedLanguage: String? = null

    // Последнее применённое состояние анимаций — чтобы не инжектить CSS зря
    private var lastAppliedAnimations: Boolean? = null

    // Последнее известное состояние обновления — чтобы отследить смену
    // и уведомить оболочку через notifyCoreStateChanged()
    private var lastUpdateAvailable: Boolean = false

    // Флаг, что авто-проверка сейчас выполняется (защита от параллельных запусков)
    private var isAutoCheckRunning: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LoggerManager.info(
            LoggerTagModel.CORE,
            "CORE.main  created"
        )

        // Проверяем, нужно ли перезапустить приложение для применения ориентации
        if (configManager.needRestartForOrientation()) {
            configManager.clearRestartForOrientationFlag()
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivityAnimated(intent)
            finishAnimated()
            return
        }

        shellManager = ShellManager(this, configManager)
        applyOrientation(shellManager)

        core = Core(this)
        // Используем applicationContext для WebView (предотвращает утечки памяти)
        webView = LauncherWebView(applicationContext)

        updateManager = SystemUpdateManager(this)

        jsBridge = JsBridge(core, this)
        jsBridge.setWebView(webView)
        webView.addJavascriptInterface(jsBridge, "Android")

        lastAppliedLanguage = configManager.getLanguageCode()
        lastAppliedAnimations = configManager.isEffectiveAnimationsEnabled()
        lastUpdateAvailable = updateManager.getCachedUpdate()?.hasUpdate == true

        // Применяем состояние анимаций к WebView сразу — CSS-инжект
        // повторится в onPageFinished, когда документ будет готов.
        webView.applyAnimationsEnabled(lastAppliedAnimations == true)

        // Загружаем оболочку
        loadShell()
        setContentView(webView)

        // Включаем иммерсивный режим ПОСЛЕ того, как View создан
        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        // Фоновая авто-проверка обновлений — с задержкой,
        // чтобы не мешать старту (WebView, ShellManager и т.д.)
        lifecycleScope.launch {
            delay(CoreConfig.AUTO_CHECK_START_DELAY_MS.milliseconds)
            maybeRunAutoUpdateCheck()
        }
    }

    /**
     * Обработка повторного запуска через Home.
     *
     * Из-за launchMode="singleTask" система при нажатии Home
     * шлёт Intent.ACTION_MAIN + CATEGORY_HOME в существующий экземпляр,
     * а не создаёт новый. WebView уже загружен — просто ничего не делаем.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LoggerManager.debug(
            LoggerTagModel.CORE,
            "CORE.main  onNewIntent  action=${intent.action}"
        )
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

            LoggerManager.info(
                LoggerTagModel.CORE,
                "CORE.loadShell  ${shellToActivate.name}  asset=${shellToActivate.isAsset}"
            )

            webView.loadShell(
                shellName = shellToActivate.name,
                isAsset = shellToActivate.isAsset
            )

            // Повторно применяем состояние анимаций к новой странице —
            // onPageFinished в WebViewClient тоже это сделает,
            // но здесь мы страхуемся на случай, если страница уже в кэше.
            webView.applyAnimationsEnabled(lastAppliedAnimations == true)
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
                LoggerManager.info(
                    LoggerTagModel.CORE,
                    "CORE.language  $lastAppliedLanguage → $currentLang"
                )
                lastAppliedLanguage = currentLang
                jsBridge.notifyCoreStateChanged()
            }

            // Проверяем смену состояния анимаций → инжектим/снимаем CSS
            val currentAnimations = configManager.isEffectiveAnimationsEnabled()
            if (lastAppliedAnimations != currentAnimations) {
                LoggerManager.info(
                    LoggerTagModel.CORE,
                    "CORE.animations  $lastAppliedAnimations → $currentAnimations"
                )
                lastAppliedAnimations = currentAnimations
                webView.applyAnimationsEnabled(currentAnimations)
                // Пуш в оболочку — чтобы она тоже обновила своё состояние
                jsBridge.notifyCoreStateChanged()
            }
        }

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        // Авто-проверка обновлений — если прошло больше интервала с последней.
        // Проверяем на возврате в активность (в т.ч. когда Quty.Launch — HOME
        // и onCreate случается редко).
        maybeRunAutoUpdateCheck()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Пушим оболочке, что ориентация/конфигурация изменилась
        if (::jsBridge.isInitialized && ::webView.isInitialized) {
            jsBridge.notifyCoreStateChanged()
        }
    }

    // ============================================================
    // ФОНОВАЯ АВТО-ПРОВЕРКА ОБНОВЛЕНИЙ
    // ============================================================

    /**
     * Запускает авто-проверку, если:
     * - авто-проверка включена в настройках;
     * - с последней проверки прошло >= интервала (см. SystemUpdateManager.shouldRunAutoCheck());
     * - сейчас нет другой запущенной проверки (isAutoCheckRunning).
     *
     * Логика:
     * 1. Проверяем условия. Если не проходят — выходим.
     * 2. Сразу ставим lastCheckTime = now (защита от спама при
     *    недоступном сервере — следующая попытка будет через интервал).
     * 3. Вызываем checkForUpdates().
     * 4. Сохраняем результат в кэш (setCachedUpdate) — его прочитают
     *    GetApps, GetCoreState, SettingsActivity, UpdateFragment.
     * 5. Если обновление появилось — уведомляем оболочку через
     *    notifyCoreStateChanged(), чтобы она перезапросила GetApps
     *    и показала маркер без перезагрузки.
     */
    private fun maybeRunAutoUpdateCheck() {
        if (!::updateManager.isInitialized) return
        if (isAutoCheckRunning) return
        if (!updateManager.shouldRunAutoCheck()) return

        isAutoCheckRunning = true

        // Обновляем lastCheckTime ДО запроса — чтобы при падении
        // следующая попытка была не раньше, чем через интервал.
        updateManager.setLastCheckTime(System.currentTimeMillis())

        lifecycleScope.launch {
            try {
                val result = updateManager.checkForUpdates()

                // Сохраняем в кэш — его прочитают GetApps / GetCoreState /
                // SettingsActivity / UpdateFragment
                updateManager.setCachedUpdate(result)

                // Если обновление появилось и раньше его не было —
                // уведомляем оболочку, чтобы она перезапросила GetApps
                val currentUpdateAvailable = result.hasUpdate
                if (currentUpdateAvailable != lastUpdateAvailable) {
                    lastUpdateAvailable = currentUpdateAvailable
                    if (::jsBridge.isInitialized) {
                        LoggerManager.info(
                            LoggerTagModel.UPDATE,
                            "UPDATE.auto  notify shell  hasUpdate=$currentUpdateAvailable"
                        )
                        jsBridge.notifyCoreStateChanged()
                    }
                }

                LoggerManager.info(
                    LoggerTagModel.UPDATE,
                    "UPDATE.auto  done  hasUpdate=${result.hasUpdate}  error=${result.error ?: "—"}"
                )
            } catch (e: Exception) {
                LoggerManager.error(
                    LoggerTagModel.UPDATE,
                    "UPDATE.auto  error  ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            } finally {
                isAutoCheckRunning = false
            }
        }
    }
}