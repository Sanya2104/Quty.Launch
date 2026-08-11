// *** core/fragments/SystemSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.Context
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.managers.SystemUpdateManager
import by.quty.launch.core.managers.VersionInfo
import kotlinx.coroutines.launch
import java.io.File

/**
 * Фрагмент системных настроек
 * Отображает информацию о системе и управление обновлениями
 */
class SystemSettingsFragment : Fragment() {

    private lateinit var versionTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var channelTextView: TextView
    private lateinit var channelContainer: View
    private lateinit var channelDivider: View
    private lateinit var updateStatus: TextView
    private lateinit var installStatus: TextView
    private lateinit var checkUpdateButton: View
    private lateinit var installFromFileButton: View
    private lateinit var updateManager: SystemUpdateManager
    private lateinit var storageManager: StorageManager

    private var versionClickCount = 0
    private var lastClickTime = 0L

    // Параметры активации DevMode (из конфига)
    private val clickTimeoutMs = CoreConfig.DEV_MODE_CLICK_TIMEOUT_MS
    private val clicksToActivate = CoreConfig.DEV_MODE_CLICKS_TO_ACTIVATE

    private var progressToast: Toast? = null

    private val selectApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                installStatus.visibility = View.VISIBLE
                installStatus.text = getString(R.string.checking_updates)
                installStatus.setTextColor(resources.getColor(R.color.text_muted, null))
                validateAndInstallApk(uri)
            }
        } else {
            installStatus.visibility = View.GONE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_system, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        storageManager = StorageManager(requireContext())
        updateManager = SystemUpdateManager(requireContext())

        versionTextView = view.findViewById(R.id.version_text)
        versionCodeTextView = view.findViewById(R.id.version_code_text)
        channelTextView = view.findViewById(R.id.channel_text)
        channelContainer = view.findViewById(R.id.channel_container)
        channelDivider = view.findViewById(R.id.channel_divider)
        updateStatus = view.findViewById(R.id.update_status)
        installStatus = view.findViewById(R.id.install_status)
        checkUpdateButton = view.findViewById(R.id.check_update_button)
        installFromFileButton = view.findViewById(R.id.install_from_file_button)

        setupVersionInfo()
        setupUpdateCheck()
        setupInstallFromFile()
    }

    private fun setupVersionInfo() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            }

            val fullVersionName = packageInfo.versionName ?: getString(R.string.version_unknown)
            val versionCode = packageInfo.longVersionCode

            val (versionName, suffix) = splitVersionName(fullVersionName)

            versionTextView.text = versionName
            versionCodeTextView.text = versionCode.toString()

            if (suffix.isNotEmpty()) {
                channelTextView.text = suffix
                channelContainer.visibility = View.VISIBLE
                channelDivider.visibility = View.VISIBLE
            } else {
                channelContainer.visibility = View.GONE
                channelDivider.visibility = View.GONE
            }

            versionTextView.isClickable = true
            versionTextView.isFocusable = true
            versionTextView.setOnClickListener {
                handleVersionClick()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            versionTextView.text = getString(R.string.version_unknown)
            versionCodeTextView.text = getString(R.string.unknown_code)
            channelContainer.visibility = View.GONE
            channelDivider.visibility = View.GONE
        }
    }

    private fun handleVersionClick() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastClickTime > clickTimeoutMs) {
            versionClickCount = 0
        }

        lastClickTime = currentTime
        versionClickCount++

        val remaining = clicksToActivate - versionClickCount

        if (versionClickCount >= clicksToActivate) {
            progressToast?.cancel()
            progressToast = null
            versionClickCount = 0
            toggleDeveloperMode()
        } else {
            showProgressToast(remaining)
        }
    }

    private fun showProgressToast(remaining: Int) {
        progressToast?.cancel()
        progressToast = Toast.makeText(
            requireContext(),
            getString(R.string.dev_mode_click_count, remaining),
            Toast.LENGTH_SHORT
        )
        progressToast?.show()
    }

    /**
     * Переключает режим разработчика
     */
    private fun toggleDeveloperMode() {
        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        val isCurrentlyEnabled = prefs.getBoolean("developer_mode", false)

        val newState = !isCurrentlyEnabled
        prefs.edit { putBoolean("developer_mode", newState) }

        // Инвалидируем кэш приложений при изменении DevMode
        CacheManager.invalidateCache(requireContext())

        // Логируем действие
        if (newState) {
            Toast.makeText(requireContext(), R.string.dev_mode_activated, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), R.string.dev_mode_deactivated, Toast.LENGTH_SHORT).show()
        }

        // Обновляем UI без перезапуска активности
        refreshActivityWithoutRestart()
    }

    /**
     * Обновляет UI без перезапуска активности
     */
    private fun refreshActivityWithoutRestart() {
        val activity = requireActivity()

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        (activity as? SettingsActivity)?.refreshPagerAdapter()
    }

    private fun splitVersionName(fullVersionName: String): Pair<String, String> {
        if (fullVersionName.isEmpty()) {
            return Pair("", "")
        }

        val separators = listOf("-", "_", " ")
        for (separator in separators) {
            val index = fullVersionName.indexOf(separator)
            if (index > 0 && index < fullVersionName.length - 1) {
                val version = fullVersionName.substring(0, index)
                val suffix = fullVersionName.substring(index + 1)
                return Pair(version, suffix)
            }
        }

        val digitRegex = Regex("^[\\d.]+")
        val match = digitRegex.find(fullVersionName)
        if (match != null) {
            val version = match.value
            val suffix = fullVersionName.substring(version.length)
            if (suffix.isNotEmpty()) {
                return Pair(version, suffix)
            }
        }

        return Pair(fullVersionName, "")
    }

    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            }
            packageInfo.versionName ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun setupUpdateCheck() {
        checkUpdateButton.setOnClickListener {
            checkForUpdates()
        }
    }

    private fun setupInstallFromFile() {
        installFromFileButton.setOnClickListener {
            showInstallOptionsDialog()
        }
    }

    /**
     * Показывает диалог с выбором способа установки
     */
    private fun showInstallOptionsDialog() {
        val options = arrayOf(
            getString(R.string.install_option_file_manager),
            getString(R.string.install_option_downloads)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.install_option_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> selectApkFile()
                    1 -> selectApkFromDownloads()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun selectApkFile() {
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

    private fun selectApkFromDownloads() {
        try {
            val cursor = requireContext().contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("%.apk"),
                "${MediaStore.Downloads.DISPLAY_NAME} ASC"
            )

            val apkList = mutableListOf<Pair<String, Uri>>()
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    apkList.add(Pair(name, uri))
                }
            }

            if (apkList.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_apk_found), Toast.LENGTH_LONG).show()
                return
            }

            val names = apkList.map { it.first }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.select_apk_title))
                .setItems(names) { _, which ->
                    val uri = apkList[which].second
                    installStatus.visibility = View.VISIBLE
                    installStatus.text = getString(R.string.checking_updates)
                    installStatus.setTextColor(resources.getColor(R.color.text_muted, null))
                    validateAndInstallApk(uri)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.error_open_file_manager), Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateAndInstallApk(uri: Uri) {
        lifecycleScope.launch {
            try {
                val packageInfo = getPackageInfoFromUri(uri)

                if (packageInfo == null) {
                    installStatus.text = getString(R.string.invalid_apk)
                    installStatus.setTextColor(resources.getColor(R.color.text_error, null))
                    view?.postDelayed({
                        installStatus.visibility = View.GONE
                    }, 3000)
                    return@launch
                }

                if (packageInfo.packageName != requireContext().packageName) {
                    installStatus.text = getString(R.string.apk_validation_failed)
                    installStatus.setTextColor(resources.getColor(R.color.text_error, null))
                    view?.postDelayed({
                        installStatus.visibility = View.GONE
                    }, 3000)
                    return@launch
                }

                val apkVersion = packageInfo.versionName ?: getString(R.string.version_unknown)
                installStatus.text = getString(R.string.apk_validated_success, apkVersion)
                installStatus.setTextColor(resources.getColor(R.color.status_granted, null))

                view?.postDelayed({
                    installStatus.visibility = View.GONE
                }, 3000)

                showConfirmInstallDialog(uri, apkVersion)

            } catch (_: Exception) {
                installStatus.text = getString(R.string.invalid_apk)
                installStatus.setTextColor(resources.getColor(R.color.text_error, null))
                view?.postDelayed({
                    installStatus.visibility = View.GONE
                }, 3000)
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

    private fun showConfirmInstallDialog(uri: Uri, version: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.install_local_apk))
            .setMessage(getString(R.string.install_local_message, version))
            .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                installApk(uri)
            }
            .setNegativeButton(getString(R.string.dialog_later), null)
            .show()
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

    private fun checkForUpdates() {
        updateStatus.visibility = View.VISIBLE
        updateStatus.text = getString(R.string.checking_updates)
        updateStatus.setTextColor(resources.getColor(R.color.text_muted, null))

        lifecycleScope.launch {
            val result = updateManager.checkForUpdates()

            if (result.hasUpdate && result.versionInfo != null) {
                updateStatus.text = getString(R.string.update_available, result.versionInfo.version)
                updateStatus.setTextColor(resources.getColor(R.color.status_granted, null))
                showUpdateDialog(result.versionInfo)
            } else if (result.error != null) {
                updateStatus.text = getString(R.string.update_error)
                updateStatus.setTextColor(resources.getColor(R.color.text_error, null))

                val errorMessage = when {
                    result.error.contains("UnknownHostException") ||
                            result.error.contains("ConnectException") ||
                            result.error.contains("SocketTimeoutException") ||
                            result.error.contains("Network") ->
                        getString(R.string.no_internet_connection)
                    result.error.startsWith(getString(R.string.server_error_prefix) + ":") -> {
                        val code = result.error.replace("[^0-9]".toRegex(), "")
                        getString(R.string.server_error, code.toIntOrNull() ?: 0)
                    }
                    else -> result.error
                }

                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                view?.postDelayed({
                    updateStatus.visibility = View.GONE
                }, 3000)
            } else {
                updateStatus.text = getString(R.string.no_updates)
                updateStatus.setTextColor(resources.getColor(R.color.text_muted, null))
                view?.postDelayed({
                    updateStatus.visibility = View.GONE
                }, 3000)
            }
        }
    }

    private fun showUpdateDialog(versionInfo: VersionInfo) {
        val criticalTag = if (versionInfo.isCritical) getString(R.string.critical_tag) else ""
        val currentVersionCode = getCurrentVersionCode()
        val currentFullVersionName = getCurrentVersionName()
        val (currentVersionName, currentSuffix) = splitVersionName(currentFullVersionName)

        val messageWithVersionInfo = buildString {
            append(getString(R.string.update_dialog_message,
                versionInfo.changelog, versionInfo.releaseDate, versionInfo.size))
            append("\n\n")
            append(getString(R.string.update_version_info,
                versionInfo.version, versionInfo.versionCode.toString()))
            append("\n")
            if (currentSuffix.isNotEmpty()) {
                append(getString(R.string.current_version_info_with_channel,
                    currentVersionName, currentVersionCode.toString(), currentSuffix))
            } else {
                append(getString(R.string.current_version_info,
                    currentVersionName, currentVersionCode.toString()))
            }
        }

        val dialogBuilder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.update_dialog_title, versionInfo.version, criticalTag))
            .setMessage(messageWithVersionInfo)
            .setPositiveButton(getString(R.string.update_action)) { _, _ ->
                downloadAndInstall(versionInfo)
            }
            .setNegativeButton(getString(R.string.dialog_later), null)

        dialogBuilder.setNeutralButton(getString(R.string.download_apk)) { _, _ ->
            downloadApkOnly(versionInfo)
        }

        dialogBuilder.show()
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            }
            packageInfo.longVersionCode
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    private fun downloadApkOnly(versionInfo: VersionInfo) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.downloading_title))
            .setMessage(getString(R.string.downloading_prepare))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : SystemUpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.downloading_progress, percent))
                }

                override fun onSuccess(uri: Uri) {
                    progressDialog.dismiss()

                    val fileName = "Quty.Launch-${versionInfo.version}.apk"
                    val file = storageManager.get(StorageDirectory.UPDATES, fileName)
                    val message = getString(R.string.apk_saved_to, file.absolutePath)

                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.download_complete_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                            updateManager.installApk(uri, versionInfo.versionCode)
                        }
                        .setNegativeButton(getString(R.string.close), null)
                        .show()
                }

                override fun onError(message: String) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(),
                        getString(R.string.download_error) + ": $message",
                        Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun downloadAndInstall(versionInfo: VersionInfo) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.downloading_title))
            .setMessage(getString(R.string.downloading_prepare))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : SystemUpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.downloading_progress, percent))
                }

                override fun onSuccess(uri: Uri) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_title))
                        .setMessage(getString(R.string.install_message))
                        .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                            updateManager.installApk(uri, versionInfo.versionCode)
                        }
                        .setNegativeButton(getString(R.string.dialog_later), null)
                        .show()
                }

                override fun onError(message: String) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(),
                        getString(R.string.download_error) + ": $message",
                        Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    fun refreshInfo() {
        setupVersionInfo()
        updateStatus.visibility = View.GONE
        installStatus.visibility = View.GONE
    }
}