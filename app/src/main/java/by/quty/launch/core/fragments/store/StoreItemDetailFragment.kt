// *** core/fragments/store/StoreItemDetailFragment.kt *** //
package by.quty.launch.core.fragments.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.StoreActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StoreManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.model.StoreItemModel
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.io.File

/**
 * Детальный экран элемента магазина.
 *
 * Один фрагмент на все случаи. Принимает имя элемента через arguments;
 * сам элемент запрашивает из StoreActivity.findItemByName().
 *
 * Кнопка действия динамическая:
 * - «Установить» (primary) — canInstall;
 * - «Обновить» (primary) — canUpdate;
 * - «Применить» (primary) — canApply;
 * - «Активна» (secondary, disabled) — isActiveSameVersion.
 *
 * Нижние действия:
 * - «Поделиться» (secondary) — canShare;
 * - «Удалить» (danger) — canDelete.
 *
 * После установки / применения / удаления — markRestartRequired() на
 * StoreActivity, чтобы при выходе показался диалог перезапуска.
 */
class StoreItemDetailFragment : Fragment() {

    companion object {
        private const val ARG_ITEM_NAME = "arg_item_name"

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

    // ===== UI =====
    private lateinit var previewImage: ImageView
    private lateinit var nameText: TextView
    private lateinit var authorText: TextView
    private lateinit var btnAction: Button

    private lateinit var installProgressContainer: LinearLayout
    private lateinit var installProgressBar: ProgressBar
    private lateinit var installProgressText: TextView

    private lateinit var versionContainer: LinearLayout
    private lateinit var versionDivider: View
    private lateinit var versionValue: TextView

    private lateinit var installedVersionContainer: LinearLayout
    private lateinit var installedVersionValue: TextView
    private lateinit var statusNewerBadge: TextView
    private lateinit var installedVersionDivider: View

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
    private lateinit var repoButton: Button
    private lateinit var telegramContainer: LinearLayout
    private lateinit var telegramButton: Button

    private lateinit var shareButton: Button
    private lateinit var deleteButton: Button
    private lateinit var actionsContainer: LinearLayout

    // Флаг, что установка/применение сейчас выполняется
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemName = arguments?.getString(ARG_ITEM_NAME) ?: return
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_store_item_detail, container, false)
    }

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

    private fun setupViews(view: View) {
        previewImage = view.findViewById(R.id.item_preview)
        nameText = view.findViewById(R.id.item_name)
        authorText = view.findViewById(R.id.item_author)
        btnAction = view.findViewById(R.id.item_action_button)

        installProgressContainer = view.findViewById(R.id.install_progress_container)
        installProgressBar = view.findViewById(R.id.install_progress_bar)
        installProgressText = view.findViewById(R.id.install_progress_text)

        versionContainer = view.findViewById(R.id.item_version_container)
        versionDivider = view.findViewById(R.id.item_version_divider)
        versionValue = view.findViewById(R.id.item_version_value)

        installedVersionContainer = view.findViewById(R.id.item_installed_version_container)
        installedVersionValue = view.findViewById(R.id.item_installed_version_value)
        statusNewerBadge = view.findViewById(R.id.item_status_newer)
        installedVersionDivider = view.findViewById(R.id.item_installed_version_divider)

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
        repoButton = view.findViewById(R.id.item_repo_button)
        telegramContainer = view.findViewById(R.id.item_telegram_container)
        telegramButton = view.findViewById(R.id.item_telegram_button)

        shareButton = view.findViewById(R.id.item_share_button)
        deleteButton = view.findViewById(R.id.item_delete_button)
        actionsContainer = view.findViewById(R.id.item_actions_container)
    }

    private fun bindItem(item: StoreItemModel) {
        val context = requireContext()

        bindPreview(item)

        nameText.text = item.displayName
        authorText.text = item.author ?: context.getString(R.string.author_default)

        // ===== Строки метаданных с разделителями =====

        // Версия (магазинная или локальная)
        val hasVersion = !item.version.isNullOrEmpty()
        if (hasVersion) {
            versionValue.text = "v${item.version}"
        }
        setRowVisible(versionContainer, versionDivider, hasVersion)

        // Установленная версия (если отличается)
        val installedVersion = item.installedVersion
        val remoteVersion = item.remoteInfo?.version
        val showInstalledRow = item.isInstalled && installedVersion != null &&
                (remoteVersion == null || remoteVersion != installedVersion)

        if (showInstalledRow) {
            installedVersionValue.text = "v$installedVersion"
            statusNewerBadge.visibility = if (item.installedIsNewer) View.VISIBLE else View.GONE
        }
        setRowVisible(installedVersionContainer, installedVersionDivider, showInstalledRow)
        if (!showInstalledRow) {
            statusNewerBadge.visibility = View.GONE
        }

        // Размер
        val hasSize = !item.fileSize.isNullOrEmpty()
        if (hasSize) {
            sizeValue.text = item.fileSize
        }
        setRowVisible(sizeContainer, sizeDivider, hasSize)

        // Дата публикации
        val hasDate = !item.datePublished.isNullOrEmpty()
        if (hasDate) {
            dateValue.text = formatDate(item.datePublished)
        }
        setRowVisible(dateContainer, dateDivider, hasDate)

        // Тип — всегда видим
        typeValue.text = when {
            item.localShell?.isAsset == true -> context.getString(R.string.store_type_builtin)
            item.localShell?.isCustom == true -> context.getString(R.string.store_type_custom)
            else -> context.getString(R.string.store_type_catalog)
        }
        setRowVisible(typeContainer, typeDivider, true)

        // Мин. версия Quty.Launch
        val hasMinVersion = !item.minQutyLaunchVersion.isNullOrEmpty()
        if (hasMinVersion) {
            minVersionValue.text = item.minQutyLaunchVersion
        }
        setRowVisible(minVersionContainer, minVersionDivider, hasMinVersion)

        // Ориентация
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
        setRowVisible(orientationContainer, orientationDivider, hasOrientation)

        // ===== Репозиторий =====
        if (item.repoUrl.isNullOrEmpty()) {
            repoContainer.visibility = View.GONE
        } else {
            repoContainer.visibility = View.VISIBLE
            repoButton.setOnClickListener { openUrl(item.repoUrl) }
        }

        // ===== Telegram =====
        if (item.telegramUrl.isNullOrEmpty()) {
            telegramContainer.visibility = View.GONE
        } else {
            telegramContainer.visibility = View.VISIBLE
            telegramButton.setOnClickListener { openUrl(item.telegramUrl) }
        }

        // ===== Кнопка действия =====
        updateActionButton(item)

        // ===== Нижние действия =====
        if (item.localShell?.isCustom == true) {
            actionsContainer.visibility = View.VISIBLE

            shareButton.visibility = if (item.canShare) View.VISIBLE else View.GONE
            shareButton.setOnClickListener { shareItem(item) }

            deleteButton.visibility = if (item.canDelete) View.VISIBLE else View.GONE
            deleteButton.setOnClickListener { confirmDelete(item) }
        } else {
            actionsContainer.visibility = View.GONE
        }
    }

    /**
     * Связка видимости контейнера и разделителя.
     */
    private fun setRowVisible(container: View, divider: View?, visible: Boolean) {
        container.visibility = if (visible) View.VISIBLE else View.GONE
        divider?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Пересчитывает состояние кнопки действия.
     */
    private fun updateActionButton(item: StoreItemModel) {
        when {
            item.isActiveSameVersion -> {
                applyButtonStyle(isPrimary = false)
                btnAction.text = getString(R.string.store_applied)
                btnAction.isEnabled = false
                btnAction.alpha = 0.7f
                btnAction.setOnClickListener(null)
            }
            item.canUpdate -> {
                applyButtonStyle(isPrimary = true)
                btnAction.text = getString(R.string.store_update)
                btnAction.isEnabled = true
                btnAction.alpha = 1.0f
                btnAction.setOnClickListener { installItem(item) }
            }
            item.canApply -> {
                applyButtonStyle(isPrimary = true)
                btnAction.text = getString(R.string.store_apply)
                btnAction.isEnabled = true
                btnAction.alpha = 1.0f
                btnAction.setOnClickListener { applyItem(item) }
            }
            item.canInstall -> {
                applyButtonStyle(isPrimary = true)
                btnAction.text = getString(R.string.store_install)
                btnAction.isEnabled = true
                btnAction.alpha = 1.0f
                btnAction.setOnClickListener { installItem(item) }
            }
            else -> {
                applyButtonStyle(isPrimary = false)
                btnAction.text = getString(R.string.store_applied)
                btnAction.isEnabled = false
                btnAction.alpha = 0.7f
                btnAction.setOnClickListener(null)
            }
        }
    }

    private fun applyButtonStyle(isPrimary: Boolean) {
        val context = requireContext()
        if (isPrimary) {
            btnAction.setBackgroundResource(R.drawable.bg_glass_button_primary)
            btnAction.setTextColor(ContextCompat.getColor(context, R.color.white))
        } else {
            btnAction.setBackgroundResource(R.drawable.bg_glass_button_secondary)
            btnAction.setTextColor(getThemeColor(R.attr.textPrimaryColor))
        }
    }

    /**
     * Установка / обновление.
     */
    private fun installItem(item: StoreItemModel) {
        if (isProcessing) return
        isProcessing = true

        showInstallProgress(0)

        val wasActive = configManager.getActiveShell() == item.name

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

                        // Перечитываем оболочку
                        val updatedShell = shellManager.getAvailableShells()
                            .find { it.name == item.name }

                        if (updatedShell != null) {
                            if (wasActive) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    shellManager.setActiveShell(updatedShell)
                                    (activity as? by.quty.launch.BaseActivity)
                                        ?.markRestartRequired()
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
                        updateActionButton(item)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            )

            if (!success && isProcessing) {
                isProcessing = false
                if (isAdded) {
                    hideInstallProgress()
                    updateActionButton(item)
                }
            }
        }
    }

    /**
     * Применить (сделать активной).
     */
    private fun applyItem(item: StoreItemModel) {
        if (isProcessing) return
        isProcessing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val availableShell = shellManager.getAvailableShells()
                .find { it.name == item.name }

            if (availableShell == null) {
                isProcessing = false
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

            reloadAndRebind()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, item.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Пересобирает текущий item через StoreActivity.
     *
     * refreshItems() асинхронный — используем колбэк,
     * чтобы получить актуальный StoreItemModel после пересборки.
     */
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
            // Игнорируем
        }
    }

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

        titleView.setText(R.string.store_delete_title)
        messageView.text = getString(R.string.store_delete_message, item.displayName)

        okButton.setText(R.string.store_delete_action)
        cancelButton.setText(R.string.cancel)

        cancelButton.setOnClickListener { dialog.dismiss() }

        okButton.setOnClickListener {
            dialog.dismiss()
            performDelete(item)
        }

        dialog.show()
    }

    private fun performDelete(item: StoreItemModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = shellManager.deleteShellByName(item.name)

            if (success) {
                Toast.makeText(
                    requireContext(),
                    R.string.store_delete_success,
                    Toast.LENGTH_SHORT
                ).show()

                (activity as? by.quty.launch.BaseActivity)?.markRestartRequired()

                (activity as? StoreActivity)?.hideItemDetail()
                (activity as? StoreActivity)?.refreshItems()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.store_delete_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

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

    private fun showInstallProgress(percent: Int) {
        btnAction.visibility = View.GONE
        installProgressContainer.visibility = View.VISIBLE
        installProgressBar.isIndeterminate = false
        installProgressBar.progress = percent
        installProgressText.text = getString(R.string.downloading_progress, percent)
    }

    private fun hideInstallProgress() {
        installProgressContainer.visibility = View.GONE
        btnAction.visibility = View.VISIBLE
    }

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

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}