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

/**
 * Единая точка доступа к prefs launcher_prefs и логирования изменений.
 */
class ConfigManager(context: Context) {

    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    init {
        ensureLanguageInitialized()
    }

    // ============================================================
    // ЗНАЧЕНИЯ ПО УМОЛЧАНИЮ
    // ============================================================

    /** Имя оболочки по умолчанию. */
    fun getDefaultShell(): String = CoreConfig.DEFAULT_SHELL

    /** Ориентация по умолчанию. */
    fun getDefaultOrientation(): String = CoreConfig.DEFAULT_ORIENTATION

    /** Fullscreen по умолчанию. */
    fun getDefaultFullscreen(): Boolean = CoreConfig.DEFAULT_FULLSCREEN

    /** Strict mode по умолчанию. */
    fun getDefaultStrictMode(): Boolean = CoreConfig.DEFAULT_STRICT_MODE

    /** Тема по умолчанию. */
    fun getDefaultThemeMode(): String = CoreConfig.DEFAULT_THEME_MODE

    /** Язык по умолчанию. */
    fun getDefaultLanguage(): String = CoreConfig.DEFAULT_LANGUAGE

    // ============================================================
    // АКТИВНАЯ ОБОЛОЧКА
    // ============================================================

    /** Возвращает имя активной оболочки. */
    fun getActiveShell(): String {
        return prefs.getString("active_shell", getDefaultShell()) ?: getDefaultShell()
    }

    /** Сохраняет имя активной оболочки (без логирования — это делает ShellManager). */
    fun setActiveShell(shellId: String) {
        prefs.edit { putString("active_shell", shellId) }
    }

    /** Версия оболочки, реально загруженной в WebView. */
    fun getActiveShellVersion(): String {
        return prefs.getString("active_shell_version", "") ?: ""
    }

    /** Сохраняет версию активной оболочки. */
    fun setActiveShellVersion(version: String) {
        prefs.edit { putString("active_shell_version", version) }
    }

    // ============================================================
    // SAFE MODE
    // ============================================================

    /** true, если активен Safe Mode. */
    fun isSafeModeActive(): Boolean {
        return prefs.getBoolean(KEY_SAFE_MODE, false)
    }

    /** Устанавливает флаг Safe Mode с логированием изменения. */
    fun setSafeModeActive(active: Boolean) {
        val previous = isSafeModeActive()
        if (previous == active) return

        prefs.edit { putBoolean(KEY_SAFE_MODE, active) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.safe_mode  $previous > $active"
        )
    }

    // ============================================================
    // ОРИЕНТАЦИЯ
    // ============================================================

    /** Возвращает выбранную ориентацию. */
    fun getOrientation(): String {
        return prefs.getString("orientation", getDefaultOrientation()) ?: getDefaultOrientation()
    }

    /** Сохраняет ориентацию с логированием изменения. */
    fun setOrientation(orientation: String) {
        val previous = getOrientation()
        if (previous == orientation) return

        prefs.edit { putString("orientation", orientation) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.orientation  $previous > $orientation"
        )
    }

    // ============================================================
    // ПОЛНОЭКРАННЫЙ РЕЖИМ
    // ============================================================

    /** true, если fullscreen включён. */
    fun isFullscreenEnabled(): Boolean {
        return prefs.getBoolean("fullscreen", getDefaultFullscreen())
    }

    /** Сохраняет настройку fullscreen. */
    fun setFullscreenEnabled(enabled: Boolean) {
        val previous = isFullscreenEnabled()
        if (previous == enabled) return

        prefs.edit { putBoolean("fullscreen", enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.fullscreen  $previous > $enabled"
        )
    }

    // ============================================================
    // СТРОГИЙ РЕЖИМ
    // ============================================================

    /** strict имеет смысл только при включённом fullscreen. */
    fun isStrictModeEnabled(): Boolean {
        return isFullscreenEnabled() && prefs.getBoolean("strict_mode", getDefaultStrictMode())
    }

    /** Сохраняет настройку strict mode. */
    fun setStrictModeEnabled(enabled: Boolean) {
        val previous = prefs.getBoolean("strict_mode", getDefaultStrictMode())
        if (previous == enabled) return

        prefs.edit { putBoolean("strict_mode", enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.strict  $previous > $enabled"
        )
    }

    // ============================================================
    // ЦВЕТОВАЯ СХЕМА
    // ============================================================

    /** Возвращает id текущей цветовой схемы. */
    fun getColorScheme(): String {
        return prefs.getString("color_scheme", getDefaultColorScheme()) ?: getDefaultColorScheme()
    }

    /** Сохраняет цветовую схему. */
    fun setColorScheme(schemeId: String) {
        val previous = getColorScheme()
        if (previous == schemeId) return

        prefs.edit { putString("color_scheme", schemeId) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.color  $previous > $schemeId"
        )
    }

    /** Возвращает id схемы по умолчанию. */
    fun getDefaultColorScheme(): String = CoreConfig.DEFAULT_COLOR_SCHEME

    /** Возвращает HEX основного цвета текущей схемы. */
    fun getSchemePrimaryColor(): String = getColorSchemeObject().primaryColor

    /** Возвращает HEX акцентного цвета текущей схемы. */
    fun getSchemeAccentColor(): String = getColorSchemeObject().accentColor

    /** Возвращает объект текущей цветовой схемы. */
    fun getColorSchemeObject(): ColorSchemeModel {
        return ColorSchemeModel.getSchemeById(getColorScheme())
    }

    // ============================================================
    // ТЕМА
    // ============================================================

    /** Возвращает сохранённый режим темы. */
    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, getDefaultThemeMode()) ?: getDefaultThemeMode()
    }

