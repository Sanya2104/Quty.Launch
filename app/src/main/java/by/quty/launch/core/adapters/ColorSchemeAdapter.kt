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
 * Квадратики цветовых схем; выбранная обводится цветом схемы.
 */
class ColorSchemeAdapter(
    private val onSchemeSelected: (ColorSchemeModel) -> Unit
) : RecyclerView.Adapter<ColorSchemeAdapter.ViewHolder>() {

    private var schemes: List<ColorSchemeModel> = ColorSchemeModel.getAllSchemes()
    private var selectedSchemeId: String = ColorSchemeModel.getDefaultScheme().id

    /** Создаёт ViewHolder из item_color_scheme.xml. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_scheme, parent, false)
        return ViewHolder(view)
    }

    /** Биндит квадратик схемы с обработчиком выбора. */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val scheme = schemes[position]
        val isSelected = scheme.id == selectedSchemeId

        holder.bind(scheme, isSelected) {
            val oldSelectedId = selectedSchemeId
            selectedSchemeId = scheme.id

            if (oldSelectedId != scheme.id) {
                val oldPosition = schemes.indexOfFirst { it.id == oldSelectedId }
                if (oldPosition >= 0) {
                    notifyItemChanged(oldPosition)
                }
                notifyItemChanged(position)
            }

            onSchemeSelected(scheme)
        }
    }

    /** Возвращает количество элементов (схем). */
    override fun getItemCount(): Int = schemes.size

    /** Устанавливает выбранную схему и обновляет два затронутых элемента. */
    fun setSelectedScheme(schemeId: String) {
        if (selectedSchemeId != schemeId) {
            val oldPosition = schemes.indexOfFirst { it.id == selectedSchemeId }
            selectedSchemeId = schemeId
            val newPosition = schemes.indexOfFirst { it.id == schemeId }

            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition)
            }
            if (newPosition >= 0 && newPosition != oldPosition) {
                notifyItemChanged(newPosition)
            }
        }
    }

    /** ViewHolder одного квадратика схемы. */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorView: ImageView = itemView.findViewById(R.id.color_preview)
        private val selectedIndicator: View = itemView.findViewById(R.id.selected_indicator)

        /** Рисует цвет и рамку выбранной схемы. */
        fun bind(scheme: ColorSchemeModel, isSelected: Boolean, onClick: () -> Unit) {
            val context = itemView.context

            val primaryColor = ContextCompat.getColor(context, scheme.primaryRes)
            colorView.setBackgroundColor(primaryColor)

            if (isSelected) {
                val ringWidth = context.resources.getDimensionPixelSize(R.dimen.spacing_xxs)
                val radiusPx = context.resources.getDimension(R.dimen.radius_medium)

                val outerRing = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setStroke(ringWidth, primaryColor)
                }

                val innerRing = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx - ringWidth
                    setStroke(ringWidth, getThemeColor(context, R.attr.colorSurface))
                }

                val layer = LayerDrawable(arrayOf(outerRing, innerRing))
                layer.setLayerInset(1, ringWidth, ringWidth, ringWidth, ringWidth)

                selectedIndicator.background = layer
                selectedIndicator.visibility = View.VISIBLE
            } else {
                selectedIndicator.background = null
                selectedIndicator.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick() }
        }

        /** Резолвит цвет темы по атрибуту. */
        private fun getThemeColor(context: Context, attr: Int): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}