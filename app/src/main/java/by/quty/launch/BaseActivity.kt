// *** BaseActivity.kt *** //
package by.quty.launch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import java.util.Locale

/**
 * База для всех активностей: тема, язык, immersive, ориентация, диалог перезапуска.
 */
abstract class BaseActivity : AppCompatActivity() {

    private lateinit var _configManager: ConfigManager
    val configManager: ConfigManager get() = _configManager

    private var isListenerAttached = false

    private var needsRestart = false

    private var isRestarting = false

    /** Применяет язык до создания контекста. */
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("launcher_prefs", MODE_PRIVATE)

        val language = if (prefs.contains("app_language")) {
            prefs.getString("app_language", CoreConfig.DEFAULT_LANGUAGE) ?: CoreConfig.DEFAULT_LANGUAGE
        } else {
            val systemLang = Locale.getDefault().language
            if (systemLang == CoreConfig.LANGUAGE_RU) CoreConfig.LANGUAGE_RU else CoreConfig.DEFAULT_LANGUAGE
        }

        val locale = when (language) {
            CoreConfig.LANGUAGE_RU -> Locale.forLanguageTag(CoreConfig.LOCALE_TAG_RU)
            else -> Locale.forLanguageTag(CoreConfig.LOCALE_TAG_EN)
        }

