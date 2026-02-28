// *** BaseActivity.kt *** //
package by.quty.launch

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
     */
    protected fun setFullScreen() {
        // Отключаем встраивание в системные области
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Настраиваем контроллер для управления системными панелями
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            // Скрываем системные панели (статус бар и навигацию)
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // Устанавливаем поведение - показывать панели при свайпе
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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