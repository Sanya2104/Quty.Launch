// *** core/utilities/UpdateHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import android.os.StatFs
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вспомогательный объект для операций обновления
 * Содержит общую логику для SystemUpdateManager и ShellUpdateManager
 *
 * Использование:
 * - compareVersions() - сравнение версий
 * - isNewerVersion() - проверка, новее ли версия
 * - downloadFile() - скачивание файла
 */
object UpdateHelper {

    // Размер буфера при скачивании (из конфига)
    private const val BUFFER_SIZE = CoreConfig.DOWNLOAD_BUFFER_SIZE

    // ============================================================
    // РАБОТА С ВЕРСИЯМИ
    // ============================================================

    /**
     * Сравнивает две версии (формат x.y.z)
     * @return 1 если v1 > v2, 0 если равны, -1 если v1 < v2
     */
    fun compareVersions(v1: String, v2: String): Int {
        return try {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }

                when {
                    p1 > p2 -> return 1
                    p1 < p2 -> return -1
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Проверяет, является ли новая версия новее текущей
     */
    fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        return compareVersions(newVersion, currentVersion) > 0
    }

    // ============================================================
    // РАБОТА С РАЗМЕРАМИ
    // ============================================================

    /**
     * Получает размер файла по URL (HEAD запрос)
     * @param url ссылка на файл
     * @return размер в байтах или -1 если не удалось
     */
    suspend fun getFileSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CoreConfig.READ_TIMEOUT_MS
            connection.connect()
            val size = connection.contentLength.toLong()
            connection.disconnect()
            size
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Проверяет, достаточно ли свободного места
     * @param requiredSpace требуемое место в байтах
     * @param path путь для проверки (по умолчанию внешнее хранилище)
     * @return true если места достаточно
     */
    fun hasEnoughFreeSpace(requiredSpace: Long, path: String = android.os.Environment.getExternalStorageDirectory().path): Boolean {
        return try {
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val freeSpace = blockSize * availableBlocks

            // Проверяем, что свободного места как минимум на 10% больше требуемого
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            // Если не удалось проверить — считаем, что места достаточно
            true
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ ФАЙЛОВ
    // ============================================================

    /**
     * Скачивает файл из интернета
     * @param context контекст приложения
     * @param url ссылка на файл
     * @param listener слушатель прогресса
     * @param destination куда сохранять (полный путь к файлу)
     * @param connectTimeout тайм-аут подключения (мс)
     * @param readTimeout тайм-аут чтения (мс)
     * @return скачанный файл или null в случае ошибки
     */
    suspend fun downloadFile(
        context: Context,
        url: String,
        listener: DownloadListener,
        destination: Destination,
        connectTimeout: Int = CoreConfig.CONNECT_TIMEOUT_MS,
        readTimeout: Int = CoreConfig.READ_TIMEOUT_MS
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            // Получаем размер файла
            val fileSize = getFileSize(url)

            // Проверяем свободное место
            if (fileSize > 0 && !hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext null
            }

            // Подключаемся
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.connect()

            // Проверяем код ответа
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = context.getString(R.string.download_http_error, connection.responseCode)
                withContext(Dispatchers.Main) {
                    listener.onError(errorMsg)
                }
                return@withContext null
            }

            inputStream = connection.inputStream

            // Подготавливаем файл для сохранения
            val file = when (destination) {
                is Destination.CustomPath -> {
                    val customFile = File(destination.path)
                    customFile.parentFile?.mkdirs()
                    outputStream = FileOutputStream(customFile)
                    customFile
                }
            }

            // Скачиваем
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastProgress = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileSize > 0) {
                    val progress = (totalBytesRead * 100 / fileSize).toInt()
                    if (progress > lastProgress) {
                        lastProgress = progress
                        withContext(Dispatchers.Main) {
                            listener.onProgress(progress)
                        }
                    }
                }
            }

            outputStream.close()
            inputStream?.close()
            connection.disconnect()

            // Проверяем, что файл не пустой
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_empty_file))
                }
                return@withContext null
            }

            withContext(Dispatchers.Main) {
                listener.onSuccess(file)
            }
            return@withContext file

        } catch (_: java.net.SocketTimeoutException) {
            withContext(Dispatchers.Main) {
                listener.onError(context.getString(R.string.download_timeout))
            }
            null
        } catch (_: java.net.UnknownHostException) {
            withContext(Dispatchers.Main) {
                listener.onError(context.getString(R.string.no_internet_connection))
            }
            null
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            null
        } finally {
            inputStream?.close()
            outputStream?.close()
            connection?.disconnect()
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ
    // ============================================================

    /**
     * Назначение файла (куда сохранять)
     */
    sealed class Destination {
        /**
         * Сохранить по указанному пути
         * @param path полный путь к файлу
         */
        data class CustomPath(val path: String) : Destination()
    }

    /**
     * Слушатель прогресса скачивания
     */
    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess(file: File)
        fun onError(message: String)
    }
}