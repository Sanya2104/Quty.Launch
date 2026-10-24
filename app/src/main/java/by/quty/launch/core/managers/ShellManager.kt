// *** core/managers/ShellManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.core.content.edit
import by.quty.launch.R
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Модель оболочки с расширенной информацией
 */
data class Shell(
    val name: String,
    val isDefault: Boolean,
    val sourcePath: String,
    val isAsset: Boolean = false,
    val displayName: String? = null,
    val isCustom: Boolean = false,
    val version: String? = null,
    val author: String? = null,
    val previewBase64: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    /**
     * Ссылка на Telegram-канал/чат оболочки (опционально).
     * Задаётся в manifest.json в поле "telegram".
     */
    val telegramUrl: String? = null,
    val minQutyLaunchVersion: String? = null,
    /**
     * Список API методов, заявленных оболочкой в manifest.json.
     * Если поле не указано — пустой список.
     */
    val apiMethods: List<String> = emptyList()
)

/**
 * Структура manifest.json оболочки
 */
@Serializable
data class ShellManifest(
    val name: String,
    val author: String = "",
    val version: String = "0.0.1",
    val preview: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    /**
     * Ссылка на Telegram-канал/чат оболочки (опционально).
     * Соответствует полю "telegram" в manifest.json.
     */
    val telegram: String? = null,
    val minQutyLaunchVersion: String? = null,
    /**
     * Список API методов, которые использует оболочка.
     * Опциональное поле — для старых оболочек по умолчанию пустой список.
     */
    val apiMethods: List<String> = emptyList()
)

class ShellManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    companion object {
        /** Расширение файла оболочки (с точкой) - из конфига */
        const val SHELL_EXTENSION_WITH_DOT = CoreConfig.SHELL_EXTENSION_WITH_DOT

