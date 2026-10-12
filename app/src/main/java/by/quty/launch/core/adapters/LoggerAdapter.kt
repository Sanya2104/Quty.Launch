// *** core/adapters/LoggerAdapter.kt *** //
package by.quty.launch.core.adapters

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.LoggerEntryModel
import by.quty.launch.core.model.LoggerLevelModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Адаптер для списка логов.
 *
 * Формат строки:
 *   │ 12:34:56.789 CORE
 *   │ Core initialized
 *
 * Особенности:
 * - Слева — вертикальная полоска 3dp во всю высоту item, цвет = уровень.
 * - Справа — колонка: первая строка «время + тег», вторая — сообщение.
 * - ERROR-строки — лёгкий красный фон (10% opacity).
 * - WARN-строки — лёгкий оранжевый фон (10% opacity).
 * - INFO/DEBUG — прозрачный фон.
 * - Если у записи есть details — тап по item открывает диалог с полным
 *   содержимым (обработчик передаётся через onItemClick).
 *
 * Порядок записей: новые (индекс 0) → старые (конец списка).
 * addEntry() добавляет в начало списка.
 */
class LoggerAdapter(
    private val entries: MutableList<LoggerEntryModel>,
    private val onItemClick: ((LoggerEntryModel) -> Unit)? = null
) : RecyclerView.Adapter<LoggerAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_logger_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    /**
     * Добавляет запись в начало списка (новые — сверху).
     */
    fun addEntry(entry: LoggerEntryModel) {
        entries.add(0, entry)
        notifyItemInserted(0)
    }

    /**
     * Полностью заменяет список записей.
     * Используется при применении фильтра.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submitEntries(newEntries: List<LoggerEntryModel>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    /**
     * Полностью очищает список.
     */
    fun clearAll() {
        val sizeBefore = entries.size
        if (sizeBefore == 0) return

        entries.clear()
        notifyItemRangeRemoved(0, sizeBefore)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val levelBar: View = itemView.findViewById(R.id.logger_level_bar)
        private val timeView: TextView = itemView.findViewById(R.id.logger_time)
        private val tagView: TextView = itemView.findViewById(R.id.logger_tag)
        private val messageView: TextView = itemView.findViewById(R.id.logger_message)

        fun bind(entry: LoggerEntryModel) {
            val context = itemView.context

            // Цвет полоски по уровню
            val levelColor = getLevelColor(context, entry.level)
            ViewCompat.setBackgroundTintList(
                levelBar,
                ColorStateList.valueOf(levelColor)
            )

            // Время
            timeView.text = timeFormat.format(Date(entry.timestamp))

            // Тег
            tagView.text = entry.tag.displayName

            // Сообщение
            messageView.text = entry.message

            // Фон строки по уровню
            when (entry.level) {
                LoggerLevelModel.ERROR -> {
                    itemView.setBackgroundResource(R.drawable.bg_logger_row_error)
                }
                LoggerLevelModel.WARN -> {
                    itemView.setBackgroundResource(R.drawable.bg_logger_row_warn)
                }
                else -> {
                    itemView.background = null
                }
            }

            // Тап по item — открыть диалог с полным details, если он есть
            itemView.setOnClickListener {
                if (entry.details != null && onItemClick != null) {
                    onItemClick.invoke(entry)
                }
            }
        }

        private fun getLevelColor(
            context: android.content.Context,
            level: LoggerLevelModel
        ): Int {
            return when (level) {
                LoggerLevelModel.DEBUG ->
                    ContextCompat.getColor(context, R.color.text_dim)
                LoggerLevelModel.INFO ->
                    ContextCompat.getColor(context, R.color.scheme_blue_primary)
                LoggerLevelModel.WARN ->
                    ContextCompat.getColor(context, R.color.status_warning)
                LoggerLevelModel.ERROR ->
                    ContextCompat.getColor(context, R.color.text_error)
            }
        }
    }
}