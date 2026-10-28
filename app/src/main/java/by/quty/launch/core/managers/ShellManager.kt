// *** core/managers/ShellManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
 * Модель оболочки.
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
    val telegramUrl: String? = null,
    val minQutyLaunchVersion: String? = null,
    val apiMethods: List<String> = emptyList()
)

/**
 * Структура manifest.json оболочки.
 */
@Serializable
data class ShellManifest(
    val name: String,
    val author: String = "",
    val version: String = "0.0.1",
    val preview: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    val telegram: String? = null,
    val minQutyLaunchVersion: String? = null,
    val apiMethods: List<String> = emptyList()
)

/**
 * Результат предпросмотра файла оболочки.
 */
sealed class ShellPreviewResult {
    /** Успешный предпросмотр. */
    data class Success(
        val name: String,
        val displayName: String,
        val version: String?,
        val author: String?,
        val orientation: String?,
        val sizeBytes: Long,
        val alreadyExists: Boolean
    ) : ShellPreviewResult()

    /** Ошибки предпросмотра. */
    sealed class Failure : ShellPreviewResult() {
        /** Неподдерживаемое расширение. */
        object UnsupportedExtension : Failure()

        /** Не удалось прочитать имя файла. */
        object CannotReadFileName : Failure()

        /** Слишком большой файл. */
        object FileTooLarge : Failure()

        /** Невалидный архив. */
        object InvalidArchive : Failure()

        /** Нет manifest.json. */
        object ManifestMissing : Failure()

        /** Невалидный manifest.json. */
        object ManifestInvalid : Failure()

        /** Ошибка ввода-вывода. */
        data class IoError(val message: String?) : Failure()
    }
}

/**
 * Результат импорта оболочки.
 */
sealed class ShellImportResult {
    /** Успешный импорт. */
    data class Success(val shell: Shell) : ShellImportResult()

    /** Ошибки импорта. */
    sealed class Failure : ShellImportResult() {
        /** Неподдерживаемое расширение. */
        object UnsupportedExtension : Failure()

        /** Не удалось прочитать имя файла. */
        object CannotReadFileName : Failure()

        /** Слишком большой файл. */
        object FileTooLarge : Failure()

        /** Невалидный архив. */
        object InvalidArchive : Failure()

        /** Нет manifest.json. */
        object ManifestMissing : Failure()

        /** Невалидный manifest.json. */
        object ManifestInvalid : Failure()

        /** Оболочка уже установлена. */
        data class AlreadyExists(val name: String) : Failure()

        /** Ошибка ввода-вывода. */
        data class IoError(val message: String?) : Failure()
    }
}

/**
 * Управление оболочками: список, активная, распаковка, импорт, удаление.
 */
class ShellManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    companion object {
        const val SHELL_EXTENSION_WITH_DOT = CoreConfig.SHELL_EXTENSION_WITH_DOT
        val SHELL_EXTENSIONS = CoreConfig.SHELL_EXTENSIONS
    }

    private val storageManager = StorageManager(context)

    private var activeShell: Shell? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Директория распакованных оболочек. */
    private val activeShellDir: File by lazy {
        File(context.filesDir, "shells/active")
    }

    /** Создаёт папку active и подгружает активную оболочку из prefs. */
    init {
        if (!activeShellDir.exists()) {
            activeShellDir.mkdirs()
        }
        loadActiveShellFromConfig()
    }

    // ============================================================
    // ЗАГРУЗКА АКТИВНОЙ ОБОЛОЧКИ
    // ============================================================

    /** Подгружает активную оболочку из prefs; при ошибке — откат к Default. */
    private fun loadActiveShellFromConfig() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()
        val foundShell = shells.find { it.name == activeShellId }

        if (foundShell != null) {
            activeShell = foundShell
        } else {
            restoreDefaultShell()
        }

        saveForcedOrientation()
    }

    /** Откатывается на Default, если активная оболочка не найдена. */
    private fun restoreDefaultShell() {
        val shells = getAvailableShells()

        var defaultShell = shells.find { it.isDefault }
        if (defaultShell == null) defaultShell = shells.find { it.name == "Default" }
        if (defaultShell == null) defaultShell = shells.firstOrNull()
        if (defaultShell == null) {
            defaultShell = Shell(
                name = "Default",
                isDefault = true,
                sourcePath = "shells/Default",
                isAsset = true,
                displayName = context.getString(R.string.shell_default_name)
            )
        }

        activeShell = defaultShell
        configManager.setActiveShell(defaultShell.name)
        configManager.setActiveShellVersion(defaultShell.version ?: "")

        LoggerManager.warn(
            LoggerTagModel.SHELL,
            "SHELL.restored  ${defaultShell.name}"
        )

        val message = context.getString(R.string.shell_active_not_found) + "\n" +
                context.getString(R.string.shell_restored_default, defaultShell.displayName ?: defaultShell.name)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Сохраняет forced_orientation активной оболочки в prefs. */
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

    /** Возвращает встроенные и кастомные оболочки (кастомные перекрывают встроенные по имени). */
    fun getAvailableShells(): List<Shell> {
        val builtInShells = getBuiltInShells()
        val customShells = getCustomShells()

        val result = mutableListOf<Shell>()

        builtInShells.forEach { builtIn ->
            val customVersion = customShells.find { it.name == builtIn.name }
            if (customVersion != null) result.add(customVersion) else result.add(builtIn)
        }

        customShells.forEach { custom ->
            if (result.none { it.name == custom.name }) {
                result.add(custom)
            }
        }

        return result
    }

    /** Возвращает кастомные оболочки из SHELLS. */
    private fun getCustomShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

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

    /** Возвращает встроенные оболочки из assets. */
    private fun getBuiltInShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        try {
            val assetPaths = context.assets.list("shells") ?: return shells

            assetPaths.forEach { shellFolder ->
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
        }

        return shells
    }

    // ============================================================
    // ЧТЕНИЕ MANIFEST И ПРЕВЬЮ
    // ============================================================

    /** Читает manifest.json из ZIP. */
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

    /** Загружает превью из ZIP и конвертирует в Base64. */
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

    /** Загружает превью из assets и конвертирует в Base64. */
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

    /** Конвертирует Bitmap в Base64 (JPEG, 80%). */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    // ============================================================
    // АКТИВНАЯ ОБОЛОЧКА
    // ============================================================

    /** Возвращает оболочку для загрузки в WebView. В Safe Mode — принудительно Default. */
    fun getShellToActivate(): Shell {
        val shells = getAvailableShells()

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

    /** Возвращает активную оболочку из памяти. */
    fun getActiveShell(): Shell? {
        if (activeShell == null) loadActiveShellFromConfig()
        return activeShell
    }

    /** Перечитывает активную оболочку из файла. */
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
     * Устанавливает активную оболочку.
     * В Safe Mode prefs всё равно пишутся — выбор применится после выхода.
     */
    suspend fun setActiveShell(shell: Shell) = withContext(Dispatchers.IO) {
        val previousShellName = activeShell?.name

        configManager.setActiveShell(shell.name)
        configManager.setActiveShellVersion(shell.version ?: "")

        activeShell = shell

        if (previousShellName != shell.name) {
            ApiRouter.clearActiveMethods()

            val marker = if (shell.isCustom) "  [custom]" else ""

            LoggerManager.info(
                LoggerTagModel.SHELL,
                "SHELL.active  ${previousShellName ?: "—"} > ${shell.name}$marker"
            )
        }

        saveForcedOrientation()

        clearActiveDir()

        if (!shell.isAsset) {
            val extractDir = File(activeShellDir, shell.name)
            if (!extractDir.exists()) extractDir.mkdirs()
            unzipShell(shell.sourcePath, extractDir)
        }
    }

    // ============================================================
    // РАСПАКОВКА ОБОЛОЧКИ
    // ============================================================

    /** Рекурсивно очищает activeShellDir. */
    private suspend fun clearActiveDir() {
        withContext(Dispatchers.IO) {
            try {
                if (activeShellDir.exists()) {
                    activeShellDir.deleteRecursively()
                }
                if (!activeShellDir.exists()) {
                    activeShellDir.mkdirs()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Распаковывает ZIP с защитой от ZIP Slip. */
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

    /** Возвращает принудительную ориентацию активной оболочки или null. */
    fun getForcedOrientationFromActiveShell(): String? {
        val shell = getActiveShell() ?: return null
        return when (val orientation = shell.orientation) {
            "portrait", "landscape", "sensor", "user" -> orientation
            else -> null
        }
    }

    /** true, если активная оболочка задаёт portrait/landscape. */
    fun hasForcedOrientation(): Boolean {
        val forced = getForcedOrientationFromActiveShell()
        return forced == "portrait" || forced == "landscape"
    }

    // ============================================================
    // УПРАВЛЕНИЕ ОБОЛОЧКАМИ
    // ============================================================

    /** Удаляет кастомную оболочку по имени. */
    suspend fun deleteShellByName(name: String): Boolean {
        return try {
            val fileName = "$name${SHELL_EXTENSION_WITH_DOT}"
            val file = storageManager.get(StorageDirectory.SHELLS, fileName)

            if (!file.exists()) {
                LoggerManager.warn(
                    LoggerTagModel.SHELL,
                    "SHELL.delete_skip  $name  not found"
                )
                return true
            }

            val deleted = storageManager.remove(file)

            if (deleted) {
                try {
                    val extractDir = File(activeShellDir, name)
                    if (extractDir.exists()) extractDir.deleteRecursively()
                } catch (_: Exception) {
                }

                LoggerManager.info(
                    LoggerTagModel.SHELL,
                    "SHELL.deleted  $name"
                )

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

    // ============================================================
    // ПРЕДПРОСМОТР ФАЙЛА ОБОЛОЧКИ (БЕЗ КОПИРОВАНИЯ)
    // ============================================================

    /** Читает файл по URI и возвращает описание без копирования в SHELLS. */
    suspend fun previewShell(uri: Uri): ShellPreviewResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val displayName = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        } ?: return@withContext ShellPreviewResult.Failure.CannotReadFileName

        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in SHELL_EXTENSIONS) {
            return@withContext ShellPreviewResult.Failure.UnsupportedExtension
        }

        val tempFile = storageManager.createTempFile(
            prefix = "preview_${displayName.substringBeforeLast('.')}",
            extension = "tmp"
        )

        try {
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                return@withContext ShellPreviewResult.Failure.IoError(null)
            }
        } catch (e: Exception) {
            tempFile.delete()
            return@withContext ShellPreviewResult.Failure.IoError(e.message)
        }

        try {
            val sizeBytes = tempFile.length()
            if (sizeBytes > CoreConfig.MAX_SHELL_SIZE_BYTES) {
                return@withContext ShellPreviewResult.Failure.FileTooLarge
            }

            val manifest = try {
                ZipFile(tempFile).use { zip ->
                    val entry = zip.getEntry("manifest.json")
                        ?: return@withContext ShellPreviewResult.Failure.ManifestMissing
                    zip.getInputStream(entry).bufferedReader().use {
                        json.decodeFromString<ShellManifest>(it.readText())
                    }
                }
            } catch (_: java.util.zip.ZipException) {
                return@withContext ShellPreviewResult.Failure.InvalidArchive
            } catch (_: Exception) {
                return@withContext ShellPreviewResult.Failure.ManifestInvalid
            }

            if (manifest.name.isBlank()) {
                return@withContext ShellPreviewResult.Failure.ManifestInvalid
            }

            val targetFile = storageManager.get(
                StorageDirectory.SHELLS,
                "${manifest.name}$SHELL_EXTENSION_WITH_DOT"
            )
            val alreadyExists = targetFile.exists()

            ShellPreviewResult.Success(
                name = manifest.name,
                displayName = manifest.name,
                version = manifest.version,
                author = manifest.author,
                orientation = manifest.orientation,
                sizeBytes = sizeBytes,
                alreadyExists = alreadyExists
            )
        } finally {
            tempFile.delete()
        }
    }

    // ============================================================
    // ИМПОРТ ОБОЛОЧКИ ИЗ ФАЙЛОВОЙ СИСТЕМЫ
    // ============================================================

    /** Импортирует оболочку из URI. При overwrite = true перезаписывает существующую. */
    suspend fun importShell(uri: Uri, overwrite: Boolean = false): ShellImportResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver

            val displayName = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    }
            } catch (_: Exception) {
                null
            } ?: return@withContext ShellImportResult.Failure.CannotReadFileName

            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension !in SHELL_EXTENSIONS) {
                LoggerManager.warn(
                    LoggerTagModel.SHELL,
                    "SHELL.import  unsupported extension  $displayName"
                )
                return@withContext ShellImportResult.Failure.UnsupportedExtension
            }

            val tempFile = storageManager.createTempFile(
                prefix = "import_${displayName.substringBeforeLast('.')}",
                extension = "tmp"
            )

            try {
                resolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    LoggerManager.warn(
                        LoggerTagModel.SHELL,
                        "SHELL.import  cannot open input stream  $displayName"
                    )
                    return@withContext ShellImportResult.Failure.IoError(null)
                }
            } catch (e: Exception) {
                tempFile.delete()
                LoggerManager.error(
                    LoggerTagModel.SHELL,
                    "SHELL.import  io error on copy  ${e.message ?: e.javaClass.simpleName}",
                    e
                )
                return@withContext ShellImportResult.Failure.IoError(e.message)
            }

            try {
                if (tempFile.length() > CoreConfig.MAX_SHELL_SIZE_BYTES) {
                    LoggerManager.warn(
                        LoggerTagModel.SHELL,
                        "SHELL.import  too large  $displayName  ${tempFile.length()} bytes"
                    )
                    return@withContext ShellImportResult.Failure.FileTooLarge
                }

                val manifest = try {
                    ZipFile(tempFile).use { zip ->
                        val entry = zip.getEntry("manifest.json")
                            ?: return@withContext ShellImportResult.Failure.ManifestMissing
                        zip.getInputStream(entry).bufferedReader().use {
                            json.decodeFromString<ShellManifest>(it.readText())
                        }
                    }
                } catch (_: java.util.zip.ZipException) {
                    return@withContext ShellImportResult.Failure.InvalidArchive
                } catch (_: Exception) {
                    return@withContext ShellImportResult.Failure.ManifestInvalid
                }

                if (manifest.name.isBlank()) {
                    return@withContext ShellImportResult.Failure.ManifestInvalid
                }

                val targetName = manifest.name
                val targetFile = storageManager.get(
                    StorageDirectory.SHELLS,
                    "$targetName$SHELL_EXTENSION_WITH_DOT"
                )

                if (targetFile.exists() && !overwrite) {
                    LoggerManager.warn(
                        LoggerTagModel.SHELL,
                        "SHELL.import  already exists  $targetName"
                    )
                    return@withContext ShellImportResult.Failure.AlreadyExists(targetName)
                }

                val saved = storageManager.set(
                    directory = StorageDirectory.SHELLS,
                    name = "$targetName$SHELL_EXTENSION_WITH_DOT",
                    inputStream = tempFile.inputStream(),
                    overwrite = true
                )

                if (!saved) {
                    LoggerManager.error(
                        LoggerTagModel.SHELL,
                        "SHELL.import  save failed  $targetName"
                    )
                    return@withContext ShellImportResult.Failure.IoError(null)
                }

                val previewBase64 = loadPreviewFromZip(targetFile, manifest.preview)
                val imported = Shell(
                    name = targetName,
                    isDefault = false,
                    sourcePath = targetFile.absolutePath,
                    isAsset = false,
                    displayName = manifest.name,
                    isCustom = true,
                    version = manifest.version,
                    author = manifest.author,
                    previewBase64 = previewBase64,
                    orientation = manifest.orientation,
                    repoUrl = manifest.repoUrl,
                    telegramUrl = manifest.telegram,
                    minQutyLaunchVersion = manifest.minQutyLaunchVersion,
                    apiMethods = manifest.apiMethods
                )

                if (activeShell?.name == targetName) {
                    reloadActiveShell()
                }

                LoggerManager.info(
                    LoggerTagModel.SHELL,
                    "SHELL.import  ok  $targetName  v${manifest.version}"
                )

                ShellImportResult.Success(imported)

            } finally {
                tempFile.delete()
            }
        }
}