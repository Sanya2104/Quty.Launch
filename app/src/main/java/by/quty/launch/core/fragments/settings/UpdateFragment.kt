// *** core/fragments/settings/UpdateFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.managers.SystemUpdateManager
import by.quty.launch.core.managers.VersionInfo
import by.quty.launch.core.utilities.AppInfoHelper
import kotlinx.coroutines.launch
import java.io.File

/**
 * Фрагмент "Центр обновления" для Настроек
 * Содержит управление обновлениями приложения:
 * - проверка онлайн-обновлений через SystemUpdateManager;
 * - локальная установка APK из файла.
 *
 * Все состояния (проверка, доступно обновление, скачивание, ошибка)
 * отображаются inline — в блоке под пунктами, без AlertDialog.
 *
 * Информация о версии приложения вынесена в AboutFragment («О системе»).
 */
class UpdateFragment : Fragment() {

    // ===== Пункты карточки =====
    private lateinit var checkUpdateButton: View
    private lateinit var installFromFileButton: View

    // ===== Блок состояния онлайн-обновления =====
    private lateinit var updateStateContainer: LinearLayout
    private lateinit var updateStateIcon: ImageView
    private lateinit var updateStateTitle: TextView
    private lateinit var updateStateSubtitle: TextView
    private lateinit var updateStateDescription: TextView
    private lateinit var updateStateProgressContainer: LinearLayout
    private lateinit var updateStateProgress: ProgressBar
    private lateinit var updateStateProgressText: TextView
    private lateinit var updateStateActions: LinearLayout
    private lateinit var updateStateBtnPrimary: Button
    private lateinit var updateStateBtnSecondary: Button

    // ===== Блок состояния локальной установки APK =====
    private lateinit var installStateContainer: LinearLayout
    private lateinit var installStateIcon: ImageView
    private lateinit var installStateTitle: TextView
    private lateinit var installStateSubtitle: TextView
    private lateinit var installStateDescription: TextView
    private lateinit var installStateActions: LinearLayout
    private lateinit var installStateBtnPrimary: Button
    private lateinit var installStateBtnSecondary: Button

    private lateinit var updateManager: SystemUpdateManager
    private lateinit var storageManager: StorageManager

    // Храним текущее доступное обновление — для повторного открытия блока после сворачивания
    private var pendingVersionInfo: VersionInfo? = null

    // URL уже скачанного APK (для кнопки «Установить» после download)
    private var pendingApkUri: Uri? = null

    // URI локально выбранного APK (для кнопки «Установить» в блоке локальной установки)
    private var pendingLocalApkUri: Uri? = null

    // Регистрируем ActivityResult для выбора APK
    private val selectApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Сбрасываем блок онлайн-обновления — показываем блок локальной установки
                hideUpdateState()
                showInstallStateChecking()
                validateAndInstallApk(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_updates, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        storageManager = StorageManager(requireContext())
        updateManager = SystemUpdateManager(requireContext())

        initViews(view)
        setupUpdateCheck()
        setupInstallFromFile()
    }

    private fun initViews(view: View) {
        checkUpdateButton = view.findViewById(R.id.check_update_button)
        installFromFileButton = view.findViewById(R.id.install_from_file_button)

        // Онлайн-обновление
        updateStateContainer = view.findViewById(R.id.update_state_container)
        updateStateIcon = view.findViewById(R.id.update_state_icon)
        updateStateTitle = view.findViewById(R.id.update_state_title)
        updateStateSubtitle = view.findViewById(R.id.update_state_subtitle)
        updateStateDescription = view.findViewById(R.id.update_state_description)
        updateStateProgressContainer = view.findViewById(R.id.update_state_progress_container)
        updateStateProgress = view.findViewById(R.id.update_state_progress)
        updateStateProgressText = view.findViewById(R.id.update_state_progress_text)
        updateStateActions = view.findViewById(R.id.update_state_actions)
        updateStateBtnPrimary = view.findViewById(R.id.update_state_btn_primary)
        updateStateBtnSecondary = view.findViewById(R.id.update_state_btn_secondary)

        // Локальная установка
        installStateContainer = view.findViewById(R.id.install_state_container)
        installStateIcon = view.findViewById(R.id.install_state_icon)
        installStateTitle = view.findViewById(R.id.install_state_title)
        installStateSubtitle = view.findViewById(R.id.install_state_subtitle)
        installStateDescription = view.findViewById(R.id.install_state_description)
        installStateActions = view.findViewById(R.id.install_state_actions)
        installStateBtnPrimary = view.findViewById(R.id.install_state_btn_primary)
        installStateBtnSecondary = view.findViewById(R.id.install_state_btn_secondary)
    }

