// *** core/fragments/logger/LoggerFilesFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.utilities.PopupMenuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Список сохранённых логов: превью по тапу, меню — поделиться/удалить.
 */
class LoggerFilesFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logger_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view.findViewById(R.id.logger_files_container)
        emptyText = view.findViewById(R.id.logger_files_empty)
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
    }

    // ============================================================
    // СПИСОК ФАЙЛОВ
    // ============================================================

    private fun refreshFiles() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                LoggerManager.listLogFiles(requireContext())
            }
            renderFiles(files)
        }
    }

    private fun renderFiles(files: List<File>) {
        container.removeAllViews()

        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }

        emptyText.visibility = View.GONE

        files.forEach { file ->
            val row = layoutInflater.inflate(
                R.layout.item_logger_file,
                container,
                false
            )

            val nameView = row.findViewById<TextView>(R.id.logger_file_name)
            val metaView = row.findViewById<TextView>(R.id.logger_file_meta)
            val menuButton = row.findViewById<ImageButton>(R.id.logger_file_menu)

            nameView.text = file.name
            metaView.text = formatMeta(file)

            row.setOnClickListener {
                showFileContent(file)
            }

            menuButton.setOnClickListener { anchor ->
                showFileMenu(file, anchor)
            }

            container.addView(row)
        }
    }

    private fun formatMeta(file: File): String {
        val time = timeFormat.format(Date(file.lastModified()))
        val size = formatSize(file.length())
        return "$time · $size"
    }

    private fun formatSize(bytes: Long): String {
        val locale = Locale.US
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024 -> String.format(locale, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(locale, "%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // ============================================================
    // ПРЕВЬЮ ФАЙЛА
    // ============================================================

    private fun showFileContent(file: File) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    file.readText()
                } catch (_: Exception) {
                    null
                }
            }

            if (content.isNullOrEmpty()) {
                return@launch
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_logger_file, null)

            val dialog = AlertDialog.Builder(
                requireContext(),
                R.style.Theme_QutyLaunch_AlertDialog_Transparent
            )
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            val nameView: TextView = dialogView.findViewById(R.id.logger_file_dialog_name)
            nameView.text = file.name

            val contentTextView: TextView = dialogView.findViewById(R.id.logger_file_dialog_content)
            contentTextView.text = content

            dialogView.findViewById<ImageButton>(R.id.logger_file_dialog_close_button).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<Button>(R.id.logger_file_dialog_close_action).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<Button>(R.id.logger_file_dialog_copy_button).setOnClickListener {
                val clipboard = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(file.name, content)
                @Suppress("UsePropertyAccessSyntax")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    R.string.logger_files_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }

            dialog.show()
        }
    }

    // ============================================================
    // МЕНЮ ФАЙЛА
    // ============================================================

    private fun showFileMenu(file: File, anchor: View) {
        PopupMenuHelper.show(
            anchor = anchor,
            items = listOf(
                PopupMenuHelper.Item(
                    text = getString(R.string.logger_files_menu_share)
                ) {
                    shareFile(file)
                },
                PopupMenuHelper.Item(
                    text = getString(R.string.logger_files_menu_delete)
                ) {
                    deleteFile(file)
                }
            )
        )
    }

    private fun shareFile(file: File) {
        try {
            val storageManager = StorageManager(requireContext())
            val uri = storageManager.getUri(file) ?: return

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, null))
        } catch (_: Exception) {
        }
    }

    private fun deleteFile(file: File) {
        lifecycleScope.launch {
            val success = LoggerManager.deleteLogFile(requireContext(), file.name)

            if (success) {
                Toast.makeText(
                    requireContext(),
                    R.string.logger_files_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                refreshFiles()
            }
        }
    }
}