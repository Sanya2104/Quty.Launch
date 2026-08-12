// *** core/adapters/LoggerAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.logger.LogEntry
import by.quty.launch.core.logger.LogLevel

/**
 * Адаптер для отображения списка логов в RecyclerView
 * Использует DiffUtil для эффективного обновления
 */
class LoggerAdapter : ListAdapter<LogEntry, LoggerAdapter.LogViewHolder>(LogDiffCallback()) {

    /**
     * ViewHolder для одного лога
     */
    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLevel: TextView = itemView.findViewById(R.id.tv_log_level)
        val tvTime: TextView = itemView.findViewById(R.id.tv_log_time)
        val tvTag: TextView = itemView.findViewById(R.id.tv_log_tag)
        val tvMessage: TextView = itemView.findViewById(R.id.tv_log_message)
        val tvSource: TextView = itemView.findViewById(R.id.tv_log_source)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = getItem(position)
        val context = holder.itemView.context

        // Уровень
        holder.tvLevel.text = entry.level.name
        holder.tvLevel.setTextColor(getLevelColor(context, entry.level))
        holder.tvLevel.setBackgroundColor(getLevelBgColor(context, entry.level))

        // Время
        holder.tvTime.text = entry.getFormattedTime()

        // Тег
        holder.tvTag.text = entry.tag

        // Сообщение
        holder.tvMessage.text = entry.message

        // Источник
        holder.tvSource.text = entry.source
        holder.tvSource.setTextColor(
            if (entry.source == "WebView") {
                ContextCompat.getColor(context, R.color.accent_blue)
            } else {
                ContextCompat.getColor(context, R.color.text_dim)
            }
        )
    }

    /**
     * Возвращает цвет текста для уровня
     */
    private fun getLevelColor(context: android.content.Context, level: LogLevel): Int {
        return when (level) {
            LogLevel.DEBUG -> ContextCompat.getColor(context, R.color.accent_green)
            LogLevel.INFO -> ContextCompat.getColor(context, R.color.accent_blue)
            LogLevel.WARN -> ContextCompat.getColor(context, R.color.status_warning)
            LogLevel.ERROR -> ContextCompat.getColor(context, R.color.text_error)
        }
    }

    /**
     * Возвращает цвет фона для уровня
     */
    private fun getLevelBgColor(context: android.content.Context, level: LogLevel): Int {
        return when (level) {
            LogLevel.DEBUG -> ContextCompat.getColor(context, R.color.log_debug_bg)
            LogLevel.INFO -> ContextCompat.getColor(context, R.color.log_info_bg)
            LogLevel.WARN -> ContextCompat.getColor(context, R.color.log_warn_bg)
            LogLevel.ERROR -> ContextCompat.getColor(context, R.color.log_error_bg)
        }
    }

    /**
     * DiffUtil для эффективного обновления списка
     */
    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            // Используем timestamp + tag как уникальный идентификатор
            return oldItem.timestamp == newItem.timestamp && oldItem.tag == newItem.tag
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}