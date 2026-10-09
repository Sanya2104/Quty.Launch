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
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import by.quty.launch.MainActivity
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Фрагмент "Разработчикам" для Настроек.
 *
 * Доступен только в DevMode (флаг в developer_prefs).
 * Содержит:
 * - Секция "Оболочка": просмотр manifest.json (с копированием), перезагрузка оболочки
 * - Секция "Управление данными": очистка кэша WebView, сброс онбординга
 * - Секция "Инструменты": перезапуск приложения
 *
 * Действия, требующие перезапуска приложения (reload shell, clear WebView cache,
 * reset onboarding), помечаются через
 * SettingsActivity.markRestartRequired() — при выходе из настроек пользователю
 * будет предложен диалог перезапуска.
 */
class DeveloperFragment : Fragment() {

    // Менеджеры
    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager

    // UI — Оболочка
    private lateinit var shellManifestRow: View
    private lateinit var shellReloadRow: View

    // UI — Данные
    private lateinit var clearWebViewCacheRow: View
    private lateinit var resetOnboardingRow: View

    // UI — Инструменты
    private lateinit var restartAppRow: View

    // JSON парсер для manifest.json
    private val json = Json { prettyPrint = true }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_developer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Инициализация менеджеров
        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
        } ?: run {
            configManager = ConfigManager(requireContext())
        }
        shellManager = ShellManager(requireContext(), configManager)
        storageManager = StorageManager(requireContext())

        // Инициализация UI-ссылок
        shellManifestRow = view.findViewById(R.id.dev_shell_manifest_row)
        shellReloadRow = view.findViewById(R.id.dev_shell_reload_row)
        clearWebViewCacheRow = view.findViewById(R.id.dev_clear_webview_cache_row)
        resetOnboardingRow = view.findViewById(R.id.dev_reset_onboarding_row)
        restartAppRow = view.findViewById(R.id.dev_restart_app_row)

        setupShellSection()
        setupDataSection()
        setupToolsSection()
    }

    // ============================================================
    // СЕКЦИЯ 1: ОБОЛОЧКА
    // ============================================================

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

            // Перезагрузка оболочки требует перезапуска приложения,
            // чтобы WebView подхватил новую версию.
            (activity as? SettingsActivity)?.markRestartRequired()
        }
    }

    /**
     * Показывает диалог с содержимым manifest.json активной оболочки.
     */
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

            // Пытаемся распарсить и отформатировать JSON
            val displayText = try {
                val parsed = json.decodeFromString<JsonObject>(content)
                json.encodeToString(JsonObject.serializer(), parsed)
            } catch (_: Exception) {
                content
            }

            // Инфлейтим кастомный диалог
            val dialogView = layoutInflater.inflate(R.layout.dialog_dev_manifest, null)

            val dialog = AlertDialog.Builder(
                requireContext(),
                R.style.Theme_QutyLaunch_AlertDialog_Transparent
            )
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            // Заполняем содержимое
            val contentTextView: TextView = dialogView.findViewById(R.id.dev_manifest_content)
            contentTextView.text = displayText

            // Кнопка закрытия (крестик)
            dialogView.findViewById<ImageButton>(R.id.dev_manifest_close_button).setOnClickListener {
                dialog.dismiss()
            }

            // Кнопка "Копировать"
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

    /**
     * Читает содержимое manifest.json.
     */
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
    // СЕКЦИЯ 2: УПРАВЛЕНИЕ ДАННЫМИ
    // ============================================================

    private fun setupDataSection() {
        clearWebViewCacheRow.setOnClickListener {
            clearWebViewCache()
        }

        resetOnboardingRow.setOnClickListener {
            showResetOnboardingDialog()
        }
    }

    /**
     * Очищает кэш WebView: cacheDir + clearCache + clearHistory + clearFormData.
     */
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

    /**
     * Диалог 1: подтверждение сброса онбординга (danger).
     */
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

    /**
     * Сбрасывает флаг onboarding_completed.
     *
     * ВАЖНО: выставляем флаг force_show_onboarding = true.
     * Без него WelcomeActivity при запуске сразу увидит, что все
     * разрешения уже выданы, и мгновенно закроется (finishAndGoToMain),
     * не показав экран пользователю.
     *
     * С флагом WelcomeActivity НЕ автозапускает Main и показывает UI
     * (экран приветствия со списком разрешений и кнопкой "Начать").
     * Флаг снимается в WelcomeActivity.finishAndGoToMain().
     */
    private fun performResetOnboarding() {
        val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            remove("onboarding_completed")
            putBoolean("force_show_onboarding", true)
        }

        (activity as? SettingsActivity)?.markRestartRequired()

        showRestartAfterResetDialog()
    }

    /**
     * Диалог 2: перезапуск после сброса онбординга.
     */
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
    // СЕКЦИЯ 3: ИНСТРУМЕНТЫ
    // ============================================================

    private fun setupToolsSection() {
        restartAppRow.setOnClickListener {
            showRestartAppDialog()
        }
    }

    /**
     * Диалог подтверждения перезапуска приложения.
     */
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

    /**
     * Перезапускает приложение — открывает MainActivity с флагом CLEAR_TASK.
     */
    private fun restartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        requireActivity().finish()
    }
}