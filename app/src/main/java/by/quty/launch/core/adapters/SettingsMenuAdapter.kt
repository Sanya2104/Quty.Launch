// *** core/adapters/SettingsMenuAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.databinding.ItemSettingsMenuBinding

class SettingsMenuAdapter(
    private val items: List<SettingsMenuModel>,
    private val onItemClick: (SettingsMenuModel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_SPACER = 1
    }

    private var selectedItemId: Int = -1

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return if (item.id < 0) VIEW_TYPE_SPACER else VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SPACER -> {
                val view = View(parent.context)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    parent.context.resources.getDimensionPixelSize(R.dimen.spacing_l)
                )
                SpacerViewHolder(view)
            }
            else -> {
                val binding = ItemSettingsMenuBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                ItemViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ItemViewHolder -> {
                val isSelected = item.id == selectedItemId
                holder.bind(item, isSelected, onItemClick)
            }
            is SpacerViewHolder -> {
                // Ничего не делаем
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun setSelectedItem(itemId: Int) {
        val oldPosition = items.indexOfFirst { it.id == selectedItemId }
        selectedItemId = itemId
        val newPosition = items.indexOfFirst { it.id == itemId }

        if (oldPosition >= 0 && items[oldPosition].id >= 0) {
            notifyItemChanged(oldPosition)
        }
        if (newPosition >= 0 && newPosition != oldPosition && items[newPosition].id >= 0) {
            notifyItemChanged(newPosition)
        }
    }

    class ItemViewHolder(
        private val binding: ItemSettingsMenuBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: SettingsMenuModel,
            isSelected: Boolean,
            onItemClick: (SettingsMenuModel) -> Unit
        ) {
            val context = binding.root.context

            // Иконка
            binding.ivIcon.setImageResource(item.icon)

            // Цветной фон для иконки (скруглённый через CardView)
            if (item.iconColor != 0) {
                val color = ContextCompat.getColor(context, item.iconColor)
                binding.iconBg.setBackgroundColor(color)
                binding.iconBg.alpha = 0.8f

                if (item.applyTint) {
                    binding.ivIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.white),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                } else {
                    binding.ivIcon.clearColorFilter()
                }
            } else {
                binding.iconBg.setBackgroundColor(ContextCompat.getColor(context, R.color.text_dim))
                binding.iconBg.alpha = 0.8f
                binding.ivIcon.clearColorFilter()
            }

            // Текст
            binding.tvTitle.text = context.getString(item.title)
            binding.tvDescription.text = context.getString(item.description)

            // === ВЫДЕЛЕНИЕ АКТИВНОГО ПУНКТА ===
            if (isSelected) {
                // Используем фон с подсветкой
                binding.root.setBackgroundResource(R.drawable.bg_settings_menu_item_selected)
            } else {
                // Обычный фон - прозрачный или с лёгким наведением
                binding.root.setBackgroundResource(R.drawable.bg_settings_menu_item)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    class SpacerViewHolder(view: View) : RecyclerView.ViewHolder(view)
}