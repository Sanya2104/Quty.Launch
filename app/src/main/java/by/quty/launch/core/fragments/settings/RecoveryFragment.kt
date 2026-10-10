// *** core/fragments/settings/RecoveryFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.RecoveryManager
import by.quty.launch.core.managers.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фрагмент "Восстановление настроек" для Настроек.
 *
 * Содержит:
 * - Секция "Восстановление настроек":
 *   • Сделать backup настроек → экспорт в .qutyconfig + шаринг через FileProvider
 *   - Восстановить настройки → импорт из .qutyconfig
 *
 * - Секция "Полный сброс настроек":
 *   • Полный сброс настроек → очистка prefs + всех папок StorageManager
 *                                + cacheDir + WebView кэша
 */
class RecoveryFragment : Fragment() {

    // Менеджеры
    private lateinit var configManager: ConfigManager
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var storageManager: StorageManager

    // UI — секция восстановления
    private lateinit var backupRow: View
    private lateinit var restoreRow: View

    // UI — секция сброса
    private lateinit var fullResetRow: View

    // Флаг, что идёт операция (блокируем повторные клики)
    private var isProcessing = false

    /**
     * ActivityResult для выбора файла `.qutyconfig` при импорте.
     */
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        handleImportUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_recovery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
        } ?: run {
            configManager = ConfigManager(requireContext())
        }

        recoveryManager = RecoveryManager(requireContext())
        storageManager = StorageManager(requireContext())

        backupRow = view.findViewById(R.id.recovery_backup_row)
        restoreRow = view.findViewById(R.id.recovery_restore_row)
        fullResetRow = view.findViewById(R.id.recovery_full_reset_row)

        setupRecoverySection()
        setupResetSection()
    }

    // ============================================================
    // СЕКЦИЯ 1: ВОССТАНОВЛЕНИЕ НАСТРОЕК
    // ============================================================

    private fun setupRecoverySection() {
        backupRow.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            performBackup()
        }

        restoreRow.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            openFilePicker()
        }
    }

    // ---------- BACKUP ----------

    /**
     * Выполняет экспорт всех prefs в файл `.qutyconfig` и открывает диалог шаринга.
     */
    private fun performBackup() {
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = recoveryManager.export()
            isProcessing = false

            if (!result.success || result.filePath == null) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.recovery_backup_error,
                        result.error ?: getString(R.string.unknown)
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Открываем диалог "Поделиться" через FileProvider
            shareFile(result.filePath)
        }
    }

    /**
     * Открывает диалог шаринга файла через FileProvider.
     */
    private fun shareFile(filePath: String) {
        try {
            val file = java.io.File(filePath)
            val uri = storageManager.getUri(file)

            if (uri == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_backup_error, getString(R.string.unknown)),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.recovery_backup_share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.recovery_backup_share_title)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.recovery_backup_error, e.message ?: getString(R.string.unknown)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- IMPORT ----------

    /**
     * Открывает системный файловый пикер для выбора `.qutyconfig`.
     */
    private fun openFilePicker() {
        try {
            openDocumentLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.recovery_import_error, e.message ?: getString(R.string.unknown)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Обрабатывает выбранный URI: копирует файл во временный, читает, валидирует.
     */
    private fun handleImportUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = readPickedFile(uri)) {
                is ReadPickedFileResult.Error -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.recovery_import_error, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is ReadPickedFileResult.Success -> {
                    validateAndShowImportDialog(result.filePath)
                }
            }
        }
    }

    /**
     * Читает содержимое выбранного файла во временный файл.
     */
    private suspend fun readPickedFile(uri: Uri): ReadPickedFileResult = withContext(Dispatchers.IO) {
        try {
            val tempFile = storageManager.createTempFile(
                prefix = "import",
                extension = "qutyconfig"
            )

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ReadPickedFileResult.Error("Не удалось открыть файл")

            ReadPickedFileResult.Success(tempFile.absolutePath)
        } catch (e: Exception) {
            ReadPickedFileResult.Error(e.message ?: "Неизвестная ошибка")
        }
    }

    /**
     * Читает `.qutyconfig`, проверяет формат и версию, и показывает диалог подтверждения.
     */
    private suspend fun validateAndShowImportDialog(filePath: String) {
        when (val readResult = recoveryManager.readConfig(filePath)) {
            is RecoveryManager.ImportReadResult.Failure -> {
                // Удаляем временный файл
                withContext(Dispatchers.IO) {
                    java.io.File(filePath).delete()
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_import_invalid_format, readResult.error),
                    Toast.LENGTH_LONG
                ).show()
            }

            is RecoveryManager.ImportReadResult.Success -> {
                val versionMismatch = readResult.formatVersion != RecoveryManager.FORMAT_VERSION

                if (versionMismatch) {
                    showVersionMismatchDialog(
                        filePath = filePath,
                        readResult = readResult
                    )
                } else {
                    showImportConfirmDialog(
                        filePath = filePath,
                        readResult = readResult
                    )
                }
            }
        }
    }

    /**
     * Диалог предупреждения о несовпадении версии формата.
     */
    private fun showVersionMismatchDialog(
        filePath: String,
        readResult: RecoveryManager.ImportReadResult.Success
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        // Заполняем контент
        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.recovery_import_version_mismatch_title)

        val message = getString(
            R.string.recovery_import_version_mismatch_message,
            readResult.formatVersion.toString(),
            RecoveryManager.FORMAT_VERSION.toString()
        )
        messageView.text = message

        okButton.setText(R.string.recovery_import_continue)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
            // Удаляем временный файл
            java.io.File(filePath).delete()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            showImportConfirmDialog(filePath, readResult)
        }

        dialog.show()
    }

    /**
     * Диалог подтверждения импорта.
     */
    private fun showImportConfirmDialog(
        filePath: String,
        readResult: RecoveryManager.ImportReadResult.Success
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.recovery_import_confirm_title)
        messageView.setText(R.string.recovery_import_confirm_message)

        okButton.setText(R.string.recovery_import_confirm_button)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
            java.io.File(filePath).delete()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            performImport(filePath, readResult)
        }

        dialog.show()
    }

    /**
     * Выполняет импорт: применяет prefs и удаляет временный файл.
     */
    private fun performImport(
        filePath: String,
        readResult: RecoveryManager.ImportReadResult.Success
    ) {
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val applied = recoveryManager.applyImport(readResult.data)

            // Удаляем временный файл
            withContext(Dispatchers.IO) {
                java.io.File(filePath).delete()
            }

            isProcessing = false

            if (applied) {
                // Помечаем, что требуется перезапуск
                (activity as? SettingsActivity)?.markRestartRequired()

                // Показываем диалог перезапуска
                showRestartDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_import_error, getString(R.string.unknown)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============================================================
    // СЕКЦИЯ 2: ПОЛНЫЙ СБРОС НАСТРОЕК
    // ============================================================

    private fun setupResetSection() {
        fullResetRow.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            showFullResetDialog()
        }
    }

    /**
     * Диалог подтверждения полного сброса.
     */
    private fun showFullResetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.recovery_reset_confirm_title)
        messageView.setText(R.string.recovery_reset_confirm_message)

        okButton.setText(R.string.recovery_reset_confirm_button)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            performFullReset()
        }

        dialog.show()
    }

    /**
     * Выполняет полный сброс и показывает диалог перезапуска.
     */
    private fun performFullReset() {
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val success = recoveryManager.fullReset()
            isProcessing = false

            if (success) {
                // Помечаем, что требуется перезапуск
                (activity as? SettingsActivity)?.markRestartRequired()

                // Показываем диалог перезапуска
                showRestartDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_reset_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============================================================
    // ДИАЛОГ ПЕРЕЗАПУСКА (использует существующий dialog_restart.xml)
    // ============================================================

    /**
     * Показывает диалог перезапуска (тот же, что и при выходе из настроек).
     *
     * Если SettingsActivity уже показывает свой диалог при выходе — этот
     * вызывается сразу, чтобы пользователь мог перезапуститься не выходя.
     */
    private fun showRestartDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_restart, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        dialogView.findViewById<android.widget.Button>(R.id.btn_later).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_restart).setOnClickListener {
            dialog.dismiss()
            (activity as? SettingsActivity)?.let { activity ->
                // Закрываем настройки, чтобы SettingsActivity при закрытии
                // не показал свой диалог повторно
                activity.finishAffinity()

                // Перезапуск
                val intent = Intent(requireContext(), by.quty.launch.MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
        }

        dialog.show()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ ТИПЫ
    // ============================================================

    private sealed class ReadPickedFileResult {
        data class Success(val filePath: String) : ReadPickedFileResult()
        data class Error(val message: String) : ReadPickedFileResult()
    }
}