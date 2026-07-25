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

    private val activeThemeDir = File(context.filesDir, "themes/active")

    // Храним имя активной темы для корректной загрузки
    private var activeThemeName: String? = null

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

        // Настраиваем WebChromeClient для перехвата console.log() из JavaScript
        setupWebChromeClient()
    }

    /**
     * Настройка WebChromeClient для перехвата логов из JavaScript
     * Все console.log(), console.error(), console.warn() и т.д.
     * отправляются в Logger
     */
    private fun setupWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            // Современный метод для Android 8.0+ (API 26+)
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

                    // Определяем папку темы по имени активной темы
                    val themeDir = getActiveThemeDir()
                    if (themeDir == null) {
                        Logger.e(
                            "LauncherWebView",
                            context.getString(R.string.webview_dir_theme_not_found)
                        )
                        return null
                    }

                    // Пытаемся найти файл в папке темы
                    var file = File(themeDir, path)

                    // Если файл не найден и путь начинается с themes/active/,
                    // пробуем убрать этот префикс
                    if (!file.exists() && path.startsWith("themes/active/")) {
                        val relativePath = path.replace("themes/active/", "")
                        file = File(themeDir, relativePath)
                    }

                    // Если всё ещё не найден, пробуем найти файл в подпапках темы
                    if (!file.exists()) {
                        val foundFile = findFileRecursively(themeDir, File(path).name)
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
                    if (path.startsWith("themes/")) {
                        val assetPath = path.replace("themes/", "")
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
             * Возвращает директорию активной темы по имени
             * Использует сохранённое имя активной темы для точного поиска
             */
            private fun getActiveThemeDir(): File? {
                // Если есть сохранённое имя активной темы — ищем конкретную папку
                activeThemeName?.let { themeName ->
                    val themeDir = File(activeThemeDir, themeName)
                    if (themeDir.exists() && themeDir.isDirectory) {
                        return themeDir
                    }
                }

                // Если имя не сохранено или папка не найдена — ищем первую папку
                val themeDirs = activeThemeDir.listFiles { it.isDirectory }
                if (!themeDirs.isNullOrEmpty()) {
                    return themeDirs[0]
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
                Logger.e(
                    "LauncherWebView",
                    context.getString(R.string.webview_error_loading, failingUrl ?: "unknown", description ?: "unknown")
                )
            }
        }
    }

    /**
     * Загружает тему
     * @param themeName имя темы (например, "default" или "custom_theme")
     * @param isAsset true если тема из assets, false если кастомная
     */
    fun loadTheme(themeName: String, isAsset: Boolean = true) {
        // Сохраняем имя активной темы для корректной загрузки ресурсов
        activeThemeName = themeName

        val url = if (isAsset) {
            "https://appassets.androidplatform.net/assets/themes/$themeName/index.html"
        } else {
            // Для кастомных тем используем quty:// схему
            "quty://themes/active/index.html"
        }
        loadUrl(url)
    }
}