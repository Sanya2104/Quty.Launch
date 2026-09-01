// *** core/fragments/settings/GeneralFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.interfaces.ParametersEventListener
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "Основное" для Настроек
 * Содержит: переключение темы (Dark/Light), полноэкранный режим, строгий режим, ориентация
 */
class GeneralFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // UI элементы
    private lateinit var themeSwitch: SwitchCompat
    private lateinit var orientationGroup: RadioGroup
    private lateinit var fullscreenCheckbox: CheckBox
    private lateinit var strictModeCheckbox: CheckBox
    private var parametersEventListener: ParametersEventListener? = null

    // Флаг для предотвращения множественных обновлений
    private var isUpdating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_general, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем ParametersEventListener (если активность его реализует)
        parametersEventListener = activity as? ParametersEventListener

        // Получаем менеджеры через активность
        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)
        }

        // Инициализация UI
        themeSwitch = view.findViewById(R.id.theme_switch)
        orientationGroup = view.findViewById(R.id.orientation_group)
        fullscreenCheckbox = view.findViewById(R.id.fullscreen_checkbox)
        strictModeCheckbox = view.findViewById(R.id.strict_mode_checkbox)

        setupThemeSwitch()
        setupOrientationSelector()
        setupFullscreenSelector()
        setupStrictModeSelector()

        // Обновляем состояние UI
        refreshParameters()
    }

    // ============================================================
    // ТЕМА (DARK / LIGHT)
    // ============================================================

    private fun setupThemeSwitch() {
        val isDark = configManager.isDarkTheme()
        themeSwitch.isChecked = isDark

        // Используем строки из ресурсов
        themeSwitch.text = if (isDark) {
            getString(R.string.settings_theme_dark)
        } else {
            getString(R.string.settings_theme_light)
        }

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Сохраняем настройку
            configManager.setDarkTheme(isChecked)

            // Применяем тему через AppCompatDelegate
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }

            // Обновляем текст из ресурсов
            themeSwitch.text = if (isChecked) {
                getString(R.string.settings_theme_dark)
            } else {
                getString(R.string.settings_theme_light)
            }

            // Используем строки из ресурсов для Toast
            Toast.makeText(
                requireContext(),
                if (isChecked) {
                    getString(R.string.toast_theme_dark_enabled)
                } else {
                    getString(R.string.toast_theme_light_enabled)
                },
                Toast.LENGTH_SHORT
            ).show()

            // Перезапускаем активность для применения темы
            activity?.recreate()
        }
    }

    // ============================================================
    // ОРИЕНТАЦИЯ
    // ============================================================

    private fun setupOrientationSelector() {
        val currentOrientation = configManager.getOrientation()

        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdating) return@setOnCheckedChangeListener

            val orientation = when (checkedId) {
                R.id.orientation_portrait -> "portrait"
                R.id.orientation_landscape -> "landscape"
                else -> "sensor"
            }
            configManager.setOrientation(orientation)

            parametersEventListener?.onOrientationChanged(orientation)
            parametersEventListener?.onSettingChanged()
        }
    }

    // ============================================================
    // ПОЛНОЭКРАННЫЙ РЕЖИМ
    // ============================================================

    private fun setupFullscreenSelector() {
        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()

        fullscreenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            configManager.setFullscreenEnabled(isChecked)

            if (!isChecked && strictModeCheckbox.isChecked) {
                strictModeCheckbox.isChecked = false
                configManager.setStrictModeEnabled(false)
                updateStrictModeState()
            }

            updateStrictModeState()

            parametersEventListener?.onFullscreenChanged(isChecked)
            parametersEventListener?.onSettingChanged()

            // Используем строки из ресурсов для Toast
            Toast.makeText(
                requireContext(),
                if (isChecked) {
                    getString(R.string.toast_fullscreen_enabled)
                } else {
                    getString(R.string.toast_fullscreen_disabled)
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // СТРОГИЙ РЕЖИМ
    // ============================================================

    private fun setupStrictModeSelector() {
        strictModeCheckbox.isChecked = configManager.isStrictModeEnabled()
        updateStrictModeState()

        strictModeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            configManager.setStrictModeEnabled(isChecked)

            parametersEventListener?.onFullscreenChanged(fullscreenCheckbox.isChecked)
            parametersEventListener?.onSettingChanged()

            // Используем строки из ресурсов для Toast
            Toast.makeText(
                requireContext(),
                if (isChecked) {
                    getString(R.string.toast_strict_mode_enabled)
                } else {
                    getString(R.string.toast_strict_mode_disabled)
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private fun updateStrictModeState() {
        val fullscreenEnabled = fullscreenCheckbox.isChecked
        strictModeCheckbox.isEnabled = fullscreenEnabled
        strictModeCheckbox.alpha = if (fullscreenEnabled) 1.0f else 0.5f
    }

    fun refreshParameters() {
        isUpdating = true

        // Обновляем тему
        val isDark = configManager.isDarkTheme()
        themeSwitch.isChecked = isDark
        themeSwitch.text = if (isDark) {
            getString(R.string.settings_theme_dark)
        } else {
            getString(R.string.settings_theme_light)
        }

        // Обновляем ориентацию
        val currentOrientation = configManager.getOrientation()
        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        // Обновляем полноэкранный и строгий режимы
        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()
        strictModeCheckbox.isChecked = configManager.isStrictModeEnabled()
        updateStrictModeState()

        isUpdating = false
    }

    override fun onResume() {
        super.onResume()
        refreshParameters()
    }
}