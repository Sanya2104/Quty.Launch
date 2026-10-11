// *** core/fragments/logger/LoggerFilesFragment.kt *** //
package by.quty.launch.core.fragments.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.adapters.LoggerFileAdapter
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.utilities.PopupMenuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Фрагмент списка сохранённых файлов логов.
 *
 * Показывает содержимое StorageDirectory.LOGS.
 * Действия:
 * - Тап по файлу — предпросмотр содержимого (диалог dialog_logger_file);
 * - Кнопка меню — «Поделиться», «Удалить».
 *
 * Список обновляется в onResume — при возврате во фрагмент
 * он всегда актуален (после сохранения лога в LoggerSettingsFragment).
 */
class LoggerFilesFragment : Fragment() {

    private lateinit var recyclerFiles: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: LoggerFileAdapter

    private val files = mutableListOf<File>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logger_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerFiles = view.findViewById(R.id.logger_files_list)
        emptyText = view.findViewById(R.id.logger_files_empty)

        setupRecycler()
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
    }

    private fun setupRecycler() {
        adapter = LoggerFileAdapter(
            files = files,
            onItemClick = { file -> showFileContent(file) },
            onMenuClick = { file, anchor -> showFileMenu(file, anchor) }
        )

        recyclerFiles.layoutManager = LinearLayoutManager(requireContext())
        recyclerFiles.adapter = adapter
    }

    private fun refreshFiles() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                LoggerManager.listLogFiles(requireContext())
            }

            adapter.submitFiles(list)
            updateEmptyState(list.isEmpty())
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            emptyText.visibility = View.VISIBLE
            recyclerFiles.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerFiles.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // ПРЕДПРОСМОТР СОДЕРЖИМОГО
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

            // Имя файла под заголовком
            val nameView: TextView = dialogView.findViewById(R.id.logger_file_dialog_name)
            nameView.text = file.name

            // Содержимое
            val contentTextView: TextView = dialogView.findViewById(R.id.logger_file_dialog_content)
            contentTextView.text = content

            // Кнопка-крестик
            dialogView.findViewById<ImageButton>(R.id.logger_file_dialog_close_button).setOnClickListener {
                dialog.dismiss()
            }

            // Кнопка «Закрыть»
            dialogView.findViewById<Button>(R.id.logger_file_dialog_close_action).setOnClickListener {
                dialog.dismiss()
            }

            // Кнопка «Копировать»
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
            // Игнорируем
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