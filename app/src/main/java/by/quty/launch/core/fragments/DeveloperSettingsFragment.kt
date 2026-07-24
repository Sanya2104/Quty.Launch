// *** core/fragments/DeveloperSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import by.quty.launch.MainActivity
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.ConfigManager
import by.quty.launch.core.ThemeManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

class DeveloperSettingsFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var themeManager: ThemeManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_developer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.let { settingsActivity ->
            configManager = settingsActivity.configManager
            themeManager = settingsActivity.themeManager
        }

        setupWebViewDebug(view)
        setupThemeInfo(view)
        setupSystemInfo(view)
        setupDataManagement(view)
        setupTools(view)
    }

    // ============================================================
    // 1. ОТЛАДКА WEBVIEW
    // ============================================================

    private fun setupWebViewDebug(view: View) {
        val debugToggle = view.findViewById<SwitchCompat>(R.id.dev_webview_debug)
        val clearCacheBtn = view.findViewById<Button>(R.id.dev_clear_cache)

        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        debugToggle.isChecked = prefs.getBoolean("webview_debug", false)

        // 1.1 Отладка WebView
        debugToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("webview_debug", isChecked) }
            if (isChecked) {
                WebView.setWebContentsDebuggingEnabled(true)
                Toast.makeText(requireContext(), R.string.dev_webview_debug_enabled, Toast.LENGTH_LONG).show()
            } else {
                WebView.setWebContentsDebuggingEnabled(false)
                Toast.makeText(requireContext(), R.string.dev_webview_debug_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        // 1.2 Очистить кэш WebView
        clearCacheBtn.setOnClickListener {
            clearWebViewCache()
        }
    }

    private fun clearWebViewCache() {
        try {
            requireContext().cacheDir.deleteRecursively()
            val webView = WebView(requireContext())
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            Toast.makeText(requireContext(), R.string.dev_webview_clear_cache_success, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.dev_error, getString(R.string.dev_unknown_error)), Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 2. ИНФОРМАЦИЯ О ТЕМЕ
    // ============================================================

    private fun setupThemeInfo(view: View) {
        val manifestBtn = view.findViewById<Button>(R.id.dev_theme_manifest)
        val reloadBtn = view.findViewById<Button>(R.id.dev_theme_reload)

        // 2.2 Показать manifest.json
        manifestBtn.setOnClickListener {
            showThemeManifest()
        }

        // 2.3 Перезагрузить тему
        reloadBtn.setOnClickListener {
            themeManager.reloadActiveTheme()
            Toast.makeText(requireContext(), R.string.dev_theme_reload_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showThemeManifest() {
        try {
            val activeTheme = themeManager.getActiveTheme()
            if (activeTheme == null) {
                Toast.makeText(requireContext(), R.string.dev_theme_not_found, Toast.LENGTH_SHORT).show()
                return
            }

            val content = if (activeTheme.isAsset) {
                val stream = requireContext().assets.open("themes/${activeTheme.name}/manifest.json")
                stream.bufferedReader().use { it.readText() }
            } else {
                val themeDir = File(requireContext().filesDir, "themes/active/${activeTheme.name}")
                val manifestFile = File(themeDir, "manifest.json")

                if (!manifestFile.exists()) {
                    Toast.makeText(requireContext(), R.string.dev_theme_manifest_not_found, Toast.LENGTH_SHORT).show()
                    return
                }
                manifestFile.readText()
            }

            try {
                val json = Json { prettyPrint = true }
                val parsed = json.decodeFromString<JsonObject>(content)
                val formatted = json.encodeToString(parsed)
                val displayText = if (formatted.length > 5000) {
                    formatted.take(5000) + "\n\n" + getString(R.string.dev_truncated)
                } else {
                    formatted
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dev_theme_manifest_title)
                    .setMessage(displayText)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (_: Exception) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dev_theme_manifest_title)
                    .setMessage(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.dev_error, getString(R.string.dev_unknown_error)), Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 3. СИСТЕМНАЯ ИНФОРМАЦИЯ
    // ============================================================

    private fun setupSystemInfo(view: View) {
        val modelView = view.findViewById<TextView>(R.id.dev_device_model_value)
        val androidView = view.findViewById<TextView>(R.id.dev_android_version_value)
        val sdkView = view.findViewById<TextView>(R.id.dev_sdk_level_value)
        val appVersionView = view.findViewById<TextView>(R.id.dev_app_version_value)
        val appCodeView = view.findViewById<TextView>(R.id.dev_app_code_value)
        val channelView = view.findViewById<TextView>(R.id.dev_app_channel_value)

        // 3.1 Модель устройства
        modelView.text = Build.MODEL

        // 3.2 Версия Android
        androidView.text = Build.VERSION.RELEASE

        // 3.3 SDK уровень
        sdkView.text = Build.VERSION.SDK_INT.toString()

        // 3.4-3.6 Информация о приложении
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

            val versionName = packageInfo.versionName ?: getString(R.string.unknown)
            val versionCode = packageInfo.longVersionCode

            // 3.4 Версия приложения
            appVersionView.text = versionName

            // 3.5 Код версии
            appCodeView.text = versionCode.toString()

            // 3.6 Канал сборки
            val channel = when {
                versionName.contains("debug", ignoreCase = true) -> "Debug"
                versionName.contains("beta", ignoreCase = true) -> "Beta"
                versionName.contains("rc", ignoreCase = true) -> "RC"
                versionName.contains("release", ignoreCase = true) -> "Release"
                else -> "Stable"
            }
            channelView.text = channel

        } catch (_: Exception) {
            appVersionView.text = getString(R.string.unknown)
            appCodeView.text = getString(R.string.unknown)
            channelView.text = getString(R.string.unknown)
        }
    }

    // ============================================================
    // 4. УПРАВЛЕНИЕ ДАННЫМИ
    // ============================================================

    private fun setupDataManagement(view: View) {
        val cacheSizeView = view.findViewById<TextView>(R.id.dev_cache_size_value)
        val themesSizeView = view.findViewById<TextView>(R.id.dev_themes_size_value)
        val clearDataBtn = view.findViewById<Button>(R.id.dev_clear_data)

        // Обновляем размеры
        updateCacheSize(cacheSizeView)
        updateThemesSize(themesSizeView)

        // Кнопка обновления размера кэша (по клику на строку)
        val cacheSizeRow = view.findViewById<View>(R.id.dev_cache_size_row)
        cacheSizeRow?.setOnClickListener {
            updateCacheSize(cacheSizeView)
            Toast.makeText(requireContext(), R.string.dev_cache_size_updated, Toast.LENGTH_SHORT).show()
        }

        // Кнопка обновления размера тем (по клику на строку)
        val themesSizeRow = view.findViewById<View>(R.id.dev_themes_size_row)
        themesSizeRow?.setOnClickListener {
            updateThemesSize(themesSizeView)
            Toast.makeText(requireContext(), R.string.dev_cache_size_updated, Toast.LENGTH_SHORT).show()
        }

        // 4.1 Очистить данные
        clearDataBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_clear_data)
                .setMessage(R.string.dev_clear_data_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    clearAppData()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateCacheSize(textView: TextView) {
        val size = getCacheSize()
        textView.text = formatSize(size)
    }

    private fun updateThemesSize(textView: TextView) {
        val size = getThemesSize()
        textView.text = formatSize(size)
    }

    private fun getCacheSize(): Long {
        var size = 0L
        val cacheDir = requireContext().cacheDir
        if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        }
        return size
    }

    private fun getThemesSize(): Long {
        var size = 0L
        // Новая структура: Quty.Launch/Themes/
        val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
        val themesDir = File(appDir, "Themes")

        if (themesDir.exists()) {
            themesDir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        }
        return size
    }

    private fun formatSize(size: Long): String {
        val locale = Locale.US
        return when {
            size >= 1024 * 1024 * 1024 -> String.format(locale, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format(locale, "%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format(locale, "%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    private fun clearAppData() {
        try {
            requireContext().cacheDir.deleteRecursively()
            val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            prefs.edit { clear() }

            // Новая структура: Quty.Launch/Themes/
            val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
            if (appDir.exists()) {
                appDir.deleteRecursively()
            }

            Toast.makeText(requireContext(), R.string.dev_clear_data_success, Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                restartApp()
            }, 1500)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.dev_error, getString(R.string.dev_unknown_error)), Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 5. ИНСТРУМЕНТЫ
    // ============================================================

    private fun setupTools(view: View) {
        val restartBtn = view.findViewById<Button>(R.id.dev_restart_app)

        // 5.3 Перезапустить приложение
        restartBtn.setOnClickListener {
            restartApp()
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private fun restartApp() {
        val intent = android.content.Intent(requireContext(), MainActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        requireActivity().finish()
    }
}