// *** core/webview/LauncherWebView.kt *** //
package by.quty.launch.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import androidx.core.net.toUri

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context.applicationContext) {

    // Используем applicationContext для предотвращения утечек памяти
    private val appContext = context.applicationContext

    private val activeShellDir = File(appContext.filesDir, "shells/active")

    // Храним имя активной оболочки для корректной загрузки
    private var activeShellName: String? = null

    // Счётчик попыток перезагрузки при ошибке
    private var errorRetryCount = 0
    private var lastErrorUrl: String? = null

    // Максимальное количество попыток перезагрузки (из конфига)
    private companion object {
        private const val MAX_RETRY_COUNT = CoreConfig.WEBVIEW_MAX_RETRY_COUNT
        private const val RETRY_DELAY_MS = CoreConfig.WEBVIEW_RETRY_DELAY_MS
    }

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // ВАЖНО: Отключаем старый опасный доступ к файлам
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // LOAD_DEFAULT вместо LOAD_NO_CACHE — на ряде прошивок LOAD_NO_CACHE
        // ломает загрузку data:image/...;base64,... URI в WebView
        // (иконки приложений не отображаются).
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Используем аппаратное ускорение для современных CSS
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // Включаем отладку WebView
        setWebContentsDebuggingEnabled(true)

        // Настраиваем WebViewAssetLoader
        setupAssetLoader()

        // Настраиваем WebChromeClient — console.log перехват отключён
        setupWebChromeClient()
    }

    /**
     * Настройка WebChromeClient.
     * Перехват console.log() отключён — оболочки используют
     * Android.call для обращения к ядру, а JS console не пробрасывается.
     */
    private fun setupWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                // Пропускаем — консоль не пробрасывается
                return false
            }

            @Suppress("DEPRECATION")
            @Deprecated("Use onConsoleMessage(ConsoleMessage) instead")
            override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
                // Ничего не делаем
            }
        }
    }

    private fun setupAssetLoader() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(appContext))
            .build()

        webViewClient = @SuppressLint("MissingOnRenderProcessGone")
        object : WebViewClient() {
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
                    val shellDir = getActiveShellDir() ?: return null

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
                            val inputStream = appContext.assets.open(assetPath)
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

            /**
             * Перехватываем навигацию по внешним ссылкам.
             *
             * - quty:// — внутренняя схема, грузим сами (вернуть false).
             * - appassets.androidplatform.net — локальный asset-домен, грузим сами.
             * - http(s):// на внешний домен — открываем во внешнем браузере,
             *   чтобы пользователь не «утонул» внутри WebView.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Внутренняя схема — грузим сами
                if (url.startsWith("quty://")) return false

                // Asset-домен (WebViewAssetLoader) — грузим сами
                if (url.startsWith("https://appassets.androidplatform.net/")) return false

                // data:, about:, file: — грузим сами (нужно для JS-логики оболочек)
                if (url.startsWith("data:") ||
                    url.startsWith("about:") ||
                    url.startsWith("file:")
                ) return false

                // Всё остальное — внешняя ссылка, открываем во внешнем браузере
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        url.toUri()
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                } catch (_: Exception) {
                    // Игнорируем ошибку открытия ссылки
                }

                // Возвращаем true — WebView сам НЕ грузит ссылку
                return true
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

                // Если это страница оболочки (не ресурс) — пробуем перезагрузить
                if (isShellPage(url)) {
                    scheduleRetry(url)
                }
            }

            /**
             * Render-процесс WebView упал (обычно из-за OOM).
             * Возвращаем true — обработали ситуацию, приложение не упадёт.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        appContext,
                        appContext.getString(R.string.webview_render_process_gone),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    try {
                        val intent = android.content.Intent(
                            appContext,
                            by.quty.launch.MainActivity::class.java
                        )
                        intent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        appContext.startActivity(intent)
                    } catch (_: Exception) {
                        // Игнорируем ошибку перезапуска
                    }
                }

                return true
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
                    return
                }

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