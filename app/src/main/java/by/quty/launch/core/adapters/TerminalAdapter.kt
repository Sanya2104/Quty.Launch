// *** core/adapters/TerminalAdapter.kt *** //
package by.quty.launch.core.adapters

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.TerminalLineModel
import by.quty.launch.core.model.TerminalSegmentModel

/**
 * Строка терминала: INPUT «> », ERROR «! », OUTPUT без префикса.
 * OUTPUT/ERROR с segments — рендерятся через Spannable (имя — акцентом, описание — dim).
 */
class TerminalAdapter(
    private val lines: List<TerminalLineModel>
) : RecyclerView.Adapter<TerminalAdapter.ViewHolder>() {

    /** Создаёт ViewHolder строки терминала. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal_line, parent, false)
        return ViewHolder(view)
    }

    /** Биндит строку. */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position])
    }

    /** Возвращает количество строк. */
    override fun getItemCount(): Int = lines.size

    /** ViewHolder одной строки терминала. */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textView: TextView = itemView.findViewById(R.id.terminal_line_text)

        /** Красит и подписывает строку в зависимости от типа. */
        fun bind(line: TerminalLineModel) {
            val context = itemView.context

            when (line.type) {
                TerminalLineModel.LineType.INPUT -> {
                    textView.text = "> ${line.text}"
                    textView.setTextColor(getThemeColor(context, R.attr.buttonPrimaryColor))
                }
                TerminalLineModel.LineType.OUTPUT -> {
                    if (line.segments.isNotEmpty()) {
                        textView.text = buildSpannable(line.text, line.segments)
                        textView.setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
                    } else {
                        textView.text = line.text
                        textView.setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
                    }
                }
                TerminalLineModel.LineType.ERROR -> {
                    if (line.segments.isNotEmpty()) {
                        textView.text = buildSpannable(line.text, line.segments)
                        textView.setTextColor(ContextCompat.getColor(context, R.color.text_error))
                    } else {
                        textView.text = "! ${line.text}"
                        textView.setTextColor(
                            ContextCompat.getColor(context, R.color.text_error)
                        )
                    }
                }
            }
        }

        /** Собирает Spannable по списку сегментов. */
        private fun buildSpannable(
            text: String,
            segments: List<TerminalSegmentModel>
        ): CharSequence {
            val builder = SpannableStringBuilder(text)

            segments.forEach { segment ->
                if (segment.start < 0 || segment.end > text.length || segment.start >= segment.end) {
                    return@forEach
                }

                when (segment.kind) {
                    TerminalSegmentModel.Kind.COMMAND_NAME -> {
                        builder.setSpan(
                            ForegroundColorSpan(
                                getThemeColor(itemView.context, R.attr.buttonPrimaryColor)
                            ),
                            segment.start,
                            segment.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        builder.setSpan(
                            StyleSpan(android.graphics.Typeface.BOLD),
                            segment.start,
                            segment.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        builder.setSpan(
                            TypefaceSpan("monospace"),
                            segment.start,
                            segment.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    TerminalSegmentModel.Kind.DESCRIPTION -> {
                        builder.setSpan(
                            ForegroundColorSpan(
                                getThemeColor(itemView.context, R.attr.textDimColor)
                            ),
                            segment.start,
                            segment.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }

            return builder
        }

        /** Резолвит цвет темы по атрибуту. */
        private fun getThemeColor(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}