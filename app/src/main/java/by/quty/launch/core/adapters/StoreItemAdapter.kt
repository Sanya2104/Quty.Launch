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
 * Адаптер для списка элементов магазина.
 *
 * Карточка-строка (стиль Google Play):
 * - превью слева (140×100dp);
 * - название + автор/версия + размер/дата по центру;
 * - статусные плашки снизу.
 *
 * Кнопок действий в карточке нет — установка/обновление/применение
 * выполняются в детальном экране (StoreItemDetailFragment).
 *
 * Превью: приоритет — previewUrl (магазин), fallback — previewBase64 (локальный).
 *
 * Отображение версии в карточке:
 * - не установлено → показываем версию с сервера;
 * - установлено → показываем локальную (установленную) версию;
 * - есть обновление → плашка «Доступна v<remote>»;
 * - installed новее → плашка «Новее».
 */
class StoreItemAdapter(
    private var items: List<StoreItemModel>,
    private val onItemClick: (StoreItemModel) -> Unit
) : RecyclerView.Adapter<StoreItemAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /**
     * Заменяет весь список.
     *
     * Использует точечные уведомления:
     * - если состав и порядок (по name) не изменились — notifyItemChanged
     *   только для реально изменившихся записей;
     * - иначе — notifyItemRangeRemoved + notifyItemRangeInserted.
     *
     * notifyDataSetChanged() не используется — lint flag + плохой UX
     * (перерисовка всех ячеек, потеря позиции скролла).
     */
    fun submitItems(newItems: List<StoreItemModel>) {
        val oldItems = items
        items = newItems

        // Быстрый путь: тот же состав и порядок по name —
        // точечно обновляем только изменившиеся элементы.
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

        // Состав/размер другой — сбрасываем и вставляем заново.
        if (oldItems.isNotEmpty()) {
            notifyItemRangeRemoved(0, oldItems.size)
        }
        if (newItems.isNotEmpty()) {
            notifyItemRangeInserted(0, newItems.size)
        }
    }

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

        fun bind(item: StoreItemModel) {
            val context = itemView.context

            // Превью — приоритет URL, fallback Base64
            bindPreview(item)

            // Название
            nameText.text = item.displayName

            // Автор · версия.
            //
            // Логика отображения версии в карточке:
            // - установлено → показываем локальную (установленную) версию;
            // - не установлено → показываем версию с сервера.
            // Версия «с сервера» при наличии обновления / «новее»
            // показывается отдельной плашкой снизу (см. bindStatuses).
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

            // Метаданные: размер · дата, иначе тип
            val metaParts = mutableListOf<String>()
            item.fileSize?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            item.datePublished?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }

            if (metaParts.isEmpty()) {
                val typeText = if (item.isLocalOnly) {
                    context.getString(R.string.store_type_local)
                } else {
                    context.getString(R.string.store_type_catalog)
                }
                metaParts.add(typeText)
            }
            metaText.text = metaParts.joinToString(" · ")

            // Клик по карточке > детали
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // ==== СТАТУСЫ ====
            bindStatuses(item)
        }

        /**
         * Загружает превью: сначала previewUrl (Glide), fallback — previewBase64.
         */
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

        /**
         * Заполняет статусные плашки.
         *
         * - «Активна» — isActive;
         * - «Доступна v<remote>» — updateAvailable (версия с сервера новее);
         * - «Установлено» — isInstalled && !isActive;
         * - «Новее» — installedIsNewer (локальная версия новее серверной).
         */
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