        /** Поддерживаемые расширения файлов оболочки (без точки) - из конфига */
        val SHELL_EXTENSIONS = CoreConfig.SHELL_EXTENSIONS

    }

    // Хранилище и менеджеры
    private val storageManager = StorageManager(context)

    // Активная оболочка в памяти
    private var activeShell: Shell? = null

    // JSON парсер
    private val json = Json { ignoreUnknownKeys = true }

    // Директория активной оболочки (для распакованных файлов)
    private val activeShellDir: File by lazy {
        File(context.filesDir, "shells/active")
    }

    init {
        // Создаём директорию для активной оболочки
        if (!activeShellDir.exists()) {
            activeShellDir.mkdirs()
        }

        // Загружаем активную оболочку из конфига
        loadActiveShellFromConfig()
    }

    // ============================================================
    // ЗАГРУЗКА АКТИВНОЙ ОБОЛОЧКИ
    // ============================================================

    /**
     * Загружает активную оболочку из конфига в память
     */
    private fun loadActiveShellFromConfig() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()

        // Проверяем, существует ли оболочка в списке доступных
        val foundShell = shells.find { it.name == activeShellId }

        if (foundShell != null) {
            activeShell = foundShell
        } else {
            // Оболочка не найдена — восстанавливаем Default
            restoreDefaultShell()
        }

        // Сохраняем принудительную ориентацию
        saveForcedOrientation()
    }

    /**
     * Восстанавливает оболочку по умолчанию, если активная оболочка не найдена
     */
    private fun restoreDefaultShell() {
        val shells = getAvailableShells()

        // Ищем оболочку по умолчанию
        var defaultShell = shells.find { it.isDefault }

        // Если оболочка по умолчанию не найдена, ищем по имени "Default"
        if (defaultShell == null) {
            defaultShell = shells.find { it.name == "Default" }
        }

        // Если всё ещё не найдена, берём первую доступную оболочку
        if (defaultShell == null) {
            defaultShell = shells.firstOrNull()
        }

        // Если совсем нет оболочек — создаём базовую оболочку
        if (defaultShell == null) {
            defaultShell = Shell(
                name = "Default",
                isDefault = true,
                sourcePath = "shells/Default",
                isAsset = true,
                displayName = context.getString(R.string.shell_default_name)
            )
        }

        // Сохраняем оболочку по умолчанию как активную
        activeShell = defaultShell
        configManager.setActiveShell(defaultShell.name)
        configManager.setActiveShellVersion(defaultShell.version ?: "")

        LoggerManager.warn(
            LoggerTagModel.SHELL,
            "SHELL.restored  ${defaultShell.name}"
        )

        // Показываем уведомление о восстановлении
        val message = context.getString(R.string.shell_active_not_found) + "\n" +
                context.getString(R.string.shell_restored_default, defaultShell.displayName ?: defaultShell.name)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Сохраняет принудительную ориентацию текущей оболочки в SharedPreferences
     */
    private fun saveForcedOrientation() {
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val forcedOrientation = getForcedOrientationFromActiveShell()

        prefs.edit {
            if (forcedOrientation != null) {
                putString("forced_orientation", forcedOrientation)
            } else {
                remove("forced_orientation")
            }
        }
    }

    // ============================================================
    // ПОЛУЧЕНИЕ ОБОЛОЧЕК
    // ============================================================

    /**
     * Получить список всех доступных оболочек
     * Встроенные оболочки заменяются кастомными версиями с тем же именем
     */
    fun getAvailableShells(): List<Shell> {
        val builtInShells = getBuiltInShells()
        val customShells = getCustomShells()

        val result = mutableListOf<Shell>()

        // Для каждой встроенной оболочки проверяем, есть ли кастомная версия
        builtInShells.forEach { builtIn ->
            val customVersion = customShells.find { it.name == builtIn.name }
            if (customVersion != null) {
                result.add(customVersion)
            } else {
                result.add(builtIn)
            }
        }

        // Добавляем остальные кастомные оболочки
        customShells.forEach { custom ->
            if (result.none { it.name == custom.name }) {
                result.add(custom)
            }
        }

        return result
    }

    /**
     * Получить кастомные оболочки из хранилища
     */
    private fun getCustomShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        // Получаем все файлы оболочек из директории SHELLS
        val shellFiles = storageManager.list(
            directory = StorageDirectory.SHELLS,
            filter = { fileName ->
                SHELL_EXTENSIONS.any { ext ->
                    fileName.endsWith(".$ext", ignoreCase = true)
                }
            }
        )

        shellFiles.forEach { file ->
            val manifest = readManifestFromZip(file)
            val previewBase64 = loadPreviewFromZip(file, manifest?.preview)

            shells.add(
                Shell(
                    name = file.nameWithoutExtension,
                    isDefault = false,
                    sourcePath = file.absolutePath,
                    isAsset = false,
                    displayName = manifest?.name ?: file.nameWithoutExtension,
                    isCustom = true,
                    version = manifest?.version,
                    author = manifest?.author,
                    previewBase64 = previewBase64,
                    orientation = manifest?.orientation,
                    repoUrl = manifest?.repoUrl,
                    telegramUrl = manifest?.telegram,
                    minQutyLaunchVersion = manifest?.minQutyLaunchVersion,
                    apiMethods = manifest?.apiMethods ?: emptyList()
                )
            )
        }

        return shells
    }

    /**
     * Получить встроенные оболочки из assets
     */
    private fun getBuiltInShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        try {
            val assetPaths = context.assets.list("shells") ?: return shells

            assetPaths.forEach { shellFolder ->
                // Проверяем наличие index.html
                try {
                    context.assets.open("shells/$shellFolder/index.html").close()
                } catch (_: Exception) {
                    return@forEach
                }

                val manifest = try {
                    context.assets.open("shells/$shellFolder/manifest.json").bufferedReader().use {
                        json.decodeFromString<ShellManifest>(it.readText())
                    }
                } catch (_: Exception) {
                    null
                }

                val previewBase64 = loadPreviewFromAssets(shellFolder, manifest?.preview)

                shells.add(
                    Shell(
                        name = shellFolder,
                        isDefault = shellFolder == "Default",
                        sourcePath = "shells/$shellFolder",
                        isAsset = true,
                        displayName = manifest?.name ?: when (shellFolder) {
                            "Default" -> context.getString(R.string.shell_default_name)
                            else -> shellFolder.replaceFirstChar { it.uppercase() }
                        },
                        isCustom = false,
                        version = manifest?.version,
                        author = manifest?.author,
                        previewBase64 = previewBase64,
                        orientation = manifest?.orientation,
                        repoUrl = manifest?.repoUrl,
                        telegramUrl = manifest?.telegram,
                        minQutyLaunchVersion = manifest?.minQutyLaunchVersion,
                        apiMethods = manifest?.apiMethods ?: emptyList()
                    )
                )
            }
        } catch (_: Exception) {
            // Игнорируем ошибки чтения assets
        }

        return shells
    }

    // ============================================================
    // ЧТЕНИЕ MANIFEST И ПРЕВЬЮ
    // ============================================================

    /**
     * Читает manifest.json из ZIP-файла оболочки
     */
    private fun readManifestFromZip(zipFile: File): ShellManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).bufferedReader().use {
                    json.decodeFromString<ShellManifest>(it.readText())
                }
            }
        } catch (e: Exception) {
            LoggerManager.warn(
                LoggerTagModel.SHELL,
                "SHELL.manifest_failed  ${zipFile.nameWithoutExtension}",
                e
            )
            null
        }
    }

    /**
     * Загружает превью из ZIP-файла оболочки
     */
    private fun loadPreviewFromZip(zipFile: File, previewPath: String?): String? {
        if (previewPath.isNullOrEmpty()) return null

        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(previewPath) ?: return null
                zip.getInputStream(entry).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { bitmapToBase64(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Загружает превью из assets
     */
    private fun loadPreviewFromAssets(shellFolder: String, previewPath: String?): String? {
        if (previewPath.isNullOrEmpty()) return null

        return try {
            val inputStream = context.assets.open("shells/$shellFolder/$previewPath")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bitmap?.let { bitmapToBase64(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Конвертирует Bitmap в Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    // ============================================================
    // АКТИВНАЯ ОБОЛОЧКА
    // ============================================================

    /**
     * Получить оболочку для активации
     *
     * ВАЖНО: если активен Safe Mode — принудительно возвращаем
     * встроенную оболочку "Default", игнорируя пользовательский выбор
     * из prefs. Выбор пользователя НЕ сбрасывается — как только
     * Safe Mode будет выключен, загрузится его оболочка.
     */
    fun getShellToActivate(): Shell {
        val shells = getAvailableShells()

        // Safe Mode — принудительно Default
        if (configManager.isSafeModeActive()) {
            val defaultShell = shells.find { it.name == "Default" }
            if (defaultShell != null) {
                LoggerManager.warn(
                    LoggerTagModel.SHELL,
                    "SHELL.safe_mode  force Default"
                )
                return defaultShell
            }
        }

        val activeShellId = configManager.getActiveShell()

        shells.find { it.name == activeShellId }?.let { return it }

        val defaultShellId = configManager.getDefaultShell()
        shells.find { it.name == defaultShellId }?.let { return it }

        shells.find { it.name == "Default" }?.let { return it }

        return shells.firstOrNull() ?: Shell(
            name = "Default",
            isDefault = true,
            sourcePath = "shells/Default",
            isAsset = true,
            displayName = context.getString(R.string.shell_default_name)
        )
    }

    /**
     * Получить активную оболочку
     */
    fun getActiveShell(): Shell? {
        if (activeShell == null) {
            loadActiveShellFromConfig()
        }
        return activeShell
    }

    /**
     * Принудительно перезагружает активную оболочку из файла
     */
    fun reloadActiveShell() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()
        activeShell = shells.find { it.name == activeShellId }
        saveForcedOrientation()

        LoggerManager.info(
            LoggerTagModel.SHELL,
            "SHELL.reload  $activeShellId"
        )
    }

    /**
     * Устанавливает активную оболочку
     *
     * ВАЖНО: список активных API методов сбрасывается ТОЛЬКО при смене
     * оболочки (когда имя реально изменилось). При пересоздании MainActivity
     * (например, при смене темы Light/Dark) с той же оболочкой состояние
     * активных методов сохраняется — пользователь не теряет картину
     * «кто что вызывал» в разделе Настроек > API методы.
     *
     * Safe Mode: в этом режиме НЕ записываем выбор в prefs — иначе
     * при выходе из Safe Mode загрузилась бы "Default" вместо оболочки,
     * которую юзер выбрал до входа в Safe Mode.
     *
     * active_shell_version: пишется сюда же — это «версия, реально
     * загруженная в WebView». Используется магазином, чтобы отличить
     * «оболочка обновлена, но не применена» от «оболочка активна
     * с текущей версией».
     */
    suspend fun setActiveShell(shell: Shell) = withContext(Dispatchers.IO) {
        // Запоминаем имя предыдущей активной оболочки
        val previousShellName = activeShell?.name

        // ВАЖНО: если активен Safe Mode — не перезаписываем активную
        // оболочку в prefs. Иначе при выходе из Safe Mode вместо
        // пользовательской оболочки (например, QutyAuto) загрузится Default.
        if (!configManager.isSafeModeActive()) {
            configManager.setActiveShell(shell.name)
            configManager.setActiveShellVersion(shell.version ?: "")
        }

        // Обновляем в памяти
        activeShell = shell

        // Сбрасываем список активных API методов только если оболочка
        // реально поменялась
        if (previousShellName != shell.name) {
            ApiRouter.clearActiveMethods()

            // Маркер [custom] — только для кастомных оболочек
            val marker = if (shell.isCustom) "  [custom]" else ""

            LoggerManager.info(
                LoggerTagModel.SHELL,
                "SHELL.active  ${previousShellName ?: "—"} > ${shell.name}$marker"
            )
        }

        // Сохраняем принудительную ориентацию
        withContext(Dispatchers.Main) {
            saveForcedOrientation()
        }

        // Очищаем директорию активной оболочки
        clearActiveDir()

        // Если это не asset оболочка — распаковываем
        if (!shell.isAsset) {
            val extractDir = File(activeShellDir, shell.name)
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }
            unzipShell(shell.sourcePath, extractDir)
        }
    }

    // ============================================================
    // РАСПАКОВКА ОБОЛОЧКИ
    // ============================================================

    /**
     * Очищает директорию активной оболочки
     */
    private suspend fun clearActiveDir() {
        if (activeShellDir.exists()) {
            // Удаляем содержимое директории через StorageManager
            // Получаем File объект и удаляем его
            val activeDir = storageManager.get("shells/active")
            if (activeDir.exists()) {
                storageManager.remove(activeDir)
                activeShellDir.mkdirs()
            }
        }
    }

    /**
     * Распаковывает ZIP-архив оболочки.
     *
     * ВАЖНО: защита от ZIP Slip. Если entry.name содержит `..` и
     * пытается выйти за пределы outputDir, entry пропускается.
     * Это критично для оболочек, импортированных через SAF — сервер
     * магазина доверенный, но пользовательский импорт — нет.
     */
    private suspend fun unzipShell(zipPath: String, outputDir: File) {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            LoggerManager.warn(
                LoggerTagModel.SHELL,
                "SHELL.unzip_failed  ${zipFile.nameWithoutExtension}  file not found"
            )
            return
        }

        try {
            val canonicalOutput = outputDir.canonicalFile

            withContext(Dispatchers.IO) {
                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val outFile = File(canonicalOutput, entry.name).canonicalFile

                        // ZIP Slip защита: файл должен лежать внутри canonicalOutput
                        val isValid = outFile.path == canonicalOutput.path ||
                                outFile.path.startsWith(canonicalOutput.path + File.separator)

                        if (!isValid) {
                            LoggerManager.warn(
                                LoggerTagModel.SHELL,
                                "SHELL.unzip_skip  zip slip  ${entry.name}"
                            )
                            return@forEach
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LoggerManager.error(
                LoggerTagModel.SHELL,
                "SHELL.unzip_failed  ${zipFile.nameWithoutExtension}  ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }

    // ============================================================
    // ПРИНУДИТЕЛЬНАЯ ОРИЕНТАЦИЯ
    // ============================================================

    /**
     * Получить принудительную ориентацию из активной оболочки
     */
    fun getForcedOrientationFromActiveShell(): String? {
        val shell = getActiveShell() ?: return null
        return when (val orientation = shell.orientation) {
            "portrait", "landscape", "sensor", "user" -> orientation
            else -> null
        }
    }

    /**
     * Проверяет, задаёт ли текущая оболочка принудительную ориентацию
     */
    fun hasForcedOrientation(): Boolean {
        val forced = getForcedOrientationFromActiveShell()
        return forced == "portrait" || forced == "landscape"
    }

    // ============================================================
    // УПРАВЛЕНИЕ ОБОЛОЧКАМИ
    // ============================================================

    /**
     * Удаляет кастомную оболочку по имени.
     *
     * Логика:
     * - если файла .qutyshell нет — считаем «уже удалено» (true),
     *   чтобы UI не показывал ложную ошибку при гонке;
     * - иначе удаляем файл;
     * - дополнительно чистим распакованную папку active/<name>
     *   (хвост от прошлой активации);
     * - если оболочка была активной — перечитываем конфиг
     *   (подхватится встроенная версия или Default).
     */
    suspend fun deleteShellByName(name: String): Boolean {
        return try {
            val fileName = "$name${SHELL_EXTENSION_WITH_DOT}"
            val file = storageManager.get(StorageDirectory.SHELLS, fileName)

            // Файла нет — уже удалён (или не было). Не считаем ошибкой.
            if (!file.exists()) {
                LoggerManager.warn(
                    LoggerTagModel.SHELL,
                    "SHELL.delete_skip  $name  not found"
                )
                return true
            }

            val deleted = storageManager.remove(file)

            if (deleted) {
                // Чистим распакованную папку активной оболочки с этим именем
                try {
                    val extractDir = File(activeShellDir, name)
                    if (extractDir.exists()) {
                        extractDir.deleteRecursively()
                    }
                } catch (_: Exception) {
                    // Игнорируем
                }

                LoggerManager.info(
                    LoggerTagModel.SHELL,
                    "SHELL.deleted  $name"
                )

                // Если оболочка была активной — перезагружаем
                val currentActive = getActiveShell()
                if (currentActive?.name == name && currentActive.isCustom) {
                    loadActiveShellFromConfig()
                }
            } else {
                LoggerManager.warn(
                    LoggerTagModel.SHELL,
                    "SHELL.delete_failed  $name"
                )
            }

            deleted
        } catch (e: Exception) {
            LoggerManager.error(
                LoggerTagModel.SHELL,
                "SHELL.delete_failed  $name  ${e.message ?: e.javaClass.simpleName}",
                e
            )
            false
        }
    }
}