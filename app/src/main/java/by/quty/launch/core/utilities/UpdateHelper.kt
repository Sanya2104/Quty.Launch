// *** core/utilities/UpdateHelper.kt *** //
package by.quty.launch.core.utilities

import android.content.Context
import android.os.StatFs
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Общие хелперы обновления: версии, размеры, скачивание файла.
 */
object UpdateHelper {

    private const val BUFFER_SIZE = CoreConfig.DOWNLOAD_BUFFER_SIZE

    // ============================================================
    // ВЕРСИИ
    // ============================================================

    /** Сравнивает x.y.z: 1 / 0 / -1. */
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

    fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        return compareVersions(newVersion, currentVersion) > 0
    }

    // ============================================================
    // РАЗМЕРЫ
    // ============================================================

    /** Размер файла по HEAD; -1 при ошибке. */
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

    /** true, если свободно > required * 1.1. */
    fun hasEnoughFreeSpace(requiredSpace: Long, path: String = android.os.Environment.getExternalStorageDirectory().path): Boolean {
        return try {
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val freeSpace = blockSize * availableBlocks

            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            true
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ
    // ============================================================

    /** Скачивает файл; при отмене корутины бросает CancellationException. */
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
            val fileSize = getFileSize(url)

            if (fileSize > 0 && !hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext null
            }

            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = context.getString(R.string.download_http_error, connection.responseCode)
                withContext(Dispatchers.Main) {
                    listener.onError(errorMsg)
                }
                return@withContext null
            }

            inputStream = connection.inputStream

            val file = when (destination) {
                is Destination.CustomPath -> {
                    val customFile = File(destination.path)
                    customFile.parentFile?.mkdirs()
                    outputStream = FileOutputStream(customFile)
                    customFile
                }
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastProgress = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isActive) {
                    throw kotlinx.coroutines.CancellationException("Download cancelled")
                }

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

        } catch (e: kotlinx.coroutines.CancellationException) {
            try {
                outputStream?.close()
                inputStream?.close()
                connection?.disconnect()
                when (destination) {
                    is Destination.CustomPath -> File(destination.path).takeIf { it.exists() }?.delete()
                }
            } catch (_: Exception) {}
            throw e

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
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    sealed class Destination {
        data class CustomPath(val path: String) : Destination()
    }

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess(file: File)
        fun onError(message: String)
    }
}