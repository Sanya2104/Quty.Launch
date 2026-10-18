// *** MainActivity.kt *** //
package by.quty.launch

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
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
 * Safe Mode: если при запуске зажать Volume Down на 1.5 секунды —
 * включится безопасный режим (грузится оболочка "default", выбранная
 * пользователем сохраняется в prefs и вернётся после выхода из Safe Mode).
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
    private var lastUpdateAvailable: Boolean = false

    // Флаг, что авто-проверка сейчас выполняется
    private var isAutoCheckRunning: Boolean = false

    // ===== Safe Mode: перехват Volume Down =====

    /**
     * Окно удержания Volume Down для включения Safe Mode (мс).
     * 1500 мс — достаточно, чтобы случайное нажатие не сработало,
     * но и не заставлять юзера держать кнопку вечность.
     */
    private val safeModeHoldDurationMs = 1500L

    /**
     * Флаг, что кнопка Volume Down сейчас зажата.
     * Защита от повторного запуска таймера.
     */
    private var isVolumeDownHeld = false

    /**
     * Флаг, что Safe Mode уже сработал в этой сессии MainActivity.
     * После срабатывания ничего не делаем — перезапуск сам всё сделает.
     */
    private var isSafeModeTriggered = false

    private val handler = Handler(Looper.getMainLooper())
    private val safeModeRunnable = Runnable {
        if (isVolumeDownHeld && !isSafeModeTriggered && !configManager.isSafeModeActive()) {
            isSafeModeTriggered = true
            enableSafeMode()
        }
    }

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
        webView = LauncherWebView(applicationContext)

        updateManager = SystemUpdateManager(this)

        jsBridge = JsBridge(core, this)
        jsBridge.setWebView(webView)
        webView.addJavascriptInterface(jsBridge, "Android")

        lastAppliedLanguage = configManager.getLanguageCode()
        lastAppliedAnimations = configManager.isEffectiveAnimationsEnabled()
        lastUpdateAvailable = updateManager.getCachedUpdate()?.hasUpdate == true

        webView.applyAnimationsEnabled(lastAppliedAnimations == true)

        loadShell()
        setContentView(webView)

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        // Фоновая авто-проверка обновлений — с задержкой
        lifecycleScope.launch {
            delay(CoreConfig.AUTO_CHECK_START_DELAY_MS.milliseconds)
            maybeRunAutoUpdateCheck()
        }
    }

    // ============================================================
    // SAFE MODE (перехват Volume Down)
    // ============================================================

    /**
     * Перехват нажатия Volume Down.
     *
     * - ACTION_DOWN → запускаем таймер на safeModeHoldDurationMs.
     * - ACTION_UP или отмена → отменяем таймер.
     *
     * Если таймер сработал — включаем Safe Mode.
     * Если кнопка отпущена раньше — уменьшаем громкость вручную,
     * компенсируя проглоченный ACTION_DOWN (иначе кнопка работала бы
     * «через раз» при коротком нажатии).
     *
     * ВАЖНО: возвращаем true на ACTION_DOWN / ACTION_UP, чтобы
     * не пикало при удержании.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {

            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (!isVolumeDownHeld && !isSafeModeTriggered && !configManager.isSafeModeActive()) {
                        isVolumeDownHeld = true
                        handler.postDelayed(safeModeRunnable, safeModeHoldDurationMs)
                    }
                    // Блокируем системный звук, пока считаем таймер
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (isVolumeDownHeld) {
                        isVolumeDownHeld = false
                        handler.removeCallbacks(safeModeRunnable)

                        // Если Safe Mode не сработал — компенсируем
                        // пропущенный ACTION_DOWN вручную.
                        if (!isSafeModeTriggered) {
                            decreaseVolumeManually()
                        }
                        return true
                    }
                    return false
                }
                else -> return super.dispatchKeyEvent(event)
            }
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * Уменьшает громкость вручную (компенсация проглоченного ACTION_DOWN).
     *
     * Нужно, чтобы юзер не замечал, что кнопка Volume Down
     * работает «через раз» при коротком нажатии.
     */
    private fun decreaseVolumeManually() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
            // Игнорируем ошибки
        }
    }

    /**
     * Включает Safe Mode:
     * - ставит флаг safe_mode_active = true;
     * - показывает тост;
     * - перезапускает MainActivity, чтобы ShellManager перечитал
     *   активную оболочку и загрузил default.
     */
    private fun enableSafeMode() {
        LoggerManager.warn(
            LoggerTagModel.CORE,
            "CORE.safe_mode  activated"
        )

        configManager.setSafeModeActive(true)

        Toast.makeText(
            this,
            R.string.safe_mode_activated_toast,
            Toast.LENGTH_LONG
        ).show()

        // Перезапускаем активность — onCreate пересоздаст ShellManager
        // и подхватит Safe Mode.
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivityAnimated(intent)
        finishAnimated()
    }

    /**
     * Снимает таймер и освобождает ресурсы.
     */
    private fun clearSafeModeState() {
        handler.removeCallbacks(safeModeRunnable)
        isVolumeDownHeld = false
    }

    override fun onDestroy() {
        clearSafeModeState()
        super.onDestroy()
    }

    // ============================================================
    // ОСТАЛЬНАЯ ЛОГИКА
    // ============================================================

    /**
     * Обработка повторного запуска через Home.
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
            shellManager.setActiveShell(shellToActivate)

            applyOrientation(shellManager)

            LoggerManager.info(
                LoggerTagModel.CORE,
                "CORE.loadShell  ${shellToActivate.name}  asset=${shellToActivate.isAsset}  safeMode=${configManager.isSafeModeActive()}"
            )

            webView.loadShell(
                shellName = shellToActivate.name,
                isAsset = shellToActivate.isAsset
            )

            webView.applyAnimationsEnabled(lastAppliedAnimations == true)
        }
    }

    override fun onResume() {
        super.onResume()

        if (::shellManager.isInitialized) {
            applyOrientation(shellManager)
        } else {
            applyOrientation()
        }

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

            val currentAnimations = configManager.isEffectiveAnimationsEnabled()
            if (lastAppliedAnimations != currentAnimations) {
                LoggerManager.info(
                    LoggerTagModel.CORE,
                    "CORE.animations  $lastAppliedAnimations → $currentAnimations"
                )
                lastAppliedAnimations = currentAnimations
                webView.applyAnimationsEnabled(currentAnimations)
                jsBridge.notifyCoreStateChanged()
            }
        }

        window.decorView.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }

        maybeRunAutoUpdateCheck()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (::jsBridge.isInitialized && ::webView.isInitialized) {
            jsBridge.notifyCoreStateChanged()
        }
    }

    // ============================================================
    // ФОНОВАЯ АВТО-ПРОВЕРКА ОБНОВЛЕНИЙ
    // ============================================================

    private fun maybeRunAutoUpdateCheck() {
        if (!::updateManager.isInitialized) return
        if (isAutoCheckRunning) return
        if (!updateManager.shouldRunAutoCheck()) return

        isAutoCheckRunning = true

        updateManager.setLastCheckTime(System.currentTimeMillis())

        lifecycleScope.launch {
            try {
                val result = updateManager.checkForUpdates()
                updateManager.setCachedUpdate(result)

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