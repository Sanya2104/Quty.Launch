// *** core/fragments/settings/AboutFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.utilities.AppInfoHelper

/**
 * Фрагмент "О системе" для Настроек
 * Содержит информацию о приложении и устройстве:
 * - шапка: иконка приложения, название, описание;
 * - блок "Приложение": версия (кликабельна — активация DevMode),
 *   код версии, канал, активная оболочка;
 * - блок "Устройство": модель, Android, SDK, производитель;
 * - блок "Контакты": Telegram, GitHub (кликабельны).
 *
 * При активации/деактивации DevMode:
 * - помечает SettingsActivity, что требуется перезапуск;
 * - просит SettingsActivity обновить список меню
 *   (пункт "Разработчикам" появляется/исчезает).
 */
class AboutFragment : Fragment() {

    // ===== Блок "Приложение" =====
    private lateinit var versionRow: View
    private lateinit var versionTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var channelTextView: TextView
    private lateinit var channelContainer: View
    private lateinit var channelDivider: View
    private lateinit var shellTextView: TextView

    // ===== Блок "Устройство" =====
    private lateinit var deviceModelText: TextView
    private lateinit var deviceAndroidText: TextView
    private lateinit var deviceSdkText: TextView
    private lateinit var deviceManufacturerText: TextView

    // ===== Блок "Контакты" =====
    private lateinit var contactTelegramRow: View
    private lateinit var contactGithubRow: View

    private var versionClickCount = 0
    private var lastClickTime = 0L

    // Параметры активации DevMode (из конфига)
    private val clickTimeoutMs = CoreConfig.DEV_MODE_CLICK_TIMEOUT_MS
    private val clicksToActivate = CoreConfig.DEV_MODE_CLICKS_TO_ACTIVATE

    private var progressToast: Toast? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Блок "Приложение"
        versionRow = view.findViewById(R.id.version_row)
        versionTextView = view.findViewById(R.id.version_text)
        versionCodeTextView = view.findViewById(R.id.version_code_text)
        channelTextView = view.findViewById(R.id.channel_text)
        channelContainer = view.findViewById(R.id.channel_container)
        channelDivider = view.findViewById(R.id.channel_divider)
        shellTextView = view.findViewById(R.id.shell_text)

        // Блок "Устройство"
        deviceModelText = view.findViewById(R.id.device_model_text)
        deviceAndroidText = view.findViewById(R.id.device_android_text)
        deviceSdkText = view.findViewById(R.id.device_sdk_text)
        deviceManufacturerText = view.findViewById(R.id.device_manufacturer_text)

        // Блок "Контакты"
        contactTelegramRow = view.findViewById(R.id.contact_telegram_row)
        contactGithubRow = view.findViewById(R.id.contact_github_row)

        setupVersionInfo()
        setupDeviceInfo()
        setupShellInfo()
        setupContacts()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Отменяем показ тоста прогресса DevMode
        progressToast?.cancel()
        progressToast = null
    }

    // ============================================================
    // БЛОК "ПРИЛОЖЕНИЕ"
    // ============================================================

    private fun setupVersionInfo() {
        val versionCode = AppInfoHelper.getVersionCode(requireContext())
        val (versionName, suffix) = AppInfoHelper.getSplitVersion(requireContext())

        if (versionName.isEmpty()) {
            versionTextView.text = getString(R.string.version_unknown)
            versionCodeTextView.text = getString(R.string.unknown_code)
            channelContainer.visibility = View.GONE
            channelDivider.visibility = View.GONE
            return
        }

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

        /*
         * DevMode активируется кликом по ВСЕЙ строке "Версия",
         * а не только по значению версии.
         * Слушатель вешаем на versionRow — контейнер строки,
         * у которого есть ripple-эффект (bg_item_rounded).
         */
        versionRow.isClickable = true
        versionRow.isFocusable = true
        versionRow.setOnClickListener {
            handleVersionClick()
        }
    }

    /**
     * Информация об активной оболочке: "Название Версия".
     * Если оболочка не найдена — "Неизвестно".
     */
    private fun setupShellInfo() {
        try {
            val configManager = ConfigManager(requireContext())
            val shellManager = ShellManager(requireContext(), configManager)
            val shell = shellManager.getActiveShell()

            if (shell == null) {
                shellTextView.text = getString(R.string.unknown)
                return
            }

            val displayName = shell.displayName ?: shell.name
            val version = shell.version

            shellTextView.text = if (!version.isNullOrEmpty()) {
                "$displayName $version"
            } else {
                displayName
            }
        } catch (_: Exception) {
            shellTextView.text = getString(R.string.unknown)
        }
    }

    // ============================================================
    // БЛОК "УСТРОЙСТВО"
    // ============================================================

    private fun setupDeviceInfo() {
        deviceModelText.text = Build.MODEL.ifEmpty { getString(R.string.unknown) }
        deviceAndroidText.text = Build.VERSION.RELEASE.ifEmpty { getString(R.string.unknown) }
        deviceSdkText.text = Build.VERSION.SDK_INT.toString()
        deviceManufacturerText.text = Build.MANUFACTURER.ifEmpty { getString(R.string.unknown) }
    }

    // ============================================================
    // БЛОК "КОНТАКТЫ"
    // ============================================================

    private fun setupContacts() {
        contactTelegramRow.setOnClickListener {
            openUrl(CoreConfig.CONTACT_TELEGRAM_URL)
        }

        contactGithubRow.setOnClickListener {
            openUrl(CoreConfig.CONTACT_GITHUB_URL)
        }
    }

    /**
     * Открывает URL во внешнем приложении (браузер, Telegram и т.п.).
     * При отсутствии подходящего приложения показывает тост.
     */
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

    // ============================================================
    // DEV MODE (клик по строке "Версия")
    // ============================================================

    /**
     * Обрабатывает клик по строке версии — 5 нажатий подряд активируют DevMode.
     * Тайм-аут между кликами — из CoreConfig.DEV_MODE_CLICK_TIMEOUT_MS.
     */
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

    /**
     * Показывает тост «Осталось нажатий: N».
     */
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
     * Переключает режим разработчика.
     *
     * После переключения:
     * - сообщает SettingsActivity, что нужен перезапуск;
     * - просит SettingsActivity перестроить меню,
     *   чтобы пункт "Разработчикам" появился или исчез.
     */
    private fun toggleDeveloperMode() {
        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        val isCurrentlyEnabled = prefs.getBoolean("developer_mode", false)

        val newState = !isCurrentlyEnabled
        prefs.edit {
            putBoolean("developer_mode", newState)

        }

        // Инвалидируем кэш приложений при изменении DevMode
        CacheManager.invalidateCache()

        // Сообщаем SettingsActivity:
        // 1. что требуется перезапуск (при выходе покажется диалог);
        // 2. что нужно перестроить меню (пункт "Разработчикам" появится/исчезнет).
        (activity as? SettingsActivity)?.let { settingsActivity ->
            settingsActivity.markRestartRequired()
            settingsActivity.refreshMenuItems()
        }

        if (newState) {
            Toast.makeText(requireContext(), R.string.dev_mode_activated, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), R.string.dev_mode_deactivated, Toast.LENGTH_SHORT).show()
        }
    }
}