// *** core/adapters/TerminalAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.TerminalLineModel

/**
 * Строка терминала: INPUT «> », ERROR «! », OUTPUT без префикса.
 */
class TerminalAdapter(
    private val lines: List<TerminalLineModel>
) : RecyclerView.Adapter<TerminalAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position])
    }

    override fun getItemCount(): Int = lines.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textView: TextView = itemView.findViewById(R.id.terminal_line_text)

        fun bind(line: TerminalLineModel) {
            val context = itemView.context

            when (line.type) {
                TerminalLineModel.LineType.INPUT -> {
                    textView.text = "> ${line.text}"
                    textView.setTextColor(getThemeColor(context, R.attr.buttonPrimaryColor))
                }
                TerminalLineModel.LineType.OUTPUT -> {
                    textView.text = line.text
                    textView.setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
                }
                TerminalLineModel.LineType.ERROR -> {
                    textView.text = "! ${line.text}"
                    textView.setTextColor(
                        ContextCompat.getColor(context, R.color.text_error)
                    )
                }
            }
        }

        private fun getThemeColor(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}