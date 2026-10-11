// *** core/fragments/logger/LoggerSettingsFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.managers.LoggerManager
import kotlinx.coroutines.launch

/**
 * Фрагмент настроек логгера.
 *
 * Содержит:
 * - Секция «Запись»:
 *   • Toggle «Включить логирование» — запись в буфер (по умолчанию on)
 *   • Toggle «Автосохранение в файл» — сохранение буфера в файл при выходе
 *     из LoggerActivity (по умолчанию off)
 * - Секция «Действия»:
 *   • «Сохранить текущий лог» — сохранить буфер в файл в StorageDirectory.LOGS
 *   • «Очистить буфер» — очистить in-memory буфер
 */
class LoggerSettingsFragment : Fragment() {

    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var autoSaveSwitch: SwitchCompat
    private lateinit var saveRow: View
    private lateinit var clearRow: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logger_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        enabledSwitch = view.findViewById(R.id.logger_enabled_switch)
        autoSaveSwitch = view.findViewById(R.id.logger_autosave_switch)
        saveRow = view.findViewById(R.id.logger_save_row)
        clearRow = view.findViewById(R.id.logger_clear_row)

        setupEnabledSwitch()
        setupAutoSaveSwitch()
        setupSaveRow()
        setupClearRow()
    }

    // ============================================================
    // SWITCH: ВКЛЮЧИТЬ ЛОГИРОВАНИЕ
    // ============================================================

    private fun setupEnabledSwitch() {
        enabledSwitch.isChecked = LoggerManager.isEnabled()

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            LoggerManager.setEnabled(requireContext(), isChecked)

            val message = if (isChecked) {
                getString(R.string.logger_settings_enabled_on)
            } else {
                getString(R.string.logger_settings_enabled_off)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // SWITCH: АВТОСОХРАНЕНИЕ
    // ============================================================

    private fun setupAutoSaveSwitch() {
        autoSaveSwitch.isChecked = LoggerManager.isAutoSaveEnabled()

        autoSaveSwitch.setOnCheckedChangeListener { _, isChecked ->
            LoggerManager.setAutoSaveEnabled(requireContext(), isChecked)

            val message = if (isChecked) {
                getString(R.string.logger_settings_autosave_on)
            } else {
                getString(R.string.logger_settings_autosave_off)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // ДЕЙСТВИЕ: СОХРАНИТЬ ТЕКУЩИЙ ЛОГ
    // ============================================================

    private fun setupSaveRow() {
        saveRow.setOnClickListener {
            if (LoggerManager.size() == 0) {
                Toast.makeText(
                    requireContext(),
                    R.string.logger_settings_save_empty,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val fileName = LoggerManager.writeToFile(requireContext())
                if (fileName != null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.logger_settings_save_success, fileName),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.logger_settings_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // ДЕЙСТВИЕ: ОЧИСТИТЬ БУФЕР
    // ============================================================

    private fun setupClearRow() {
        clearRow.setOnClickListener {
            if (LoggerManager.size() == 0) {
                Toast.makeText(
                    requireContext(),
                    R.string.logger_settings_clear_empty,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            LoggerManager.clear()
            Toast.makeText(
                requireContext(),
                R.string.logger_settings_clear_success,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}