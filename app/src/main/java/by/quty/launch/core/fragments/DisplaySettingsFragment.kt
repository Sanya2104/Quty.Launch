// *** core/fragments/DisplaySettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.ConfigManager
import by.quty.launch.core.ThemeManager
import by.quty.launch.core.interfaces.SettingsEventListener

class DisplaySettingsFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var themeManager: ThemeManager
    private lateinit var orientationGroup: RadioGroup
    private lateinit var fullscreenCheckbox: CheckBox
    private lateinit var orientationLockHint: TextView // Подсказка о блокировке ориентации
    private var settingsEventListener: SettingsEventListener? = null

    // Флаг для предотвращения множественных обновлений
    private var isUpdating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_display, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем SettingsActivity как listener
        settingsEventListener = activity as? SettingsEventListener

        // Получаем ConfigManager и ThemeManager через активность
        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            themeManager = settingsActivity.themeManager
        }

        orientationGroup = view.findViewById(R.id.orientation_group)
        fullscreenCheckbox = view.findViewById(R.id.fullscreen_checkbox)
        orientationLockHint = view.findViewById(R.id.orientation_lock_hint)

        setupOrientationSelector()
        setupFullscreenSelector()

        // Обновляем состояние UI в соответствии с текущей темой
        Handler(Looper.getMainLooper()).postDelayed({
            updateOrientationLockState()
        }, 50)
    }

    /**
     * Настройка выбора ориентации экрана
     */
    private fun setupOrientationSelector() {
        val currentOrientation = configManager.getOrientation()

        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }

        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            // Если идёт обновление или ориентация заблокирована - игнорируем
            if (isUpdating || themeManager.hasForcedOrientation()) {
                return@setOnCheckedChangeListener
            }

            val orientation = when (checkedId) {
                R.id.orientation_portrait -> "portrait"
                R.id.orientation_landscape -> "landscape"
                else -> "sensor"
            }
            configManager.setOrientation(orientation)

            // Уведомляем Activity об изменении
            settingsEventListener?.onOrientationChanged(orientation)
            settingsEventListener?.onSettingChanged()
        }
    }

    /**
     * Обновление состояния блокировки ориентации в соответствии с текущей темой
     */
    fun updateOrientationLockState() {
        isUpdating = true

        val forcedOrientation = themeManager.getForcedOrientationFromActiveTheme()

        if (forcedOrientation == "portrait" || forcedOrientation == "landscape") {
            // Тема задаёт принудительную ориентацию - блокируем RadioButton
            lockOrientationSelector(forcedOrientation)
        } else {
            // Тема не задаёт ориентацию - разблокируем RadioButton
            unlockOrientationSelector()
        }

        isUpdating = false
    }

    /**
     * Блокировка выбора ориентации и отображение подсказки
     * @param forcedOrientation принудительная ориентация ("portrait" или "landscape")
     */
    private fun lockOrientationSelector(forcedOrientation: String) {
        // Устанавливаем текст подсказки из ресурсов в зависимости от ориентации
        val hintText = when (forcedOrientation) {
            "portrait" -> getString(R.string.orientation_lock_hint_portrait)
            "landscape" -> getString(R.string.orientation_lock_hint_landscape)
            else -> getString(R.string.orientation_lock_hint_default)
        }
        orientationLockHint.text = hintText
        orientationLockHint.visibility = View.VISIBLE
        orientationLockHint.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))

        // Блокируем все RadioButton в группе
        for (i in 0 until orientationGroup.childCount) {
            orientationGroup.getChildAt(i).isEnabled = false
            orientationGroup.getChildAt(i).alpha = 0.5f // Полупрозрачность
        }

        // Отмечаем соответствующий RadioButton как выбранный
        when (forcedOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
        }
    }

    /**
     * Разблокировка выбора ориентации и скрытие подсказки
     */
    private fun unlockOrientationSelector() {
        // Скрываем подсказку
        orientationLockHint.visibility = View.GONE

        // Разблокируем все RadioButton в группе
        for (i in 0 until orientationGroup.childCount) {
            orientationGroup.getChildAt(i).isEnabled = true
            orientationGroup.getChildAt(i).alpha = 1.0f
        }

        // Восстанавливаем выбранное значение из настроек пользователя
        val currentOrientation = configManager.getOrientation()
        when (currentOrientation) {
            "portrait" -> orientationGroup.check(R.id.orientation_portrait)
            "landscape" -> orientationGroup.check(R.id.orientation_landscape)
            else -> orientationGroup.check(R.id.orientation_sensor)
        }
    }

    /**
     * Настройка полноэкранного режима
     */
    private fun setupFullscreenSelector() {
        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()

        fullscreenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            configManager.setFullscreenEnabled(isChecked)

            // Уведомляем Activity об изменении
            settingsEventListener?.onFullscreenChanged(isChecked)
            settingsEventListener?.onSettingChanged()

            Toast.makeText(
                requireContext(),
                getString(R.string.fullscreen_changed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Обновление состояния (вызывается из Activity при необходимости)
     */
    fun refreshSettings() {
        // Обновляем состояние блокировки ориентации
        updateOrientationLockState()

        // Если не заблокировано - обновляем выбранное значение из настроек
        if (!themeManager.hasForcedOrientation()) {
            val currentOrientation = configManager.getOrientation()
            when (currentOrientation) {
                "portrait" -> orientationGroup.check(R.id.orientation_portrait)
                "landscape" -> orientationGroup.check(R.id.orientation_landscape)
                else -> orientationGroup.check(R.id.orientation_sensor)
            }
        }

        fullscreenCheckbox.isChecked = configManager.isFullscreenEnabled()
    }
}