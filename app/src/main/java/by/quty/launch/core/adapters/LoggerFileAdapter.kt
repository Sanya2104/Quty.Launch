// *** core/adapters/LoggerFileAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Адаптер для списка сохранённых файлов логов.
 *
 * Каждая строка: имя файла + метаданные (время изменения, размер).
 * Кнопка меню справа — для контекстных действий (открыть, поделиться, удалить).
 */
class LoggerFileAdapter(
    private val files: MutableList<File>,
    private val onItemClick: (File) -> Unit,
    private val onMenuClick: (File, View) -> Unit
) : RecyclerView.Adapter<LoggerFileAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_logger_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    /**
     * Полностью обновляет список файлов.
     * Использует notifyDataSetChanged, потому что список меняется
     * фрагментарно (удаление, добавление) — DiffUtil тут был бы оверкиллом.
     */
    @Suppress("NotifyDataSetChanged")
    fun submitFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.logger_file_name)
        private val metaView: TextView = itemView.findViewById(R.id.logger_file_meta)
        private val menuButton: ImageButton = itemView.findViewById(R.id.logger_file_menu)

        fun bind(file: File) {
            nameView.text = file.name
            metaView.text = formatMeta(file)

            itemView.setOnClickListener {
                onItemClick(file)
            }

            menuButton.setOnClickListener { view ->
                onMenuClick(file, view)
            }
        }

        /**
         * Формат: "14:30 · 12.34 KB"
         */
        private fun formatMeta(file: File): String {
            val timeFormatter = SimpleDateFormat("HH:mm", Locale.US)
            val time = timeFormatter.format(Date(file.lastModified()))
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
    }
}