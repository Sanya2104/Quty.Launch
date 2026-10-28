// *** core/fragments/store/StoreItemDetailFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.model.StoreItemModel
import by.quty.launch.core.utilities.PopupMenuHelper
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.io.File

/**
 * Детальный экран элемента магазина: превью, метаданные, меню действий.
 */
class StoreItemDetailFragment : Fragment() {

    companion object {
        private const val ARG_ITEM_NAME = "arg_item_name"

        /** Создаёт фрагмент для указанного имени элемента. */
        fun newInstance(itemName: String): StoreItemDetailFragment {
            return StoreItemDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ITEM_NAME, itemName)
                }
            }
        }
    }

    private lateinit var itemName: String
    private var item: StoreItemModel? = null

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storeManager: StoreManager
    private lateinit var storageManager: StorageManager

    private lateinit var previewImage: ImageView
    private lateinit var nameText: TextView
    private lateinit var authorText: TextView
    private lateinit var activeBadge: TextView
    private lateinit var btnMenu: ImageButton

    private lateinit var installProgressContainer: LinearLayout
    private lateinit var installProgressBar: ProgressBar
    private lateinit var installProgressText: TextView

    private lateinit var versionContainer: LinearLayout
    private lateinit var versionDivider: View
    private lateinit var versionValue: TextView
    private lateinit var statusNewerBadge: TextView

    private lateinit var remoteVersionContainer: LinearLayout
    private lateinit var remoteVersionDivider: View
    private lateinit var remoteVersionLabel: TextView
    private lateinit var remoteVersionBadge: TextView
    private lateinit var remoteVersionValue: TextView

    private lateinit var sizeContainer: LinearLayout
    private lateinit var sizeValue: TextView
    private lateinit var sizeDivider: View
    private lateinit var dateContainer: LinearLayout
    private lateinit var dateValue: TextView
    private lateinit var dateDivider: View
    private lateinit var typeContainer: LinearLayout
    private lateinit var typeValue: TextView
    private lateinit var typeDivider: View
    private lateinit var minVersionContainer: LinearLayout
    private lateinit var minVersionValue: TextView
    private lateinit var minVersionDivider: View
    private lateinit var orientationContainer: LinearLayout
    private lateinit var orientationValue: TextView
    private lateinit var orientationDivider: View

    private lateinit var repoContainer: LinearLayout
    private lateinit var repoRow: LinearLayout
    private lateinit var telegramContainer: LinearLayout
    private lateinit var telegramRow: LinearLayout

    private lateinit var shareButton: Button
    private lateinit var deleteButton: Button
    private lateinit var actionsContainer: LinearLayout

    private var isProcessing = false

    /** Читает имя элемента из arguments. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemName = arguments?.getString(ARG_ITEM_NAME) ?: return
    }

    /** Создаёт view фрагмента. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_item_detail, container, false)
    }

    /** Находит view, получает менеджеры и биндит элемент. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configManager = (activity as? by.quty.launch.BaseActivity)?.configManager
            ?: ConfigManager(requireContext())
        shellManager = ShellManager(requireContext(), configManager)
        storeManager = StoreManager(requireContext())
        storageManager = StorageManager(requireContext())

        setupViews(view)

        val found = (activity as? StoreActivity)?.findItemByName(itemName)
        if (found == null) {
            Toast.makeText(
                requireContext(),
                R.string.store_item_not_found,
                Toast.LENGTH_SHORT
            ).show()
            (activity as? StoreActivity)?.hideItemDetail()
            return
        }
        item = found

        bindItem(found)
    }

    /** Перечитывает модель из StoreActivity при возврате (могла устареть). */
    override fun onResume() {
        super.onResume()

        if (isProcessing) return
        if (!isAdded || view == null) return

        val activity = activity as? StoreActivity ?: return
        val currentName = item?.name ?: itemName
        val fresh = activity.findItemByName(currentName)

        if (fresh == null) {
            activity.hideItemDetail()
            return
        }

        if (fresh != item) {
            item = fresh
            bindItem(fresh)
        }
    }

    /** Находит все view по id. */
    private fun setupViews(view: View) {
        previewImage = view.findViewById(R.id.item_preview)
        nameText = view.findViewById(R.id.item_name)
        authorText = view.findViewById(R.id.item_author)
        activeBadge = view.findViewById(R.id.item_action_button)
        btnMenu = view.findViewById(R.id.item_menu_button)

        installProgressContainer = view.findViewById(R.id.install_progress_container)
        installProgressBar = view.findViewById(R.id.install_progress_bar)
        installProgressText = view.findViewById(R.id.install_progress_text)

        versionContainer = view.findViewById(R.id.item_version_container)
        versionDivider = view.findViewById(R.id.item_version_divider)
        versionValue = view.findViewById(R.id.item_version_value)
        statusNewerBadge = view.findViewById(R.id.item_status_newer)

        remoteVersionContainer = view.findViewById(R.id.item_installed_version_container)
        remoteVersionDivider = view.findViewById(R.id.item_installed_version_divider)
        remoteVersionLabel = view.findViewById(R.id.item_installed_version_label)
        remoteVersionBadge = view.findViewById(R.id.item_installed_version_badge)
        remoteVersionValue = view.findViewById(R.id.item_installed_version_value)

        sizeContainer = view.findViewById(R.id.item_size_container)
        sizeValue = view.findViewById(R.id.item_size_value)
        sizeDivider = view.findViewById(R.id.item_size_divider)
        dateContainer = view.findViewById(R.id.item_date_container)
        dateValue = view.findViewById(R.id.item_date_value)
        dateDivider = view.findViewById(R.id.item_date_divider)
        typeContainer = view.findViewById(R.id.item_type_container)
        typeValue = view.findViewById(R.id.item_type_value)
        typeDivider = view.findViewById(R.id.item_type_divider)
        minVersionContainer = view.findViewById(R.id.item_min_version_container)
        minVersionValue = view.findViewById(R.id.item_min_version_value)
        minVersionDivider = view.findViewById(R.id.item_min_version_divider)
        orientationContainer = view.findViewById(R.id.item_orientation_container)
        orientationValue = view.findViewById(R.id.item_orientation_value)
        orientationDivider = view.findViewById(R.id.item_orientation_divider)

        repoContainer = view.findViewById(R.id.item_repo_container)
        repoRow = view.findViewById(R.id.item_repo_row)
        telegramContainer = view.findViewById(R.id.item_telegram_container)
        telegramRow = view.findViewById(R.id.item_telegram_row)

        shareButton = view.findViewById(R.id.item_share_button)
        deleteButton = view.findViewById(R.id.item_delete_button)
        actionsContainer = view.findViewById(R.id.item_actions_container)
    }

    /** Заполняет UI по модели. */
    private fun bindItem(item: StoreItemModel) {
        val context = requireContext()

        bindPreview(item)

        nameText.text = item.displayName
        authorText.text = item.author ?: context.getString(R.string.author_default)

        val remoteVersion = item.remoteInfo?.version
        val installedVersion = item.installedVersion

        // Главная версия: installed, иначе remote, иначе fallback.
        val primaryVersion: String? = when {
            item.isInstalled && !installedVersion.isNullOrEmpty() -> installedVersion
            !remoteVersion.isNullOrEmpty() -> remoteVersion
            else -> item.version
        }

        val hasPrimaryVersion = !primaryVersion.isNullOrEmpty()
        if (hasPrimaryVersion) {
            versionValue.text = "v$primaryVersion"
        }

        statusNewerBadge.visibility = if (item.installedIsNewer) View.VISIBLE else View.GONE

        // Второй блок: installed ? remote.
        val showRemoteRow = item.isInstalled &&
                !installedVersion.isNullOrEmpty() &&
                !remoteVersion.isNullOrEmpty() &&
                remoteVersion != installedVersion

        if (showRemoteRow) {
            remoteVersionValue.text = "v$remoteVersion"

            val labelText: String = if (item.installedIsNewer) {
                getString(R.string.store_version_remote_label)
            } else {
                getString(R.string.store_version_available_label)
            }
            remoteVersionLabel.text = labelText

            if (item.installedIsNewer) {
                remoteVersionBadge.visibility = View.GONE
            } else {
                remoteVersionBadge.visibility = View.VISIBLE
            }
        } else {
            remoteVersionBadge.visibility = View.GONE
        }

        val hasSize = !item.fileSize.isNullOrEmpty()
        if (hasSize) {
            sizeValue.text = item.fileSize
        }

        val hasDate = !item.datePublished.isNullOrEmpty()
        if (hasDate) {
            dateValue.text = formatDate(item.datePublished)
        }

        typeValue.text = when {
            item.localShell?.isAsset == true -> context.getString(R.string.store_type_builtin)
            item.localShell?.isCustom == true -> context.getString(R.string.store_type_custom)
            else -> context.getString(R.string.store_type_catalog)
        }

        val hasMinVersion = !item.minQutyLaunchVersion.isNullOrEmpty()
        if (hasMinVersion) {
            minVersionValue.text = item.minQutyLaunchVersion
        }

        val hasOrientation = !item.orientation.isNullOrEmpty()
        if (hasOrientation) {
            orientationValue.text = when (item.orientation) {
                "portrait" -> getString(R.string.orientation_portrait)
                "landscape" -> getString(R.string.orientation_landscape)
                "sensor" -> getString(R.string.orientation_auto)
                "user" -> getString(R.string.orientation_auto)
                else -> getString(R.string.shell_info_orientation_none)
            }
        }

        // Первый видимый блок без divider'а, остальные с divider'ом.
        val rows = listOf(
            Row(versionContainer, versionDivider, hasPrimaryVersion),
            Row(remoteVersionContainer, remoteVersionDivider, showRemoteRow),
            Row(sizeContainer, sizeDivider, hasSize),
            Row(dateContainer, dateDivider, hasDate),
            Row(typeContainer, typeDivider, true),
            Row(minVersionContainer, minVersionDivider, hasMinVersion),
            Row(orientationContainer, orientationDivider, hasOrientation)
        )

        var firstVisible = true
        rows.forEach { row ->
            if (row.visible) {
                row.container.visibility = View.VISIBLE
                row.divider?.visibility = if (firstVisible) View.GONE else View.VISIBLE
                firstVisible = false
            } else {
                row.container.visibility = View.GONE
                row.divider?.visibility = View.GONE
            }
        }

        if (item.repoUrl.isNullOrEmpty()) {
            repoContainer.visibility = View.GONE
            repoRow.setOnClickListener(null)
        } else {
            repoContainer.visibility = View.VISIBLE
            repoRow.setOnClickListener { openUrl(item.repoUrl) }
        }

        if (item.telegramUrl.isNullOrEmpty()) {
            telegramContainer.visibility = View.GONE
            telegramRow.setOnClickListener(null)
        } else {
            telegramContainer.visibility = View.VISIBLE
            telegramRow.setOnClickListener { openUrl(item.telegramUrl) }
        }

        updateActionUI(item)
    }

    /** Пара «контейнер + divider». */
    private data class Row(
        val container: View,
        val divider: View?,
        val visible: Boolean
    )

    /** true, если файл существует и URI получается. */
    private fun canShareItem(item: StoreItemModel): Boolean {
        if (!item.canShare) return false

        val shell = item.localShell ?: return false
        val file = File(shell.sourcePath)
        return !(!file.exists() || !file.isFile) && storageManager.getUri(file) != null
    }

    /** false для активной, asset, файлов вне SHELLS. */
    private fun canDeleteItem(item: StoreItemModel): Boolean {
        if (item.isActive) return false

        val shell = item.localShell ?: return false
        if (shell.isAsset) return false

        val file = File(shell.sourcePath)
        if (!file.exists() || !file.isFile) return false

        val shellsDir = storageManager.getDirectory(StorageDirectory.SHELLS)
        return try {
            file.canonicalFile.parentFile?.canonicalPath == shellsDir.canonicalPath
        } catch (_: Exception) {
            false
        }
    }

    /** Бейдж «Активна» + кнопка меню. */
    private fun updateActionUI(item: StoreItemModel) {
        if (isProcessing) {
            btnMenu.visibility = View.GONE
            return
        }

        activeBadge.visibility = if (item.isActiveSameVersion) View.VISIBLE else View.GONE

        val menuItems = buildMenuItems(item)
        if (menuItems.isEmpty()) {
            btnMenu.visibility = View.GONE
            btnMenu.setOnClickListener(null)
        } else {
            btnMenu.visibility = View.VISIBLE
            btnMenu.setOnClickListener { showActionMenu(menuItems) }
        }
    }

    /** Установить/Обновить доступны и для активной; Применить — только для неактивной. */
    private fun buildMenuItems(item: StoreItemModel): List<PopupMenuHelper.Item> {
        val items = mutableListOf<PopupMenuHelper.Item>()

        if (item.canInstall) {
            items.add(
                PopupMenuHelper.Item(text = getString(R.string.store_install)) {
                    installItem(item)
                }
            )
        }

        if (item.canUpdate) {
            items.add(
                PopupMenuHelper.Item(text = getString(R.string.store_update)) {
                    installItem(item)
                }
            )
        }

        if (!item.isActiveSameVersion && item.canApply) {
            items.add(
                PopupMenuHelper.Item(text = getString(R.string.store_apply)) {
                    applyItem(item)
                }
            )
        }

        if (canShareItem(item)) {
            items.add(
                PopupMenuHelper.Item(text = getString(R.string.store_detail_share)) {
                    shareItem(item)
                }
            )
        }

        if (canDeleteItem(item)) {
            items.add(
                PopupMenuHelper.Item(text = getString(R.string.store_detail_delete)) {
                    confirmDelete(item)
                }
            )
        }

        return items
    }

    /** Показывает popup-меню действий. */
    private fun showActionMenu(items: List<PopupMenuHelper.Item>) {
        PopupMenuHelper.show(
            anchor = btnMenu,
            items = items
        )
    }

    /** Установка / обновление. Перепроверяет nowActive после завершения. */
    private fun installItem(item: StoreItemModel) {
        if (isProcessing) return
        isProcessing = true

        showInstallProgress(0)
        btnMenu.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val success = storeManager.installItem(
                item = item,
                listener = object : StoreManager.InstallListener {
                    override fun onProgress(percent: Int) {
                        if (!isAdded) return
                        showInstallProgress(percent)
                    }

                    override fun onSuccess() {
                        if (!isAdded) return
                        isProcessing = false
                        hideInstallProgress()

                        val updatedShell = shellManager.getAvailableShells()
                            .find { it.name == item.name }

                        // Перепроверяем nowActive — активная могла смениться.
                        val nowActive = configManager.getActiveShell() == item.name

                        if (updatedShell != null) {
                            if (nowActive) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    shellManager.setActiveShell(updatedShell)
                                    (activity as? by.quty.launch.BaseActivity)
                                        ?.markRestartRequired()

                                    applyLocalActiveState(updatedShell.version)

                                    reloadAndRebind()
                                }
                            } else {
                                shellManager.reloadActiveShell()
                                (activity as? by.quty.launch.BaseActivity)
                                    ?.markRestartRequired()
                                reloadAndRebind()
                            }
                        }

                        Toast.makeText(
                            requireContext(),
                            getString(R.string.store_install_success, item.displayName),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onError(message: String) {
                        if (!isAdded) return
                        isProcessing = false
                        hideInstallProgress()
                        updateActionUI(item)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            )

            if (!success && isProcessing) {
                isProcessing = false
                if (isAdded) {
                    hideInstallProgress()
                    updateActionUI(item)
                }
            }
        }
    }

    /** Сделать активной. */
    private fun applyItem(item: StoreItemModel) {
        if (isProcessing) return
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val availableShell = shellManager.getAvailableShells()
                .find { it.name == item.name }

            if (availableShell == null) {
                isProcessing = false
                updateActionUI(item)
                Toast.makeText(
                    requireContext(),
                    R.string.store_install_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            shellManager.setActiveShell(availableShell)

            isProcessing = false

            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            applyLocalActiveState(availableShell.version)

            reloadAndRebind()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, item.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Локально обновляет модель: isActive = true + активная версия. */
    private fun applyLocalActiveState(activeVersion: String?) {
        val current = item ?: return

        val effectiveVersion = if (current.installedIsNewer) {
            current.installedVersion
        } else {
            current.remoteInfo?.version
        }

        val resolvedActiveVersion = activeVersion ?: effectiveVersion ?: current.activeVersion

        val updated = current.copy(
            isActive = true,
            activeVersion = resolvedActiveVersion
        )
        item = updated
        updateActionUI(updated)
    }

    /** Перечитывает элемент из StoreActivity и перерисовывает. */
    private fun reloadAndRebind() {
        val activity = activity as? StoreActivity ?: return
        val currentName = item?.name ?: return

        activity.refreshItems {
            if (!isAdded) return@refreshItems
            val updated = activity.findItemByName(currentName)
            if (updated != null) {
                item = updated
                bindItem(updated)
            }
        }
    }

    /** Отправляет файл оболочки через share-диалог. */
    private fun shareItem(item: StoreItemModel) {
        val shell = item.localShell ?: return
        try {
            val file = File(shell.sourcePath)
            if (!file.exists()) return

            val uri = storageManager.getUri(file) ?: return

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (_: Exception) {
        }
    }

    /** Диалог подтверждения удаления. */
    private fun confirmDelete(item: StoreItemModel) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView = dialogView.findViewById<TextView>(R.id.recovery_confirm_title)
        val messageView = dialogView.findViewById<TextView>(R.id.recovery_confirm_message)
        val okButton = dialogView.findViewById<Button>(R.id.recovery_confirm_ok_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.recovery_confirm_cancel_button)

        titleView.text = getString(R.string.store_delete_title)
        messageView.text = getString(R.string.store_delete_message, item.displayName)

        okButton.text = getString(R.string.store_delete_action)
        cancelButton.text = getString(R.string.cancel)

        cancelButton.setOnClickListener { dialog.dismiss() }

        okButton.setOnClickListener {
            dialog.dismiss()
            performDelete(item)
        }

        dialog.show()
    }

    /** Удаляет оболочку; после refreshItems закрывает или обновляет экран. */
    private fun performDelete(item: StoreItemModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = shellManager.deleteShellByName(item.name)

            if (!isAdded) return@launch

            if (!success) {
                Toast.makeText(
                    requireContext(),
                    R.string.store_delete_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Toast.makeText(
                requireContext(),
                R.string.store_delete_success,
                Toast.LENGTH_SHORT
            ).show()

            (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

            val activity = activity as? StoreActivity ?: return@launch
            val currentName = item.name

            activity.refreshItems {
                if (!isAdded) return@refreshItems

                val updated = activity.findItemByName(currentName)

                if (updated == null) {
                    activity.hideItemDetail()
                } else {
                    this@StoreItemDetailFragment.item = updated
                    bindItem(updated)
                }
            }
        }
    }

    /** Загружает превью оболочки (Glide по URL или Base64). */
    private fun bindPreview(item: StoreItemModel) {
        if (!item.previewUrl.isNullOrEmpty()) {
            Glide.with(this)
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
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                previewImage.setImageBitmap(bitmap)
            } else {
                previewImage.setImageResource(R.drawable.ic_image)
            }
        } catch (_: Exception) {
            previewImage.setImageResource(R.drawable.ic_image)
        }
    }

    /** Показывает прогресс установки. */
    private fun showInstallProgress(percent: Int) {
        activeBadge.visibility = View.GONE
        installProgressContainer.visibility = View.VISIBLE
        installProgressBar.isIndeterminate = false
        installProgressBar.progress = percent
        installProgressText.text = getString(R.string.downloading_progress, percent)
    }

    /** Скрывает прогресс установки. */
    private fun hideInstallProgress() {
        installProgressContainer.visibility = View.GONE
        item?.let { updateActionUI(it) }
    }

    /** ISO YYYY-MM-DD > DD.MM.YYYY. */
    private fun formatDate(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return try {
            val input = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val output = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
            val date = input.parse(isoDate) ?: return isoDate
            output.format(date)
        } catch (_: Exception) {
            isoDate
        }
    }

    /** Открывает URL во внешнем браузере. */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.settings_personalization_shell_info_repo_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}