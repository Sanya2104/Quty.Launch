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
 * Базовый класс для всех активностей приложения
 * Содержит общую логику для работы с ориентацией, полноэкранным режимом и конфигурацией
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * Менеджер конфигурации для работы с настройками приложения
     * Инициализируется в onCreate и доступен во всех дочерних активностях
     */
    private lateinit var _configManager: ConfigManager
    val configManager: ConfigManager get() = _configManager

    // Флаг, что слушатель активен
    private var isListenerAttached = false

    // Флаг, что были изменения, требующие перезапуска
    private var needsRestart = false

    // Флаг для предотвращения множественных перезапусков
    private var isRestarting = false

    /**
     * Применяет язык до создания контекста активности.
     * Работает для всех активностей, наследующих BaseActivity.
     */
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("launcher_prefs", MODE_PRIVATE)

        // Если язык ещё не сохранён — определяем системный
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
        // Применяем тему ДО super.onCreate()
        applyThemeFromPrefs()
        // Применяем цветовую схему ДО super.onCreate()
        applyColorSchemeFromPrefs()

        super.onCreate(savedInstanceState)

        _configManager = ConfigManager(this)

        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)

        if (!onboardingCompleted && this !is WelcomeActivity) {
            // Если онбординг не пройден, а мы не в WelcomeActivity — перенаправляем
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

    /**
     * Применяет тему (Light/Dark/System) ДО super.onCreate()
     */
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

    /**
     * Применяет цветовую схему к активности
     * Вызывается ДО super.onCreate()
     */
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
    // ПЕРЕЗАПУСК ПРИЛОЖЕНИЯ (RESTART REQUIRED)
    // ============================================================

    /**
     * Отмечает, что пользователь изменил настройку,
     * которая требует перезапуска приложения.
     */
    fun markRestartRequired() {
        if (!needsRestart) {
            needsRestart = true
        }
    }

    /**
     * Возвращает текущее состояние необходимости перезапуска.
     */
    fun getNeedsRestart(): Boolean {
        return needsRestart
    }

    /**
     * Проверяет, были ли изменения, требующие перезапуска.
     * Если да — показывает диалог. Если нет — закрывает активность.
     */
    fun checkAndShowRestartDialog() {
        if (needsRestart) {
            showRestartDialog()
        } else {
            finish()
        }
    }

    /**
     * Показывает кастомный Glassmorphism диалог с предложением перезапустить приложение.
     *
     * open — чтобы дочерние активности (например, TerminalActivity) могли
     * при необходимости переопределить поведение (например, добавить свой
     * текст или действие перед показом).
     */
    open fun showRestartDialog() {
        if (isRestarting) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_restart, null)

        // Используем прозрачную тему — у dialog_restart свой bg_glass_dialog
        val dialog = AlertDialog.Builder(
            this,
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Прозрачный фон окна
        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        // ===== РАЗМЫТИЕ ДЛЯ ANDROID 12+ =====
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

        // Настраиваем кнопки
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

            /*
             * НЕ СБРАСЫВАЕМ needsRestart!
             *
             * Пользователь выбрал "Позже", поэтому изменения
             * всё ещё требуют перезапуска.
             *
             * При следующем закрытии настроек диалог снова появится.
             */
        }

        dialog.show()
    }

    /**
     * Перезапуск приложения с задержкой.
     *
     * protected open — дочерние активности могут переопределить,
     * если нужна своя логика (например, разный набор флагов).
     */
    protected open fun restartApp() {
        // Устанавливаем флаг для применения ориентации
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

    /**
     * Запускает Activity. Если анимации отключены (пользовательская
     * настройка ИЛИ системный флаг ANIMATOR_DURATION_SCALE == 0) —
     * переход выполняется без анимации.
     *
     * ВАЖНО: public, а не protected — чтобы фрагменты (DeveloperFragment,
     * ShellFragment и т.п.) могли вызывать через (activity as? BaseActivity).
     * Фрагменты не являются наследниками BaseActivity, protected им не виден.
     */
    fun startActivityAnimated(intent: Intent) {
        startActivity(intent)
        applyTransitionIfNeeded()
    }

    /**
     * Закрывает текущую Activity. Если анимации отключены — без анимации.
     *
     * ВАЖНО: public по той же причине, что и startActivityAnimated().
     */
    fun finishAnimated() {
        finish()
        applyTransitionIfNeeded()
    }

    /**
     * Отключает анимацию перехода, если она должна быть отключена.
     *
     * overridePendingTransition(0, 0) — синхронный вызов, работает
     * сразу после startActivity() / finish(). На Android 14+ помечен
     * как deprecated, но всё ещё работает и является самым простым
     * способом без ActivityOptions bundle при каждом вызове.
     */
    @Suppress("DEPRECATION")
    private fun applyTransitionIfNeeded() {
        if (!::_configManager.isInitialized) return
        if (!configManager.isEffectiveAnimationsEnabled()) {
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Установка полноэкранного режима
     * Скрывает системные панели (статус бар и навигацию)
     *
     * @param strictMode если true - панели не появляются даже при свайпе
     */
    protected fun enableImmersiveMode(strictMode: Boolean = false) {
        // Если полноэкранный режим отключен — выходим
        if (!configManager.isFullscreenEnabled()) return

        val currentWindow = window ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+) - используем современный API
                enableImmersiveModeModern(currentWindow, strictMode)
            } else {
                // Android 10 и ниже (API 29-) - используем старый API
                enableImmersiveModeLegacy(currentWindow, strictMode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Современный способ для Android 11+ (API 30+)
     */
    @SuppressLint("WrongConstant")
    private fun enableImmersiveModeModern(window: android.view.Window, strictMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        // Разрешаем окну занимать всю область экрана, включая системные панели
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = window.insetsController
        if (insetsController != null) {
            // Скрываем статус бар и навигацию
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

            if (strictMode) {
                // Строгий режим: панели НЕ появляются при свайпе
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ (API 31+): используем BEHAVIOR_DEFAULT
                    insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
                } else {
                    // Android 11 (API 30): используем BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Обычный режим: панели появляются при свайпе
                insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            // Настраиваем слушатель для строгого режима (без цикла!)
            if (strictMode) {
                setupStrictModeListenerModern(window, insetsController)
            } else {
                removeStrictModeListenerModern(window)
            }
        }

        // Обрабатываем вырез камеры
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /**
     * Настраивает слушатель для строгого режима (Android 11+)
     */
    @SuppressLint("WrongConstant")
    private fun setupStrictModeListenerModern(window: android.view.Window, insetsController: WindowInsetsController) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        // Удаляем старый слушатель, если был
        removeStrictModeListenerModern(window)

        val decorView = window.decorView

        // Используем OnApplyWindowInsetsListener для отслеживания изменений
        decorView.setOnApplyWindowInsetsListener { view, insets ->
            // Проверяем, что строгий режим всё ещё активен
            if (configManager.isStrictModeEnabled() && configManager.isFullscreenEnabled()) {
                // Проверяем, видимы ли системные панели
                val isStatusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                val isNavBarVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())

                // Если панели появились — скрываем их снова
                if (isStatusBarVisible || isNavBarVisible) {
                    insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
            }

            // Возвращаем оригинальный инсет для дальнейшей обработки
            view.onApplyWindowInsets(insets)
        }

        isListenerAttached = true
    }

    /**
     * Удаляет слушатель (Android 11+)
     */
    private fun removeStrictModeListenerModern(window: android.view.Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val decorView = window.decorView
        if (isListenerAttached) {
            decorView.setOnApplyWindowInsetsListener(null)
            isListenerAttached = false
        }
    }

    /**
     * Старый способ для Android 10 и ниже (API 29-)
     * Использует устаревшие API, но они всё ещё работают на этих версиях
     */
    @Suppress("DEPRECATION")
    private fun enableImmersiveModeLegacy(window: android.view.Window, strictMode: Boolean) {
        val decorView = window.decorView

        // Базовые флаги для скрытия панелей
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN

        // Добавляем флаг в зависимости от режима
        flags = if (strictMode) {
            // Строгий режим: IMMERSIVE (не показывать панели даже при свайпе)
            flags or View.SYSTEM_UI_FLAG_IMMERSIVE
        } else {
            // Обычный режим: IMMERSIVE_STICKY (панели появляются при свайпе)
            flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        decorView.systemUiVisibility = flags

        // Настраиваем слушатель для строгого режима (без цикла!)
        if (strictMode) {
            setupStrictModeListenerLegacy(decorView)
        } else {
            removeStrictModeListenerLegacy(decorView)
        }
    }

    /**
     * Настраивает слушатель для строгого режима (Android 10 и ниже)
     */
    @Suppress("DEPRECATION")
    private fun setupStrictModeListenerLegacy(decorView: View) {
        // Удаляем старый слушатель, если был
        removeStrictModeListenerLegacy(decorView)

        // Используем устаревший, но единственный доступный на Android 10- слушатель
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Проверяем, что строгий режим всё ещё активен
            if (!configManager.isStrictModeEnabled() || !configManager.isFullscreenEnabled()) {
                return@setOnSystemUiVisibilityChangeListener
            }

            // Если панели появились (флаг FULLSCREEN снят) — скрываем их снова
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

    /**
     * Удаляет слушатель (Android 10 и ниже)
     */
    @Suppress("DEPRECATION")
    private fun removeStrictModeListenerLegacy(decorView: View) {
        decorView.setOnSystemUiVisibilityChangeListener(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window?.decorView?.post {
                // Защита от вызова до инициализации configManager
                if (!::_configManager.isInitialized) return@post
                val strictMode = configManager.isStrictModeEnabled()
                enableImmersiveMode(strictMode)
            }
        }
    }

    /**
     * ПРИМЕНЯЕТ ЭФФЕКТИВНУЮ ОРИЕНТАЦИЮ
     *
     * Приоритет:
     * 1. Если оболочка задаёт ориентацию (portrait/landscape) - используем её
     * 2. Иначе используем настройку пользователя из ConfigManager
     */
    protected fun applyOrientation(shellManager: ShellManager? = null) {
        // Получаем ориентацию из ConfigManager (настройка пользователя)
        val userOrientation = configManager.getOrientation()

        // Проверяем, задаёт ли оболочка принудительную ориентацию
        val forcedOrientation = shellManager?.getForcedOrientationFromActiveShell()

        // Если оболочка задаёт portrait или landscape - используем её
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

        // ПРИНУДИТЕЛЬНО применяем ориентацию
        requestedOrientation = targetOrientation
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Защита от вызова до инициализации configManager
        if (!::_configManager.isInitialized) return

        window?.decorView?.post {
            val strictMode = configManager.isStrictModeEnabled()
            enableImmersiveMode(strictMode)
        }
    }

    override fun onDestroy() {
        // Удаляем слушатели при уничтожении активности
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.let { removeStrictModeListenerModern(it) }
        } else {
            window?.decorView?.let { removeStrictModeListenerLegacy(it) }
        }

        super.onDestroy()
    }
}