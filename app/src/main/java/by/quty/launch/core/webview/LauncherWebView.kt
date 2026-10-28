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
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import androidx.core.net.toUri

/**
 * WebView-хост оболочки: asset-загрузчик, quty://, внешние ссылки, CSS для отключения анимаций.
 */
@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context.applicationContext) {

    private val appContext = context.applicationContext

    private val activeShellDir = File(appContext.filesDir, "shells/active")

    private var activeShellName: String? = null

    /** Последнее применённое состояние анимаций — для onPageFinished. */
    private var lastAnimationsEnabled: Boolean = true

    private var errorRetryCount = 0
    private var lastErrorUrl: String? = null

    private companion object {
        private const val MAX_RETRY_COUNT = CoreConfig.WEBVIEW_MAX_RETRY_COUNT
        private const val RETRY_DELAY_MS = CoreConfig.WEBVIEW_RETRY_DELAY_MS
    }

    /** Настраивает WebView: настройки, asset loader, chrome client. */
    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        setLayerType(LAYER_TYPE_HARDWARE, null)

        setWebContentsDebuggingEnabled(true)

        setupAssetLoader()
        setupWebChromeClient()
    }

    /** WebChromeClient без перехвата console.log. */
    private fun setupWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                return false
            }

            @Suppress("DEPRECATION")
            @Deprecated("Use onConsoleMessage(ConsoleMessage) instead")
            override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
            }
        }
    }

    /** Настраивает asset loader, обработчик quty://, retry и внешних ссылок. */
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

                if (url.startsWith("quty://")) {
                    return handleQutyScheme(url)
                }

                return assetLoader.shouldInterceptRequest(request.url)
            }

            /** Отдаёт файл оболочки по quty://, с fallback на assets. */
            private fun handleQutyScheme(url: String): WebResourceResponse? {
                try {
                    var path = url.replace("quty://", "")

                    if (path.startsWith("./")) {
                        path = path.substring(2)
                    }

                    val shellDir = getActiveShellDir() ?: return null

                    var file = File(shellDir, path)

                    if (!file.exists() && path.startsWith("shells/active/")) {
                        val relativePath = path.replace("shells/active/", "")
                        file = File(shellDir, relativePath)
                    }

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

                    if (path.startsWith("shells/")) {
                        val assetPath = path.replace("shells/", "")
                        try {
                            val inputStream = appContext.assets.open(assetPath)
                            val mimeType = URLConnection.guessContentTypeFromName(File(assetPath).name)
                                ?: "text/html"
                            return WebResourceResponse(mimeType, "UTF-8", inputStream)
                        } catch (_: Exception) {
                        }
                    }

                    LoggerManager.warn(
                        LoggerTagModel.WEBVIEW,
                        "WEBVIEW.quty  file not found  $path"
                    )
                    return null
                } catch (e: Exception) {
                    LoggerManager.warn(
                        LoggerTagModel.WEBVIEW,
                        "WEBVIEW.quty  error  ${e.message ?: e.javaClass.simpleName}",
                        e
                    )
                    return null
                }
            }

            /** Возвращает папку распакованной активной оболочки. */
            private fun getActiveShellDir(): File? {
                activeShellName?.let { shellName ->
                    val shellDir = File(activeShellDir, shellName)
                    if (shellDir.exists() && shellDir.isDirectory) {
                        return shellDir
                    }
                }

                val shellDirs = activeShellDir.listFiles { it.isDirectory }
                if (!shellDirs.isNullOrEmpty()) {
                    return shellDirs[0]
                }

                return null
            }

            /** Ищет файл по имени рекурсивно в папке. */
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

            /** Внутренние схемы — грузим сами, внешние — в браузер. */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("quty://")) return false

                if (url.startsWith("https://appassets.androidplatform.net/")) return false

                if (url.startsWith("data:") ||
                    url.startsWith("about:") ||
                    url.startsWith("file:")
                ) return false

                try {
                    LoggerManager.debug(
                        LoggerTagModel.WEBVIEW,
                        "WEBVIEW.external  $url"
                    )
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        url.toUri()
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                } catch (_: Exception) {
                }

                return true
            }

            /** Применяет состояние анимаций после загрузки страницы. */
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                applyAnimationsEnabledInternal(lastAnimationsEnabled)
            }

            /** Логирует ошибку и пробует перезагрузить страницу оболочки. */
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)

                val url = failingUrl ?: "unknown"

                LoggerManager.error(
                    LoggerTagModel.WEBVIEW,
                    "WEBVIEW.error  code=$errorCode  $description"
                )

                if (isShellPage(url)) {
                    scheduleRetry(url)
                }
            }

            /** Render-процесс упал: возвращаем true, перезапускаем MainActivity. */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                LoggerManager.error(
                    LoggerTagModel.WEBVIEW,
                    "WEBVIEW.crash  crashed=${detail?.didCrash()}"
                )

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
                    }
                }

                return true
            }

            /** true, если URL — страница оболочки. */
            private fun isShellPage(url: String): Boolean {
                return url.contains("index.html") ||
                        url.endsWith("/") ||
                        url.matches(Regex(".*quty://.*"))
            }

            /** Планирует повторную загрузку с лимитом попыток. */
            private fun scheduleRetry(url: String) {
                if (lastErrorUrl == url) {
                    errorRetryCount++
                } else {
                    errorRetryCount = 1
                    lastErrorUrl = url
                }

                if (errorRetryCount > MAX_RETRY_COUNT) {
                    LoggerManager.warn(
                        LoggerTagModel.WEBVIEW,
                        "WEBVIEW.retry_limit  attempts=$errorRetryCount"
                    )
                    return
                }

                LoggerManager.warn(
                    LoggerTagModel.WEBVIEW,
                    "WEBVIEW.retry  attempt=$errorRetryCount"
                )

                postDelayed({
                    if (url == lastErrorUrl) {
                        loadUrl(url)
                    }
                }, RETRY_DELAY_MS)
            }
        }
    }

    /** Загружает оболочку: assets или quty://. */
    fun loadShell(shellName: String, isAsset: Boolean = true) {
        activeShellName = shellName

        errorRetryCount = 0
        lastErrorUrl = null

        LoggerManager.info(
            LoggerTagModel.WEBVIEW,
            "WEBVIEW.load  $shellName  asset=$isAsset"
        )

        val url = if (isAsset) {
            "https://appassets.androidplatform.net/assets/shells/$shellName/index.html"
        } else {
            "quty://shells/active/index.html"
        }
        loadUrl(url)
    }

    /** Включает/выключает CSS-анимации через инжект `<style>`. */
    fun applyAnimationsEnabled(enabled: Boolean) {
        lastAnimationsEnabled = enabled
        applyAnimationsEnabledInternal(enabled)
    }

    /** Сам инжект. Вызывается из applyAnimationsEnabled и onPageFinished. */
    private fun applyAnimationsEnabledInternal(enabled: Boolean) {
        val js = if (enabled) {
            """
                (function() {
                    var el = document.getElementById('__quty_no_anim');
                    if (el) el.remove();
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    if (document.getElementById('__quty_no_anim')) return;
                    var s = document.createElement('style');
                    s.id = '__quty_no_anim';
                    s.textContent = '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }';
                    (document.head || document.documentElement).appendChild(s);
                })();
            """.trimIndent()
        }

        post {
            try {
                evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }
}