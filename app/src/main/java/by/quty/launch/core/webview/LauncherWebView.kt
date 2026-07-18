// *** core/webview/LauncherWebView.kt *** //
package by.quty.launch.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context) {

    private val activeThemeDir = File(context.filesDir, "themes/active")

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
                return try {
                    // Формат: quty://themes/active/index.html
                    val path = url.replace("quty://", "")

                    // Ищем файл в активной теме
                    val file = File(activeThemeDir, path)

                    // Если файл не найден по прямому пути, пробуем найти в папке с именем темы
                    if (!file.exists()) {
                        val subDirs = activeThemeDir.listFiles { it.isDirectory }
                        subDirs?.forEach { dir ->
                            val candidateFile = File(dir, path)
                            if (candidateFile.exists()) {
                                val mimeType = URLConnection.guessContentTypeFromName(candidateFile.name)
                                    ?: "text/plain"
                                return WebResourceResponse(mimeType, "UTF-8", FileInputStream(candidateFile))
                            }
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
                            val mimeType = URLConnection.guessContentTypeFromName(file.name)
                                ?: "text/plain"
                            return WebResourceResponse(mimeType, "UTF-8", inputStream)
                        } catch (_: Exception) {
                            // Файла нет в assets
                        }
                    }

                    null // 404 Not Found
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                android.util.Log.e("LauncherWebView", "Error: $failingUrl - $description")
            }
        }
    }

    /**
     * Загружает тему
     * @param themeName путь к теме (например, "default" или "custom_theme")
     * @param isAsset true если тема из assets, false если кастомная
     */
    fun loadTheme(themeName: String, isAsset: Boolean = true) {
        val url = if (isAsset) {
            "https://appassets.androidplatform.net/assets/themes/$themeName/index.html"
        } else {
            // Для кастомных тем используем quty:// схему
            "quty://themes/active/index.html"
        }
        loadUrl(url)
    }
}