// *** BaseActivity.kt *** //
package by.quty.launch

import android.content.pm.ActivityInfo
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
    protected lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Инициализируем менеджер конфигурации при создании активности
        configManager = ConfigManager(this)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            WindowCompat.setDecorFitsSystemWindows(window, false)

            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsCompat.Type.statusBars()
                        or WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            // Обрабатываем вырез камеры
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        } else {
            // Android 4.4-10 — старый способ
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post { enableImmersiveMode() }
        }
    }

    /**
     * Применение сохраненной ориентации экрана
     * Читает настройку ориентации из ConfigManager и применяет её
     * Возможные значения:
     * - "portrait" - портретная ориентация (вертикальная)
     * - "landscape" - ландшафтная ориентация (горизонтальная)
     * - "sensor" - автоматическая ориентация (следует за датчиками)
     */
    protected fun applyOrientation() {
        val orientation = configManager.getOrientation()

        requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
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