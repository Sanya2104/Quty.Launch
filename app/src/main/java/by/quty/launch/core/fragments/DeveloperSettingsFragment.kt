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
import java.text.SimpleDateFormat
import java.util.*

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
        setupTestTools(view)
        setupExperimental(view)
    }

    // ============================================================
    // 1. ОТЛАДКА WEBVIEW
    // ============================================================

    private fun setupWebViewDebug(view: View) {
        val debugToggle = view.findViewById<SwitchCompat>(R.id.dev_webview_debug)
        val bordersToggle = view.findViewById<SwitchCompat>(R.id.dev_webview_borders)
        val fpsToggle = view.findViewById<SwitchCompat>(R.id.dev_webview_fps)
        val clearCacheBtn = view.findViewById<Button>(R.id.dev_clear_cache)

        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)

        debugToggle.isChecked = prefs.getBoolean("webview_debug", false)
        bordersToggle.isChecked = prefs.getBoolean("webview_borders", false)
        fpsToggle.isChecked = prefs.getBoolean("webview_fps", false)

        // 1.1 Отладка WebView
        debugToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("webview_debug", isChecked) }
            if (isChecked) {
                WebView.setWebContentsDebuggingEnabled(true)
                Toast.makeText(requireContext(), "Отладка WebView включена\nПодключитесь через Chrome DevTools", Toast.LENGTH_LONG).show()
            } else {
                WebView.setWebContentsDebuggingEnabled(false)
                Toast.makeText(requireContext(), "Отладка WebView выключена", Toast.LENGTH_SHORT).show()
            }
        }

        // 1.3 Показать границы WebView
        bordersToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("webview_borders", isChecked) }
            Toast.makeText(
                requireContext(),
                if (isChecked) "Границы WebView включены" else "Границы WebView выключены",
                Toast.LENGTH_SHORT
            ).show()
            // TODO: Реализовать показ границ WebView
        }

        // 1.4 Показать FPS
        fpsToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("webview_fps", isChecked) }
            Toast.makeText(
                requireContext(),
                if (isChecked) "FPS включён" else "FPS выключён",
                Toast.LENGTH_SHORT
            ).show()
            // TODO: Реализовать показ FPS
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
            Toast.makeText(requireContext(), R.string.dev_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 2. ИНФОРМАЦИЯ О ТЕМЕ
    // ============================================================

    private fun setupThemeInfo(view: View) {
        val themePathBtn = view.findViewById<Button>(R.id.dev_theme_path)
        val manifestBtn = view.findViewById<Button>(R.id.dev_theme_manifest)
        val reloadBtn = view.findViewById<Button>(R.id.dev_theme_reload)
        val assetsOnlyToggle = view.findViewById<SwitchCompat>(R.id.dev_theme_assets_only)

        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        assetsOnlyToggle.isChecked = prefs.getBoolean("assets_only_themes", false)

        // 2.1 Путь к активной теме
        themePathBtn.setOnClickListener {
            val activeTheme = themeManager.getActiveTheme()
            val path = if (activeTheme?.isAsset == true) {
                "assets/themes/${activeTheme.name}/"
            } else {
                activeTheme?.sourcePath ?: "Тема не найдена"
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_theme_path_title)
                .setMessage(path)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 2.2 Показать manifest.json
        manifestBtn.setOnClickListener {
            showThemeManifest()
        }

        // 2.3 Перезагрузить тему
        reloadBtn.setOnClickListener {
            themeManager.reloadActiveTheme()
            Toast.makeText(requireContext(), R.string.dev_theme_reload_success, Toast.LENGTH_SHORT).show()
        }

        // 2.4 Режим "Только assets"
        assetsOnlyToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("assets_only_themes", isChecked) }
            Toast.makeText(
                requireContext(),
                if (isChecked) "Режим 'Только assets' включен" else "Режим 'Только assets' выключен",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showThemeManifest() {
        try {
            val activeTheme = themeManager.getActiveTheme()
            if (activeTheme == null) {
                Toast.makeText(requireContext(), "Активная тема не найдена", Toast.LENGTH_SHORT).show()
                return
            }

            val content = if (activeTheme.isAsset) {
                val stream = requireContext().assets.open("themes/${activeTheme.name}/manifest.json")
                stream.bufferedReader().use { it.readText() }
            } else {
                val file = File(activeTheme.sourcePath)
                val parent = file.parentFile
                val manifestFile = File(parent, "manifest.json")
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
                    formatted.take(5000) + "\n\n... (обрезано)"
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
            Toast.makeText(requireContext(), R.string.dev_error, Toast.LENGTH_SHORT).show()
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

            val versionName = packageInfo.versionName ?: getString(R.string.dev_unknown)
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
            appVersionView.text = getString(R.string.dev_unknown)
            appCodeView.text = getString(R.string.dev_unknown)
            channelView.text = getString(R.string.dev_unknown)
        }
    }

    // ============================================================
    // 4. УПРАВЛЕНИЕ ДАННЫМИ
    // ============================================================

    private fun setupDataManagement(view: View) {
        val dataPathBtn = view.findViewById<Button>(R.id.dev_data_path)
        val themesPathBtn = view.findViewById<Button>(R.id.dev_themes_path)
        val cacheSizeBtn = view.findViewById<Button>(R.id.dev_cache_size)
        val exportLogsBtn = view.findViewById<Button>(R.id.dev_export_logs)
        val clearDataBtn = view.findViewById<Button>(R.id.dev_clear_data)

        // 4.1 Путь к данным
        dataPathBtn.setOnClickListener {
            val path = requireContext().applicationInfo.dataDir
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_data_path_title)
                .setMessage(path)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 4.2 Путь к темам
        themesPathBtn.setOnClickListener {
            val path = File(Environment.getExternalStorageDirectory(), "QutyThemes").absolutePath
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_themes_path_title)
                .setMessage(path)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 4.3 Размер кэша
        cacheSizeBtn.setOnClickListener {
            val cacheSize = getCacheSize()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_cache_size_title)
                .setMessage(formatSize(cacheSize))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 4.4 Экспорт логов
        exportLogsBtn.setOnClickListener {
            exportLogs()
        }

        // 4.5 Очистить данные
        clearDataBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_clear_data)
                .setMessage(R.string.dev_clear_data_confirm)
                .setPositiveButton("Очистить") { _, _ ->
                    clearAppData()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun getCacheSize(): Long {
        var size = 0L
        val cacheDir = requireContext().cacheDir
        if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
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

    private fun exportLogs() {
        try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val fileName = "quty_launch_logs_$timestamp.txt"
            val file = File(requireContext().cacheDir, fileName)

            val logs = StringBuilder()
            logs.append("=== Quty.Launch Logs ===\n")
            logs.append("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            logs.append("Device: ${Build.MODEL}\n")
            logs.append("Android: ${Build.VERSION.RELEASE}\n")
            logs.append("SDK: ${Build.VERSION.SDK_INT}\n")

            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().packageManager.getPackageInfo(
                        requireContext().packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                }
            } catch (_: Exception) {
                null
            }

            logs.append("App Version: ${packageInfo?.versionName ?: getString(R.string.dev_unknown)}\n")
            logs.append("App Code: ${packageInfo?.longVersionCode ?: "?"}\n")
            logs.append("===========================\n\n")

            val activeTheme = themeManager.getActiveTheme()
            if (activeTheme != null) {
                logs.append("--- Active Theme ---\n")
                logs.append("  Name: ${activeTheme.name}\n")
                logs.append("  Display Name: ${activeTheme.displayName ?: "—"}\n")
                logs.append("  Is Asset: ${activeTheme.isAsset}\n")
                logs.append("  Is Custom: ${activeTheme.isCustom}\n")
                logs.append("  Version: ${activeTheme.version ?: "—"}\n")
                logs.append("  Author: ${activeTheme.author ?: "—"}\n")
                logs.append("  Source Path: ${activeTheme.sourcePath}\n\n")
            }

            logs.append("--- Settings ---\n")
            logs.append("  Active theme: ${configManager.getActiveTheme()}\n")
            logs.append("  Orientation: ${configManager.getOrientation()}\n")
            logs.append("  Fullscreen: ${configManager.isFullscreenEnabled()}\n")
            logs.append("  Strict mode: ${configManager.isStrictModeEnabled()}\n\n")

            logs.append("--- System ---\n")
            logs.append("  Cache size: ${formatSize(getCacheSize())}\n")
            logs.append("  Data dir: ${requireContext().applicationInfo.dataDir}\n")
            logs.append("  Themes dir: ${File(Environment.getExternalStorageDirectory(), "QutyThemes").absolutePath}\n")

            file.writeText(logs.toString())
            Toast.makeText(requireContext(), getString(R.string.dev_export_logs_success, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAppData() {
        try {
            requireContext().cacheDir.deleteRecursively()
            val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            prefs.edit { clear() }
            val themesDir = File(Environment.getExternalStorageDirectory(), "QutyThemes")
            if (themesDir.exists()) {
                themesDir.deleteRecursively()
            }
            Toast.makeText(requireContext(), R.string.dev_clear_data_success, Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                restartApp()
            }, 1500)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 5. ТЕСТОВЫЕ ИНСТРУМЕНТЫ
    // ============================================================

    private fun setupTestTools(view: View) {
        val testNotificationBtn = view.findViewById<Button>(R.id.dev_test_notification)
        val testErrorBtn = view.findViewById<Button>(R.id.dev_test_error)
        val resetOnboardingBtn = view.findViewById<Button>(R.id.dev_reset_onboarding)
        val restartBtn = view.findViewById<Button>(R.id.dev_restart_app)

        // 5.1 Тестовое уведомление
        testNotificationBtn.setOnClickListener {
            Toast.makeText(requireContext(), R.string.dev_test_notification_message, Toast.LENGTH_LONG).show()
        }

        // 5.2 Симулировать ошибку
        testErrorBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_test_error_title)
                .setMessage(R.string.dev_test_error_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 5.4 Сброс онбординга
        resetOnboardingBtn.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("onboarding_completed", false) }
            Toast.makeText(requireContext(), R.string.dev_reset_onboarding_success, Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                restartApp()
            }, 1000)
        }

        // 5.3 Перезапустить приложение
        restartBtn.setOnClickListener {
            restartApp()
        }
    }

    // ============================================================
    // 6. ЭКСПЕРИМЕНТАЛЬНЫЕ ФУНКЦИИ
    // ============================================================

    private fun setupExperimental(view: View) {
        val experimentalApiToggle = view.findViewById<SwitchCompat>(R.id.dev_experimental_api)
        val enableAnimationsToggle = view.findViewById<SwitchCompat>(R.id.dev_enable_animations)

        val prefs = requireContext().getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)

        experimentalApiToggle.isChecked = prefs.getBoolean("experimental_api", false)
        enableAnimationsToggle.isChecked = prefs.getBoolean("enable_animations", true)

        // 6.1 Экспериментальный API
        experimentalApiToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("experimental_api", isChecked) }
            Toast.makeText(
                requireContext(),
                if (isChecked) "Экспериментальный API включен" else "Экспериментальный API выключен",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 6.2 Включить анимации
        enableAnimationsToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("enable_animations", isChecked) }
            applyAnimationsSetting(isChecked)
            Toast.makeText(
                requireContext(),
                if (isChecked) "Анимации включены" else "Анимации выключены",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyAnimationsSetting(enabled: Boolean) {
        try {
            val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("animations_enabled", enabled) }
        } catch (_: Exception) {
            // Игнорируем
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