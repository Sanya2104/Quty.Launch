// *** core/adapters/StoreCatalogAdapter.kt *** //
package by.quty.launch.core.adapters

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import by.quty.launch.R
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.core.utilities.UpdateHelper
import com.bumptech.glide.Glide

/**
 * Адаптер для списка оболочек в каталоге магазина.
 *
 * Отображает карточки с превью, названием, версией и кнопкой действия.
 * Кнопка динамически меняет состояние:
 * - "Установить" (не установлена)
 * - Прогресс (идёт скачивание)
 * - "Применить" (установлена, но не активна)
 * - "Обновить" (установлена, но версия в магазине новее)
 * - "Применено" (активна с той же версией) — с обводкой, не активна
 */
class StoreCatalogAdapter(
    private var shells: List<ShellStoreModel>,
    private val installedShellVersions: Map<String, String>,
    private val activeShellName: String?,
    private val activeShellVersion: String?,
    private val onItemClick: (ShellStoreModel) -> Unit,
    private val onInstallClick: (ShellStoreModel) -> Unit,
    private val onUpdateClick: (ShellStoreModel) -> Unit,
    private val onApplyClick: (ShellStoreModel) -> Unit
) : RecyclerView.Adapter<StoreCatalogAdapter.ViewHolder>() {

    // Карта прогресса скачивания (id оболочки -> процент)
    private val downloadProgress = mutableMapOf<String, Int>()

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

    /**
     * Обновляет прогресс скачивания для конкретной оболочки.
     */
    fun updateProgress(shellId: String, progress: Int) {
        downloadProgress[shellId] = progress
        val position = shells.indexOfFirst { it.id == shellId }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    /**
     * Убирает прогресс скачивания (например, при отмене или завершении).
     */
    fun clearProgress(shellId: String) {
        downloadProgress.remove(shellId)
        val position = shells.indexOfFirst { it.id == shellId }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val previewImage: ImageView = itemView.findViewById(R.id.shell_preview)
        private val nameText: TextView = itemView.findViewById(R.id.shell_name)
        private val versionText: TextView = itemView.findViewById(R.id.shell_version)

        private val actionButton: Button = itemView.findViewById(R.id.btn_action)
        private val progressContainer: LinearLayout = itemView.findViewById(R.id.progress_container)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val progressText: TextView = itemView.findViewById(R.id.progress_text)

        fun bind(item: ShellStoreModel) {
            val context = itemView.context

            // Превью
            Glide.with(context)
                .load(item.previewUrl)
                .placeholder(R.drawable.ic_image)
                .error(R.drawable.ic_image)
                .centerCrop()
                .into(previewImage)

            // Название и версия
            nameText.text = item.displayName
            versionText.text = context.getString(R.string.shell_version_with_label, item.version)

            // Клик по карточке → детали
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // Логика кнопки действия
            val progress = downloadProgress[item.id]

            if (progress != null) {
                // Идёт скачивание — показываем прогресс
                actionButton.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                progressBar.progress = progress
                progressText.text = "$progress%"
                return
            }

            actionButton.visibility = View.VISIBLE
            progressContainer.visibility = View.GONE

            // Определяем состояние оболочки
            val installedVersion = installedShellVersions[item.name]

            if (installedVersion == null) {
                // Не установлена — кнопка "Установить" (primary — с заливкой)
                applyButtonStyle(isPrimary = true)
                actionButton.text = context.getString(R.string.store_install)
                actionButton.isEnabled = true
                actionButton.alpha = 1.0f
                actionButton.setOnClickListener { onInstallClick(item) }
                return
            }

            // Оболочка установлена. Проверяем, новее ли версия в магазине.
            val hasUpdate = UpdateHelper.isNewerVersion(item.version, installedVersion)

            if (hasUpdate) {
                // Установлена, но есть новая версия — кнопка "Обновить" (primary)
                applyButtonStyle(isPrimary = true)
                actionButton.text = context.getString(R.string.store_update)
                actionButton.isEnabled = true
                actionButton.alpha = 1.0f
                actionButton.setOnClickListener { onUpdateClick(item) }
                return
            }

            // Версия в магазине совпадает или старее, чем установленная.
            // Проверяем, активна ли эта оболочка и с той же ли версией.
            val isActiveSameVersion =
                activeShellName == item.name && activeShellVersion == item.version

            if (isActiveSameVersion) {
                // Активна с той же версией — "Применено" (secondary — с обводкой, disabled)
                applyButtonStyle(isPrimary = false)
                actionButton.text = context.getString(R.string.store_applied)
                actionButton.isEnabled = false
                actionButton.alpha = 0.7f
                actionButton.setOnClickListener(null)
            } else {
                // Установлена, но не активна (или активна с другой версией) — "Применить" (primary)
                applyButtonStyle(isPrimary = true)
                actionButton.text = context.getString(R.string.store_apply)
                actionButton.isEnabled = true
                actionButton.alpha = 1.0f
                actionButton.setOnClickListener { onApplyClick(item) }
            }
        }

        /**
         * Применяет стиль кнопки:
         * - isPrimary = true  → bg_glass_button_primary + белый текст
         * - isPrimary = false → bg_glass_button_secondary + текст из ?attr/textPrimaryColor
         */
        private fun applyButtonStyle(isPrimary: Boolean) {
            val context = itemView.context

            if (isPrimary) {
                actionButton.setBackgroundResource(R.drawable.bg_glass_button_primary)
                actionButton.setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                actionButton.setBackgroundResource(R.drawable.bg_glass_button_secondary)
                actionButton.setTextColor(getThemeColor(context, R.attr.textPrimaryColor))
            }
        }

        /**
         * Резолвит цвет из атрибута темы.
         */
        private fun getThemeColor(context: android.content.Context, attr: Int): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}