// *** core/adapters/StoreCatalogAdapter.kt *** //
package by.quty.launch.core.adapters

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.core.utilities.UpdateHelper
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Адаптер для списка оболочек в каталоге магазина.
 *
 * Карточка-строка (стиль Google Play):
 * - превью слева (140×100dp);
 * - название + автор/версия + размер/дата по центру;
 * - статусные плашки снизу (Активна / Есть обновление / Установлено / Новее).
 *
 * Кнопок действий в карточке нет — установка/обновление/применение
 * выполняются в детальном экране (StoreDetailFragment) и в Библиотеке.
 *
 * ВАЖНО: в строке «автор · версия» ВСЕГДА выводится версия из
 * репозитория (магазина), а не локально установленная. Локальное
 * расхождение обозначается плашками:
 * - «Новее» — установленная версия НОВЕЕ магазинной;
 * - зачёркивание локальной версии рядом с магазинной — когда в
 *   магазине новее установленной.
 *
 * Статусы:
 * - «Активна» (зелёная плашка) — оболочка активна и версия совпадает
 *   с версией в магазине.
 * - «Активна» + «Есть обновление» — оболочка активна, но в магазине
 *   более новая версия.
 * - «Установлено» (серая плашка) — оболочка установлена, но не активна.
 * - «Новее» (teal плашка) — установленная версия новее магазинной.
 *   Может соседствовать с «Активна» / «Установлено».
 * - ничего — оболочка не установлена.
 *
 * activeShellVersion — это «версия, реально загруженная в WebView»
 * (из ConfigManager.getActiveShellVersion()), а не версия файла
 * на диске.
 */
