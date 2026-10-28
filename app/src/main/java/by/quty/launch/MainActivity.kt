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
 * HOME-лаунчер + хост WebView с активной оболочкой.
 * Volume Down 1.5 сек при старте — Safe Mode.
 */
class MainActivity : BaseActivity() {

    private lateinit var core: Core
    private lateinit var webView: LauncherWebView
    private lateinit var shellManager: ShellManager
    private lateinit var jsBridge: JsBridge
    private lateinit var updateManager: SystemUpdateManager

    private var lastAppliedLanguage: String? = null

    private var lastAppliedAnimations: Boolean? = null

    private var lastUpdateAvailable: Boolean = false

    private var isAutoCheckRunning: Boolean = false

    // ===== Safe Mode: перехват Volume Down =====

    /** Время удержания Volume Down для Safe Mode. */
    private val safeModeHoldDurationMs = 1500L

    private var isVolumeDownHeld = false

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

        lifecycleScope.launch {
            delay(CoreConfig.AUTO_CHECK_START_DELAY_MS.milliseconds)
            maybeRunAutoUpdateCheck()
        }
    }

    // ============================================================
    // SAFE MODE
    // ============================================================

    /** Перехват Volume Down: удержание 1.5 сек включает Safe Mode. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {

            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (!isVolumeDownHeld && !isSafeModeTriggered && !configManager.isSafeModeActive()) {
                        isVolumeDownHeld = true
                        handler.postDelayed(safeModeRunnable, safeModeHoldDurationMs)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (isVolumeDownHeld) {
                        isVolumeDownHeld = false
                        handler.removeCallbacks(safeModeRunnable)

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

    /** Компенсация «проглоченного» ACTION_DOWN при коротком нажатии. */
    private fun decreaseVolumeManually() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
        }
    }

    /** Ставит флаг Safe Mode и перезапускает приложение. */
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

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivityAnimated(intent)
        finishAnimated()
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LoggerManager.debug(
            LoggerTagModel.CORE,
            "CORE.main  onNewIntent  action=${intent.action}"
        )
    }

    /** Загружает активную оболочку в WebView. */
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
                    "CORE.language  $lastAppliedLanguage > $currentLang"
                )
                lastAppliedLanguage = currentLang
                jsBridge.notifyCoreStateChanged()
            }

            val currentAnimations = configManager.isEffectiveAnimationsEnabled()
            if (lastAppliedAnimations != currentAnimations) {
                LoggerManager.info(
                    LoggerTagModel.CORE,
                    "CORE.animations  $lastAppliedAnimations > $currentAnimations"
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