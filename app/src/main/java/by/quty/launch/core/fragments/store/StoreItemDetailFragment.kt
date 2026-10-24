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
 * Детальный экран элемента магазина.
 *
 * Один фрагмент на все случаи. Принимает имя элемента через arguments;
 * сам элемент запрашивает из StoreActivity.findItemByName().
 *
 * Бейдж «Активна» (item_action_button) отображается ТОЛЬКО когда элемент
 * активен (isActiveSameVersion == true). Стилизован под статус «Активна»
 * в каталоге (bg_status_active + status_granted + 13sp + medium).
 * Клик по нему не обрабатывается — это не кнопка, а индикатор.
 *
 * Во всех остальных случаях бейдж скрыт, а действия доступны через
 * выпадающее меню (ic_menu_dots) справа от названия:
 *   Установить → Обновить → Применить → Поделиться → Удалить
 * (пункт появляется только если соответствующее условие выполнено).
 *
 * Если элемент активен — меню содержит только Поделиться / Удалить
 * (что доступно по флагам canShare / canDelete).
 *
 * Пункт «Поделиться» показывается только если файл реально существует
 * и StorageManager может выдать на него URI (см. canShareItem()).
 *
 * Пункт «Удалить» показывается только если файл реально лежит в
 * StorageDirectory.SHELLS (т.е. это пользовательская оболочка).
 * Встроенные (asset) оболочки удалить нельзя — у них нет файла в SHELLS
 * (см. canDeleteItem()).
 *
 * Разделители в карточке метаданных:
 * - у первого видимого блока divider = GONE;
 * - у каждого следующего видимого блока divider = VISIBLE.
 * Это единое правило — как в dialog_shell_info.xml.
 *
 * Если в меню нет ни одного пункта — ic_menu_dots тоже скрыт.
 *
 * После установки / применения / удаления — markRestartRequired() на
 * StoreActivity, чтобы при выходе показался диалог перезапуска.
 *
 * После удаления — ждём refreshItems() через колбэк и:
 * - если элемент остался (например, встроенная с тем же именем) —
 *   перерисовываем деталь по актуальной модели;
 * - если элемента нет (оболочки больше нет ни локально, ни на сервере) —
 *   закрываем деталь.
 * Это устраняет гонку между async refreshItems() в StoreActivity и
 * открытием детали сразу после удаления.
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
    private lateinit var activeBadge: TextView
    private lateinit var btnMenu: ImageButton

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
        activeBadge = view.findViewById(R.id.item_action_button)
        btnMenu = view.findViewById(R.id.item_menu_button)

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

        // ===== Значения метаданных =====

        // Версия (магазинная или локальная)
        val hasVersion = !item.version.isNullOrEmpty()
        if (hasVersion) {
            versionValue.text = "v${item.version}"
        }

        // Установленная версия (если отличается)
        val installedVersion = item.installedVersion
        val remoteVersion = item.remoteInfo?.version
        val showInstalledRow = item.isInstalled && installedVersion != null &&
                (remoteVersion == null || remoteVersion != installedVersion)

        if (showInstalledRow) {
            installedVersionValue.text = "v$installedVersion"
            statusNewerBadge.visibility = if (item.installedIsNewer) View.VISIBLE else View.GONE
        } else {
            statusNewerBadge.visibility = View.GONE
        }

        // Размер
        val hasSize = !item.fileSize.isNullOrEmpty()
        if (hasSize) {
            sizeValue.text = item.fileSize
        }

        // Дата публикации
        val hasDate = !item.datePublished.isNullOrEmpty()
        if (hasDate) {
            dateValue.text = formatDate(item.datePublished)
        }

        // Тип — всегда видим
        typeValue.text = when {
            item.localShell?.isAsset == true -> context.getString(R.string.store_type_builtin)
            item.localShell?.isCustom == true -> context.getString(R.string.store_type_custom)
            else -> context.getString(R.string.store_type_catalog)
        }

        // Мин. версия Quty.Launch
        val hasMinVersion = !item.minQutyLaunchVersion.isNullOrEmpty()
        if (hasMinVersion) {
            minVersionValue.text = item.minQutyLaunchVersion
        }

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

        // ===== Разделители + видимость блоков =====
        //
        // Правило одинаково для всех блоков:
        // - у первого видимого блока divider = GONE;
        // - у каждого следующего видимого блока divider = VISIBLE.
        // Это устраняет «висячий» разделитель в начале карточки
        // и корректно работает при любом наборе скрытых полей.
        val rows = listOf(
            Row(versionContainer, versionDivider, hasVersion),
            Row(installedVersionContainer, installedVersionDivider, showInstalledRow),
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

        // ===== Бейдж «Активна» + меню =====
        updateActionUI(item)
    }

    /**
     * Пара «контейнер блока + разделитель ПЕРЕД ним».
     * Используется для единой логики разделителей:
     * у первого видимого блока divider = GONE, у остальных = VISIBLE.
     */
    private data class Row(
        val container: View,
        val divider: View?,
        val visible: Boolean
    )

    /**
     * Проверяет, можно ли реально поделиться файлом ресурса.
     *
     * Одного флага item.canShare (== localShell != null) недостаточно:
     * - файла может не быть на диске (был удалён, путь битый);
     * - StorageManager.getUri() может вернуть null (FileProvider недоступен).
     *
     * @return true, если файл существует и на него можно получить URI.
     */
    private fun canShareItem(item: StoreItemModel): Boolean {
        if (!item.canShare) return false

        val shell = item.localShell ?: return false
        val file = File(shell.sourcePath)
        return !(!file.exists() || !file.isFile) && storageManager.getUri(file) != null
    }

    /**
     * Проверяет, можно ли реально удалить оболочку.
     *
     * Одного флага item.canDelete (== localShell.isCustom && !isActive)
     * недостаточно — он опирается на Shell.isCustom, который не различает
     * «кастомная поверх встроенной» и «встроенная». Из-за этого после
     * удаления пользовательской версии поверх встроенной у встроенной
     * всё ещё мог показываться пункт «Удалить».
     *
     * Надёжная проверка — по файловой системе:
     * - активную удалять нельзя;
     * - встроенную (isAsset) удалять нельзя — у неё нет файла в SHELLS;
     * - файл должен существовать и лежать именно в StorageDirectory.SHELLS
     *   (т.е. это действительно пользовательская оболочка, а не asset).
     */
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

    /**
     * Пересчитывает состояние бейджа «Активна» и кнопки-меню.
     *
     * Логика:
     * - isActiveSameVersion == true → бейдж «Активна» (стилизован под
     *   статус в каталоге, не кликабельный) + меню (Поделиться / Удалить);
     * - иначе → бейдж скрыт, меню содержит действия по флагам
     *   (Установить / Обновить / Применить / Поделиться / Удалить).
     *
     * Если в меню нет ни одного пункта — кнопка-меню тоже скрыта.
     */
    private fun updateActionUI(item: StoreItemModel) {
        if (isProcessing) {
            // Пока идёт установка — прогресс уже показан, меню скрыто
            btnMenu.visibility = View.GONE
            return
        }

        // Бейдж виден ТОЛЬКО когда активна та же версия
        activeBadge.visibility = if (item.isActiveSameVersion) View.VISIBLE else View.GONE

        // Меню-кнопка
        val menuItems = buildMenuItems(item)
        if (menuItems.isEmpty()) {
            btnMenu.visibility = View.GONE
            btnMenu.setOnClickListener(null)
        } else {
            btnMenu.visibility = View.VISIBLE
            btnMenu.setOnClickListener { showActionMenu(menuItems) }
        }
    }

    /**
     * Собирает пункты выпадающего меню для текущего элемента.
     *
     * Если элемент активен (isActiveSameVersion) — только Поделиться / Удалить.
     * Иначе — Установить / Обновить / Применить / Поделиться / Удалить
     * (что доступно по флагам модели).
     *
     * Пункт «Поделиться» добавляется только если canShareItem() == true
     * (файл существует и URI получается).
     * Пункт «Удалить» добавляется только если canDeleteItem() == true
     * (файл реально лежит в SHELLS и это не asset).
     */
    private fun buildMenuItems(item: StoreItemModel): List<PopupMenuHelper.Item> {
        val items = mutableListOf<PopupMenuHelper.Item>()

        if (!item.isActiveSameVersion) {
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
            if (item.canApply) {
                items.add(
                    PopupMenuHelper.Item(text = getString(R.string.store_apply)) {
                        applyItem(item)
                    }
                )
            }
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

    private fun showActionMenu(items: List<PopupMenuHelper.Item>) {
        PopupMenuHelper.show(
            anchor = btnMenu,
            items = items
        )
    }

    /**
     * Установка / обновление.
     */
    private fun installItem(item: StoreItemModel) {
        if (isProcessing) return
        isProcessing = true

        showInstallProgress(0)
        btnMenu.visibility = View.GONE
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

                                    // Мгновенно обновляем бейдж/меню по локальному
                                    // «активному» состоянию — не ждём reloadAndRebind().
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

            // Мгновенно обновляем бейдж/меню по локальному «активному»
            // состоянию — бейдж появляется сразу после нажатия «Применить».
            applyLocalActiveState(availableShell.version)

            reloadAndRebind()

            Toast.makeText(
                requireContext(),
                getString(R.string.shell_applied_toast, item.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Локально применяет состояние «элемент активен с версией [activeVersion]».
     *
     * Нужно, чтобы бейдж «Активна» появлялся мгновенно после нажатия
     * «Применить» / успешной установки активной оболочки, не дожидаясь
     * асинхронного reloadAndRebind() (refreshItems() идёт из StoreActivity).
     *
     * Пересобираем копию модели с isActive = true и activeVersion = [activeVersion].
     * Сам reloadAndRebind() потом подтянет авторитетные данные.
     */
    private fun applyLocalActiveState(activeVersion: String?) {
        val current = item ?: return

        // effective-версия из isActiveSameVersion в модели:
        //   installedIsNewer ? installedVersion : remoteInfo?.version
        val effectiveVersion = if (current.installedIsNewer) {
            current.installedVersion
        } else {
            current.remoteInfo?.version
        }

        // Берём версию, которую реально загрузили в WebView.
        // Если её нет — пробуем effective-версию, иначе оставляем как есть.
        val resolvedActiveVersion = activeVersion ?: effectiveVersion ?: current.activeVersion

        val updated = current.copy(
            isActive = true,
            activeVersion = resolvedActiveVersion
        )
        item = updated
        updateActionUI(updated)
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

    /**
     * Удаление оболочки.
     *
     * ВАЖНО: после удаления файла сначала дожидаемся пересборки
     * currentItems в StoreActivity (через колбэк refreshItems), и только
     * потом решаем, что делать с текущим экраном:
     * - если элемент всё ещё существует (например, встроенная оболочка
     *   с тем же именем — кастомная была поверх неё) — перерисовываем
     *   деталь по актуальной модели;
     * - если элемента больше нет (ни локально, ни на сервере) —
     *   закрываем деталь.
     *
     * Без ожидания refreshItems() пользователь мог открыть деталь
     * до пересборки currentItems и увидеть устаревшую модель
     * (например, без установленной версии и без пункта «Обновить»).
     */
    private fun performDelete(item: StoreItemModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = shellManager.deleteShellByName(item.name)

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

            // Дожидаемся пересборки currentItems, чтобы каталог и деталь
            // работали с актуальной моделью (без гонки).
            activity.refreshItems {
                if (!isAdded) return@refreshItems

                val updated = activity.findItemByName(currentName)

                if (updated == null) {
                    // Оболочки больше нет — закрываем деталь.
                    activity.hideItemDetail()
                } else {
                    // Оболочка осталась (например, встроенная с тем же именем) —
                    // остаёмся на детали и перерисовываем её по актуальной модели.
                    this@StoreItemDetailFragment.item = updated
                    bindItem(updated)
                }
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
        activeBadge.visibility = View.GONE
        installProgressContainer.visibility = View.VISIBLE
        installProgressBar.isIndeterminate = false
        installProgressBar.progress = percent
        installProgressText.text = getString(R.string.downloading_progress, percent)
    }

    private fun hideInstallProgress() {
        installProgressContainer.visibility = View.GONE
        // Бейдж/меню видимость восстановит updateActionUI(item)
        item?.let { updateActionUI(it) }
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
}