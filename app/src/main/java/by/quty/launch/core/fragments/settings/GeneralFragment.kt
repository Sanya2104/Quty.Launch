// *** core/fragments/settings/GeneralFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "Основное" для Настроек
 * Содержит: переключение темы (Light/Dark/System), ориентация экрана,
 * полноэкранный режим, строгий режим, выбор языка
 */
class GeneralFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // UI элементы для темы
    private lateinit var themeLightCard: LinearLayout
    private lateinit var themeDarkCard: LinearLayout
    private lateinit var themeSystemCard: LinearLayout

    // UI элементы для ориентации
    private lateinit var orientationAutoCard: LinearLayout
    private lateinit var orientationPortraitCard: LinearLayout
    private lateinit var orientationLandscapeCard: LinearLayout
    private lateinit var orientationLockHint: TextView

    private lateinit var fullscreenSwitch: SwitchCompat
    private lateinit var strictModeSwitch: SwitchCompat

    // UI элементы для языка
    private lateinit var languageRussianCard: LinearLayout
    private lateinit var languageEnglishCard: LinearLayout

    // Флаг для предотвращения множественных обновлений
    private var isUpdating = false

    // Флаг, что требуется перезагрузка
    private var needsRestart = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_general, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)
            needsRestart = settingsActivity.getNeedsRestart()
        }

        // Тема
        themeLightCard = view.findViewById(R.id.theme_light_card)
        themeDarkCard = view.findViewById(R.id.theme_dark_card)
        themeSystemCard = view.findViewById(R.id.theme_system_card)

        // Ориентация
        orientationAutoCard = view.findViewById(R.id.orientation_auto_card)
        orientationPortraitCard = view.findViewById(R.id.orientation_portrait_card)
        orientationLandscapeCard = view.findViewById(R.id.orientation_landscape_card)
        orientationLockHint = view.findViewById(R.id.orientation_lock_hint)

        fullscreenSwitch = view.findViewById(R.id.fullscreen_switch)
        strictModeSwitch = view.findViewById(R.id.strict_mode_switch)

        // Язык
        languageRussianCard = view.findViewById(R.id.language_russian_card)
        languageEnglishCard = view.findViewById(R.id.language_english_card)

        setupThemeSelector()
        setupOrientationSelector()
        setupFullscreenSelector()
        setupStrictModeSelector()
        setupLanguageSelector()

        Handler(Looper.getMainLooper()).postDelayed({
            updateOrientationLockState()
        }, 50)

        refreshSettings()
    }

    // ============================================================
    // ТЕМА (LIGHT / DARK / SYSTEM)
    // ============================================================

    private fun setupThemeSelector() {
        val mode = configManager.getThemeMode()
        selectTheme(mode)

        themeLightCard.setOnClickListener {
            if (isUpdating) return@setOnClickListener
            selectTheme("light")
            applyTheme("light")
        }

        themeDarkCard.setOnClickListener {
            if (isUpdating) return@setOnClickListener
            selectTheme("dark")
            applyTheme("dark")
        }

        themeSystemCard.setOnClickListener {
            if (isUpdating) return@setOnClickListener
            selectTheme("system")
            applyTheme("system")
        }
    }

    private fun selectTheme(mode: String) {
        themeLightCard.setBackgroundResource(R.drawable.bg_theme_card)
        themeDarkCard.setBackgroundResource(R.drawable.bg_theme_card)
        themeSystemCard.setBackgroundResource(R.drawable.bg_theme_card)

        when (mode) {
            "light" -> themeLightCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            "dark" -> themeDarkCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            "system" -> themeSystemCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
        }
    }

    private fun applyTheme(mode: String) {
        if (isUpdating) return

        configManager.setThemeMode(mode)
        configManager.applyTheme()

        markRestartRequired()

        val message = when (mode) {
            "light" -> getString(R.string.toast_theme_light_enabled)
            "dark" -> getString(R.string.toast_theme_dark_enabled)
            "system" -> getString(R.string.toast_theme_system_enabled)
            else -> ""
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // ОРИЕНТАЦИЯ
    // ============================================================

    private fun setupOrientationSelector() {
        val orientation = configManager.getOrientation()
        selectOrientation(orientation)

        orientationAutoCard.setOnClickListener {
            if (isUpdating || shellManager.hasForcedOrientation()) return@setOnClickListener
            selectOrientation("sensor")
            configManager.setOrientation("sensor")
            markRestartRequired()

            Toast.makeText(requireContext(), getString(R.string.toast_orientation_auto), Toast.LENGTH_SHORT).show()
        }

        orientationPortraitCard.setOnClickListener {
            if (isUpdating || shellManager.hasForcedOrientation()) return@setOnClickListener
            selectOrientation("portrait")
            configManager.setOrientation("portrait")
            markRestartRequired()

            Toast.makeText(requireContext(), getString(R.string.toast_orientation_portrait), Toast.LENGTH_SHORT).show()
        }

        orientationLandscapeCard.setOnClickListener {
            if (isUpdating || shellManager.hasForcedOrientation()) return@setOnClickListener
            selectOrientation("landscape")
            configManager.setOrientation("landscape")
            markRestartRequired()

            Toast.makeText(requireContext(), getString(R.string.toast_orientation_landscape), Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectOrientation(orientation: String) {
        orientationAutoCard.setBackgroundResource(R.drawable.bg_theme_card)
        orientationPortraitCard.setBackgroundResource(R.drawable.bg_theme_card)
        orientationLandscapeCard.setBackgroundResource(R.drawable.bg_theme_card)

        when (orientation) {
            "sensor" -> orientationAutoCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            "portrait" -> orientationPortraitCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            "landscape" -> orientationLandscapeCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
        }
    }

    private fun updateOrientationLockState() {
        isUpdating = true

        val forcedOrientation = shellManager.getForcedOrientationFromActiveShell()

        if (forcedOrientation == "portrait" || forcedOrientation == "landscape") {
            lockOrientationSelector(forcedOrientation)
        } else {
            unlockOrientationSelector()
        }

        isUpdating = false
    }

    private fun lockOrientationSelector(forcedOrientation: String) {
        val hintText = when (forcedOrientation) {
            "portrait" -> getString(R.string.orientation_lock_hint_portrait)
            "landscape" -> getString(R.string.orientation_lock_hint_landscape)
            else -> getString(R.string.orientation_lock_hint_default)
        }

        orientationLockHint.text = hintText
        orientationLockHint.visibility = View.VISIBLE
        orientationLockHint.setTextColor(getColorFromAttribute(requireContext(), R.attr.statusWarningColor))

        orientationAutoCard.isEnabled = false
        orientationAutoCard.alpha = 0.5f
        orientationPortraitCard.isEnabled = false
        orientationPortraitCard.alpha = 0.5f
        orientationLandscapeCard.isEnabled = false
        orientationLandscapeCard.alpha = 0.5f

        when (forcedOrientation) {
            "portrait" -> selectOrientation("portrait")
            "landscape" -> selectOrientation("landscape")
        }
    }

    private fun unlockOrientationSelector() {
        orientationLockHint.visibility = View.GONE

        orientationAutoCard.isEnabled = true
        orientationAutoCard.alpha = 1.0f
        orientationPortraitCard.isEnabled = true
        orientationPortraitCard.alpha = 1.0f
        orientationLandscapeCard.isEnabled = true
        orientationLandscapeCard.alpha = 1.0f

        val currentOrientation = configManager.getOrientation()
        selectOrientation(currentOrientation)
    }

    // ============================================================
    // ПОЛНОЭКРАННЫЙ РЕЖИМ
    // ============================================================

    private fun setupFullscreenSelector() {
        fullscreenSwitch.isChecked = configManager.isFullscreenEnabled()

        fullscreenSwitch.setOnCheckedChangeListener { _, isChecked ->
            configManager.setFullscreenEnabled(isChecked)

            if (!isChecked && strictModeSwitch.isChecked) {
                strictModeSwitch.isChecked = false
                configManager.setStrictModeEnabled(false)
                updateStrictModeState()
            }

            updateStrictModeState()
            markRestartRequired()

            Toast.makeText(
                requireContext(),
                if (isChecked) getString(R.string.toast_fullscreen_enabled) else getString(R.string.toast_fullscreen_disabled),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // СТРОГИЙ РЕЖИМ
    // ============================================================

    private fun setupStrictModeSelector() {
        strictModeSwitch.isChecked = configManager.isStrictModeEnabled()
        updateStrictModeState()

        strictModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener

            configManager.setStrictModeEnabled(isChecked)
            markRestartRequired()

            Toast.makeText(
                requireContext(),
                if (isChecked) getString(R.string.toast_strict_mode_enabled) else getString(R.string.toast_strict_mode_disabled),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // ЯЗЫК
    // ============================================================

    private fun setupLanguageSelector() {
        val current = configManager.getLanguage()
        selectLanguage(current)

        languageRussianCard.setOnClickListener {
            if (isUpdating) return@setOnClickListener
            if (configManager.getLanguage() == ConfigManager.LANGUAGE_RU) return@setOnClickListener
            selectLanguage(ConfigManager.LANGUAGE_RU)
            applyLanguage(ConfigManager.LANGUAGE_RU)
        }

        languageEnglishCard.setOnClickListener {
            if (isUpdating) return@setOnClickListener
            if (configManager.getLanguage() == ConfigManager.LANGUAGE_EN) return@setOnClickListener
            selectLanguage(ConfigManager.LANGUAGE_EN)
            applyLanguage(ConfigManager.LANGUAGE_EN)
        }
    }

    private fun selectLanguage(language: String) {
        languageRussianCard.setBackgroundResource(R.drawable.bg_theme_card)
        languageEnglishCard.setBackgroundResource(R.drawable.bg_theme_card)

        when (language) {
            ConfigManager.LANGUAGE_RU -> languageRussianCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            ConfigManager.LANGUAGE_EN -> languageEnglishCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
        }
    }

    private fun applyLanguage(language: String) {
        if (isUpdating) return

        configManager.setLanguage(language)

        // Тост показываем на СТАРОМ языке (активность ещё не пересоздана)
        val message = when (language) {
            ConfigManager.LANGUAGE_RU -> getString(R.string.toast_language_ru)
            else -> getString(R.string.toast_language_en)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

        // ВАЖНО: помечаем, что требуется перезапуск.
        // При выходе из настроек SettingsActivity покажет диалог перезапуска.
        // Флаг сохраняется через onSaveInstanceState и переживёт recreate().
        markRestartRequired()

        // Мгновенно пересоздаём SettingsActivity → UI обновится на новый язык
        (activity as? SettingsActivity)?.applyLanguageAndRecreate()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private fun markRestartRequired() {
        needsRestart = true
        (activity as? SettingsActivity)?.markRestartRequired()
    }

    private fun updateStrictModeState() {
        val fullscreenEnabled = fullscreenSwitch.isChecked
        strictModeSwitch.isEnabled = fullscreenEnabled
        strictModeSwitch.alpha = if (fullscreenEnabled) 1.0f else 0.5f
    }

    fun refreshSettings() {
        isUpdating = true

        // Тема
        val mode = configManager.getThemeMode()
        selectTheme(mode)

        // Ориентация
        updateOrientationLockState()

        // Полноэкранный и строгий
        fullscreenSwitch.isChecked = configManager.isFullscreenEnabled()
        strictModeSwitch.isChecked = configManager.isStrictModeEnabled()
        updateStrictModeState()

        // Язык
        selectLanguage(configManager.getLanguage())

        isUpdating = false
    }

    override fun onResume() {
        super.onResume()

        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }

        refreshSettings()
    }

    private fun getColorFromAttribute(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}