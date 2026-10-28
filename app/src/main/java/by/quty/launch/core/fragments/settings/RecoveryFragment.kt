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
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.RecoveryManager
import by.quty.launch.core.managers.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Восстановление и сброс: backup/restore .qutyconfig через SAF, полный сброс.
 */
class RecoveryFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var storageManager: StorageManager

    private lateinit var backupRow: View
    private lateinit var restoreRow: View
    private lateinit var fullResetRow: View

    private var isProcessing = false

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        handleImportUri(uri)
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(CoreConfig.RECOVERY_MIME_TYPE)
    ) { uri: Uri? ->
        if (uri == null) {
            isProcessing = false
            return@registerForActivityResult
        }
        writeExportToUri(uri)
    }

    /** Создаёт view фрагмента. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_recovery, container, false)
    }

    /** Находит view, создаёт менеджеры и настраивает секции. */
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
    // BACKUP / RESTORE
    // ============================================================

    /** Настраивает секцию «Восстановление»: backup и restore. */
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

    /** Открывает SAF для сохранения .qutyconfig. */
    private fun performBackup() {
        isProcessing = true

        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val defaultFileName = "qutyconfig_${dateFormat.format(Date())}${CoreConfig.RECOVERY_FILE_EXTENSION}"

        try {
            createDocumentLauncher.launch(defaultFileName)
        } catch (e: Exception) {
            isProcessing = false
            Toast.makeText(
                requireContext(),
                getString(R.string.recovery_backup_error, e.message ?: getString(R.string.unknown)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Пишет JSON в выбранный URI. */
    private fun writeExportToUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val json = recoveryManager.buildExportJson()

            if (json == null) {
                isProcessing = false
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_backup_error, getString(R.string.unknown)),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: return@withContext false
                    true
                } catch (_: Exception) {
                    false
                }
            }

            isProcessing = false

            if (success) {
                Toast.makeText(
                    requireContext(),
                    R.string.recovery_backup_success,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_backup_error, getString(R.string.unknown)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Открывает SAF для выбора .qutyconfig. */
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

    /** Копирует выбранный файл во временный и переходит к валидации. */
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

    /** Копирует выбранный файл во временный. */
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
            } ?: return@withContext ReadPickedFileResult.Error(
                getString(R.string.recovery_error_cant_open_file)
            )

            ReadPickedFileResult.Success(tempFile.absolutePath)
        } catch (e: Exception) {
            ReadPickedFileResult.Error(e.message ?: getString(R.string.unknown))
        }
    }

    /** Читает файл, проверяет формат и показывает диалог импорта. */
    private suspend fun validateAndShowImportDialog(filePath: String) {
        when (val readResult = recoveryManager.readConfig(filePath)) {
            is RecoveryManager.ImportReadResult.Failure -> {
                withContext(Dispatchers.IO) {
                    java.io.File(filePath).delete()
                }

                val errorText = getImportFailureText(readResult)

                Toast.makeText(
                    requireContext(),
                    getString(R.string.recovery_import_invalid_format, errorText),
                    Toast.LENGTH_LONG
                ).show()
            }

            is RecoveryManager.ImportReadResult.Success -> {
                val versionMismatch = readResult.formatVersion != CoreConfig.RECOVERY_FORMAT_VERSION

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

    /** Локализует ошибку чтения. */
    private fun getImportFailureText(failure: RecoveryManager.ImportReadResult.Failure): String {
        return when (failure) {
            is RecoveryManager.ImportReadResult.Failure.FileNotFound ->
                getString(R.string.recovery_error_file_not_found)

            is RecoveryManager.ImportReadResult.Failure.FileEmpty ->
                getString(R.string.recovery_error_file_empty)

            is RecoveryManager.ImportReadResult.Failure.InvalidFormat ->
                getString(R.string.recovery_error_invalid_format, failure.expectedFormat)

            is RecoveryManager.ImportReadResult.Failure.NoData ->
                getString(R.string.recovery_error_no_data)

            is RecoveryManager.ImportReadResult.Failure.ParseError ->
                failure.message ?: getString(R.string.recovery_error_parse)
        }
    }

    /** Показывает диалог о несовпадении версии формата. */
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

        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.recovery_import_version_mismatch_title)

        val message = getString(
            R.string.recovery_import_version_mismatch_message,
            readResult.formatVersion.toString(),
            CoreConfig.RECOVERY_FORMAT_VERSION.toString()
        )
        messageView.text = message

        okButton.setText(R.string.recovery_import_continue)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
            java.io.File(filePath).delete()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            showImportConfirmDialog(filePath, readResult)
        }

        dialog.show()
    }

    /** Показывает диалог подтверждения импорта. */
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

    /** Применяет импорт и показывает диалог перезапуска. */
    private fun performImport(
        filePath: String,
        readResult: RecoveryManager.ImportReadResult.Success
    ) {
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val applied = recoveryManager.applyImport(readResult.data)

            withContext(Dispatchers.IO) {
                java.io.File(filePath).delete()
            }

            isProcessing = false

            if (applied) {
                (activity as? SettingsActivity)?.markRestartRequired()
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
    // ПОЛНЫЙ СБРОС
    // ============================================================

    /** Настраивает секцию «Полный сброс». */
    private fun setupResetSection() {
        fullResetRow.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            showFullResetDialog()
        }
    }

    /** Показывает диалог подтверждения полного сброса. */
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

    /** Выполняет полный сброс и показывает диалог перезапуска. */
    private fun performFullReset() {
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val success = recoveryManager.fullReset()
            isProcessing = false

            if (success) {
                (activity as? SettingsActivity)?.markRestartRequired()
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
    // ДИАЛОГ ПЕРЕЗАПУСКА
    // ============================================================

    /** Показывает диалог перезапуска после импорта/сброса. */
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

            val activity = activity as? SettingsActivity ?: return@setOnClickListener

            val intent = Intent(requireContext(), by.quty.launch.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

            activity.startActivityAnimated(intent)

            activity.finishAffinity()
        }

        dialog.show()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ ТИПЫ
    // ============================================================

    /** Результат копирования выбранного файла. */
    private sealed class ReadPickedFileResult {
        /** Успешное копирование, путь к temp-файлу. */
        data class Success(val filePath: String) : ReadPickedFileResult()

        /** Ошибка копирования. */
        data class Error(val message: String) : ReadPickedFileResult()
    }
}