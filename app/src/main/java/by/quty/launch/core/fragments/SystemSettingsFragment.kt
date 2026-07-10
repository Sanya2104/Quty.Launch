// *** core/fragments/SystemSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.R
import by.quty.launch.core.UpdateManager
import by.quty.launch.core.VersionInfo
import kotlinx.coroutines.launch
import java.io.File

class SystemSettingsFragment : Fragment() {

    private lateinit var versionTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var channelTextView: TextView
    private lateinit var updateStatus: TextView
    private lateinit var checkUpdateButton: View
    private lateinit var updateManager: UpdateManager

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
        checkUpdateButton = view.findViewById(R.id.check_update_button)

        updateManager = UpdateManager(requireContext())

        setupVersionInfo()
        setupUpdateCheck()
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
     * Например: "1.2.0-beta" -> ("1.2.0", "beta")
     *           "1.2.0" -> ("1.2.0", "")
     */
    private fun splitVersionName(fullVersionName: String): Pair<String, String> {
        // Ищем разделитель: дефис, подчёркивание или пробел
        val separators = listOf("-", "_", " ")
        for (separator in separators) {
            val index = fullVersionName.indexOf(separator)
            if (index > 0 && index < fullVersionName.length - 1) {
                val version = fullVersionName.substring(0, index)
                val suffix = fullVersionName.substring(index + 1)
                return Pair(version, suffix)
            }
        }
        // Если разделитель не найден
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

                override fun onSuccess(file: File) {
                    progressDialog.dismiss()

                    val message = getString(R.string.download_complete, file.absolutePath)
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

                override fun onSuccess(file: File) {
                    progressDialog.dismiss()

                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_title))
                        .setMessage(getString(R.string.install_message))
                        .setPositiveButton(getString(R.string.install_action)) { _, _ ->
                            updateManager.installApk(file)
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
     * Открыть папку с загрузками
     */
    private fun openDownloadsFolder() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (downloadsDir != null && downloadsDir.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    downloadsDir
                )
                intent.setDataAndType(uri, "resource/folder")
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Папка с загрузками не найдена", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            Toast.makeText(requireContext(), "APK сохранён в: ${downloadsDir?.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    fun refreshInfo() {
        setupVersionInfo()
        updateStatus.visibility = View.GONE
    }
}