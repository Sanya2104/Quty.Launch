// *** BaseActivity.kt *** //
package by.quty.launch

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import by.quty.launch.core.managers.ConfigManager

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

    /**
     * Публичный геттер для ConfigManager
     * Используется для доступа из фрагментов
     */
    val configManager: ConfigManager
        get() = _configManager

    // Флаг, что слушатель активен
    private var isListenerAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, был ли пройден онбординг
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

        // Инициализируем менеджер конфигурации при создании активности
        _configManager = ConfigManager(this)
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
            insetsController.hide(WindowInsetsCompat.Type.statusBars()
                    or WindowInsetsCompat.Type.navigationBars())

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
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /**
     * Настраивает слушатель для строгого режима (Android 11+)
     * Использует OnApplyWindowInsetsListener вместо устаревшего OnSystemUiVisibilityChangeListener
     */
    @SuppressLint("WrongConstant")
    private fun setupStrictModeListenerModern(
        window: android.view.Window,
        insetsController: WindowInsetsController
    ) {
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
                    insetsController.hide(WindowInsetsCompat.Type.statusBars()
                            or WindowInsetsCompat.Type.navigationBars())
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
                val strictMode = configManager.isStrictModeEnabled()
                enableImmersiveMode(strictMode)
            }
        }
    }

    /**
     * Применение сохраненной ориентации экрана
     * Сначала проверяет, не задаёт ли оболочка принудительную ориентацию
     * Если да - применяет её, иначе использует настройки пользователя
     */
    protected fun applyOrientation() {
        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)

        // Проверяем, есть ли принудительная ориентация от оболочки
        val forcedOrientation = prefs.getString("forced_orientation", null)

        val orientationToApply = when (forcedOrientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> {
                // Если нет принудительной ориентации - используем настройки пользователя
                val userOrientation = configManager.getOrientation()
                when (userOrientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            }
        }

        requestedOrientation = orientationToApply
    }

    /**
     * Получение строкового представления текущей ориентации
     * @return String - "portrait", "landscape" или "sensor"
     */
    protected fun getCurrentOrientationString(): String {
        return when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "portrait"
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
            else -> "sensor"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Удаляем слушатели при уничтожении активности
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.let { removeStrictModeListenerModern(it) }
        } else {
            window?.decorView?.let { removeStrictModeListenerLegacy(it) }
        }
    }
}