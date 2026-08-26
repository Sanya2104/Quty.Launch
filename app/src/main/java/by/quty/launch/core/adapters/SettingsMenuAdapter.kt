// *** core/adapters/SettingsMenuAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.core.model.SettingsMenuModel
import by.quty.launch.databinding.ItemSettingsMenuBinding

class SettingsMenuAdapter(
    private val items: List<SettingsMenuModel>,
    private val onItemClick: (SettingsMenuModel) -> Unit
) : RecyclerView.Adapter<SettingsMenuAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingsMenuBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemSettingsMenuBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SettingsMenuModel, onItemClick: (SettingsMenuModel) -> Unit) {
            val context = binding.root.context

            binding.ivIcon.setImageResource(item.icon)
            binding.tvTitle.text = context.getString(item.title)
            binding.tvDescription.text = context.getString(item.description)

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}