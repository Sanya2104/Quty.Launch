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

class SystemSettingsFragment : Fragment() {

    private lateinit var versionTextView: TextView
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
        updateStatus = view.findViewById(R.id.update_status)
        checkUpdateButton = view.findViewById(R.id.check_update_button)

        updateManager = UpdateManager(requireContext())

        setupVersionInfo()
        setupUpdateCheck()
    }

    private fun setupVersionInfo() {
        val versionText = getCurrentVersionName()
        versionTextView.text = getString(R.string.version_format, versionText.trim())
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
                    // Парсим код ошибки если есть
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

    private fun showUpdateDialog(versionInfo: VersionInfo) {
        val criticalTag = if (versionInfo.isCritical) getString(R.string.critical_tag) else ""

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.update_dialog_title, versionInfo.version, criticalTag))
            .setMessage(getString(R.string.update_dialog_message,
                versionInfo.changelog, versionInfo.releaseDate, versionInfo.size))
            .setPositiveButton(getString(R.string.update_action)) { _, _ ->
                downloadAndInstall(versionInfo)
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

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

                override fun onSuccess(file: java.io.File) {
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

    fun refreshInfo() {
        setupVersionInfo()
        updateStatus.visibility = View.GONE
    }
}