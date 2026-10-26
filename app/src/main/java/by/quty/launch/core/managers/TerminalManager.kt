// *** core/managers/TerminalManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebViewCompat
import by.quty.launch.MainActivity
import by.quty.launch.R
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.ColorSchemeModel
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.model.TerminalCommandModel
import by.quty.launch.core.utilities.AppInfoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Менеджер терминала Quty.Launch.
 *
 * Терминал — внутренняя консоль ядра. Он НЕ запускает системные процессы,
 * работает только с состоянием самого Quty.Launch через существующие менеджеры.
 *
 * Синтаксис команд:
 * - простые: help, version, status, clear, exit
 * - составные: shell.info, api.call, prefs.set (одно слово через точку)
 * - с аргументами: set theme "dark", api.call "LaunchApp" '{"packageName":"..."}'
 *
 * Аргументы с пробелами оборачиваются в двойные ИЛИ одинарные кавычки.
 * Одинарные удобны, когда внутри нужно передать JSON с двойными кавычками.
 * Экранирование кавычек внутри кавычек не поддерживается.
 *
 * Write-команды (изменяющие состояние) требуют активного DevMode —
 * флаг developer_mode в SharedPreferences "developer_prefs".
 * Write-команды также помечаются флагом isWrite — Activity по нему
 * выставляет needsRestart и при закрытии предлагает перезапуск.
 */
object TerminalManager {

    /**
     * Ширина колонки в выводе команд (выравнивание ключей по левому краю).
     */
    private const val STATUS_PAD_WIDTH = 13

    /**
     * Префикс сообщения об ошибке. TerminalActivity различает ERROR/OUTPUT
     * по `result.startsWith("!")`.
     */
    private const val ERROR_PREFIX = "!"

    // ============================================================
    // КОНСТАНТЫ
    // ============================================================

    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_FORCE_SHOW_ONBOARDING = "force_show_onboarding"

    private const val PREFS_LAUNCHER = "launcher_prefs"
    private const val PREFS_DEVELOPER = "developer_prefs"

    /**
     * Порог свободного места для doctor.
     * Если меньше — предупреждение.
     */
    private const val DOCTOR_MIN_FREE_SPACE_BYTES = 10L * 1024 * 1024  // 10 MB

    /**
     * Максимальный «возраст» кэша приложений (мс), после которого — предупреждение.
     * Синхронизировано с CoreConfig.CACHE_APPS_VALIDITY_MS.
     */
    private const val DOCTOR_CACHE_MAX_AGE_MS = CoreConfig.CACHE_APPS_VALIDITY_MS

