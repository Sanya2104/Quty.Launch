// *** core/adapters/ColorSchemeAdapter.kt *** //
package by.quty.launch.core.adapters

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.ColorSchemeModel

/**
 * Адаптер для отображения цветовых схем в виде квадратиков
 * Используется в настройках оформления
 *
 * Выбранная схема обводится **цветом самой схемы** с прозрачным gap'ом
 * между ring'ом и цветным квадратом — это гармонирует с квадратиком
 * и работает одинаково в светлой и тёмной теме.
 */
class ColorSchemeAdapter(
    private val onSchemeSelected: (ColorSchemeModel) -> Unit
) : RecyclerView.Adapter<ColorSchemeAdapter.ViewHolder>() {

    private var schemes: List<ColorSchemeModel> = ColorSchemeModel.getAllSchemes()
    private var selectedSchemeId: String = ColorSchemeModel.getDefaultScheme().id

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

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorView: ImageView = itemView.findViewById(R.id.color_preview)
        private val selectedIndicator: View = itemView.findViewById(R.id.selected_indicator)

        fun bind(scheme: ColorSchemeModel, isSelected: Boolean, onClick: () -> Unit) {
            val context = itemView.context

            // Устанавливаем цвет фона (сам цвет схемы)
            val primaryColor = ContextCompat.getColor(context, scheme.primaryRes)
            colorView.setBackgroundColor(primaryColor)

            // Показываем обводку для выбранного
            if (isSelected) {
                // Толщина ring'а и радиус (переименовали, чтобы не путать с cornerRadius свойства)
                val ringWidth = context.resources.getDimensionPixelSize(R.dimen.spacing_xxs)
                val radiusPx = context.resources.getDimension(R.dimen.radius_medium)

                // Внешний ring — цветом схемы
                val outerRing = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setStroke(ringWidth, primaryColor)
                }

                // Внутренний ring — цветом фона (gap между внешним ring'ом и цветным квадратом)
                val innerRing = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx - ringWidth
                    setStroke(ringWidth, getThemeColor(context, R.attr.colorSurface))
                }

                // Накладываем слои: внешний ring + внутренний ring
                val layer = LayerDrawable(arrayOf(outerRing, innerRing))
                layer.setLayerInset(1, ringWidth, ringWidth, ringWidth, ringWidth)

                selectedIndicator.background = layer
                selectedIndicator.visibility = View.VISIBLE
            } else {
                selectedIndicator.background = null
                selectedIndicator.visibility = View.GONE
            }

            // Обработка клика
            itemView.setOnClickListener { onClick() }
        }

        /**
         * Получает цвет из атрибута темы.
         */
        private fun getThemeColor(context: Context, attr: Int): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}