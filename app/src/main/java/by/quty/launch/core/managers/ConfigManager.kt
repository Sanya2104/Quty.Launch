// *** core/managers/ConfigManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.ColorSchemeModel
import androidx.core.content.edit

class ConfigManager(context: Context) {

    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    // Значения по умолчанию из CoreConfig
    fun getDefaultShell(): String = CoreConfig.DEFAULT_SHELL
    fun getDefaultOrientation(): String = CoreConfig.DEFAULT_ORIENTATION
    fun getDefaultFullscreen(): Boolean = CoreConfig.DEFAULT_FULLSCREEN
    fun getDefaultStrictMode(): Boolean = CoreConfig.DEFAULT_STRICT_MODE

    // Получение активной оболочки
    fun getActiveShell(): String {
        return prefs.getString("active_shell", getDefaultShell()) ?: getDefaultShell()
    }

    // Сохранение активной оболочки
    fun setActiveShell(shellId: String) {
        prefs.edit { putString("active_shell", shellId) }
    }

    // Получение ориентации
    fun getOrientation(): String {
        return prefs.getString("orientation", getDefaultOrientation()) ?: getDefaultOrientation()
    }

    // Сохранение ориентации
    fun setOrientation(orientation: String) {
        prefs.edit { putString("orientation", orientation) }
    }

    // Получение полноэкранного режима
    fun isFullscreenEnabled(): Boolean {
        return prefs.getBoolean("fullscreen", getDefaultFullscreen())
    }

    // Сохранение полноэкранного режима
    fun setFullscreenEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("fullscreen", enabled) }
    }

    // Получение строгого режима
    fun isStrictModeEnabled(): Boolean {
        // Строгий режим работает только если включен полноэкранный
        if (!isFullscreenEnabled()) return false
        return prefs.getBoolean("strict_mode", getDefaultStrictMode())
    }

    // Сохранение строгого режима
    fun setStrictModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("strict_mode", enabled) }
    }

    // ============================================================
    // ЦВЕТОВАЯ СХЕМА
    // ============================================================

    /**
     * Возвращает ID текущей цветовой схемы
     */
    fun getColorScheme(): String {
        return prefs.getString("color_scheme", getDefaultColorScheme()) ?: getDefaultColorScheme()
    }

    /**
     * Сохраняет ID цветовой схемы
     */
    fun setColorScheme(schemeId: String) {
        prefs.edit { putString("color_scheme", schemeId) }
    }

    /**
     * Возвращает цветовую схему по умолчанию
     */
    fun getDefaultColorScheme(): String {
        return CoreConfig.DEFAULT_COLOR_SCHEME
    }

    /**
     * Возвращает primary цвет текущей схемы в HEX
     */
    fun getSchemePrimaryColor(): String {
        return getColorSchemeObject().primaryColor
    }

    /**
     * Возвращает accent цвет текущей схемы в HEX
     */
    fun getSchemeAccentColor(): String {
        return getColorSchemeObject().accentColor
    }

    /**
     * Возвращает объект текущей цветовой схемы
     */
    fun getColorSchemeObject(): ColorSchemeModel {
        return ColorSchemeModel.getSchemeById(getColorScheme())
    }
}