class StoreCatalogAdapter(
    private var shells: List<ShellStoreModel>,
    private val installedShellVersions: Map<String, String>,
    private val activeShellName: String?,
    private val activeShellVersion: String?,
    private val onItemClick: (ShellStoreModel) -> Unit
) : RecyclerView.Adapter<StoreCatalogAdapter.ViewHolder>() {

    // Форматтеры для даты (из "YYYY-MM-DD" в "DD.MM.YYYY")
    private val inputDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputDateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_catalog_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = shells[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = shells.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val previewImage: ImageView = itemView.findViewById(R.id.shell_preview)
        private val nameText: TextView = itemView.findViewById(R.id.shell_name)
        private val authorVersionText: TextView = itemView.findViewById(R.id.shell_author_version)
        private val metaText: TextView = itemView.findViewById(R.id.shell_meta)

        private val statusContainer: LinearLayout =
            itemView.findViewById(R.id.shell_status_container)
        private val statusActive: TextView = itemView.findViewById(R.id.shell_status_active)
        private val statusUpdate: TextView = itemView.findViewById(R.id.shell_status_update)
        private val statusInstalled: TextView =
            itemView.findViewById(R.id.shell_status_installed)
        private val statusNewer: TextView = itemView.findViewById(R.id.shell_status_newer)

        fun bind(item: ShellStoreModel) {
            val context = itemView.context

            // Превью
            Glide.with(context)
                .load(item.previewUrl)
                .placeholder(R.drawable.ic_image)
                .error(R.drawable.ic_image)
                .centerCrop()
                .into(previewImage)

            // Название
            nameText.text = item.displayName

            // Автор · версия (всегда версия из репозитория, с зачёркиванием
            // локальной, если в магазине новее)
            bindAuthorVersion(item)

            // Размер · дата
            metaText.text = context.getString(
                R.string.store_card_meta,
                item.fileSize.ifBlank { context.getString(R.string.unknown) },
                formatDate(item.datePublished)
            )

            // Клик по карточке → детали
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // ==== СТАТУСЫ ====
            bindStatuses(item)
        }

        /**
         * Заполняет строку «автор · версия».
         *
         * ВСЕГДА выводится версия из репозитория (item.version).
         * Если в магазине новее установленной — добавляем
         * «→ vЛокальная (зачёркнуто)».
         *
         * Если локальная новее магазинной — ничего не зачёркиваем,
         * просто «автор · vМагазин». Локальное расхождение показывается
         * плашкой «Новее» (см. bindStatuses).
         */
        private fun bindAuthorVersion(item: ShellStoreModel) {
            val context = itemView.context
            val installedVersion = installedShellVersions[item.name]

            // Магазин новее установленной — «автор · vМагазин → vУстановлено»
            if (installedVersion != null &&
                UpdateHelper.isNewerVersion(item.version, installedVersion)
            ) {
                val dimColor = getThemeColor(context, R.attr.textDimColor)
                val strikeSpan = StrikethroughSpan()
                val dimSpan = ForegroundColorSpan(dimColor)

                val builder = SpannableStringBuilder()
                builder.append(item.author)
                builder.append(" · v")
                builder.append(item.version)
                builder.append(" → v")

                val oldStart = builder.length
                builder.append(installedVersion)
                val oldEnd = builder.length

                builder.setSpan(strikeSpan, oldStart, oldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(dimSpan, oldStart, oldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                authorVersionText.text = builder
                return
            }

            // Обычная строка «автор · vМагазин» (в т.ч. когда локальная
            // новее магазинной — расхождение показывается плашкой «Новее»)
            authorVersionText.text = context.getString(
                R.string.store_card_author_version,
                item.author,
                item.version
            )
        }

        /**
         * Определяет и отображает статусные плашки.
         *
         * Логика:
         * - не установлена → ничего;
         * - установлена и магазин новее → «Активна» (если активна)
         *   + «Есть обновление», либо «Установлено» (если не активна);
         * - установлена, версия = магазин → «Активна» или «Установлено»;
         * - установленная версия новее магазинной → «Активна» или
         *   «Установлено» + «Новее».
         */
        private fun bindStatuses(item: ShellStoreModel) {
            // Скрываем все плашки
            statusActive.visibility = View.GONE
            statusUpdate.visibility = View.GONE
            statusInstalled.visibility = View.GONE
            statusNewer.visibility = View.GONE

            val installedVersion = installedShellVersions[item.name]

            if (installedVersion == null) {
                // Не установлена — статусов нет
                statusContainer.visibility = View.GONE
                return
            }

            // Активна ли (по имени) и какая версия реально загружена
            val isActiveByName = activeShellName == item.name

            // Установленная версия новее магазинной?
            val installedIsNewer = UpdateHelper.isNewerVersion(installedVersion, item.version)

            // Магазин новее установленной?
            val storeHasNewer = UpdateHelper.isNewerVersion(item.version, installedVersion)

            // Активна и версия совпадает с магазинной (реально загружена)
            val isActiveSameVersion =
                isActiveByName && activeShellVersion == item.version

            // --- Базовая плашка (Активна / Установлено) ---
            when {
                isActiveSameVersion -> {
                    // Активна с текущей версией магазина
                    statusActive.visibility = View.VISIBLE
                }
                isActiveByName -> {
                    // Назначена активной, но загружена другая версия
                    // (например, обновились — но не применили)
                    statusActive.visibility = View.VISIBLE
                }
                else -> {
                    // Не активна
                    statusInstalled.visibility = View.VISIBLE
                }
            }

            // --- Дополнительная плашка ---
            when {
                storeHasNewer && isActiveByName -> {
                    // Активна + есть новее в магазине
                    statusUpdate.visibility = View.VISIBLE
                }
                installedIsNewer -> {
                    // Установленная версия новее магазинной — «Новее»
                    statusNewer.visibility = View.VISIBLE
                }
            }

            statusContainer.visibility = View.VISIBLE
        }

        /**
         * Форматирует дату из "YYYY-MM-DD" (Git) в "DD.MM.YYYY".
         * Если дата пустая или не парсится — возвращает пустую строку.
         */
        private fun formatDate(isoDate: String): String {
            if (isoDate.isBlank()) return ""
            return try {
                val date = inputDateFormat.parse(isoDate) ?: return isoDate
                outputDateFormat.format(date)
            } catch (_: Exception) {
                isoDate
            }
        }

        /**
         * Резолвит цвет из атрибута темы.
         */
        private fun getThemeColor(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}