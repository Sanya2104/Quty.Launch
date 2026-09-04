// *** core/fragments/settings/GeneralFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.interfaces.ParametersEventListener
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Фрагмент "Основное" для Настроек
 * Содержит: переключение темы (Light/Dark/System), полноэкранный режим, строгий режим, ориентация
 */
class GeneralFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager

    // UI элементы для темы
    private lateinit var themeLightCard: LinearLayout
    private lateinit var themeDarkCard: LinearLayout
    private lateinit var themeSystemCard: LinearLayout

    private lateinit var orientationGroup: RadioGroup
    private lateinit var fullscreenCheckbox: CheckBox
    private lateinit var strictModeCheckbox: CheckBox
    private var parametersEventListener: ParametersEventListener? = null

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

        parametersEventListener = activity as? ParametersEventListener

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            shellManager = ShellManager(requireContext(), configManager)

            /*
             * Восстанавливаем локальное состояние из Activity,
             * если Fragment был пересоздан.
             */
            needsRestart = settingsActivity.getNeedsRestart()
        }

        // Тема
        themeLightCard = view.findViewById(R.id.theme_light_card)
        themeDarkCard = view.findViewById(R.id.theme_dark_card)
        themeSystemCard = view.findViewById(R.id.theme_system_card)

        // Остальное
        orientationGroup = view.findViewById(R.id.orientation_group)
        fullscreenCheckbox = view.findViewById(R.id.fullscreen_checkbox)
        strictModeCheckbox = view.findViewById(R.id.strict_mode_checkbox)

        setupThemeSelector()
        setupOrientationSelector()
        setupFullscreenSelector()
        setupStrictModeSelector()

        refreshParameters()
    }

    // ============================================================
    // ТЕМА (LIGHT / DARK / SYSTEM) - ПЛИТКИ С РАМКОЙ
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
        // Сбрасываем все рамки
        themeLightCard.setBackgroundResource(R.drawable.bg_theme_card)
        themeDarkCard.setBackgroundResource(R.drawable.bg_theme_card)
        themeSystemCard.setBackgroundResource(R.drawable.bg_theme_card)

        // Подсвечиваем выбранный
        when (mode) {
            "light" -> {
                themeLightCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            }
            "dark" -> {
                themeDarkCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            }
            "system" -> {
                themeSystemCard.setBackgroundResource(R.drawable.bg_theme_card_selected)
            }
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

            // Отмечаем, что требуется перезагрузка
            markRestartRequired()

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

            markRestartRequired()

            parametersEventListener?.onFullscreenChanged(isChecked)
            parametersEventListener?.onSettingChanged()

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

        strictModeCheckbox.setOnCheckedChangeListener {_, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener

            configManager.setStrictModeEnabled(isChecked)

            // Отмечаем, что требуется перезагрузка
            markRestartRequired()

            parametersEventListener?.onFullscreenChanged(fullscreenCheckbox.isChecked)
            parametersEventListener?.onSettingChanged()

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

    /**
     * Отмечает изменение настройки, требующее перезагрузки.
     *
     * Основное состояние передаётся в SettingsActivity,
     * поэтому оно не зависит от жизненного цикла Fragment.
     */
    private fun markRestartRequired() {
        needsRestart = true
        (activity as? SettingsActivity)?.markRestartRequired()
    }

    private fun updateStrictModeState() {
        val fullscreenEnabled = fullscreenCheckbox.isChecked
        strictModeCheckbox.isEnabled = fullscreenEnabled
        strictModeCheckbox.alpha = if (fullscreenEnabled) 1.0f else 0.5f
    }

    fun refreshParameters() {
        isUpdating = true

        val mode = configManager.getThemeMode()
        selectTheme(mode)

        val currentOrientation = configManager.getOrientation()
        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()
        strictModeCheckbox.isChecked = configManager.isStrictModeEnabled()
        updateStrictModeState()

        /*
         * НЕ сбрасываем needsRestart здесь.
         * refreshParameters() только синхронизирует UI
         * с ConfigManager. Изменение состояния перезапуска
         * должно сохраняться до тех пор, пока приложение
         * действительно не будет перезапущено.
         */
        isUpdating = false
    }

    override fun onResume() {
        super.onResume()

        /*
         * Синхронизируем локальный флаг с Activity.
         * Это необходимо, если Fragment был пересоздан.
         */
        (activity as? SettingsActivity)?.let {
            needsRestart = it.getNeedsRestart()
        }
        refreshParameters()
    }
}