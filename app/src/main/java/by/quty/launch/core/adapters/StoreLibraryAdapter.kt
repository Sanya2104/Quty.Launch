// *** core/adapters/StoreLibraryAdapter.kt *** //
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
import by.quty.launch.core.managers.Shell
import by.quty.launch.core.managers.StorageManager
import com.bumptech.glide.Glide
import java.io.File

/**
 * Адаптер для списка установленных оболочек в Библиотеке магазина.
 *
 * Карточка-строка (стиль Google Play, как в каталоге):
 * - превью слева (140×100dp, из previewBase64);
 * - название + автор/версия + тип/размер по центру;
 * - статусные плашки снизу (Активна / Есть обновление).
 *
 * ВАЖНО: плашки «Установлено» нет — в Библиотеке все оболочки по
 * определению установлены. Показываем только «Активна» и «Есть
 * обновление».
 *
 * Отличия от StoreCatalogAdapter:
 * - данные из Shell (локальная оболочка), а не ShellStoreModel;
 * - превью из Base64 (у локальных нет previewUrl);
 * - метаданные «тип · размер» (размер файла .qutyshell на диске).
 *
 * «Есть обновление» показывается, если:
 * - фрагмент библиотеки провёл проверку обновлений;
 * - у оболочки найдена запись в каталоге;
 * - версия в каталоге новее локальной.
 *
 * Состояние проверки передаётся через updateAvailableNames (множество
 * имён оболочек, у которых есть обновление).
 */
class StoreLibraryAdapter(
    private val shells: List<Shell>,
    private val activeShellName: String?,
    private val activeShellVersion: String?,
    private val updateAvailableNames: Set<String>,
    private val onItemClick: (Shell) -> Unit
) : RecyclerView.Adapter<StoreLibraryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_library_card, parent, false)
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

        fun bind(shell: Shell) {
            val context = itemView.context

            // Превью из Base64
            bindPreview(shell)

            // Название
            nameText.text = shell.displayName ?: shell.name

            // Автор · версия
            val author = shell.author ?: context.getString(R.string.author_default)
            val version = shell.version ?: context.getString(R.string.unknown)
            authorVersionText.text = context.getString(
                R.string.store_card_author_version,
                author,
                version
            )

            // Тип · размер
            val typeText = if (shell.isCustom) {
                context.getString(R.string.shell_type_custom)
            } else {
                context.getString(R.string.shell_type_builtin)
            }
            val sizeText = getShellFileSize(shell)
            metaText.text = if (sizeText != null) {
                context.getString(R.string.store_library_meta, typeText, sizeText)
            } else {
                typeText
            }

            // Клик по карточке → детали
            itemView.setOnClickListener {
                onItemClick(shell)
            }

            // ==== СТАТУСЫ ====
            bindStatuses(shell)
        }

        /**
         * Загружает превью из shell.previewBase64.
         * Если превью нет — placeholder ic_image.
         */
        private fun bindPreview(shell: Shell) {
            val base64 = shell.previewBase64
            if (base64.isNullOrEmpty()) {
                previewImage.setImageResource(R.drawable.ic_image)
                return
            }

            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                Glide.with(itemView.context)
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
         * Определяет и отображает статусные плашки.
         *
         * Логика:
         * - активна (по имени и реально загруженной версии) → «Активна»;
         * - есть обновление на сервере → «Есть обновление»;
         * - иначе — плашек нет (оболочка просто установлена).
         *
         * Контейнер статусов скрыт, если ни одна плашка не активна.
         */
        private fun bindStatuses(shell: Shell) {
            statusActive.visibility = View.GONE
            statusUpdate.visibility = View.GONE

            var hasAnyStatus = false

            // Активна ли (по имени и реально загруженной версии)
            val isActive = activeShellName == shell.name &&
                    activeShellVersion == shell.version

            if (isActive) {
                statusActive.visibility = View.VISIBLE
                hasAnyStatus = true
            }

            // Есть обновление?
            if (shell.name in updateAvailableNames) {
                statusUpdate.visibility = View.VISIBLE
                hasAnyStatus = true
            }

            statusContainer.visibility = if (hasAnyStatus) View.VISIBLE else View.GONE
        }

        /**
         * Размер файла оболочки на диске.
         * Для встроенных (isAsset) возвращает null — файла нет.
         */
        private fun getShellFileSize(shell: Shell): String? {
            if (shell.isAsset) return null

            return try {
                val file = File(shell.sourcePath)
                if (!file.exists()) return null

                val storageManager = StorageManager(itemView.context)
                storageManager.formatSize(file.length())
            } catch (_: Exception) {
                null
            }
        }
    }
}