    /** Сохраняет режим темы. */
    fun setThemeMode(mode: String) {
        val previous = getThemeMode()
        if (previous == mode) return

        prefs.edit { putString(KEY_THEME_MODE, mode) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.theme  $previous > $mode"
        )
    }

    /** true, если фактическая тема тёмная. */
    @Suppress("unused")
    fun isDarkTheme(): Boolean {
        return when (getThemeMode()) {
            "dark" -> true
            "light" -> false
            "system" -> isSystemInDarkMode()
            else -> false
        }
    }

    /** true, если системная тема тёмная. */
    private fun isSystemInDarkMode(): Boolean {
        val config = appContext.resources.configuration
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Применяет тему через AppCompatDelegate. */
    fun applyTheme() {
        val mode = when (getThemeMode()) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // ============================================================
    // ПЕРЕЗАПУСК ДЛЯ ОРИЕНТАЦИИ
    // ============================================================

    /** true, если приложение должно перезапуститься для применения ориентации. */
    fun needRestartForOrientation(): Boolean {
        return prefs.getBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, false)
    }

    /** Сбрасывает флаг перезапуска для ориентации. */
    fun clearRestartForOrientationFlag() {
        prefs.edit { putBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, false) }
    }

    /** Ставит флаг перезапуска для применения ориентации. */
    fun setRestartForOrientationFlag() {
        prefs.edit { putBoolean(KEY_NEED_RESTART_FOR_ORIENTATION, true) }
    }

    // ============================================================
    // АНИМАЦИИ
    // ============================================================

    /** Пользовательская настройка анимаций (без учёта системного флага). */
    fun isAnimationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_ANIMATIONS_ENABLED, getDefaultAnimationsEnabled())
    }

    /** Сохраняет пользовательскую настройку анимаций. */
    fun setAnimationsEnabled(enabled: Boolean) {
        val previous = isAnimationsEnabled()
        if (previous == enabled) return

        prefs.edit { putBoolean(KEY_ANIMATIONS_ENABLED, enabled) }
        LoggerManager.info(
            LoggerTagModel.CONFIG,
            "CONFIG.animations  $previous > $enabled"
        )
    }

    /** Значение анимаций по умолчанию. */
    fun getDefaultAnimationsEnabled(): Boolean = CoreConfig.DEFAULT_ANIMATIONS_ENABLED

    /** Системный флаг ANIMATOR_DURATION_SCALE > 0. */
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

    /** Фактическое состояние: пользовательская настройка И системный флаг. */
    fun isEffectiveAnimationsEnabled(): Boolean {
        return isAnimationsEnabled() && isSystemAnimationEnabled()
    }

    // ============================================================
    // ЯЗЫК
    // ============================================================

    /** При первом обращении сохраняет язык системы (ru/en). */
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

    /** Возвращает сохранённый язык. */
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, getDefaultLanguage()) ?: getDefaultLanguage()
    }

    /** Сохраняет язык с нормализацией (только ru/en). */
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
            "CONFIG.language  $previous > $normalized"
        )
    }

    /** Возвращает код языка (ru/en). */
    fun getLanguageCode(): String = getLanguage()

    companion object {
        const val LANGUAGE_RU = CoreConfig.LANGUAGE_RU
        const val LANGUAGE_EN = CoreConfig.LANGUAGE_EN

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_NEED_RESTART_FOR_ORIENTATION = "need_restart_for_orientation"
        private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
        private const val KEY_SAFE_MODE = "safe_mode_active"
    }
}