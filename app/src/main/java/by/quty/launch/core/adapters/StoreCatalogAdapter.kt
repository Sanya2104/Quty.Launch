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
 * - статусные плашки снизу (Активна / Есть обновление / Установлено).
 *
 * Кнопок действий в карточке нет — установка/обновление/применение
 * выполняются в детальном экране (StoreDetailFragment) и в Библиотеке.
 *
 * Статусы:
 * - «Активна» (зелёная плашка) — оболочка активна и версия совпадает
 *   с версией в магазине.
 * - «Активна» + «Есть обновление» — оболочка активна, но в магазине
 *   более новая версия.
 * - «Установлено» (серая плашка) — оболочка установлена, но не активна.
 * - ничего — оболочка не установлена.
 *
 * Строка «автор · версия» при наличии обновления превращается в
 * «автор · vX.Y.Z → vX.Y.W», где старая зачёркнута и приглушена.
 *
 * activeShellVersion — это «версия, реально загруженная в WebView»
 * (из ConfigManager.getActiveShellVersion()), а не версия файла
 * на диске. Иначе после установки обновления поверх активной
 * оболочки статус «Активна» показывался бы до нажатия «Применить».
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

            // Автор · версия (или автор · vX → vY, если есть обновление)
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
         * Если у установленной оболочки версия в магазине новее —
         * строка превращается в «автор · vX → vY»,
         * где старая зачёркнута и приглушена.
         */
        private fun bindAuthorVersion(item: ShellStoreModel) {
            val context = itemView.context
            val installedVersion = installedShellVersions[item.name]

            // Нет обновления (или оболочка не установлена) —
            // простая строка «автор · vX.Y.Z»
            if (installedVersion == null ||
                !UpdateHelper.isNewerVersion(item.version, installedVersion)
            ) {
                authorVersionText.text = context.getString(
                    R.string.store_card_author_version,
                    item.author,
                    item.version
                )
                return
            }

            // Есть обновление — «автор · vX → vY (зачёркнутая)»
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
        }

        /**
         * Определяет и отображает статусные плашки.
         *
         * Логика:
         * - не установлена → ничего;
         * - активна + версия совпадает → «Активна»;
         * - активна + в магазине новее → «Активна» + «Есть обновление»;
         * - установлена, но не активна → «Установлено».
         */
        private fun bindStatuses(item: ShellStoreModel) {
            // Скрываем все плашки
            statusActive.visibility = View.GONE
            statusUpdate.visibility = View.GONE
            statusInstalled.visibility = View.GONE

            val installedVersion = installedShellVersions[item.name]

            if (installedVersion == null) {
                // Не установлена — статусов нет
                statusContainer.visibility = View.GONE
                return
            }

            // «Активна» — только если имя совпадает И реально загруженная
            // версия (active_shell_version) равна версии из магазина.
            val isActive = activeShellName == item.name &&
                    activeShellVersion == item.version

            if (isActive) {
                // Активна — показываем «Активна» всегда
                statusActive.visibility = View.VISIBLE

                // Проверяем, есть ли более новая версия в магазине
                // (для этой ветки не должно быть, но на всякий случай)
                val hasUpdate = UpdateHelper.isNewerVersion(item.version, installedVersion)
                if (hasUpdate) {
                    statusUpdate.visibility = View.VISIBLE
                }
            } else if (activeShellName == item.name) {
                // Оболочка назначена активной, но реально загружена
                // старая версия (была обновлена, но не применена) —
                // показываем «Активна» (по имени) и «Есть обновление»
                // (потому что в магазине новее, чем реально загружено).
                statusActive.visibility = View.VISIBLE
                val hasUpdate = activeShellVersion != null &&
                        UpdateHelper.isNewerVersion(item.version, activeShellVersion)
                if (hasUpdate) {
                    statusUpdate.visibility = View.VISIBLE
                }
            } else {
                // Установлена, но не активна — «Установлено»
                statusInstalled.visibility = View.VISIBLE
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