    /**
     * Возвращает список зарегистрированных команд.
     */
    fun getCommands(context: Context): List<TerminalCommandModel> {
        val configManager = ConfigManager(context)

        return listOf(
            // ===== Служебные =====
            TerminalCommandModel(
                name = "help",
                descriptionRes = R.string.terminal_cmd_help_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { helpHandler(context, it) }
            ),
            TerminalCommandModel(
                name = "version",
                descriptionRes = R.string.terminal_cmd_version_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { versionHandler(context) }
            ),
            TerminalCommandModel(
                name = "status",
                descriptionRes = R.string.terminal_cmd_status_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { statusHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "clear",
                descriptionRes = R.string.terminal_cmd_clear_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { "" }
            ),
            TerminalCommandModel(
                name = "exit",
                descriptionRes = R.string.terminal_cmd_exit_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { "" }
            ),

            // ===== Информация о ядре =====
            TerminalCommandModel(
                name = "about",
                descriptionRes = R.string.terminal_cmd_about_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { aboutHandler(context) }
            ),
            TerminalCommandModel(
                name = "device",
                descriptionRes = R.string.terminal_cmd_device_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { deviceHandler(context) }
            ),
            TerminalCommandModel(
                name = "shell.info",
                descriptionRes = R.string.terminal_cmd_shell_info_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { shellInfoHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "shell.list",
                descriptionRes = R.string.terminal_cmd_shell_list_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { shellListHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "theme",
                descriptionRes = R.string.terminal_cmd_theme_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { themeHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "lang",
                descriptionRes = R.string.terminal_cmd_lang_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { langHandler(configManager) }
            ),
            TerminalCommandModel(
                name = "orientation",
                descriptionRes = R.string.terminal_cmd_orientation_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { orientationHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "devmode",
                descriptionRes = R.string.terminal_cmd_devmode_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { devModeHandler(context) }
            ),
            TerminalCommandModel(
                name = "storage",
                descriptionRes = R.string.terminal_cmd_storage_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { storageHandler(context) }
            ),

            // ===== Диагностика =====
            TerminalCommandModel(
                name = "doctor",
                descriptionRes = R.string.terminal_cmd_doctor_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { doctorHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "memory",
                descriptionRes = R.string.terminal_cmd_memory_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { memoryHandler() }
            ),
            TerminalCommandModel(
                name = "webview",
                descriptionRes = R.string.terminal_cmd_webview_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { webViewHandler(context) }
            ),
            TerminalCommandModel(
                name = "screen",
                descriptionRes = R.string.terminal_cmd_screen_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { screenHandler(context) }
            ),

            // ===== API =====
            TerminalCommandModel(
                name = "api.list",
                descriptionRes = R.string.terminal_cmd_api_list_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { apiListHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "api.call",
                descriptionRes = R.string.terminal_cmd_api_call_desc,
                requireDevMode = true,
                isWrite = false,
                handler = { apiCallHandler(context, it) }
            ),

            // ===== Служебные для тестов =====
            TerminalCommandModel(
                name = "echo",
                descriptionRes = R.string.terminal_cmd_echo_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { echoHandler(it) }
            ),

            // ===== История =====
            TerminalCommandModel(
                name = "history",
                descriptionRes = R.string.terminal_cmd_history_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { historyHandler(context, it) }
            ),
            TerminalCommandModel(
                name = "history.clear",
                descriptionRes = R.string.terminal_cmd_history_clear_desc,
                requireDevMode = false,
                isWrite = false,
                handler = { historyClearHandler(context) }
            ),

            // ===== Управление состоянием (write, DevMode) =====
            TerminalCommandModel(
                name = "set",
                descriptionRes = R.string.terminal_cmd_set_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { setHandler(context, it) }
            ),
            TerminalCommandModel(
                name = "reload.shell",
                descriptionRes = R.string.terminal_cmd_reload_shell_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { reloadShellHandler(context, configManager) }
            ),
            TerminalCommandModel(
                name = "clear.webview",
                descriptionRes = R.string.terminal_cmd_clear_webview_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { clearWebViewHandler(context) }
            ),
            TerminalCommandModel(
                name = "clear.cache",
                descriptionRes = R.string.terminal_cmd_clear_cache_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { clearCacheHandler() }
            ),
            TerminalCommandModel(
                name = "reset.onboarding",
                descriptionRes = R.string.terminal_cmd_reset_onboarding_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { resetOnboardingHandler(context) }
            ),
            TerminalCommandModel(
                name = "restart",
                descriptionRes = R.string.terminal_cmd_restart_desc,
                requireDevMode = true,
                isWrite = true,
                handler = { restartHandler(context) }
            )
        )
    }

    /**
     * Проверяет, активен ли DevMode.
     */
    fun isDevModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_DEVELOPER, Context.MODE_PRIVATE)
        return prefs.getBoolean("developer_mode", false)
    }

    /**
     * Разбирает строку ввода на имя команды и список аргументов.
     */
    fun parse(input: String): ParsedCommand? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null

        val name = tokens.first()
        val args = tokens.drop(1)

        return ParsedCommand(name, args)
    }

    /**
     * Токенизация строки с поддержкой двойных И одинарных кавычек.
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null

        for (ch in input) {
            when {
                quoteChar == null && (ch == '"' || ch == '\'') -> {
                    quoteChar = ch
                }
                quoteChar != null && ch == quoteChar -> {
                    quoteChar = null
                }
                ch == ' ' && quoteChar == null -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    /**
     * Выполняет команду по имени.
     *
     * ВАЖНО: все ошибки возвращаются с префиксом "!" — TerminalActivity
     * различает ERROR/OUTPUT именно по этому префиксу.
     */
    suspend fun execute(context: Context, parsed: ParsedCommand): String {
        val commands = getCommands(context)
        val command = commands.find { it.name == parsed.name }
            ?: return errorMessage(context.getString(R.string.terminal_error_unknown_command, parsed.name))

        if (command.requireDevMode && !isDevModeEnabled(context)) {
            return errorMessage(context.getString(R.string.terminal_error_devmode_required))
        }

        return try {
            command.handler(parsed.args)
        } catch (e: Exception) {
            errorMessage(
                context.getString(R.string.terminal_error_execution, e.message ?: "Unknown error")
            )
        }
    }

    /**
     * Возвращает true, если команда является write-командой.
     */
    fun isWriteCommand(context: Context, name: String): Boolean {
        return getCommands(context).find { it.name == name }?.isWrite == true
    }

    /**
     * Формирует сообщение об ошибке с префиксом "!".
     * TerminalActivity удалит префикс при выводе, но использует его
     * для определения типа строки (ERROR / OUTPUT).
     */
    private fun errorMessage(message: String): String {
        return "$ERROR_PREFIX $message"
    }

    // ============================================================
    // ОБРАБОТЧИКИ: СПРАВКА И ИНФО
    // ============================================================

    private fun helpHandler(context: Context, args: List<String>): String {
        val commands = getCommands(context)

        if (args.isNotEmpty()) {
            val targetName = args.first()
            val target = commands.find { it.name == targetName }
                ?: return errorMessage(context.getString(R.string.terminal_error_unknown_command, targetName))

            val description = context.getString(target.descriptionRes)
            return context.getString(R.string.terminal_help_command, target.name, description)
        }

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.terminal_help_header))
        sb.appendLine()

        val nameWidth = commands.maxOf { it.name.length }.coerceAtLeast(6) + 2

        commands.forEach { cmd ->
            val name = cmd.name.padEnd(nameWidth, ' ')
            val description = context.getString(cmd.descriptionRes)
            sb.appendLine("  $name$description")
        }

        return sb.toString().trimEnd()
    }

    private fun aboutHandler(context: Context): String {
        val (versionName, suffix) = AppInfoHelper.getSplitVersion(context)
        val fullVersion = if (suffix.isNotEmpty()) "$versionName $suffix" else versionName

        return buildString {
            appendLine(context.getString(R.string.app_name))
            appendLine(context.getString(R.string.about_app_description))
            appendLine(pad("Version:") + fullVersion)
            appendLine(pad("Author:") + context.getString(R.string.author_default))
            appendLine(pad("Repo:") + CoreConfig.CONTACT_GITHUB_URL)
            append(pad("Telegram:") + CoreConfig.CONTACT_TELEGRAM_URL)
        }
    }

    private fun versionHandler(context: Context): String {
        val (versionName, suffix) = AppInfoHelper.getSplitVersion(context)
        val versionCode = AppInfoHelper.getVersionCode(context)

        val channel = suffix.ifEmpty { context.getString(R.string.terminal_value_none) }

        return buildString {
            append(context.getString(R.string.terminal_version_format, versionName))
            append("\n")
            append(context.getString(R.string.terminal_version_code, versionCode.toString()))
            append("\n")
            append(context.getString(R.string.terminal_version_channel, channel))
        }
    }

    private fun statusHandler(context: Context, configManager: ConfigManager): String {
        val shellManager = ShellManager(context, configManager)
        val shell = shellManager.getActiveShell()
        val shellLine = if (shell != null) {
            val name = shell.displayName ?: shell.name
            val version = shell.version ?: "?"
            "$name $version"
        } else {
            context.getString(R.string.terminal_value_none)
        }

        val themeMode = configManager.getThemeMode()
        val themeActual = if (themeMode == "system") {
            context.getString(R.string.terminal_theme_auto)
        } else {
            themeMode
        }
        val themeLine = "$themeActual (mode: $themeMode)"

        val orientation = configManager.getOrientation()
        val fullscreen = if (configManager.isFullscreenEnabled()) "on" else "off"
        val strict = if (configManager.isStrictModeEnabled()) "on" else "off"
        val language = configManager.getLanguage()
        val colorScheme = configManager.getColorScheme()
        val devMode = if (isDevModeEnabled(context)) "on" else "off"

        return buildString {
            appendLine(pad("Shell:") + shellLine)
            appendLine(pad("Theme:") + themeLine)
            appendLine(pad("Language:") + language)
            appendLine(pad("Orientation:") + orientation)
            appendLine(pad("Fullscreen:") + fullscreen)
            appendLine(pad("Strict:") + strict)
            appendLine(pad("ColorScheme:") + colorScheme)
            append(pad("DevMode:") + devMode)
        }
    }

    private fun deviceHandler(context: Context): String {
        val model = Build.MODEL.ifEmpty { context.getString(R.string.unknown) }
        val manufacturer = Build.MANUFACTURER.ifEmpty { context.getString(R.string.unknown) }
        val android = Build.VERSION.RELEASE.ifEmpty { context.getString(R.string.unknown) }
        val sdk = Build.VERSION.SDK_INT.toString()

        return buildString {
            appendLine(pad("Model:") + model)
            appendLine(pad("Manufacturer:") + manufacturer)
            appendLine(pad("Android:") + android)
            append(pad("SDK:") + sdk)
        }
    }

    private fun shellInfoHandler(context: Context, configManager: ConfigManager): String {
        val shellManager = ShellManager(context, configManager)
        val shell = shellManager.getActiveShell()
            ?: return context.getString(R.string.terminal_shell_info_none)

        val none = context.getString(R.string.terminal_value_none)
        val builtin = context.getString(R.string.shell_type_builtin)
        val custom = context.getString(R.string.shell_type_custom)

        val displayName = shell.displayName ?: shell.name
        val version = shell.version ?: none
        val author = shell.author ?: context.getString(R.string.author_default)
        val type = if (shell.isCustom) custom else builtin
        val minVersion = shell.minQutyLaunchVersion ?: none
        val orientation = shell.orientation ?: none
        val repo = shell.repoUrl ?: none

        return buildString {
            appendLine(pad("Name:") + shell.name)
            appendLine(pad("Display:") + displayName)
            appendLine(pad("Version:") + version)
            appendLine(pad("Author:") + author)
            appendLine(pad("Type:") + type)
            appendLine(pad("MinVersion:") + minVersion)
            appendLine(pad("Orientation:") + orientation)
            append(pad("Repo:") + repo)
        }
    }

    private fun shellListHandler(context: Context, configManager: ConfigManager): String {
        val shellManager = ShellManager(context, configManager)
        val shells = shellManager.getAvailableShells()

        if (shells.isEmpty()) {
            return context.getString(R.string.terminal_shell_list_empty)
        }

        val activeName = configManager.getActiveShell()
        val markerActive = context.getString(R.string.terminal_shell_marker_active)
        val markerCustom = context.getString(R.string.terminal_shell_marker_custom)

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.terminal_shell_list_header))
        sb.appendLine()

        shells.forEach { shell ->
            val isActive = shell.name == activeName
            val displayName = shell.displayName ?: shell.name
            val version = shell.version ?: "?"
            val marker = if (isActive) "?" else "0"
            val suffix = buildString {
                if (isActive) append(" $markerActive")
                if (shell.isCustom) {
                    if (isNotEmpty()) append(" ")
                    append(markerCustom)
                }
            }
            sb.appendLine("  $marker $displayName $version$suffix")
        }

        return sb.toString().trimEnd()
    }

