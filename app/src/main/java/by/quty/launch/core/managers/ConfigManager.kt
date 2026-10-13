// *** core/managers/ConfigManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.ColorSchemeModel
import by.quty.launch.core.model.LoggerTagModel
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

    // Сохранение активной оболочки.
    //
    // ВАЖНО: здесь НЕ логируем — это низкоуровневый сеттер,
    // логирование смены оболочки делает ShellManager (тег SHELL).
    fun setActiveShell(shellId: String) {
        prefs.edit { putString("active_shell", shellId) }
    }

    // Получение ориентации
    fun getOrientation(): String {
        return prefs.getString("orientation", getDefaultOrientation()) ?: getDefaultOrientation()
    }

    // Сохранение ориентации
    fun setOrientation(orientation: String) {
        val previous = getOrientation()
        if (previous == orientation) return

        prefs.edit { putString("orientation", orientation) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.orientation  $previous → $orientation"
        )
    }

    // Получение полноэкранного режима
    fun isFullscreenEnabled(): Boolean {
        return prefs.getBoolean("fullscreen", getDefaultFullscreen())
    }

    // Сохранение полноэкранного режима
    fun setFullscreenEnabled(enabled: Boolean) {
        val previous = isFullscreenEnabled()
        if (previous == enabled) return

        prefs.edit { putBoolean("fullscreen", enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.fullscreen  $previous → $enabled"
        )
    }

    // Получение строгого режима
    fun isStrictModeEnabled(): Boolean {
        return isFullscreenEnabled() && prefs.getBoolean("strict_mode", getDefaultStrictMode())
    }

    // Сохранение строгого режима
    fun setStrictModeEnabled(enabled: Boolean) {
        val previous = prefs.getBoolean("strict_mode", getDefaultStrictMode())
        if (previous == enabled) return

        prefs.edit { putBoolean("strict_mode", enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.strict  $previous → $enabled"
        )
    }

    // ============================================================
    // ЦВЕТОВАЯ СХЕМА
    // ============================================================

    fun getColorScheme(): String {
        return prefs.getString("color_scheme", getDefaultColorScheme()) ?: getDefaultColorScheme()
    }

    fun setColorScheme(schemeId: String) {
        val previous = getColorScheme()
        if (previous == schemeId) return

        prefs.edit { putString("color_scheme", schemeId) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.color  $previous → $schemeId"
        )
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
        val previous = getThemeMode()
        if (previous == mode) return

        prefs.edit { putString(KEY_THEME_MODE, mode) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.theme  $previous → $mode"
        )
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
    // АНИМАЦИИ
    // ============================================================

    /**
     * Пользовательская настройка: хочет ли пользователь анимации.
     * НЕ учитывает системный флаг ANIMATOR_DURATION_SCALE.
     */
    fun isAnimationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_ANIMATIONS_ENABLED, getDefaultAnimationsEnabled())
    }

    /**
     * Сохраняет пользовательскую настройку анимаций.
     */
    fun setAnimationsEnabled(enabled: Boolean) {
        val previous = isAnimationsEnabled()
        if (previous == enabled) return

        prefs.edit { putBoolean(KEY_ANIMATIONS_ENABLED, enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.animations  $previous → $enabled"
        )
    }

    /**
     * Значение по умолчанию для пользовательской настройки анимаций.
     */
    fun getDefaultAnimationsEnabled(): Boolean = CoreConfig.DEFAULT_ANIMATIONS_ENABLED

    /**
     * Системный флаг: включены ли анимации на уровне ОС.
     * Читает Settings.Global.ANIMATOR_DURATION_SCALE.
     *
     * Возвращает true, если не удалось прочитать (fail-safe: не отключаем
     * анимации из-за ошибки доступа к Settings).
     */
    fun isSystemAnimationEnabled(): Boolean {
        return try {
            val scale = Settings.Global.getFloat(
                appContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale > 0f
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Фактическое состояние анимаций: пользовательская настройка
     * И системный флаг.
     *
     * Используется в BaseActivity (overridePendingTransition) и для
     * передачи в WebView через CSS-инжект.
     */
    fun isEffectiveAnimationsEnabled(): Boolean {
        return isAnimationsEnabled() && isSystemAnimationEnabled()
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

        val previous = getLanguage()
        if (previous == normalized) return

        prefs.edit { putString(KEY_LANGUAGE, normalized) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.language  $previous → $normalized"
        )
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
        private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
    }

}