    // ============================================================
    // ОБЩИЕ ХЕЛПЕРЫ ДЛЯ БЛОКОВ СОСТОЯНИЙ
    // ============================================================

    /**
     * Сброс и скрытие блока онлайн-обновления
     */
    private fun hideUpdateState() {
        updateStateContainer.isVisible = false
        updateStateSubtitle.isVisible = false
        updateStateDescription.isVisible = false
        updateStateProgressContainer.isVisible = false
        updateStateProgressText.isVisible = false
        updateStateActions.isVisible = false
        updateStateBtnPrimary.isVisible = false
        updateStateBtnSecondary.isVisible = false

        // Снимаем слушатели
        updateStateBtnPrimary.setOnClickListener(null)
        updateStateBtnSecondary.setOnClickListener(null)
    }

    /**
     * Сброс и скрытие блока локальной установки
     */
    private fun hideInstallState() {
        installStateContainer.isVisible = false
        installStateSubtitle.isVisible = false
        installStateDescription.isVisible = false
        installStateActions.isVisible = false
        installStateBtnPrimary.isVisible = false
        installStateBtnSecondary.isVisible = false

        installStateBtnPrimary.setOnClickListener(null)
        installStateBtnSecondary.setOnClickListener(null)
    }

    /**
     * Настройка кнопок в блоке онлайн-обновления
     */
    private fun setUpdateActions(
        primaryText: String? = null,
        primaryAction: (() -> Unit)? = null,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null
    ) {
        if (primaryText != null && primaryAction != null) {
            updateStateBtnPrimary.text = primaryText
            updateStateBtnPrimary.isVisible = true
            updateStateBtnPrimary.setOnClickListener { primaryAction() }
        } else {
            updateStateBtnPrimary.isVisible = false
            updateStateBtnPrimary.setOnClickListener(null)
        }

        if (secondaryText != null && secondaryAction != null) {
            updateStateBtnSecondary.text = secondaryText
            updateStateBtnSecondary.isVisible = true
            updateStateBtnSecondary.setOnClickListener { secondaryAction() }
        } else {
            updateStateBtnSecondary.isVisible = false
            updateStateBtnSecondary.setOnClickListener(null)
        }

        updateStateActions.isVisible = updateStateBtnPrimary.isVisible || updateStateBtnSecondary.isVisible
    }

    /**
     * Настройка кнопок в блоке локальной установки
     */
    private fun setInstallActions(
        primaryText: String? = null,
        primaryAction: (() -> Unit)? = null,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null
    ) {
        if (primaryText != null && primaryAction != null) {
            installStateBtnPrimary.text = primaryText
            installStateBtnPrimary.isVisible = true
            installStateBtnPrimary.setOnClickListener { primaryAction() }
        } else {
            installStateBtnPrimary.isVisible = false
            installStateBtnPrimary.setOnClickListener(null)
        }

        if (secondaryText != null && secondaryAction != null) {
            installStateBtnSecondary.text = secondaryText
            installStateBtnSecondary.isVisible = true
            installStateBtnSecondary.setOnClickListener { secondaryAction() }
        } else {
            installStateBtnSecondary.isVisible = false
            installStateBtnSecondary.setOnClickListener(null)
        }

        installStateActions.isVisible = installStateBtnPrimary.isVisible || installStateBtnSecondary.isVisible
    }

