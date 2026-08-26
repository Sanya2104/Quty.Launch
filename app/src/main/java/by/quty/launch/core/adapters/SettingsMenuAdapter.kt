// *** core/adapters/SettingsMenuAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.databinding.ItemSettingsMenuBinding

class SettingsMenuAdapter(
    private val items: List<SettingsMenuModel>,
    private val onItemClick: (SettingsMenuModel) -> Unit
) : RecyclerView.Adapter<SettingsMenuAdapter.ViewHolder>() {

    private var selectedItemId: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingsMenuBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isSelected = item.id == selectedItemId
        holder.bind(item, isSelected, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    /**
     * Устанавливает выбранный пункт
     */
    fun setSelectedItem(itemId: Int) {
        val oldPosition = items.indexOfFirst { it.id == selectedItemId }
        selectedItemId = itemId
        val newPosition = items.indexOfFirst { it.id == itemId }

        if (oldPosition >= 0) {
            notifyItemChanged(oldPosition)
        }
        if (newPosition >= 0 && newPosition != oldPosition) {
            notifyItemChanged(newPosition)
        }
    }

    /**
     * Возвращает список пунктов
     */
    fun getItems(): List<SettingsMenuModel> = items

    class ViewHolder(
        private val binding: ItemSettingsMenuBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: SettingsMenuModel,
            isSelected: Boolean,
            onItemClick: (SettingsMenuModel) -> Unit
        ) {
            val context = binding.root.context

            binding.ivIcon.setImageResource(item.icon)
            binding.tvTitle.text = context.getString(item.title)
            binding.tvDescription.text = context.getString(item.description)

            // Подсветка выбранного пункта
            if (isSelected) {
                binding.root.setBackgroundColor(
                    context.getColor(R.color.background_card)
                )
                binding.root.setBackgroundResource(R.drawable.bg_selected_item)
            } else {
                binding.root.setBackgroundResource(0)
                binding.root.setBackgroundColor(0)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}