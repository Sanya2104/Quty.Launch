// *** core/fragments/SystemSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.UpdateManager
import by.quty.launch.core.VersionInfo
import kotlinx.coroutines.launch
import android.provider.MediaStore
import androidx.core.net.toUri
import android.content.pm.PackageInfo
import java.io.File

class SystemSettingsFragment : Fragment() {

    private lateinit var versionTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var channelTextView: TextView
    private lateinit var updateStatus: TextView
    private lateinit var installStatus: TextView
    private lateinit var checkUpdateButton: View
    private lateinit var installFromFileButton: View
    private lateinit var updateManager: UpdateManager

    // Регистрируем ActivityResult для выбора файла (замена startActivityForResult)
    private val selectApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                installStatus.visibility = View.VISIBLE
                installStatus.text = getString(R.string.checking_updates)
                installStatus.setTextColor(resources.getColor(android.R.color.darker_gray, null))
                validateAndInstallApk(uri)
            }
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

        versionTextView = view.findViewById(R.id.version_text)
        versionCodeTextView = view.findViewById(R.id.version_code_text)
        channelTextView = view.findViewById(R.id.channel_text)
        updateStatus = view.findViewById(R.id.update_status)
        installStatus = view.findViewById(R.id.install_status)
        checkUpdateButton = view.findViewById(R.id.check_update_button)
        installFromFileButton = view.findViewById(R.id.install_from_file_button)

        updateManager = UpdateManager(requireContext())

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

            // Разделяем versionName на основную версию и суффикс
            val (versionName, suffix) = splitVersionName(fullVersionName)

            versionTextView.text = getString(R.string.version_format, versionName)
            versionCodeTextView.text = getString(R.string.version_code_format, versionCode.toString())

            // Отображаем канал, если есть суффикс
            if (suffix.isNotEmpty()) {
                channelTextView.text = getString(R.string.channel_format, suffix)
                channelTextView.visibility = View.VISIBLE
            } else {
                channelTextView.visibility = View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            versionTextView.text = getString(R.string.version_format, getString(R.string.version_unknown))
            versionCodeTextView.text = getString(R.string.version_code_format, "?")
            channelTextView.visibility = View.GONE
        }
    }

    /**
     * Разделяет versionName на основную версию и суффикс
     * Например: "0.0.37-Alpha" -> ("0.0.37", "Alpha")
     *           "0.0.37Alpha" -> ("0.0.37", "Alpha")  <- важно!
     *           "0.0.37" -> ("0.0.37", "")
     */
    private fun splitVersionName(fullVersionName: String): Pair<String, String> {
        // Если строка пустая
        if (fullVersionName.isEmpty()) {
            return Pair("", "")
        }

        // 1. Пытаемся найти разделители: дефис, подчёркивание, пробел
        val separators = listOf("-", "_", " ")
        for (separator in separators) {
            val index = fullVersionName.indexOf(separator)
            if (index > 0 && index < fullVersionName.length - 1) {
                val version = fullVersionName.substring(0, index)
                val suffix = fullVersionName.substring(index + 1)
                return Pair(version, suffix)
            }
        }

        // 2. Если разделитель не найден, пробуем найти границу между цифрами и буквами
        // Пример: "0.0.37Alpha" -> "0.0.37" + "Alpha"
        val digitRegex = Regex("^[\\d.]+")
        val match = digitRegex.find(fullVersionName)
        if (match != null) {
            val version = match.value
            val suffix = fullVersionName.substring(version.length)
            if (suffix.isNotEmpty()) {
                return Pair(version, suffix)
            }
        }

        // 3. Если ничего не подошло — возвращаем как есть
        return Pair(fullVersionName, "")
    }

    /**
     * Получение названия текущей версии через PackageManager
     */
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
            selectApkFile()
        }
    }

    private fun selectApkFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive"))
            }
            selectApkLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.error_open_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateAndInstallApk(uri: Uri) {
        lifecycleScope.launch {
            try {
                val packageInfo = getPackageInfoFromUri(uri)

                if (packageInfo == null) {
                    installStatus.text = getString(R.string.invalid_apk)
                    installStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    view?.postDelayed({
                        installStatus.visibility = View.GONE
                    }, 3000)
                    return@launch
                }

                if (packageInfo.packageName != requireContext().packageName) {
                    installStatus.text = getString(R.string.apk_validation_failed)
                    installStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    view?.postDelayed({
                        installStatus.visibility = View.GONE
                    }, 3000)
                    return@launch
                }

                val apkVersion = packageInfo.versionName ?: getString(R.string.version_unknown)
                installStatus.text = getString(R.string.apk_validated_success, apkVersion)
                installStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))

                view?.postDelayed({
                    installStatus.visibility = View.GONE
                }, 3000)

                showConfirmInstallDialog(uri, apkVersion)

            } catch (_: Exception) {
                installStatus.text = getString(R.string.invalid_apk)
                installStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
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
            .setNegativeButton(getString(R.string.later), null)
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
        updateStatus.setTextColor(resources.getColor(android.R.color.darker_gray, null))

        lifecycleScope.launch {
            val result = updateManager.checkForUpdates()

            if (result.hasUpdate && result.versionInfo != null) {
                updateStatus.text = getString(R.string.update_available, result.versionInfo.version)
                updateStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                showUpdateDialog(result.versionInfo)
            } else if (result.error != null) {
                updateStatus.text = getString(R.string.update_error)
                updateStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                val errorMessage = if (result.error.startsWith("Ошибка сервера:")) {
                    val code = result.error.replace("[^0-9]".toRegex(), "")
                    getString(R.string.server_error, code.toIntOrNull() ?: 0)
                } else {
                    result.error
                }
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                view?.postDelayed({
                    updateStatus.visibility = View.GONE
                }, 3000)
            } else {
                updateStatus.text = getString(R.string.no_updates)
                updateStatus.setTextColor(resources.getColor(android.R.color.darker_gray, null))
                view?.postDelayed({
                    updateStatus.visibility = View.GONE
                }, 3000)
            }
        }
    }

    /**
     * Показ диалога обновления с кнопкой "Скачать APK"
     */
    private fun showUpdateDialog(versionInfo: VersionInfo) {
        val criticalTag = if (versionInfo.isCritical) getString(R.string.critical_tag) else ""

        // Получаем текущий versionCode для отображения
        val currentVersionCode = getCurrentVersionCode()
        val currentFullVersionName = getCurrentVersionName()
        val (currentVersionName, currentSuffix) = splitVersionName(currentFullVersionName)

        // Формируем сообщение с информацией о версиях
        val messageWithVersionInfo = buildString {
            append(getString(R.string.update_dialog_message,
                versionInfo.changelog, versionInfo.releaseDate, versionInfo.size))
            append("\n\n")
            append(getString(R.string.update_version_info,
                versionInfo.version, versionInfo.versionCode.toString()))
            append("\n")
            // Показываем текущую версию с каналом, если есть
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
            .setNegativeButton(getString(R.string.later), null)

        // Добавляем нейтральную кнопку "Скачать APK"
        dialogBuilder.setNeutralButton(getString(R.string.download_apk)) { _, _ ->
            downloadApkOnly(versionInfo)
        }

        dialogBuilder.show()
    }

    /**
     * Получение текущего versionCode
     */
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

    /**
     * Скачать APK без автоматической установки
     */
    private fun downloadApkOnly(versionInfo: VersionInfo) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.downloading_title))
            .setMessage(getString(R.string.downloading_prepare))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : UpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.downloading_progress, percent))
                }

                override fun onSuccess(uri: Uri) {
                    progressDialog.dismiss()
                    val fileName = "Quty.Launch-${versionInfo.version}.apk"
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val message = getString(R.string.download_complete, "$downloadsDir/$fileName")

                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.download_complete_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.open_folder)) { _, _ ->
                            openDownloadsFolder()
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

    /**
     * Скачать и установить APK
     */
    private fun downloadAndInstall(versionInfo: VersionInfo) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.downloading_title))
            .setMessage(getString(R.string.downloading_prepare))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            updateManager.downloadApk(versionInfo, object : UpdateManager.DownloadListener {
                override fun onProgress(percent: Int) {
                    progressDialog.setMessage(getString(R.string.downloading_progress, percent))
                }

                override fun onSuccess(uri: Uri) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_title))
                        .setMessage(getString(R.string.install_message))
                        .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                            updateManager.installApk(uri)
                        }
                        .setNegativeButton(getString(R.string.later), null)
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

    /**
     * Открыть папку с загрузками (улучшенная версия для всех Android версий)
     */
    private fun openDownloadsFolder() {
        try {
            // Способ 1: Найти и открыть последний скачанный APK
            try {
                val cursor = requireContext().contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("Quty.Launch-%.apk"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC LIMIT 1"
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                        return
                    }
                }
            } catch (_: Exception) {
                // Пробуем следующий способ
            }

            // Способ 2: Открыть папку Download через DocumentsUI
            try {
                val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/root")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Пробуем следующий способ
            }

            // Способ 3: Использовать ACTION_OPEN_DOCUMENT_TREE
            try {
                val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    setDataAndType(uri, "vnd.android.document/root")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Пробуем следующий способ
            }

            // Способ 4: Показать Toast с путём
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            Toast.makeText(
                requireContext(),
                "✅ APK сохранён в:\n${downloadsDir?.absolutePath}",
                Toast.LENGTH_LONG
            ).show()

        } catch (_: Exception) {
            // В случае любой ошибки показываем Toast
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            Toast.makeText(
                requireContext(),
                "✅ APK сохранён в:\n${downloadsDir?.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun refreshInfo() {
        setupVersionInfo()
        updateStatus.visibility = View.GONE
        installStatus.visibility = View.GONE
    }
}