        Locale.setDefault(locale)

        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)

        val localizedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeFromPrefs()
        applyColorSchemeFromPrefs()

        super.onCreate(savedInstanceState)

        _configManager = ConfigManager(this)

        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)

        if (!onboardingCompleted && this !is WelcomeActivity) {
            val intent = Intent(this, WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("base_needs_restart", needsRestart)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        needsRestart = savedInstanceState.getBoolean("base_needs_restart", false)
    }

    /** Тема из prefs (light/dark/system). */
    private fun applyThemeFromPrefs() {
        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        val mode = prefs.getString("theme_mode", CoreConfig.DEFAULT_THEME_MODE) ?: CoreConfig.DEFAULT_THEME_MODE

        val nightMode = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** Цветовая схема из prefs. */
    private fun applyColorSchemeFromPrefs() {
        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        val schemeId = prefs.getString("color_scheme", CoreConfig.DEFAULT_COLOR_SCHEME) ?: CoreConfig.DEFAULT_COLOR_SCHEME

        val themeRes = when (schemeId) {
            "red" -> R.style.Theme_QutyLaunch_Red
            "orange" -> R.style.Theme_QutyLaunch_Orange
            "lime" -> R.style.Theme_QutyLaunch_Lime
            "green" -> R.style.Theme_QutyLaunch_Green
            "teal" -> R.style.Theme_QutyLaunch_Teal
            "cyan" -> R.style.Theme_QutyLaunch_Cyan
            "blue" -> R.style.Theme_QutyLaunch_Blue
            "purple" -> R.style.Theme_QutyLaunch_Purple
            "pink" -> R.style.Theme_QutyLaunch_Pink
            else -> R.style.Theme_QutyLaunch_Teal
        }
        setTheme(themeRes)
    }

    // ============================================================
    // ПЕРЕЗАПУСК ПРИЛОЖЕНИЯ
    // ============================================================

    fun markRestartRequired() {
        if (!needsRestart) {
            needsRestart = true
        }
    }

    fun getNeedsRestart(): Boolean {
        return needsRestart
    }

    /** Показывает диалог перезапуска, если были изменения; иначе finish(). */
    fun checkAndShowRestartDialog() {
        if (needsRestart) {
            showRestartDialog()
        } else {
            finish()
        }
    }

    /** Glassmorphism-диалог с предложением перезапустить. */
    open fun showRestartDialog() {
        if (isRestarting) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_restart, null)

        val dialog = AlertDialog.Builder(
            this,
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window?.decorView?.rootView
            decorView?.let { view ->
                val renderEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                view.setRenderEffect(renderEffect)
                dialog.setOnDismissListener {
                    view.setRenderEffect(null)
                }
            }
        }

        dialogView.findViewById<Button>(R.id.btn_restart).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }
            isRestarting = true
            restartApp()
        }

        dialogView.findViewById<Button>(R.id.btn_later).setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.decorView?.rootView?.setRenderEffect(null)
            }
            // needsRestart не сбрасываем — диалог появится при следующем закрытии.
        }

        dialog.show()
    }

    /** Перезапуск приложения с задержкой 500 мс. */
    protected open fun restartApp() {
        configManager.setRestartForOrientationFlag()

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivityAnimated(intent)
            finishAnimated()
        }, 500)
    }

    // ============================================================
    // АНИМАЦИИ ПЕРЕХОДОВ
    // ============================================================

    /** startActivity с учётом настройки анимаций. */
    fun startActivityAnimated(intent: Intent) {
        startActivity(intent)
        applyTransitionIfNeeded()
    }

    /** finish с учётом настройки анимаций. */
    fun finishAnimated() {
        finish()
        applyTransitionIfNeeded()
    }

    @Suppress("DEPRECATION")
    private fun applyTransitionIfNeeded() {
        if (!::_configManager.isInitialized) return
        if (!configManager.isEffectiveAnimationsEnabled()) {
            overridePendingTransition(0, 0)
        }
    }

    // ============================================================
    // IMMERSIVE MODE
    // ============================================================

    /** Скрывает системные панели. strictMode — не показывать даже при свайпе. */
    protected fun enableImmersiveMode(strictMode: Boolean = false) {
        if (!configManager.isFullscreenEnabled()) return

        val currentWindow = window ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                enableImmersiveModeModern(currentWindow, strictMode)
            } else {
                enableImmersiveModeLegacy(currentWindow, strictMode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("WrongConstant")
    private fun enableImmersiveModeModern(window: android.view.Window, strictMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = window.insetsController
        if (insetsController != null) {
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

            if (strictMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
                } else {
                    insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            if (strictMode) {
                setupStrictModeListenerModern(window, insetsController)
            } else {
                removeStrictModeListenerModern(window)
            }
        }

        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @SuppressLint("WrongConstant")
    private fun setupStrictModeListenerModern(window: android.view.Window, insetsController: WindowInsetsController) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        removeStrictModeListenerModern(window)

        val decorView = window.decorView

        decorView.setOnApplyWindowInsetsListener { view, insets ->
            if (configManager.isStrictModeEnabled() && configManager.isFullscreenEnabled()) {
                val isStatusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                val isNavBarVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())

                if (isStatusBarVisible || isNavBarVisible) {
                    insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
            }

            view.onApplyWindowInsets(insets)
        }

        isListenerAttached = true
    }

    private fun removeStrictModeListenerModern(window: android.view.Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val decorView = window.decorView
        if (isListenerAttached) {
            decorView.setOnApplyWindowInsetsListener(null)
            isListenerAttached = false
        }
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveModeLegacy(window: android.view.Window, strictMode: Boolean) {
        val decorView = window.decorView

        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN

        flags = if (strictMode) {
            flags or View.SYSTEM_UI_FLAG_IMMERSIVE
        } else {
            flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        decorView.systemUiVisibility = flags

        if (strictMode) {
            setupStrictModeListenerLegacy(decorView)
        } else {
            removeStrictModeListenerLegacy(decorView)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupStrictModeListenerLegacy(decorView: View) {
        removeStrictModeListenerLegacy(decorView)

        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (!configManager.isStrictModeEnabled() || !configManager.isFullscreenEnabled()) {
                return@setOnSystemUiVisibilityChangeListener
            }

            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_IMMERSIVE
                        )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun removeStrictModeListenerLegacy(decorView: View) {
        decorView.setOnSystemUiVisibilityChangeListener(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window?.decorView?.post {
                if (!::_configManager.isInitialized) return@post
                val strictMode = configManager.isStrictModeEnabled()
                enableImmersiveMode(strictMode)
            }
        }
    }

    /** Применяет ориентацию: приоритет — forced из shell, иначе user. */
    protected fun applyOrientation(shellManager: ShellManager? = null) {
        val userOrientation = configManager.getOrientation()

        val forcedOrientation = shellManager?.getForcedOrientationFromActiveShell()

        val orientation = when (forcedOrientation) {
            "portrait", "landscape" -> forcedOrientation
            else -> userOrientation
        }

        val targetOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "user" -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        requestedOrientation = targetOrientation
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (!::_configManager.isInitialized) return

        window?.decorView?.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.let { removeStrictModeListenerModern(it) }
        } else {
            window?.decorView?.let { removeStrictModeListenerLegacy(it) }
        }

        super.onDestroy()
    }
}