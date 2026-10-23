// *** configs/ApiConfig.kt *** //
package by.quty.launch.configs

/**
 * Конфигурация API методов и связанных с ними настроек
 */
object ApiConfig {

    // ============================================================
    // ===== API - GetApps ========================================
    // ============================================================

    /** Пакет для активности логгера */
    const val LOGGER_PACKAGE = "by.quty.launch.logger"

    /** Пакет для активности настроек */
    const val SETTINGS_PACKAGE = "by.quty.launch.settings"

    /** Пакет для активности терминала */
    const val TERMINAL_PACKAGE = "by.quty.launch.terminal"

    // ============================================================
    // ===== API - GetStatusBar ===================================
    // ============================================================

    /** Количество значений для усреднения скорости интернета */
    const val SPEED_HISTORY_SIZE = 3

    /** Количество значений для усреднения температуры CPU */
    const val TEMP_HISTORY_SIZE = 3

    /** Максимальная температура CPU для отображения (°C) */
    const val MAX_CPU_TEMP_CELSIUS = 120

    /** Минимальная температура CPU для отображения (°C) */
    const val MIN_CPU_TEMP_CELSIUS = 0
}