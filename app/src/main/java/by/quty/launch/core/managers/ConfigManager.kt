// *** core/managers/ConfigManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.ColorSchemeModel
import java.util.Locale

class ConfigManager(context: Context) {

    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    init {
        // При первой инициализации — определяем язык системы и сохраняем
        ensureLanguageInitialized()
    }

    // Значения по умолчанию из CoreConfig
    fun getDefaultShell(): String = CoreConfig.DEFAULT_SHELL
    fun getDefaultOrientation(): String = CoreConfig.DEFAULT_ORIENTATION
    fun getDefaultFullscreen(): Boolean = CoreConfig.DEFAULT_FULLSCREEN
    fun getDefaultStrictMode(): Boolean = CoreConfig.DEFAULT_STRICT_MODE
    fun getDefaultThemeMode(): String = CoreConfig.DEFAULT_THEME_MODE
    fun getDefaultLanguage(): String = CoreConfig.DEFAULT_LANGUAGE

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
        return isFullscreenEnabled() && prefs.getBoolean("strict_mode", getDefaultStrictMode())
    }

    // Сохранение строгого режима
    fun setStrictModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("strict_mode", enabled) }
    }

    // ============================================================
    // ЦВЕТОВАЯ СХЕМА
    // ============================================================

    fun getColorScheme(): String {
        return prefs.getString("color_scheme", getDefaultColorScheme()) ?: getDefaultColorScheme()
    }

    fun setColorScheme(schemeId: String) {
        prefs.edit { putString("color_scheme", schemeId) }
    }

    fun getDefaultColorScheme(): String {
        return CoreConfig.DEFAULT_COLOR_SCHEME
    }

    fun getSchemePrimaryColor(): String {
        return getColorSchemeObject().primaryColor
    }

    fun getSchemeAccentColor(): String {
        return getColorSchemeObject().accentColor
    }

    fun getColorSchemeObject(): ColorSchemeModel {
        return ColorSchemeModel.getSchemeById(getColorScheme())
    }

    // ============================================================
    // ТЕМА (LIGHT / DARK / SYSTEM)
    // ============================================================

    /**
     * Возвращает режим темы: "light", "dark" или "system"
     */
    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, getDefaultThemeMode()) ?: getDefaultThemeMode()
    }

    /**
     * Сохраняет режим темы
     * @param mode "light", "dark" или "system"
     */
    fun setThemeMode(mode: String) {
        prefs.edit { putString(KEY_THEME_MODE, mode) }
    }

    /**
     * Возвращает true если должна быть включена тёмная тема.
     * Используется в GeneralFragment для отображения состояния переключателя.
     */
    @Suppress("unused")
    fun isDarkTheme(): Boolean {
        return when (getThemeMode()) {
            "dark" -> true
            "light" -> false
            "system" -> isSystemInDarkMode()
            else -> false
        }
    }

    /**
     * Проверяет, включена ли тёмная тема в системе
     */
    private fun isSystemInDarkMode(): Boolean {
        val resources = appContext.resources
        val config = resources.configuration
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Применяет тему через AppCompatDelegate
     * Вызывается из BaseActivity
     */
    fun applyTheme() {
        val mode = when (getThemeMode()) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun needRestartForOrientation(): Boolean {
        return prefs.getBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, false)
    }

    fun clearRestartForOrientationFlag() {
        prefs.edit { putBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, false) }
    }

    fun setRestartForOrientationFlag() {
        prefs.edit { putBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, true) }
    }

    // ============================================================
    // ЯЗЫК
    // ============================================================

    /**
     * При первой инициализации — определяет язык системы.
     * Если язык системы русский — ставит "ru", иначе "en".
     * Если ключ уже есть — ничего не делает.
     */
    private fun ensureLanguageInitialized() {
        if (prefs.contains(KEY_LANGUAGE)) return

        val systemLang = Locale.getDefault().language
        val initial = if (systemLang == CoreConfig.LANGUAGE_RU) {
            CoreConfig.LANGUAGE_RU
        } else {
            CoreConfig.LANGUAGE_EN
        }
        prefs.edit { putString(KEY_LANGUAGE, initial) }
    }

    /**
     * Возвращает выбранный язык: "ru" или "en"
     */
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, getDefaultLanguage()) ?: getDefaultLanguage()
    }

    /**
     * Сохраняет выбранный язык
     * @param language "ru" или "en"
     */
    fun setLanguage(language: String) {
        val normalized = when (language) {
            CoreConfig.LANGUAGE_RU -> CoreConfig.LANGUAGE_RU
            else -> CoreConfig.LANGUAGE_EN
        }
        prefs.edit { putString(KEY_LANGUAGE, normalized) }
    }

    /**
     * Возвращает BCP-47 код языка для передачи в WebView
     */
    fun getLanguageCode(): String {
        return getLanguage()
    }

    companion object {
        const val LANGUAGE_RU = CoreConfig.LANGUAGE_RU
        const val LANGUAGE_EN = CoreConfig.LANGUAGE_EN

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_NEED_RESTART_FOR_ORIENTATION = "need_restart_for_orientation"
    }

}