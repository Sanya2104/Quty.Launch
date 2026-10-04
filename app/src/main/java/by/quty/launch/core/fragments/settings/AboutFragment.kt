// *** core/fragments/settings/AboutFragment.kt *** //
package by.quty.launch.core.fragments.settings

import android.content.Context
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
import by.quty.launch.core.utilities.AppInfoHelper

/**
 * Фрагмент "О системе" для Настроек
 * Содержит информацию о приложении и устройстве:
 * - версия, код версии, канал сборки;
 * - активация DevMode по клику на версию (5 раз).
 *
 * При активации/деактивации DevMode:
 * - помечает SettingsActivity, что требуется перезапуск;
 * - просит SettingsActivity обновить список меню
 *   (пункт "Разработчикам" появляется/исчезает).
 *
 * TODO: Добавить в будущем:
 * - Иконка приложения
 * - Модель устройства, Android версия, SDK уровень
 * - Информация об активной оболочке
 */
class AboutFragment : Fragment() {

    private lateinit var versionTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var channelTextView: TextView
    private lateinit var channelContainer: View
    private lateinit var channelDivider: View

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

        versionTextView = view.findViewById(R.id.version_text)
        versionCodeTextView = view.findViewById(R.id.version_code_text)
        channelTextView = view.findViewById(R.id.channel_text)
        channelContainer = view.findViewById(R.id.channel_container)
        channelDivider = view.findViewById(R.id.channel_divider)

        setupVersionInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Отменяем показ тоста прогресса DevMode
        progressToast?.cancel()
        progressToast = null
    }

    // ============================================================
    // ИНФОРМАЦИЯ О ВЕРСИИ + DEV MODE
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

        versionTextView.isClickable = true
        versionTextView.isFocusable = true
        versionTextView.setOnClickListener {
            handleVersionClick()
        }
    }

    /**
     * Обрабатывает клик по версии — 5 нажатий подряд активируют DevMode.
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
        prefs.edit { putBoolean("developer_mode", newState) }

        // Инвалидируем кэш приложений при изменении DevMode
        CacheManager.invalidateCache(requireContext())

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