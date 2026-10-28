// *** core/fragments/settings/DeveloperFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import by.quty.launch.BaseActivity
import by.quty.launch.MainActivity
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.managers.SystemUpdateManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * DevMode-фрагмент: manifest shell, reload, видимость Терминала/Логгера, сброс данных, инструменты.
 */
class DeveloperFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager
    private lateinit var updateManager: SystemUpdateManager

    private lateinit var shellManifestRow: View
    private lateinit var shellReloadRow: View

    private lateinit var showTerminalSwitch: SwitchCompat
    private lateinit var showLoggerSwitch: SwitchCompat

    private lateinit var clearWebViewCacheRow: View
    private lateinit var resetOnboardingRow: View

    private lateinit var resetUpdateTimerRow: View
    private lateinit var restartAppRow: View

    private val json = Json { prettyPrint = true }

    private val devPrefs by lazy {
        requireContext().getSharedPreferences(PREFS_DEVELOPER, Context.MODE_PRIVATE)
    }

    /** Создаёт view фрагмента. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_developer, container, false)
    }

    /** Находит view, создаёт менеджеры и настраивает секции. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
        } ?: run {
            configManager = ConfigManager(requireContext())
        }
        shellManager = ShellManager(requireContext(), configManager)
        storageManager = StorageManager(requireContext())
        updateManager = SystemUpdateManager(requireContext())

        shellManifestRow = view.findViewById(R.id.dev_shell_manifest_row)
        shellReloadRow = view.findViewById(R.id.dev_shell_reload_row)
        showTerminalSwitch = view.findViewById(R.id.dev_show_terminal_switch)
        showLoggerSwitch = view.findViewById(R.id.dev_show_logger_switch)
        clearWebViewCacheRow = view.findViewById(R.id.dev_clear_webview_cache_row)
        resetOnboardingRow = view.findViewById(R.id.dev_reset_onboarding_row)
        resetUpdateTimerRow = view.findViewById(R.id.dev_reset_update_timer_row)
        restartAppRow = view.findViewById(R.id.dev_restart_app_row)

        setupShellSection()
        setupAppsSection()
        setupDataSection()
        setupToolsSection()
    }

    // ============================================================
    // СЕКЦИЯ 1: ОБОЛОЧКА
    // ============================================================

    /** Настраивает секцию «Оболочка»: manifest и reload. */
    private fun setupShellSection() {
        shellManifestRow.setOnClickListener {
            showShellManifestDialog()
        }

        shellReloadRow.setOnClickListener {
            shellManager.reloadActiveShell()
            Toast.makeText(
                requireContext(),
                R.string.dev_shell_reload_success,
                Toast.LENGTH_SHORT
            ).show()

            (activity as? SettingsActivity)?.markRestartRequired()
        }
    }

    /** Диалог с manifest.json активной оболочки + копирование. */
    private fun showShellManifestDialog() {
        try {
            val activeShell = shellManager.getActiveShell()
            if (activeShell == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.dev_shell_not_found,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val content = readManifestContent(activeShell.name, activeShell.isAsset, activeShell.sourcePath)
                ?: run {
                    Toast.makeText(
                        requireContext(),
                        R.string.dev_shell_manifest_not_found,
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }

            val displayText = try {
                val parsed = json.decodeFromString<JsonObject>(content)
                json.encodeToString(JsonObject.serializer(), parsed)
            } catch (_: Exception) {
                content
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_dev_manifest, null)

            val dialog = AlertDialog.Builder(
                requireContext(),
                R.style.Theme_QutyLaunch_AlertDialog_Transparent
            )
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            val contentTextView: TextView = dialogView.findViewById(R.id.dev_manifest_content)
            contentTextView.text = displayText

            dialogView.findViewById<ImageButton>(R.id.dev_manifest_close_button).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<Button>(R.id.dev_manifest_copy_button).setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("manifest.json", displayText)
                @Suppress("UsePropertyAccessSyntax")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    R.string.dev_shell_manifest_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }

            dialog.show()

        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.dev_error, getString(R.string.dev_unknown_error)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Читает manifest.json (assets или ZIP). */
    private fun readManifestContent(shellName: String, isAsset: Boolean, sourcePath: String): String? {
        return try {
            if (isAsset) {
                val stream = requireContext().assets.open("shells/$shellName/manifest.json")
                stream.bufferedReader().use { it.readText() }
            } else {
                val file = File(sourcePath)
                if (!file.exists()) return null

                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return null
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // СЕКЦИЯ 2: ВИДИМОСТЬ ТЕРМИНАЛА И ЛОГГЕРА
    // ============================================================

    /**
     * Переключатели видимости Терминала/Логгера в GetApps.
     * При изменении — invalidateCache + markRestartRequired.
     */
    private fun setupAppsSection() {
        showTerminalSwitch.isChecked = devPrefs.getBoolean(KEY_SHOW_TERMINAL, false)
        showLoggerSwitch.isChecked = devPrefs.getBoolean(KEY_SHOW_LOGGER, false)

        showTerminalSwitch.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit { putBoolean(KEY_SHOW_TERMINAL, isChecked) }
            onAppVisibilityChanged()
        }

        showLoggerSwitch.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit { putBoolean(KEY_SHOW_LOGGER, isChecked) }
            onAppVisibilityChanged()
        }
    }

    /** Инвалидирует кэш и помечает restart при смене видимости. */
    private fun onAppVisibilityChanged() {
        CacheManager.invalidateCache()
        (activity as? SettingsActivity)?.markRestartRequired()
    }

    // ============================================================
    // СЕКЦИЯ 3: ДАННЫЕ
    // ============================================================

    /** Настраивает секцию «Управление данными». */
    private fun setupDataSection() {
        clearWebViewCacheRow.setOnClickListener {
            clearWebViewCache()
        }

        resetOnboardingRow.setOnClickListener {
            showResetOnboardingDialog()
        }
    }

    /** Очистка WebView cache/history/formData/ssl. */
    private fun clearWebViewCache() {
        try {
            requireContext().cacheDir.deleteRecursively()

            val webView = WebView(requireContext())
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()

            Toast.makeText(
                requireContext(),
                R.string.dev_webview_clear_cache_success,
                Toast.LENGTH_SHORT
            ).show()

            (activity as? SettingsActivity)?.markRestartRequired()

        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.dev_error, getString(R.string.dev_unknown_error)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Показывает диалог подтверждения сброса онбординга. */
    private fun showResetOnboardingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dev_confirm, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView: TextView = dialogView.findViewById(R.id.dev_confirm_title)
        titleView.setText(R.string.dev_reset_onboarding_dialog_title)

        val messageView: TextView = dialogView.findViewById(R.id.dev_confirm_message)
        messageView.setText(R.string.dev_reset_onboarding_dialog_message)

        val okButton: Button = dialogView.findViewById(R.id.dev_confirm_ok_button)
        okButton.setText(R.string.dev_reset_onboarding_confirm)

        dialogView.findViewById<Button>(R.id.dev_confirm_cancel_button).setOnClickListener {
            dialog.dismiss()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            performResetOnboarding()
        }

        dialog.show()
    }

    /** Сбрасывает onboarding_completed + ставит force_show_onboarding. */
    private fun performResetOnboarding() {
        val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            remove("onboarding_completed")
            putBoolean("force_show_onboarding", true)
        }

        (activity as? SettingsActivity)?.markRestartRequired()

        showRestartAfterResetDialog()
    }

    /** Диалог перезапуска после сброса онбординга. */
    private fun showRestartAfterResetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dev_restart, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        val titleView: TextView = dialogView.findViewById(R.id.dev_restart_title)
        titleView.setText(R.string.dev_reset_onboarding_restart_title)

        val messageView: TextView = dialogView.findViewById(R.id.dev_restart_message)
        messageView.setText(R.string.dev_reset_onboarding_restart_message)

        val okButton: Button = dialogView.findViewById(R.id.dev_restart_ok_button)
        okButton.setText(R.string.dev_reset_onboarding_restart_confirm)

        dialogView.findViewById<Button>(R.id.dev_restart_cancel_button).setOnClickListener {
            dialog.dismiss()
        }

        okButton.setOnClickListener {
            dialog.dismiss()
            restartApp()
        }

        dialog.show()
    }

    // ============================================================
    // СЕКЦИЯ 4: ИНСТРУМЕНТЫ
    // ============================================================

    /** Настраивает инструменты: таймер обновлений и рестарт. */
    private fun setupToolsSection() {
        resetUpdateTimerRow.setOnClickListener {
            resetUpdateTimer()
        }

        restartAppRow.setOnClickListener {
            showRestartAppDialog()
        }
    }

    /** Сбрасывает lastCheckTime и кэш update. */
    private fun resetUpdateTimer() {
        updateManager.setLastCheckTime(0L)
        updateManager.clearCachedUpdate()

        Toast.makeText(
            requireContext(),
            R.string.dev_reset_update_timer_success,
            Toast.LENGTH_LONG
        ).show()
    }

    /** Показывает диалог подтверждения перезапуска приложения. */
    private fun showRestartAppDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dev_restart, null)

        val dialog = AlertDialog.Builder(
            requireContext(),
            R.style.Theme_QutyLaunch_AlertDialog_Transparent
        )
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

        dialogView.findViewById<Button>(R.id.dev_restart_cancel_button).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.dev_restart_ok_button).setOnClickListener {
            dialog.dismiss()
            restartApp()
        }

        dialog.show()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    /** Перезапуск через BaseActivity, если доступен. */
    private fun restartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val baseActivity = activity as? BaseActivity
        if (baseActivity != null) {
            baseActivity.startActivityAnimated(intent)
            baseActivity.finishAnimated()
        } else {
            startActivity(intent)
            requireActivity().finish()
        }
    }

    companion object {
        private const val PREFS_DEVELOPER = "developer_prefs"
        private const val KEY_SHOW_TERMINAL = "show_terminal_in_apps"
        private const val KEY_SHOW_LOGGER = "show_logger_in_apps"
    }
}