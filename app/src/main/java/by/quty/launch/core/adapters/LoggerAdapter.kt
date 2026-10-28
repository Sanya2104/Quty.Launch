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
 * Строка лога: цветная полоска уровня, время+тег, сообщение. Тап — details.
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

    /** Добавляет запись в начало (новые сверху). */
    fun addEntry(entry: LoggerEntryModel) {
        entries.add(0, entry)
        notifyItemInserted(0)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitEntries(newEntries: List<LoggerEntryModel>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

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

            val levelColor = getLevelColor(context, entry.level)
            ViewCompat.setBackgroundTintList(
                levelBar,
                ColorStateList.valueOf(levelColor)
            )

            timeView.text = timeFormat.format(Date(entry.timestamp))
            tagView.text = entry.tag.displayName
            messageView.text = entry.message

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

            itemView.setOnClickListener {
                if (entry.details != null && onItemClick != null) {
                    onItemClick.invoke(entry)
                }
            }
        }

        private fun getLevelColor(context: android.content.Context, level: LoggerLevelModel): Int {
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