// *** core/adapters/ShellStoreAdapter.kt *** //
package by.quty.launch.core.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.ShellStoreModel
import com.bumptech.glide.Glide

/**
 * Адаптер для списка оболочек в магазине
 * Использует ListAdapter с DiffUtil для эффективного обновления
 */
class ShellStoreAdapter(
    private val onItemClick: (ShellStoreModel) -> Unit,
    private val onInstallClick: (ShellStoreModel) -> Unit
) : ListAdapter<ShellStoreModel, ShellStoreAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_shell, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val previewImage: ImageView = itemView.findViewById(R.id.shell_preview)
        private val nameText: TextView = itemView.findViewById(R.id.shell_name)
        private val authorText: TextView = itemView.findViewById(R.id.shell_author)
        private val sizeText: TextView = itemView.findViewById(R.id.shell_size)
        private val descriptionText: TextView = itemView.findViewById(R.id.shell_description)
        private val installButton: Button = itemView.findViewById(R.id.btn_install)
        private val installedBadge: View = itemView.findViewById(R.id.installed_badge)

        fun bind(item: ShellStoreModel) {
            val context = itemView.context

            // Название
            nameText.text = item.displayName

            // Автор
            authorText.text = item.author

            // Размер
            sizeText.text = item.fileSize

            // Описание (обрезаем до 2 строк)
            descriptionText.text = item.description
            descriptionText.maxLines = 2
            descriptionText.ellipsize = android.text.TextUtils.TruncateAt.END

            // Превью
            try {
                Glide.with(itemView.context)
                    .load(item.previewUrl)
                    .placeholder(R.drawable.ic_settings)
                    .error(R.drawable.ic_settings)
                    .centerCrop()
                    .into(previewImage)
            } catch (_: Exception) {
                previewImage.setImageResource(R.drawable.ic_settings)
            }

            // Статус установки
            if (item.isInstalled) {
                installButton.text = context.getString(R.string.store_installed)
                installButton.isEnabled = false
                installButton.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.status_granted)
                )
                installedBadge.visibility = View.VISIBLE
            } else {
                installButton.text = context.getString(R.string.store_install)
                installButton.isEnabled = true
                installButton.setBackgroundColor(
                    getColorFromAttribute(context, R.attr.buttonPrimaryColor)
                )
                installedBadge.visibility = View.GONE
            }

            // Клик по карточке → детальный вид
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // Клик по кнопке установки
            installButton.setOnClickListener {
                if (!item.isInstalled) {
                    onInstallClick(item)
                }
            }
        }

        /**
         * Получает цвет из атрибута темы
         */
        private fun getColorFromAttribute(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ShellStoreModel>() {
        override fun areItemsTheSame(oldItem: ShellStoreModel, newItem: ShellStoreModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ShellStoreModel, newItem: ShellStoreModel): Boolean {
            return oldItem == newItem
        }
    }
}