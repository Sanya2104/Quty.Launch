// *** BaseActivity.kt *** //
package by.quty.launch

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
import by.quty.launch.core.ConfigManager

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
     * Позволяет показать их свайпом от края экрана
     *
     * Если полноэкранный режим отключен в настройках — ничего не делаем,
     * системные панели остаются видимыми.
     */
    protected fun enableImmersiveMode() {
        // Если полноэкранный режим отключен — выходим
        if (!configManager.isFullscreenEnabled()) return

        // Защита от NullPointerException на Android 16
        val currentWindow = window ?: return
        val decorView = currentWindow.decorView

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+
                WindowCompat.setDecorFitsSystemWindows(currentWindow, false)

                currentWindow.insetsController?.let { controller ->
                    controller.hide(WindowInsetsCompat.Type.statusBars()
                            or WindowInsetsCompat.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                // Обрабатываем вырез камеры
                currentWindow.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

            } else {
                // Android 4.4-10 — старый способ
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } catch (e: Exception) {
            // Логируем ошибку, но не падаем
            e.printStackTrace()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window?.decorView?.post { enableImmersiveMode() }
        }
    }

    /**
     * Применение сохраненной ориентации экрана
     * Сначала проверяет, не задаёт ли тема принудительную ориентацию
     * Если да - применяет её, иначе использует настройки пользователя
     */
    protected fun applyOrientation() {
        val prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)

        // Проверяем, есть ли принудительная ориентация от темы
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
}