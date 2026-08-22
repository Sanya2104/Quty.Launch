// *** core/adapters/ColorSchemeAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.ColorSchemeModel

/**
 * Адаптер для отображения цветовых схем в виде квадратиков
 * Используется в настройках оформления
 */
class ColorSchemeAdapter(
    private val onSchemeSelected: (ColorSchemeModel) -> Unit
) : RecyclerView.Adapter<ColorSchemeAdapter.ViewHolder>() {

    private var schemes: List<ColorSchemeModel> = ColorSchemeModel.getAllSchemes()
    private var selectedSchemeId: String = ColorSchemeModel.getDefaultScheme().id

    // Единый Toast для предотвращения накопления
    private var toast: Toast? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_scheme, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val scheme = schemes[position]
        val isSelected = scheme.id == selectedSchemeId

        holder.bind(scheme, isSelected) {
            // Сохраняем старый ID для сравнения
            val oldSelectedId = selectedSchemeId

            // Обновляем выбранный ID
            selectedSchemeId = scheme.id

            // Обновляем только изменившиеся элементы
            if (oldSelectedId != scheme.id) {
                // Находим позицию старого выбранного элемента
                val oldPosition = schemes.indexOfFirst { it.id == oldSelectedId }

                // Обновляем новый и старый элементы
                if (oldPosition >= 0) {
                    notifyItemChanged(oldPosition)
                }
                notifyItemChanged(position)
            }

            // Показываем Toast с названием из ресурсов
            showToast(holder.itemView.context, scheme)

            onSchemeSelected(scheme)
        }
    }

    override fun getItemCount(): Int = schemes.size

    /**
     * Устанавливает выбранную схему
     */
    fun setSelectedScheme(schemeId: String) {
        if (selectedSchemeId != schemeId) {
            val oldPosition = schemes.indexOfFirst { it.id == selectedSchemeId }
            selectedSchemeId = schemeId
            val newPosition = schemes.indexOfFirst { it.id == schemeId }

            // Обновляем оба элемента
            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition)
            }
            if (newPosition >= 0 && newPosition != oldPosition) {
                notifyItemChanged(newPosition)
            }
        }
    }

    /**
     * Показывает Toast с мгновенной заменой предыдущего
     */
    private fun showToast(context: android.content.Context, scheme: ColorSchemeModel) {
        // Отменяем предыдущий Toast, если он был
        toast?.cancel()

        // Получаем название из ресурсов
        val displayName = scheme.getDisplayName(context)

        // Создаём новый Toast
        toast = Toast.makeText(
            context,
            context.getString(R.string.color_scheme_applied, displayName),
            Toast.LENGTH_SHORT
        )

        // Показываем
        toast?.show()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorView: ImageView = itemView.findViewById(R.id.color_preview)
        private val selectedIndicator: View = itemView.findViewById(R.id.selected_indicator)

        fun bind(scheme: ColorSchemeModel, isSelected: Boolean, onClick: () -> Unit) {
            val context = itemView.context

            // Устанавливаем цвет фона
            val primaryColor = ContextCompat.getColor(context, scheme.primaryRes)
            colorView.setBackgroundColor(primaryColor)

            // Показываем обводку для выбранного
            if (isSelected) {
                colorView.setBackgroundResource(R.drawable.bg_color_scheme_selected)
                // Поверх фона накладываем цвет
                colorView.setBackgroundColor(primaryColor)
                selectedIndicator.visibility = View.VISIBLE
            } else {
                colorView.setBackgroundResource(R.drawable.bg_color_scheme_item)
                colorView.setBackgroundColor(primaryColor)
                selectedIndicator.visibility = View.GONE
            }

            // Обработка клика
            itemView.setOnClickListener { onClick() }
        }
    }
}