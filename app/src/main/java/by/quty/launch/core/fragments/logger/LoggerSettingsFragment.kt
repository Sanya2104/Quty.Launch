// *** core/fragments/logger/LoggerSettingsFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
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
 * - Секция «Отображение»:
 *   • Spinner «Лимит длины деталей» — 1000 / 3000 / 5000 символов
 * - Секция «Действия»:
 *   • «Сохранить текущий лог» — сохранить буфер в файл в StorageDirectory.LOGS
 *   • «Удалить все файлы логов» — удалить все файлы из StorageDirectory.LOGS
 */
class LoggerSettingsFragment : Fragment() {

    /**
     * Доступные значения лимита длины details.
     */
    private val detailsLimitOptions = listOf(1000, 3000, 5000)

    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var autoSaveSwitch: SwitchCompat
    private lateinit var detailsLimitSpinner: Spinner
    private lateinit var saveRow: View
    private lateinit var deleteFilesRow: View

    // Защита от срабатывания OnItemSelectedListener при программной установке
    private var isSpinnerInitializing = false

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
        detailsLimitSpinner = view.findViewById(R.id.logger_details_limit_spinner)
        saveRow = view.findViewById(R.id.logger_save_row)
        deleteFilesRow = view.findViewById(R.id.logger_delete_files_row)

        setupEnabledSwitch()
        setupAutoSaveSwitch()
        setupDetailsLimitSpinner()
        setupSaveRow()
        setupDeleteFilesRow()
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
    // SPINNER: ЛИМИТ ДЛИНЫ DETAILS
    // ============================================================

    private fun setupDetailsLimitSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            detailsLimitOptions.map { it.toString() }
        )
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        detailsLimitSpinner.adapter = adapter

        // Устанавливаем текущее значение
        val currentLimit = LoggerManager.getDetailsLimit()
        val currentIndex = detailsLimitOptions.indexOf(currentLimit).takeIf { it >= 0 } ?: 1
        isSpinnerInitializing = true
        detailsLimitSpinner.setSelection(currentIndex, false)
        isSpinnerInitializing = false

        detailsLimitSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isSpinnerInitializing) return

                val newLimit = detailsLimitOptions[position]
                if (newLimit == LoggerManager.getDetailsLimit()) return

                LoggerManager.setDetailsLimit(requireContext(), newLimit)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.logger_settings_details_limit_changed, newLimit),
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Ничего не делаем
            }
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
    // ДЕЙСТВИЕ: УДАЛИТЬ ВСЕ ФАЙЛЫ ЛОГОВ
    // ============================================================

    private fun setupDeleteFilesRow() {
        deleteFilesRow.setOnClickListener {
            val files = LoggerManager.listLogFiles(requireContext())
            if (files.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.logger_settings_delete_files_empty,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val success = LoggerManager.deleteAllLogFiles(requireContext())
                val message = if (success) {
                    R.string.logger_settings_delete_files_success
                } else {
                    R.string.logger_settings_delete_files_error
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}