// *** core/adapters/StoreItemAdapter.kt *** //
package by.quty.launch.core.adapters

import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.StoreItemModel
import com.bumptech.glide.Glide

/**
 * Карточка элемента магазина: превью, имя, автор/версия, метаданные, плашки.
 */
class StoreItemAdapter(
    private var items: List<StoreItemModel>,
    private val onItemClick: (StoreItemModel) -> Unit
) : RecyclerView.Adapter<StoreItemAdapter.ViewHolder>() {

    private val inputDateFormat = "yyyy-MM-dd"
    private val outputDateFormat = "dd.MM.yyyy"

    /** Создаёт ViewHolder карточки магазина. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_list, parent, false)
        return ViewHolder(view)
    }

    /** Биндит карточку. */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /** Возвращает количество элементов. */
    override fun getItemCount(): Int = items.size

    /** Точечные уведомления: changed — если состав/порядок тот же, иначе remove+insert. */
    fun submitItems(newItems: List<StoreItemModel>) {
        val oldItems = items
        items = newItems

        if (oldItems.size == newItems.size &&
            oldItems.map { it.name } == newItems.map { it.name }
        ) {
            for (i in newItems.indices) {
                if (oldItems[i] != newItems[i]) {
                    notifyItemChanged(i)
                }
            }
            return
        }

        if (oldItems.isNotEmpty()) {
            notifyItemRangeRemoved(0, oldItems.size)
        }
        if (newItems.isNotEmpty()) {
            notifyItemRangeInserted(0, newItems.size)
        }
    }

    /** ISO YYYY-MM-DD > DD.MM.YYYY. */
    private fun formatDate(isoDate: String): String {
        if (isoDate.isBlank()) return ""

        return try {
            val input = java.text.SimpleDateFormat(inputDateFormat, java.util.Locale.US)
            val output = java.text.SimpleDateFormat(outputDateFormat, java.util.Locale.US)
            val date = input.parse(isoDate) ?: return isoDate
            output.format(date)
        } catch (_: Exception) {
            isoDate
        }
    }

    /** ViewHolder одной карточки каталога. */
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val previewImage: ImageView = itemView.findViewById(R.id.item_preview)
        private val nameText: TextView = itemView.findViewById(R.id.item_name)
        private val authorVersionText: TextView = itemView.findViewById(R.id.item_author_version)
        private val metaText: TextView = itemView.findViewById(R.id.item_meta)

        private val statusContainer: LinearLayout = itemView.findViewById(R.id.item_status_container)
        private val statusActive: TextView = itemView.findViewById(R.id.item_status_active)
        private val statusUpdate: TextView = itemView.findViewById(R.id.item_status_update)
        private val statusInstalled: TextView = itemView.findViewById(R.id.item_status_installed)
        private val statusNewer: TextView = itemView.findViewById(R.id.item_status_newer)

        /** Заполняет карточку: превью, имя, версия, метаданные, статусы. */
        fun bind(item: StoreItemModel) {
            val context = itemView.context

            bindPreview(item)

            nameText.text = item.displayName

            // Версия: installed приоритет, иначе remote/fallback.
            val displayVersion: String? = when {
                item.isInstalled && !item.installedVersion.isNullOrEmpty() -> item.installedVersion
                else -> item.remoteInfo?.version ?: item.version
            }

            val author = item.author ?: context.getString(R.string.author_default)
            val version = displayVersion ?: context.getString(R.string.unknown)
            authorVersionText.text = context.getString(
                R.string.store_card_author_version,
                author,
                version
            )

            // Метаданные: размер · дата, иначе тип.
            val metaParts = mutableListOf<String>()
            item.fileSize?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            item.datePublished?.takeIf { it.isNotBlank() }?.let { metaParts.add(formatDate(it)) }

            if (metaParts.isEmpty()) {
                val typeText = if (item.isLocalOnly) {
                    context.getString(R.string.store_type_local)
                } else {
                    context.getString(R.string.store_type_catalog)
                }
                metaParts.add(typeText)
            }
            metaText.text = metaParts.joinToString(" · ")

            itemView.setOnClickListener {
                onItemClick(item)
            }

            bindStatuses(item)
        }

        /** previewUrl приоритет, fallback previewBase64. */
        private fun bindPreview(item: StoreItemModel) {
            val context = itemView.context

            if (!item.previewUrl.isNullOrEmpty()) {
                Glide.with(context)
                    .load(item.previewUrl)
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .centerCrop()
                    .into(previewImage)
                return
            }

            val base64 = item.previewBase64
            if (base64.isNullOrEmpty()) {
                previewImage.setImageResource(R.drawable.ic_image)
                return
            }

            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                Glide.with(context)
                    .load(bytes)
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .centerCrop()
                    .into(previewImage)
            } catch (_: Exception) {
                previewImage.setImageResource(R.drawable.ic_image)
            }
        }

        /** Плашки: Активна / Доступна v… / Установлено / Новее. */
        private fun bindStatuses(item: StoreItemModel) {
            val context = itemView.context

            statusActive.visibility = View.GONE
            statusUpdate.visibility = View.GONE
            statusInstalled.visibility = View.GONE
            statusNewer.visibility = View.GONE

            var hasAny = false

            if (item.isActive) {
                statusActive.visibility = View.VISIBLE
                hasAny = true
            }

            if (item.updateAvailable) {
                val remote = item.remoteInfo?.version
                if (!remote.isNullOrEmpty()) {
                    statusUpdate.text = context.getString(
                        R.string.store_status_update_with_version,
                        remote
                    )
                }
                statusUpdate.visibility = View.VISIBLE
                hasAny = true
            }

            if (item.isInstalled && !item.isActive) {
                statusInstalled.visibility = View.VISIBLE
                hasAny = true
            }

            if (item.installedIsNewer) {
                statusNewer.visibility = View.VISIBLE
                hasAny = true
            }

            statusContainer.visibility = if (hasAny) View.VISIBLE else View.GONE
        }
    }
}