    /**
     * Хелпер: установить подзаголовок в блоке онлайн-обновления.
     * Если text пустой/null — скрывает TextView.
     */
    private fun setUpdateSubtitle(text: String?) {
        if (text.isNullOrEmpty()) {
            updateStateSubtitle.isVisible = false
        } else {
            updateStateSubtitle.text = text
            updateStateSubtitle.isVisible = true
        }
    }

    /**
     * Хелпер: установить подзаголовок в блоке локальной установки.
     * Если text пустой/null — скрывает TextView.
     */
    private fun setInstallSubtitle(text: String?) {
        if (text.isNullOrEmpty()) {
            installStateSubtitle.isVisible = false
        } else {
            installStateSubtitle.text = text
            installStateSubtitle.isVisible = true
        }
    }

    // ============================================================
    // ПРОВЕРКА ОБНОВЛЕНИЙ (ОНЛАЙН)
    // ============================================================

    private fun setupUpdateCheck() {
        checkUpdateButton.setOnClickListener {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        // Скрываем блок локальной установки — работаем в контексте онлайн-обновления
        hideInstallState()

        // Показываем блок в состоянии "проверка"
        showUpdateStateChecking()

        lifecycleScope.launch {
            val result = updateManager.checkForUpdates()

            if (result.hasUpdate && result.versionInfo != null) {
                pendingVersionInfo = result.versionInfo
                showUpdateStateAvailable(result.versionInfo)
            } else if (result.error != null) {
                val errorMessage = resolveErrorMessage(result.error)
                showUpdateStateError(errorMessage)
            } else {
                showUpdateStateUpToDate()
            }
        }
    }

    /**
     * Разворачивает текст ошибки в человекочитаемое сообщение
     */
    private fun resolveErrorMessage(error: String): String {
        return when {
            error.contains("UnknownHostException") ||
                    error.contains("ConnectException") ||
                    error.contains("SocketTimeoutException") ||
                    error.contains("Network") ->
                getString(R.string.no_internet_connection)

            error.startsWith(getString(R.string.server_error_prefix) + ":") -> {
                val code = error.replace("[^0-9]".toRegex(), "")
                getString(R.string.server_error, code.toIntOrNull() ?: 0)
            }
            else -> error
        }
    }

    // ============================================================
    // СОСТОЯНИЯ БЛОКА ОНЛАЙН-ОБНОВЛЕНИЯ
    // ============================================================

    /**
     * Состояние: проверка
     */
    private fun showUpdateStateChecking() {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_update_server)
        updateStateIcon.imageTintList = null

        updateStateTitle.text = getString(R.string.checking_updates)
        updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))
        setUpdateSubtitle(getString(R.string.update_state_subtitle_checking))
        updateStateDescription.isVisible = false

        // Прогресс indeterminate: убираем progressTint-логику — показываем бесконечный
        updateStateProgress.isIndeterminate = true
        updateStateProgressContainer.isVisible = true
        updateStateProgressText.isVisible = false

        setUpdateActions()
    }

    /**
     * Состояние: доступно обновление
     */
    private fun showUpdateStateAvailable(versionInfo: VersionInfo) {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_update_server)
        updateStateIcon.imageTintList = null

        val criticalTag = if (versionInfo.isCritical) getString(R.string.critical_tag) else ""

        // Заголовок — «Доступно обновление»
        updateStateTitle.text = getString(R.string.update_state_available_title)
        updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))

        // Подзаголовок — «Новая версия X» + канал, если critical
        val subtitle = buildString {
            append(getString(R.string.update_state_new_version, versionInfo.version))
            if (versionInfo.isCritical) {
                append("  ")
                append(criticalTag)
            }
        }
        setUpdateSubtitle(subtitle)

        // Описание — changelog + текущая версия
        val currentVersionCode = AppInfoHelper.getVersionCode(requireContext())
        val (currentVersionName, currentSuffix) = AppInfoHelper.getSplitVersion(requireContext())

        val description = buildString {
            append(getString(R.string.update_dialog_message, versionInfo.changelog, versionInfo.releaseDate, versionInfo.size))
            append("\n\n")
            append(getString(R.string.update_version_info, versionInfo.version, versionInfo.versionCode.toString()))
            append("\n")
            if (currentSuffix.isNotEmpty()) {
                append(getString(R.string.current_version_info_with_channel, currentVersionName, currentVersionCode.toString(), currentSuffix))
            } else {
                append(getString(R.string.current_version_info, currentVersionName, currentVersionCode.toString()))
            }
        }
        updateStateDescription.text = description
        updateStateDescription.isVisible = true

        updateStateProgress.isIndeterminate = false
        updateStateProgressContainer.isVisible = false
        updateStateProgressText.isVisible = false

        setUpdateActions(
            primaryText = getString(R.string.update_action),
            primaryAction = { downloadAndInstall(versionInfo) },
            secondaryText = getString(R.string.download_apk),
            secondaryAction = { downloadApkOnly(versionInfo) }
        )
    }

    /**
     * Состояние: скачивание
     */
    private fun showUpdateStateDownloading(versionInfo: VersionInfo? = null) {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_download)
        updateStateIcon.imageTintList = null

        updateStateTitle.text = getString(R.string.downloading_title)
        updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))

        // Подзаголовок — версия, если известна
        val version = versionInfo?.version ?: pendingVersionInfo?.version
        if (version != null) {
            setUpdateSubtitle(getString(R.string.update_state_version_label, version))
        } else {
            setUpdateSubtitle(null)
        }

        updateStateDescription.isVisible = false

        updateStateProgress.isIndeterminate = false
        updateStateProgress.progress = 0
        updateStateProgressContainer.isVisible = true
        updateStateProgressText.isVisible = true
        updateStateProgressText.text = getString(R.string.downloading_progress, 0)

        setUpdateActions(
            secondaryText = getString(R.string.cancel),
            secondaryAction = {
                // Просто сворачиваем блок — корутина продолжит скачивание,
                // но UI мы не блокируем. Полноценная отмена требует доработки SystemUpdateManager.
                hideUpdateState()
            }
        )
    }

    /**
     * Состояние: скачивание завершено, готово к установке
     */
    private fun showUpdateStateDownloadComplete(filePath: String, uri: Uri) {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_update_server)
        updateStateIcon.imageTintList = null

        updateStateTitle.text = getString(R.string.download_complete_title)
        updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))

        val version = pendingVersionInfo?.version
        if (version != null) {
            setUpdateSubtitle(getString(R.string.update_state_version_label, version))
        } else {
            setUpdateSubtitle(null)
        }

        updateStateDescription.text = getString(R.string.apk_saved_to, filePath)
        updateStateDescription.isVisible = true

        updateStateProgress.isIndeterminate = false
        updateStateProgressContainer.isVisible = false
        updateStateProgressText.isVisible = false

        pendingApkUri = uri

        setUpdateActions(
            primaryText = getString(R.string.install_action),
            primaryAction = {
                val versionCode = pendingVersionInfo?.versionCode
                if (versionCode != null) {
                    updateManager.installApk(uri, versionCode)
                } else {
                    updateManager.installApk(uri, null)
                }
            },
            secondaryText = getString(R.string.close),
            secondaryAction = { hideUpdateState() }
        )
    }

    /**
     * Состояние: обновлений нет
     */
    private fun showUpdateStateUpToDate() {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_update_server)
        updateStateIcon.imageTintList = null

        updateStateTitle.text = getString(R.string.update_state_up_to_date_title)
        updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))

        // Подзаголовок — «Quty.Launch 0.0.149 Alpha»
        val (versionName, suffix) = AppInfoHelper.getSplitVersion(requireContext())
        val suffixPart = if (suffix.isNotEmpty()) " $suffix" else ""
        setUpdateSubtitle(getString(R.string.update_state_current_version_label, versionName, suffixPart))

        updateStateDescription.isVisible = false

        updateStateProgress.isIndeterminate = false
        updateStateProgressContainer.isVisible = false
        updateStateProgressText.isVisible = false

        setUpdateActions(
            secondaryText = getString(R.string.close),
            secondaryAction = { hideUpdateState() }
        )
    }

    /**
     * Состояние: ошибка
     */
    private fun showUpdateStateError(message: String) {
        updateStateContainer.isVisible = true

        updateStateIcon.setImageResource(R.drawable.ic_update_server)
        // Красный тинт для иконки
        updateStateIcon.imageTintList =
            android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.text_error, null)
            )

        updateStateTitle.text = getString(R.string.update_error)
        updateStateTitle.setTextColor(resources.getColor(R.color.text_error, null))
        setUpdateSubtitle(null)

        updateStateDescription.text = message
        updateStateDescription.isVisible = true

        updateStateProgress.isIndeterminate = false
        updateStateProgressContainer.isVisible = false
        updateStateProgressText.isVisible = false

        setUpdateActions(
            primaryText = getString(R.string.welcome_button_retry),
            primaryAction = { checkForUpdates() },
            secondaryText = getString(R.string.close),
            secondaryAction = { hideUpdateState() }
        )
    }

    // ============================================================
    // СКАЧИВАНИЕ
    // ============================================================

    private fun downloadApkOnly(versionInfo: VersionInfo) {
        showUpdateStateDownloading(versionInfo)

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : SystemUpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    updateStateProgress.isIndeterminate = false
                    updateStateProgress.progress = percent
                    updateStateProgressText.text = getString(R.string.downloading_progress, percent)
                }

                override fun onSuccess(uri: Uri) {
                    val fileName = "${CoreConfig.APK_FILE_PREFIX}-${versionInfo.version}.apk"
                    val file = storageManager.get(StorageDirectory.UPDATES, fileName)
                    showUpdateStateDownloadComplete(file.absolutePath, uri)
                }

                override fun onError(message: String) {
                    showUpdateStateError(
                        getString(R.string.download_error) + ": " + message
                    )
                }
            })
        }
    }

    private fun downloadAndInstall(versionInfo: VersionInfo) {
        showUpdateStateDownloading(versionInfo)

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : SystemUpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    updateStateProgress.isIndeterminate = false
                    updateStateProgress.progress = percent
                    updateStateProgressText.text = getString(R.string.downloading_progress, percent)
                }

                override fun onSuccess(uri: Uri) {
                    val fileName = "${CoreConfig.APK_FILE_PREFIX}-${versionInfo.version}.apk"
                    val file = storageManager.get(StorageDirectory.UPDATES, fileName)

                    // Сразу ставим на установку, но через блок — с кнопкой «Установить» и «Позже»
                    updateStateContainer.isVisible = true
                    updateStateIcon.setImageResource(R.drawable.ic_update_server)
                    updateStateIcon.imageTintList = null

                    updateStateTitle.text = getString(R.string.install_title)
                    updateStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))
                    setUpdateSubtitle(getString(R.string.update_state_version_label, versionInfo.version))

                    updateStateDescription.text = getString(R.string.install_message)
                    updateStateDescription.isVisible = true

                    updateStateProgressContainer.isVisible = false
                    updateStateProgressText.isVisible = false

                    setUpdateActions(
                        primaryText = getString(R.string.install_action),
                        primaryAction = {
                            updateManager.installApk(uri, versionInfo.versionCode)
                        },
                        secondaryText = getString(R.string.dialog_later),
                        secondaryAction = {
                            // Показываем финальное состояние — APK сохранён
                            showUpdateStateDownloadComplete(file.absolutePath, uri)
                        }
                    )
                }

                override fun onError(message: String) {
                    showUpdateStateError(
                        getString(R.string.download_error) + ": " + message
                    )
                }
            })
        }
    }

    // ============================================================
    // ЛОКАЛЬНАЯ УСТАНОВКА APK
    // ============================================================

    private fun setupInstallFromFile() {
        installFromFileButton.setOnClickListener {
            selectApkFile()
        }
    }

    private fun selectApkFile() {
        // Скрываем блок онлайн-обновления — переходим в контекст локальной установки
        hideUpdateState()

        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive"))
            }
            selectApkLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            selectApkAlternative()
        }
    }

    private fun selectApkAlternative() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive"))
            }
            selectApkLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.error_open_file_manager), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Состояние: проверка локального APK
     */
    private fun showInstallStateChecking() {
        installStateContainer.isVisible = true
        installStateIcon.setImageResource(R.drawable.ic_update_folder)
        installStateIcon.imageTintList = null

        installStateTitle.text = getString(R.string.checking_updates)
        installStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))
        setInstallSubtitle(getString(R.string.update_state_subtitle_checking))
        installStateDescription.isVisible = false

        setInstallActions()
    }

    /**
     * Состояние: APK готов к установке
     */
    private fun showInstallStateReady(uri: Uri, version: String) {
        installStateContainer.isVisible = true
        installStateIcon.setImageResource(R.drawable.ic_update_folder)
        installStateIcon.imageTintList = null

        installStateTitle.text = getString(R.string.install_local_apk)
        installStateTitle.setTextColor(getColorFromAttr(R.attr.textPrimaryColor))
        setInstallSubtitle(getString(R.string.update_state_version_label, version))

        installStateDescription.text = getString(R.string.install_local_message, version)
        installStateDescription.isVisible = true

        pendingLocalApkUri = uri

        setInstallActions(
            primaryText = getString(R.string.install_action),
            primaryAction = {
                installApk(uri)
                hideInstallState()
            },
            secondaryText = getString(R.string.cancel),
            secondaryAction = { hideInstallState() }
        )
    }

    /**
     * Состояние: ошибка локального APK
     */
    private fun showInstallStateError(message: String) {
        installStateContainer.isVisible = true
        installStateIcon.setImageResource(R.drawable.ic_update_folder)
        installStateIcon.imageTintList =
            android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.text_error, null)
            )

        installStateTitle.text = getString(R.string.update_error)
        installStateTitle.setTextColor(resources.getColor(R.color.text_error, null))
        setInstallSubtitle(null)

        installStateDescription.text = message
        installStateDescription.isVisible = true

        setInstallActions(
            secondaryText = getString(R.string.close),
            secondaryAction = { hideInstallState() }
        )
    }

    private fun validateAndInstallApk(uri: Uri) {
        lifecycleScope.launch {
            try {
                val packageInfo = getPackageInfoFromUri(uri)

                if (packageInfo == null) {
                    showInstallStateError(getString(R.string.invalid_apk))
                    return@launch
                }

                if (packageInfo.packageName != requireContext().packageName) {
                    showInstallStateError(getString(R.string.apk_validation_failed))
                    return@launch
                }

                val apkVersion = packageInfo.versionName ?: getString(R.string.version_unknown)
                showInstallStateReady(uri, apkVersion)

            } catch (_: Exception) {
                showInstallStateError(getString(R.string.invalid_apk))
            }
        }
    }

    private fun getPackageInfoFromUri(uri: Uri): PackageInfo? {
        return try {
            val pm = requireContext().packageManager
            val file = getFileFromUri(uri)
            if (file != null && file.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(file.absolutePath, 0)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIndex = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val displayNameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)

                    if (dataIndex >= 0) {
                        val path = it.getString(dataIndex)
                        if (!path.isNullOrEmpty()) {
                            return File(path)
                        }
                    }

                    if (displayNameIndex >= 0) {
                        val fileName = it.getString(displayNameIndex)
                        if (!fileName.isNullOrEmpty()) {
                            return File(requireContext().cacheDir, fileName).apply {
                                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                                    outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun installApk(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                "${getString(R.string.install_error)}: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Получить цвет из атрибута темы
     */
    private fun getColorFromAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}