    private fun themeHandler(context: Context, configManager: ConfigManager): String {
        val mode = configManager.getThemeMode()
        val actual = if (mode == "system") {
            context.getString(R.string.terminal_theme_auto)
        } else {
            mode
        }
        return "$actual (mode: $mode)"
    }

    private fun langHandler(configManager: ConfigManager): String {
        return configManager.getLanguage()
    }

    private fun orientationHandler(context: Context, configManager: ConfigManager): String {
        val userOrientation = configManager.getOrientation()
        val shellManager = ShellManager(context, configManager)
        val forced = shellManager.getForcedOrientationFromActiveShell()

        return if (forced != null && forced != userOrientation) {
            "$userOrientation (forced: $forced)"
        } else {
            userOrientation
        }
    }

    private fun devModeHandler(context: Context): String {
        return if (isDevModeEnabled(context)) "on" else "off"
    }

    private fun storageHandler(context: Context): String {
        val storageManager = StorageManager(context)

        val cacheDirSize = storageManager.getDirectorySize(StorageDirectory.CACHE)
        val appCacheDirSize = context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        val cacheTotal = cacheDirSize + appCacheDirSize

        val tempSize = storageManager.getDirectorySize(StorageDirectory.TEMP)
        val updatesSize = storageManager.getDirectorySize(StorageDirectory.UPDATES)
        val shellsSize = storageManager.getDirectorySize(StorageDirectory.SHELLS)

        val total = cacheTotal + tempSize + updatesSize + shellsSize

        return buildString {
            appendLine(pad("Cache:") + storageManager.formatSize(cacheTotal))
            appendLine(pad("Temp:") + storageManager.formatSize(tempSize))
            appendLine(pad("Updates:") + storageManager.formatSize(updatesSize))
            appendLine(pad("Shells:") + storageManager.formatSize(shellsSize))
            appendLine(context.getString(R.string.terminal_storage_divider))
            append(pad("Total:") + storageManager.formatSize(total))
        }
    }

