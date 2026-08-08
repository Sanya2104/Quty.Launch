// *** core/fragments/DeveloperSettingsFragment.kt *** //
package by.quty.launch.core.fragments

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.quty.launch.MainActivity
import by.quty.launch.R
import by.quty.launch.SettingsActivity
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.logger.Logger
import by.quty.launch.core.logger.LoggerFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

class DeveloperSettingsFragment : Fragment() {

    private lateinit var configManager: ConfigManager
    private lateinit var shellManager: ShellManager
    private lateinit var storageManager: StorageManager

    // Элементы управления логами
    private lateinit var switchPersist: SwitchCompat
    private lateinit var spinnerMaxFiles: Spinner
    private lateinit var spinnerMaxSize: Spinner
    private lateinit var btnLogsShow: Button
    private lateinit var btnLogsClear: Button
    private lateinit var btnLogsReset: Button

    // Флаг загрузки настроек (чтобы не триггерить apply при инициализации)
    private var isLoadingSettings = false

    // Для отложенного применения настроек
    private var applyHandler: Handler? = null
    private var applyRunnable: Runnable? = null

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
            shellManager = settingsActivity.shellManager
            storageManager = StorageManager(requireContext())
        }

        setupWebViewDebug(view)
        setupShellInfo(view)
        setupSystemInfo(view)
        setupDataManagement(view)
        setupLogsManagement(view)
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
    // 2. ИНФОРМАЦИЯ ОБ ОБОЛОЧКЕ
    // ============================================================

    private fun setupShellInfo(view: View) {
        val manifestBtn = view.findViewById<Button>(R.id.dev_shell_manifest)
        val reloadBtn = view.findViewById<Button>(R.id.dev_shell_reload)

        manifestBtn.setOnClickListener {
            showShellManifest()
        }

        reloadBtn.setOnClickListener {
            shellManager.reloadActiveShell()
            Toast.makeText(requireContext(), R.string.dev_shell_reload_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShellManifest() {
        try {
            val activeShell = shellManager.getActiveShell()
            if (activeShell == null) {
                Toast.makeText(requireContext(), R.string.dev_shell_not_found, Toast.LENGTH_SHORT).show()
                return
            }

            val content = if (activeShell.isAsset) {
                val stream = requireContext().assets.open("shells/${activeShell.name}/manifest.json")
                stream.bufferedReader().use { it.readText() }
            } else {
                // Для кастомных оболочек читаем из ZIP через StorageManager
                val file = File(activeShell.sourcePath)
                if (!file.exists()) {
                    Toast.makeText(requireContext(), R.string.dev_shell_manifest_not_found, Toast.LENGTH_SHORT).show()
                    return
                }

                try {
                    java.util.zip.ZipFile(file).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: run {
                            Toast.makeText(requireContext(), R.string.dev_shell_manifest_not_found, Toast.LENGTH_SHORT).show()
                            return
                        }
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.dev_shell_manifest_not_found, Toast.LENGTH_SHORT).show()
                    return
                }
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
                    .setTitle(R.string.dev_shell_manifest_title)
                    .setMessage(displayText)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (_: Exception) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dev_shell_manifest_title)
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

        modelView.text = Build.MODEL
        androidView.text = Build.VERSION.RELEASE
        sdkView.text = Build.VERSION.SDK_INT.toString()

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

            appVersionView.text = versionName
            appCodeView.text = versionCode.toString()

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
        val shellsSizeView = view.findViewById<TextView>(R.id.dev_shells_size_value)
        val logsSizeView = view.findViewById<TextView>(R.id.dev_logs_size_value)
        val clearDataBtn = view.findViewById<Button>(R.id.dev_clear_data)
        val clearAppsCacheBtn = view.findViewById<Button>(R.id.dev_clear_apps_cache)

        // Обновляем размеры
        updateCacheSize(cacheSizeView)
        updateShellsSize(shellsSizeView)
        updateLogsSize(logsSizeView)

        // Кнопка обновления размера кэша (по клику на строку)
        val cacheSizeRow = view.findViewById<View>(R.id.dev_cache_size_row)
        cacheSizeRow?.setOnClickListener {
            updateCacheSize(cacheSizeView)
            Toast.makeText(requireContext(), R.string.dev_cache_size_updated, Toast.LENGTH_SHORT).show()
        }

        // Кнопка обновления размера оболочек (по клику на строку)
        val shellsSizeRow = view.findViewById<View>(R.id.dev_shells_size_row)
        shellsSizeRow?.setOnClickListener {
            updateShellsSize(shellsSizeView)
            Toast.makeText(requireContext(), R.string.dev_cache_size_updated, Toast.LENGTH_SHORT).show()
        }

        // Кнопка обновления размера логов (по клику на строку)
        val logsSizeRow = view.findViewById<View>(R.id.dev_logs_size_row)
        logsSizeRow?.setOnClickListener {
            updateLogsSize(logsSizeView)
            Toast.makeText(requireContext(), R.string.dev_cache_size_updated, Toast.LENGTH_SHORT).show()
        }

        // 4.2 Очистить кэш приложений
        clearAppsCacheBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_clear_apps_cache)
                .setMessage(R.string.dev_clear_apps_cache_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    clearAppsCache()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

    /**
     * Очищает кэш списка приложений
     */
    private fun clearAppsCache() {
        try {
            lifecycleScope.launch {
                CacheManager.clearCache(requireContext())
                withContext(Dispatchers.Main) {
                    val cacheSizeView = view?.findViewById<TextView>(R.id.dev_cache_size_value)
                    cacheSizeView?.let { updateCacheSize(it) }
                    Toast.makeText(requireContext(), R.string.dev_clear_apps_cache_success, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.dev_error, getString(R.string.dev_unknown_error)), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCacheSize(textView: TextView) {
        val cacheDirSize = storageManager.getDirectorySize(StorageDirectory.CACHE)
        val appCacheSize = requireContext().cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val size = cacheDirSize + appCacheSize
        textView.text = storageManager.formatSize(size)
    }

    private fun updateShellsSize(textView: TextView) {
        val size = storageManager.getDirectorySize(StorageDirectory.SHELLS)
        textView.text = storageManager.formatSize(size)
    }

    private fun updateLogsSize(textView: TextView) {
        val size = storageManager.getDirectorySize(StorageDirectory.LOGS)
        textView.text = storageManager.formatSize(size)
    }

    private fun clearAppData() {
        try {
            lifecycleScope.launch {
                // Очищаем кэш приложения
                requireContext().cacheDir.deleteRecursively()

                // Очищаем SharedPreferences
                val prefs = requireContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                prefs.edit { clear() }

                // Очищаем все данные через StorageManager
                storageManager.remove(StorageDirectory.SHELLS, recursive = true)
                storageManager.remove(StorageDirectory.LOGS, recursive = true)
                storageManager.remove(StorageDirectory.UPDATES, recursive = true)
                storageManager.remove(StorageDirectory.CACHE, recursive = true)
                storageManager.remove(StorageDirectory.TEMP, recursive = true)
                storageManager.remove(StorageDirectory.EXPORTS, recursive = true)
                storageManager.remove(StorageDirectory.BACKUPS, recursive = true)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.dev_clear_data_success, Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        restartApp()
                    }, 1500)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.dev_error, getString(R.string.dev_unknown_error)), Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 5. УПРАВЛЕНИЕ ЛОГАМИ
    // ============================================================

    private fun setupLogsManagement(view: View) {
        switchPersist = view.findViewById(R.id.dev_logs_persist)
        spinnerMaxFiles = view.findViewById(R.id.dev_logs_max_files)
        spinnerMaxSize = view.findViewById(R.id.dev_logs_max_size)
        btnLogsShow = view.findViewById(R.id.dev_logs_show)
        btnLogsClear = view.findViewById(R.id.dev_logs_clear)
        btnLogsReset = view.findViewById(R.id.dev_logs_reset)

        // 1. Сначала создаём адаптеры
        val filesAdapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.dev_logs_files_count,
            android.R.layout.simple_spinner_item
        )
        filesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMaxFiles.adapter = filesAdapter

        val sizeAdapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.dev_logs_file_size,
            android.R.layout.simple_spinner_item
        )
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMaxSize.adapter = sizeAdapter

        // 2. Загружаем сохранённые настройки
        loadLogsSettingsWithoutTrigger()

        // 3. Устанавливаем слушатели
        spinnerMaxFiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isLoadingSettings) return
                val value = parent?.getItemAtPosition(position).toString().toIntOrNull() ?: 5
                applyLogsSettings(maxFiles = value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMaxSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isLoadingSettings) return
                val value = parent?.getItemAtPosition(position).toString().replace(" MB", "").toIntOrNull() ?: 5
                applyLogsSettings(maxSizeMB = value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchPersist.setOnCheckedChangeListener { _, isChecked ->
            val prefs = requireContext().getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("persist_enabled", isChecked) }

            LoggerFile.setPersistEnabled(isChecked)

            if (isChecked) {
                Toast.makeText(requireContext(), R.string.dev_logs_persist_enabled, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.dev_logs_persist_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        btnLogsShow.setOnClickListener {
            showLogsDialog()
        }

        btnLogsClear.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_logs_clear)
                .setMessage(R.string.dev_logs_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    clearLogs()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        btnLogsReset.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_logs_reset)
                .setMessage(R.string.dev_logs_reset_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    resetLogsSettings()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Загружает настройки логов из SharedPreferences БЕЗ триггера слушателей
     */
    private fun loadLogsSettingsWithoutTrigger() {
        isLoadingSettings = true

        val prefs = requireContext().getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)

        val persistEnabled = prefs.getBoolean("persist_enabled", true)
        val maxFiles = prefs.getInt("max_files", 5)
        val maxSizeMB = prefs.getInt("max_size_mb", 5)

        switchPersist.isChecked = persistEnabled

        val filesPos = when (maxFiles) {
            3 -> 0
            5 -> 1
            10 -> 2
            else -> 1
        }
        spinnerMaxFiles.setSelection(filesPos, false)

        val sizePos = when (maxSizeMB) {
            3 -> 0
            5 -> 1
            10 -> 2
            else -> 1
        }
        spinnerMaxSize.setSelection(sizePos, false)

        isLoadingSettings = false
    }

    /**
     * Применяет настройки логов
     */
    private fun applyLogsSettings(maxFiles: Int = -1, maxSizeMB: Int = -1) {
        if (isLoadingSettings) return

        applyRunnable?.let { applyHandler?.removeCallbacks(it) }

        applyRunnable = Runnable {
            val prefs = requireContext().getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)

            val currentMaxFiles = if (maxFiles > 0) maxFiles else prefs.getInt("max_files", 5)
            val currentMaxSizeMB = if (maxSizeMB > 0) maxSizeMB else prefs.getInt("max_size_mb", 5)

            prefs.edit {
                putInt("max_files", currentMaxFiles)
                putInt("max_size_mb", currentMaxSizeMB)
            }

            LoggerFile.reconfigure(currentMaxFiles, currentMaxSizeMB)

            val logsSizeView = view?.findViewById<TextView>(R.id.dev_logs_size_value)
            logsSizeView?.let { updateLogsSize(it) }

            Toast.makeText(requireContext(), R.string.dev_logs_applied, Toast.LENGTH_SHORT).show()
        }

        applyHandler = Handler(Looper.getMainLooper())
        applyHandler?.postDelayed(applyRunnable!!, 300)
    }

    /**
     * Сбрасывает настройки логов к значениям по умолчанию
     */
    private fun resetLogsSettings() {
        val prefs = requireContext().getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)

        prefs.edit {
            putBoolean("persist_enabled", true)
            putInt("max_files", 5)
            putInt("max_size_mb", 5)
        }

        isLoadingSettings = true
        switchPersist.isChecked = true
        spinnerMaxFiles.setSelection(1, false)
        spinnerMaxSize.setSelection(1, false)
        isLoadingSettings = false

        LoggerFile.init(storageManager, 5, 5, true, requireContext())

        Toast.makeText(requireContext(), R.string.dev_logs_reset_success, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // 5.7 ДИАЛОГИ УПРАВЛЕНИЯ ЛОГАМИ
    // ============================================================

    /**
     * Показывает диалог со списком файлов логов
     */
    private fun showLogsDialog() {
        val logFiles = storageManager.list(
            directory = StorageDirectory.LOGS,
            extension = "json"
        ).sortedByDescending { it.lastModified() }

        if (logFiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.dev_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = logFiles.map { file ->
            val size = storageManager.formatSize(file.length())
            "${file.name} ($size)"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dev_logs_title)
            .setItems(fileNames) { _, which ->
                val selectedFile = logFiles[which]
                showLogFileActionsDialog(selectedFile)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Показывает диалог с действиями для выбранного файла лога
     */
    private fun showLogFileActionsDialog(file: File) {
        val options = arrayOf(
            getString(R.string.dev_logs_view),
            getString(R.string.dev_logs_share),
            getString(R.string.dev_logs_delete),
            getString(R.string.dev_logs_copy)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLogFileContent(file)
                    1 -> shareLogFile(file)
                    2 -> confirmDeleteLogFile(file)
                    3 -> copyLogFileContent(file)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Копирует содержимое файла лога в буфер обмена
     */
    private fun copyLogFileContent(file: File) {
        try {
            lifecycleScope.launch {
                val content = storageManager.getString(file)
                withContext(Dispatchers.Main) {
                    if (content.isNullOrEmpty()) {
                        Toast.makeText(requireContext(), R.string.dev_logs_empty, Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("LogFile", content)
                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(requireContext(), R.string.dev_logs_copied, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_logs_copy_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Показывает содержимое файла лога
     */
    private fun showLogFileContent(file: File) {
        try {
            lifecycleScope.launch {
                val content = storageManager.getString(file)
                withContext(Dispatchers.Main) {
                    if (content.isNullOrEmpty()) {
                        Toast.makeText(requireContext(), R.string.dev_logs_empty, Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    val displayContent = if (content.length > 5000) {
                        content.take(5000) + getString(R.string.dev_logs_file_truncated, storageManager.formatSize(file.length()))
                    } else {
                        content
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.dev_logs_file_title, file.name))
                        .setMessage(displayContent)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.dev_logs_copy) { _, _ ->
                            copyLogFileContent(file)
                        }
                        .setNegativeButton(R.string.dev_logs_delete) { _, _ ->
                            confirmDeleteLogFile(file)
                        }
                        .show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_logs_delete_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Открывает диалог для отправки файла лога
     */
    private fun shareLogFile(file: File) {
        try {
            val uri = storageManager.getUri(file)
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.dev_logs_share_error, Toast.LENGTH_SHORT).show()
                return
            }

            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.dev_logs_share_title)))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_logs_share_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Подтверждение удаления файла лога
     */
    private fun confirmDeleteLogFile(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dev_logs_delete)
            .setMessage(getString(R.string.dev_logs_delete_confirm, file.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteLogFile(file)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Удаляет файл лога
     */
    private fun deleteLogFile(file: File) {
        try {
            lifecycleScope.launch {
                val deleted = storageManager.remove(file)
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toast.makeText(requireContext(), R.string.dev_logs_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.dev_logs_delete_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_logs_delete_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Очищает все файлы логов и логи в памяти
     */
    private fun clearLogs() {
        try {
            lifecycleScope.launch {
                Logger.clear()
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.dev_logs_cleared, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.dev_logs_clear_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 6. ИНСТРУМЕНТЫ
    // ============================================================

    private fun setupTools(view: View) {
        val restartBtn = view.findViewById<Button>(R.id.dev_restart_app)

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