// *** core/fragments/store/StoreImportFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellImportResult
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.ShellPreviewResult
import kotlinx.coroutines.launch

/**
 * Импорт оболочки: SAF > preview > диалог > importShell.
 */
class StoreImportFragment : Fragment() {

    private lateinit var shellManager: ShellManager
    private lateinit var configManager: ConfigManager

    private lateinit var selectFileRow: View

    private lateinit var stateContainer: LinearLayout
    private lateinit var stateIcon: ImageView
    private lateinit var stateTitle: TextView
    private lateinit var stateSubtitle: TextView
    private lateinit var stateProgressContainer: LinearLayout
    private lateinit var stateActions: LinearLayout
    private lateinit var stateBtnPrimary: Button
    private lateinit var stateBtnSecondary: Button

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        previewAndConfirm(uri)
    }

    /** Создаёт view фрагмента. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_import, container, false)
    }

    /** Находит view, создаёт менеджеры и вешает клик выбора файла. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configManager = (activity as? by.quty.launch.BaseActivity)?.configManager
            ?: ConfigManager(requireContext())
        shellManager = ShellManager(requireContext(), configManager)

        initViews(view)

        selectFileRow.setOnClickListener { launchPicker() }

        hideState()
    }

    /** Находит все view по id. */
    private fun initViews(view: View) {
        selectFileRow = view.findViewById(R.id.import_select_file_row)

        stateContainer = view.findViewById(R.id.import_state_container)
        stateIcon = view.findViewById(R.id.import_state_icon)
        stateTitle = view.findViewById(R.id.import_state_title)
        stateSubtitle = view.findViewById(R.id.import_state_subtitle)
        stateProgressContainer = view.findViewById(R.id.import_state_progress_container)
        stateActions = view.findViewById(R.id.import_state_actions)
        stateBtnPrimary = view.findViewById(R.id.import_state_btn_primary)
        stateBtnSecondary = view.findViewById(R.id.import_state_btn_secondary)
    }

    // ============================================================
    // SAF
    // ============================================================

    /** MIME, потому что .qutyshell не зарегистрирован в системе. */
    private fun launchPicker() {
        try {
            openDocumentLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            showError(
                getString(R.string.store_import_error_open_picker, e.message ?: getString(R.string.unknown))
            )
        }
    }

    // ============================================================
    // ПРЕДПРОСМОТР + ДИАЛОГ
    // ============================================================

    /** Читает выбранный файл и показывает диалог предпросмотра. */
    private fun previewAndConfirm(uri: Uri) {
        showChecking()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = shellManager.previewShell(uri)

            if (!isAdded) return@launch

            when (result) {
                is ShellPreviewResult.Success -> {
                    hideState()
                    showPreviewDialog(uri, result)
                }
                is ShellPreviewResult.Failure.UnsupportedExtension -> {
                    showError(getString(R.string.store_import_error_extension))
                }
                is ShellPreviewResult.Failure.CannotReadFileName -> {
                    showError(getString(R.string.store_import_error_unknown))
                }
                is ShellPreviewResult.Failure.FileTooLarge -> {
                    showError(getString(R.string.store_import_error_too_large))
                }
                is ShellPreviewResult.Failure.InvalidArchive -> {
                    showError(getString(R.string.store_import_error_invalid_archive))
                }
                is ShellPreviewResult.Failure.ManifestMissing -> {
                    showError(getString(R.string.store_import_error_manifest_missing))
                }
                is ShellPreviewResult.Failure.ManifestInvalid -> {
                    showError(getString(R.string.store_import_error_manifest_invalid))
                }
                is ShellPreviewResult.Failure.IoError -> {
                    showError(
                        getString(
                            R.string.store_import_error_io,
                            result.message ?: getString(R.string.unknown)
                        )
                    )
                }
            }
        }
    }

    /** Диалог предпросмотра с описанием и «Отмена»/«Продолжить». */
    private fun showPreviewDialog(uri: Uri, preview: ShellPreviewResult.Success) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shell_import_preview, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val nameView: TextView = dialogView.findViewById(R.id.import_preview_name)
        val warningView: TextView = dialogView.findViewById(R.id.import_preview_warning)
        val versionValue: TextView = dialogView.findViewById(R.id.import_preview_version_value)
        val authorValue: TextView = dialogView.findViewById(R.id.import_preview_author_value)
        val sizeValue: TextView = dialogView.findViewById(R.id.import_preview_size_value)
        val orientationValue: TextView = dialogView.findViewById(R.id.import_preview_orientation_value)
        val orientationContainer: LinearLayout = dialogView.findViewById(R.id.import_preview_orientation_container)

        val cancelButton: Button = dialogView.findViewById(R.id.import_preview_cancel_button)
        val okButton: Button = dialogView.findViewById(R.id.import_preview_ok_button)

        nameView.text = preview.displayName

        if (preview.alreadyExists) {
            warningView.isVisible = true
            warningView.setText(R.string.store_import_preview_already_exists)
        } else {
            warningView.isVisible = false
        }

        versionValue.text = preview.version?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.unknown)
        authorValue.text = preview.author?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.author_default)
        sizeValue.text = formatSize(preview.sizeBytes)

        val orientationText = when (preview.orientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            "sensor" -> getString(R.string.orientation_auto)
            "user" -> getString(R.string.orientation_auto)
            else -> null
        }
        if (orientationText.isNullOrEmpty()) {
            orientationContainer.isVisible = false
        } else {
            orientationContainer.isVisible = true
            orientationValue.text = orientationText
        }

        if (preview.alreadyExists) {
            okButton.setText(R.string.store_import_overwrite_confirm)
        } else {
            okButton.setText(R.string.store_import_continue)
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        okButton.setOnClickListener {
            dialog.dismiss()
            importShell(uri, overwrite = preview.alreadyExists)
        }

        dialog.show()
    }

    // ============================================================
    // ИМПОРТ
    // ============================================================

    /** Импортирует оболочку и показывает результат. */
    private fun importShell(uri: Uri, overwrite: Boolean) {
        showChecking()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = shellManager.importShell(uri, overwrite)

            if (!isAdded) return@launch

            when (result) {
                is ShellImportResult.Success -> {
                    (activity as? StoreActivity)?.refreshItems()
                    (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()
                    showSuccess(result.shell.displayName ?: result.shell.name)
                }
                is ShellImportResult.Failure.AlreadyExists -> {
                    showAlreadyExistsDialog(uri, result.name)
                }
                is ShellImportResult.Failure.UnsupportedExtension -> {
                    showError(getString(R.string.store_import_error_extension))
                }
                is ShellImportResult.Failure.CannotReadFileName -> {
                    showError(getString(R.string.store_import_error_unknown))
                }
                is ShellImportResult.Failure.FileTooLarge -> {
                    showError(getString(R.string.store_import_error_too_large))
                }
                is ShellImportResult.Failure.InvalidArchive -> {
                    showError(getString(R.string.store_import_error_invalid_archive))
                }
                is ShellImportResult.Failure.ManifestMissing -> {
                    showError(getString(R.string.store_import_error_manifest_missing))
                }
                is ShellImportResult.Failure.ManifestInvalid -> {
                    showError(getString(R.string.store_import_error_manifest_invalid))
                }
                is ShellImportResult.Failure.IoError -> {
                    showError(
                        getString(
                            R.string.store_import_error_io,
                            result.message ?: getString(R.string.unknown)
                        )
                    )
                }
            }
        }
    }

    // ============================================================
    // ДИАЛОГ ПЕРЕЗАПИСИ (гонка)
    // ============================================================

    /** Диалог перезаписи существующей оболочки. */
    private fun showAlreadyExistsDialog(uri: Uri, name: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView = dialogView.findViewById<TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.recovery_confirm_cancel_button)

        titleView.setText(R.string.store_import_overwrite_title)
        messageView.text = getString(R.string.store_import_overwrite_message, name)
        okButton.setText(R.string.store_import_overwrite_confirm)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener {
            dialog.dismiss()
            hideState()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            importShell(uri, overwrite = true)
        }

        dialog.show()
    }

    // ============================================================
    // СОСТОЯНИЯ
    // ============================================================

    /** Скрывает блок состояния. */
    private fun hideState() {
        stateContainer.isVisible = false
        stateProgressContainer.isVisible = false
        stateActions.isVisible = false
        stateBtnPrimary.isVisible = false
        stateBtnSecondary.isVisible = false
        stateBtnPrimary.setOnClickListener(null)
        stateBtnSecondary.setOnClickListener(null)
        stateSubtitle.isVisible = false
    }

    /** Показывает состояние «проверка файла». */
    private fun showChecking() {
        stateContainer.isVisible = true
        stateIcon.setImageResource(R.drawable.ic_update_server_checking)
        stateIcon.imageTintList = null
        stateTitle.setTextColor(getThemeColor(R.attr.textPrimaryColor))
        stateTitle.setText(R.string.store_import_checking_title)
        stateSubtitle.isVisible = false
        stateProgressContainer.isVisible = true
        stateActions.isVisible = false
        stateBtnPrimary.isVisible = false
        stateBtnSecondary.isVisible = false
    }

    /** Показывает состояние «импорт успешен». */
    private fun showSuccess(displayName: String) {
        stateContainer.isVisible = true
        stateIcon.setImageResource(R.drawable.ic_update_check)
        stateIcon.imageTintList = null
        stateTitle.setTextColor(getThemeColor(R.attr.textPrimaryColor))
        stateTitle.setText(R.string.store_import_success_title)
        stateSubtitle.isVisible = true
        stateSubtitle.text = displayName
        stateProgressContainer.isVisible = false
        stateActions.isVisible = true
        stateBtnPrimary.isVisible = true
        stateBtnPrimary.setText(R.string.store_import_apply_hint)
        stateBtnPrimary.setOnClickListener {
            (activity as? StoreActivity)?.let { store ->
                val found = store.findItemByName(displayName)
                if (found != null) {
                    store.showItemDetail(found)
                } else {
                    store.refreshItems {
                        val again = store.findItemByName(displayName)
                        if (again != null) store.showItemDetail(again)
                    }
                }
            }
        }
        stateBtnSecondary.isVisible = true
        stateBtnSecondary.setText(R.string.close)
        stateBtnSecondary.setOnClickListener { hideState() }
    }

    /** Показывает состояние «ошибка импорта». */
    private fun showError(message: String) {
        stateContainer.isVisible = true
        stateIcon.setImageResource(R.drawable.ic_update_error)
        stateIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.text_error)
        )
        stateTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_error))
        stateTitle.setText(R.string.store_import_error_title)
        stateSubtitle.isVisible = false
        stateProgressContainer.isVisible = false
        stateActions.isVisible = true
        stateBtnPrimary.isVisible = true
        stateBtnPrimary.setText(R.string.store_import_retry)
        stateBtnPrimary.setOnClickListener { launchPicker() }
        stateBtnSecondary.isVisible = true
        stateBtnSecondary.setText(R.string.close)
        stateBtnSecondary.setOnClickListener { hideState() }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /** Резолвит цвет темы по атрибуту. */
    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /** Форматирует размер в B/KB/MB/GB. */
    private fun formatSize(bytes: Long): String {
        val locale = java.util.Locale.US
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024 -> String.format(locale, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(locale, "%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}