    // ============================================================
    // ОБРАБОТЧИКИ: ДИАГНОСТИКА
    // ============================================================

    private fun doctorHandler(context: Context, configManager: ConfigManager): String {
        val ok = context.getString(R.string.terminal_doctor_ok)
        val warn = context.getString(R.string.terminal_doctor_warn)
        val fail = context.getString(R.string.terminal_doctor_fail)

        var okCount = 0
        var warnCount = 0
        var failCount = 0

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.terminal_doctor_header))
        sb.appendLine()

        // 1. DevMode
        val devMode = isDevModeEnabled(context)
        val devModeText = if (devMode) {
            okCount++
            "$ok " + context.getString(R.string.terminal_doctor_devmode_on)
        } else {
            okCount++
            "$ok " + context.getString(R.string.terminal_doctor_devmode_off)
        }
        sb.appendLine("  " + pad(devModeText))
        sb.appendLine("       " + context.getString(R.string.terminal_doctor_label_devmode))

        // 2. Активная оболочка
        val shellManager = ShellManager(context, configManager)
        val shell = shellManager.getActiveShell()
        val (shellMark, shellText) = if (shell != null) {
            okCount++
            ok to "${shell.displayName ?: shell.name} ${shell.version ?: "?"}"
        } else {
            failCount++
            fail to context.getString(R.string.terminal_doctor_shell_missing)
        }
        sb.appendLine("  $shellMark " + context.getString(R.string.terminal_doctor_label_shell) + ": $shellText")

        // 3. Валидность manifest
        val (manifestMark, manifestText) = if (shell != null && shell.name.isNotEmpty() && !shell.version.isNullOrEmpty()) {
            okCount++
            ok to context.getString(R.string.terminal_doctor_manifest_ok)
        } else {
            warnCount++
            warn to context.getString(R.string.terminal_doctor_manifest_warn)
        }
        sb.appendLine("  $manifestMark " + context.getString(R.string.terminal_doctor_label_manifest) + ": $manifestText")

        // 4. Разрешения
        val permManager = PermissionManager
        val permsOk = permManager.hasAllRequiredPermissions(context)
        val total = permManager.getRequiredPermissions().size
        val granted = permManager.getRequiredPermissions().count {
            permManager.hasPermission(context, it)
        }
        val (permMark, permText) = if (permsOk) {
            okCount++
            ok to context.getString(R.string.terminal_doctor_perms_ok, granted, total)
        } else {
            failCount++
            fail to context.getString(R.string.terminal_doctor_perms_fail, granted, total)
        }
        sb.appendLine("  $permMark " + context.getString(R.string.terminal_doctor_label_perms) + ": $permText")

        // 5. Свободное место
        val storageManager = StorageManager(context)
        val freeSpace = try {
            storageManager.getDirectory(StorageDirectory.BASE).freeSpace
        } catch (_: Exception) {
            0L
        }
        val freeFormatted = storageManager.formatSize(freeSpace)
        val (freeMark, freeText) = if (freeSpace >= DOCTOR_MIN_FREE_SPACE_BYTES) {
            okCount++
            ok to freeFormatted
        } else {
            warnCount++
            warn to context.getString(R.string.terminal_doctor_free_space_low, freeFormatted)
        }
        sb.appendLine("  $freeMark " + context.getString(R.string.terminal_doctor_label_free_space) + ": $freeText")

        // 6. Свежесть кэша приложений
        val cacheAgeMs = getCacheAgeMs(context)
        val (cacheMark, cacheText) = when {
            cacheAgeMs == null -> {
                okCount++
                ok to context.getString(R.string.terminal_doctor_cache_empty)
            }
            cacheAgeMs <= DOCTOR_CACHE_MAX_AGE_MS -> {
                okCount++
                val ageMin = cacheAgeMs / 60_000
                ok to context.getString(R.string.terminal_doctor_cache_fresh, ageMin.toString())
            }
            else -> {
                warnCount++
                val ageMin = cacheAgeMs / 60_000
                warn to context.getString(R.string.terminal_doctor_cache_stale, ageMin.toString())
            }
        }
        sb.appendLine("  $cacheMark " + context.getString(R.string.terminal_doctor_label_cache) + ": $cacheText")

        // 7. WebView доступен
        val webViewPackage = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: Exception) {
            null
        }
        val (wvMark, wvText) = if (webViewPackage != null) {
            okCount++
            ok to context.getString(
                R.string.terminal_doctor_webview_ok,
                webViewPackage.versionName ?: "?"
            )
        } else {
            failCount++
            fail to context.getString(R.string.terminal_doctor_webview_fail)
        }
        sb.appendLine("  $wvMark " + context.getString(R.string.terminal_doctor_label_webview) + ": $wvText")

        // Сводка
        sb.appendLine()
        sb.append(
            context.getString(
                R.string.terminal_doctor_summary,
                okCount, warnCount, failCount
            )
        )

        return sb.toString()
    }

    private fun memoryHandler(): String {
        val runtime = Runtime.getRuntime()

        val maxBytes = runtime.maxMemory()
        val totalBytes = runtime.totalMemory()
        val freeBytes = runtime.freeMemory()
        val usedBytes = totalBytes - freeBytes

        return buildString {
            appendLine(pad("Used:") + formatBytes(usedBytes))
            appendLine(pad("Free:") + formatBytes(freeBytes))
            appendLine(pad("Max:") + formatBytes(maxBytes))
            append(pad("Total:") + formatBytes(totalBytes))
        }
    }

    private fun webViewHandler(context: Context): String {
        val packageInfo = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: Exception) {
            null
        } ?: return context.getString(R.string.terminal_webview_not_found)

        val packageName = packageInfo.packageName
        val version = packageInfo.versionName ?: "?"
        val provider = try {
            val pm = context.packageManager
            val appInfo = packageInfo.applicationInfo
            if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else "?"
        } catch (_: Exception) {
            "?"
        }

        return buildString {
            appendLine(pad("Package:") + packageName)
            appendLine(pad("Version:") + version)
            append(pad("Provider:") + provider)
        }
    }

    private fun screenHandler(context: Context): String {
        val metrics: DisplayMetrics = context.resources.displayMetrics

        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        val density = metrics.density
        val dpi = metrics.densityDpi

        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()

        val densityString = String.format(java.util.Locale.US, "%.2f", density)

        return buildString {
            appendLine(pad("Resolution:") + "$widthPx ? $heightPx px")
            appendLine(pad("Density:") + "$densityString ($dpi dpi)")
            append(pad("DP size:") + "$widthDp ? $heightDp dp")
        }
    }

    private fun getCacheAgeMs(context: Context): Long? {
        return try {
            val storageManager = StorageManager(context)
            val file = storageManager.get(
                StorageDirectory.CACHE,
                CoreConfig.CACHE_APPS_FILE_NAME
            )
            if (!file.exists()) return null

            val jsonString = file.readText()
            if (jsonString.isEmpty()) return null

            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonString).jsonObject
            val timestamp = root["timestamp"]?.jsonPrimitive?.longOrNull ?: return null

            System.currentTimeMillis() - timestamp
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // ОБРАБОТЧИКИ: API
    // ============================================================

    private fun apiListHandler(context: Context, configManager: ConfigManager): String {
        val methods = ApiRouter.getRegisteredMethods()
        if (methods.isEmpty()) {
            return context.getString(R.string.terminal_api_list_empty)
        }

        val active = ApiRouter.getActiveMethods()
        val shellManager = ShellManager(context, configManager)
        val declared = shellManager.getActiveShell()?.apiMethods?.toSet() ?: emptySet()

        val markerCalled = "?"
        val markerIdle = "0"
        val tagDeclared = context.getString(R.string.terminal_api_list_declared)
        val tagCalled = context.getString(R.string.terminal_api_list_called)

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.terminal_api_list_header, methods.size))
        sb.appendLine()

        val nameWidth = methods.maxOf { it.name.length }.coerceAtLeast(6) + 2

        methods
            .sortedBy { it.name }
            .forEach { method ->
                val isCalled = method.name in active
                val isDeclared = method.name in declared
                val marker = if (isCalled) markerCalled else markerIdle

                val tags = buildList {
                    if (isDeclared) add(tagDeclared)
                    if (isCalled) add(tagCalled)
                }.joinToString(", ")

                val name = method.name.padEnd(nameWidth, ' ')
                sb.appendLine("  $marker $name$tags")
            }

        return sb.toString().trimEnd()
    }

    private suspend fun apiCallHandler(context: Context, args: List<String>): String {
        if (args.isEmpty()) {
            return errorMessage(context.getString(R.string.terminal_api_call_usage))
        }

        val methodName = args[0]
        val params = args.getOrNull(1)

        return withContext(Dispatchers.IO) {
            ApiRouter.execute(methodName, params)
        }
    }

    private fun echoHandler(args: List<String>): String {
        return args.joinToString(" ")
    }

    // ============================================================
    // ОБРАБОТЧИКИ: ИСТОРИЯ
    // ============================================================

    private fun historyHandler(context: Context, args: List<String>): String {
        val full = TerminalHistoryManager.getAll(context)

        if (full.isEmpty()) {
            return context.getString(R.string.terminal_history_empty)
        }

        val limit = args.firstOrNull()?.toIntOrNull()
        val list = if (limit != null && limit > 0) {
            full.takeLast(limit)
        } else {
            full
        }

        val startIndex = full.size - list.size

        return buildString {
            list.forEachIndexed { i, cmd ->
                val number = startIndex + i + 1
                appendLine("  ${number.toString().padStart(3, ' ')}  $cmd")
            }
        }.trimEnd()
    }

    private fun historyClearHandler(context: Context): String {
        TerminalHistoryManager.clear(context)
        return context.getString(R.string.terminal_history_cleared)
    }

    // ============================================================
    // ОБРАБОТЧИКИ: WRITE
    // ============================================================

    /**
     * Обработчик `set <key> <value>`.
     */
    private fun setHandler(
        context: Context,
        args: List<String>
    ): String {
        if (args.size < 2) {
            return errorMessage(context.getString(R.string.terminal_set_usage))
        }

        val key = args[0]
        val value = args[1]

        // Локальный ConfigManager — единая точка правды для prefs.
        // Логирование изменений (CONFIG.*) делает сам ConfigManager.
        val configManager = ConfigManager(context)

        return when (key) {
            "theme" -> {
                if (value !in listOf("light", "dark", "system")) {
                    return errorMessage(
                        context.getString(R.string.terminal_set_invalid_value, value, "light, dark, system")
                    )
                }
                configManager.setThemeMode(value)
                configManager.applyTheme()
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  theme=$value")
                context.getString(R.string.terminal_set_ok_restart)
            }
            "lang" -> {
                if (value !in listOf("ru", "en")) {
                    return errorMessage(
                        context.getString(R.string.terminal_set_invalid_value, value, "ru, en")
                    )
                }
                configManager.setLanguage(value)
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  lang=$value")
                context.getString(R.string.terminal_set_ok_restart)
            }
            "orientation" -> {
                if (value !in listOf("sensor", "portrait", "landscape")) {
                    return errorMessage(
                        context.getString(R.string.terminal_set_invalid_value, value, "sensor, portrait, landscape")
                    )
                }
                configManager.setOrientation(value)
                // Гарантируем перезапуск — иначе на части устройств
                // (особенно Android 12+) смена ориентации не применится
                // к уже запущенной MainActivity.
                configManager.setRestartForOrientationFlag()
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  orientation=$value")
                context.getString(R.string.terminal_set_ok_restart)
            }
            "fullscreen" -> {
                val bool = parseOnOff(value)
                    ?: return errorMessage(
                        context.getString(R.string.terminal_set_invalid_value, value, "on, off")
                    )
                configManager.setFullscreenEnabled(bool)
                // Если выключаем полноэкранный — strict теряет смысл,
                // снимаем его, чтобы не осталось противоречивого состояния
                // (isStrictModeEnabled вернёт false, но prefs остались бы true).
                if (!bool && configManager.isStrictModeEnabled()) {
                    configManager.setStrictModeEnabled(false)
                }
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  fullscreen=$bool")
                context.getString(R.string.terminal_set_ok_restart)
            }
            "strict" -> {
                val bool = parseOnOff(value)
                    ?: return errorMessage(
                        context.getString(R.string.terminal_set_invalid_value, value, "on, off")
                    )
                // Контракт ConfigManager: strict имеет смысл только при
                // включённом fullscreen. Иначе — явная ошибка, чтобы юзер
                // не получил «молчаливое» состояние, где strict=true,
                // а isStrictModeEnabled()=false.
                if (bool && !configManager.isFullscreenEnabled()) {
                    return errorMessage(
                        context.getString(R.string.terminal_set_strict_requires_fullscreen)
                    )
                }
                configManager.setStrictModeEnabled(bool)
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  strict=$bool")
                context.getString(R.string.terminal_set_ok_restart)
            }
            "color" -> {
                val validIds = ColorSchemeModel.getAllSchemes().map { it.id }
                if (value !in validIds) {
                    return errorMessage(
                        context.getString(
                            R.string.terminal_set_invalid_value,
                            value,
                            validIds.joinToString(", ")
                        )
                    )
                }
                configManager.setColorScheme(value)
                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.set  color=$value")
                context.getString(R.string.terminal_set_ok_restart)
            }
            else -> errorMessage(context.getString(R.string.terminal_set_unknown_key, key))
        }
    }

    private suspend fun reloadShellHandler(
        context: Context,
        configManager: ConfigManager
    ): String = withContext(Dispatchers.Main) {
        val shellManager = ShellManager(context, configManager)
        shellManager.reloadActiveShell()
        LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.reload.shell")
        context.getString(R.string.terminal_set_ok_restart)
    }

    private suspend fun clearWebViewHandler(context: Context): String =
        withContext(Dispatchers.Main) {
            try {
                val webView = WebView(context)
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.clearSslPreferences()
                webView.destroy()

                LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.clear.webview")

                context.getString(R.string.terminal_clear_ok)
            } catch (e: Exception) {
                errorMessage(
                    context.getString(R.string.terminal_error_execution, e.message ?: "Unknown error")
                )
            }
        }

    private suspend fun clearCacheHandler(): String = withContext(Dispatchers.IO) {
        CacheManager.clearCache()
        LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.clear.cache")
        "OK"
    }

    private suspend fun resetOnboardingHandler(context: Context): String =
        withContext(Dispatchers.Main) {
            LoggerManager.warn(LoggerTagModel.TERMINAL, "TERMINAL.reset.onboarding")

            val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
            prefs.edit {
                remove(KEY_ONBOARDING_COMPLETED)
                putBoolean(KEY_FORCE_SHOW_ONBOARDING, true)
            }
            restartApp(context)
            context.getString(R.string.terminal_reset_onboarding_ok)
        }

    private suspend fun restartHandler(context: Context): String =
        withContext(Dispatchers.Main) {
            LoggerManager.info(LoggerTagModel.TERMINAL, "TERMINAL.restart")
            restartApp(context)
            context.getString(R.string.terminal_restart_ok)
        }

    /**
     * Перезапуск приложения.
     */
    private fun restartApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val baseActivity = context as? by.quty.launch.BaseActivity
        if (baseActivity != null) {
            baseActivity.startActivityAnimated(intent)
            baseActivity.finishAnimated()
        } else {
            context.startActivity(intent)
        }
    }

    // ============================================================
    // ХЕЛПЕРЫ
    // ============================================================

    private fun parseOnOff(value: String): Boolean? {
        return when (value.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> null
        }
    }

    private fun pad(text: String): String {
        return text.padEnd(STATUS_PAD_WIDTH, ' ')
    }

    private fun formatBytes(bytes: Long): String {
        val locale = java.util.Locale.US
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024 -> String.format(locale, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(locale, "%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ
    // ============================================================

    data class ParsedCommand(
        val name: String,
        val args: List<String>
    )
}