// *** core/webview/LauncherWebView.kt *** //
package by.quty.launch.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import by.quty.launch.R
import by.quty.launch.core.logger.Logger
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context) {

    private val activeShellDir = File(context.filesDir, "shells/active")

    // Храним имя активной оболочки для корректной загрузки
    private var activeShellName: String? = null

    // Счётчик попыток перезагрузки при ошибке
    private var errorRetryCount = 0
    private var lastErrorUrl: String? = null

    // Максимальное количество попыток перезагрузки
    private companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // ВАЖНО: Отключаем старый опасный доступ к файлам
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        // Используем аппаратное ускорение для современных CSS
        setLayerType(LAYER_TYPE_HARDWARE, null)

        setWebContentsDebuggingEnabled(true)

        // Настраиваем WebViewAssetLoader
        setupAssetLoader()

        // Настраиваем WebChromeClient
        // Логи из JavaScript отправляются через JsBridge.log() для избежания дублирования
        setupWebChromeClient()
    }

    /**
     * Настройка WebChromeClient
     * Не перехватывает console.log() для предотвращения дублирования
     * Логи из JavaScript отправляются через JsBridge.log()
     */
    private fun setupWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            // Перехват console.log() отключён — логи отправляются через JsBridge.log()
            // Чтобы включить перехват для отладки, раскомментируйте код ниже
            /*
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                // Перехватываем все логи из JavaScript
                val level = consoleMessage.messageLevel().name
                val message = consoleMessage.message()
                val sourceId = consoleMessage.sourceId() ?: "unknown"

                // Отправляем в наш логгер
                Logger.fromWebView(level, "[$sourceId] $message")

                // Возвращаем true, чтобы сообщение не дублировалось в системном логе
                return true
            }

            // Для обратной совместимости с Android < 8.0 (API < 26)
            @Suppress("DEPRECATION")
            @Deprecated("Use onConsoleMessage(ConsoleMessage) instead")
            override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
                // Отправляем в наш логгер
                Logger.fromWebView("log", "[$sourceID:$lineNumber] $message")
            }
            */

            // Оставляем только для отладки в браузере (не влияет на логгер)
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                // Просто пропускаем — логи уже отправлены через JsBridge.log()
                return false
            }
        }
    }

    private fun setupAssetLoader() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
            .build()

        webViewClient = object : WebViewClient() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // Обрабатываем наш собственный протокол
                if (url.startsWith("quty://")) {
                    return handleQutyScheme(url)
                }

                // Всё остальное через стандартный загрузчик
                return assetLoader.shouldInterceptRequest(request.url)
            }

            private fun handleQutyScheme(url: String): WebResourceResponse? {
                try {
                    var path = url.replace("quty://", "")

                    if (path.startsWith("./")) {
                        path = path.substring(2)
                    }

                    // Определяем папку оболочки по имени активной оболочки
                    val shellDir = getActiveShellDir()
                    if (shellDir == null) {
                        Logger.e(
                            "LauncherWebView",
                            context.getString(R.string.webview_dir_shell_not_found)
                        )
                        return null
                    }

                    // Пытаемся найти файл в папке оболочки
                    var file = File(shellDir, path)

                    // Если файл не найден и путь начинается с shells/active/,
                    // пробуем убрать этот префикс
                    if (!file.exists() && path.startsWith("shells/active/")) {
                        val relativePath = path.replace("shells/active/", "")
                        file = File(shellDir, relativePath)
                    }

                    // Если всё ещё не найден, пробуем найти файл в подпапках оболочки
                    if (!file.exists()) {
                        val foundFile = findFileRecursively(shellDir, File(path).name)
                        if (foundFile != null) {
                            file = foundFile
                        }
                    }

                    if (file.exists() && file.isFile) {
                        val mimeType = URLConnection.guessContentTypeFromName(file.name)
                            ?: "text/plain"
                        return WebResourceResponse(mimeType, "UTF-8", FileInputStream(file))
                    }

                    // Если файл не найден, пробуем загрузить из assets (запасной вариант)
                    if (path.startsWith("shells/")) {
                        val assetPath = path.replace("shells/", "")
                        try {
                            val inputStream = context.assets.open(assetPath)
                            val mimeType = URLConnection.guessContentTypeFromName(File(assetPath).name)
                                ?: "text/html"
                            return WebResourceResponse(mimeType, "UTF-8", inputStream)
                        } catch (_: Exception) {
                            // Файла нет в assets
                        }
                    }

                    return null
                } catch (_: Exception) {
                    return null
                }
            }

            /**
             * Возвращает директорию активной оболочки по имени
             * Использует сохранённое имя активной оболочки для точного поиска
             */
            private fun getActiveShellDir(): File? {
                // Если есть сохранённое имя активной оболочки — ищем конкретную папку
                activeShellName?.let { shellName ->
                    val shellDir = File(activeShellDir, shellName)
                    if (shellDir.exists() && shellDir.isDirectory) {
                        return shellDir
                    }
                }

                // Если имя не сохранено или папка не найдена — ищем первую папку
                val shellDirs = activeShellDir.listFiles { it.isDirectory }
                if (!shellDirs.isNullOrEmpty()) {
                    return shellDirs[0]
                }

                return null
            }

            private fun findFileRecursively(dir: File, fileName: String): File? {
                val files = dir.listFiles()
                files?.forEach { file ->
                    if (file.isFile && file.name == fileName) {
                        return file
                    }
                    if (file.isDirectory) {
                        val found = findFileRecursively(file, fileName)
                        if (found != null) {
                            return found
                        }
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url.toString()
                if (url.startsWith("quty://")) {
                    return false
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)

                val url = failingUrl ?: "unknown"
                val errorDesc = description ?: "unknown error"

                Logger.e(
                    "LauncherWebView",
                    context.getString(R.string.webview_error_loading, url, errorDesc)
                )

                // Если это страница оболочки (не ресурс) — пробуем перезагрузить
                if (isShellPage(url)) {
                    scheduleRetry(url)
                }
            }

            /**
             * Проверяет, является ли URL страницей оболочки (не ресурсом)
             */
            private fun isShellPage(url: String): Boolean {
                return url.contains("index.html") ||
                        url.endsWith("/") ||
                        url.matches(Regex(".*quty://.*"))
            }

            /**
             * Планирует повторную загрузку с задержкой
             */
            private fun scheduleRetry(url: String) {
                // Если это тот же URL, увеличиваем счётчик
                if (lastErrorUrl == url) {
                    errorRetryCount++
                } else {
                    // Новый URL — сбрасываем счётчик
                    errorRetryCount = 1
                    lastErrorUrl = url
                }

                // Если превышен лимит — не перезагружаем
                if (errorRetryCount > MAX_RETRY_COUNT) {
                    Logger.e(
                        "LauncherWebView",
                        context.getString(R.string.webview_max_retries_reached, url, errorRetryCount)
                    )
                    return
                }

                Logger.d(
                    "LauncherWebView",
                    context.getString(R.string.webview_retry_loading, url, errorRetryCount, MAX_RETRY_COUNT)
                )

                // Задержка перед перезагрузкой
                postDelayed({
                    if (url == lastErrorUrl) {
                        loadUrl(url)
                    }
                }, RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Загружает оболочку
     * @param shellName имя оболочки (например, "default" или "custom_shell")
     * @param isAsset true если оболочка из assets, false если кастомная
     */
    fun loadShell(shellName: String, isAsset: Boolean = true) {
        // Сохраняем имя активной оболочки для корректной загрузки ресурсов
        activeShellName = shellName

        // Сбрасываем счётчик ошибок при новой загрузке
        errorRetryCount = 0
        lastErrorUrl = null

        val url = if (isAsset) {
            "https://appassets.androidplatform.net/assets/shells/$shellName/index.html"
        } else {
            // Для кастомных оболочек используем quty:// схему
            "quty://shells/active/index.html"
        }
        loadUrl(url)
    }
}