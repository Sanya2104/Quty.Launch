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
import java.net.URLConnection
import androidx.core.net.toUri

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context) {

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // ВАЖНО: Отключаем старый опасный доступ к файлам
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

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
                    // Формат: quty://themes/active/assets/js/app.js
                    val path = url.replace("quty://", "")

                    // Определяем, откуда грузить: из активной темы или из assets
                    val file = when {
                        path.startsWith("themes/active/") -> {
                            // Активная тема (распакованная)
                            val themePath = path.replace("themes/active/", "")
                            File(context.filesDir, "themes/active/$themePath")
                        }
                        path.startsWith("themes/builtin/") -> {
                            // Встроенная тема (запасной вариант)
                            val themePath = path.replace("themes/builtin/", "")
                            context.assets.open("themes/$themePath")
                            return assetLoader.shouldInterceptRequest(
                                "https://appassets.androidplatform.net/assets/themes/$themePath".toUri()
                            )
                        }
                        else -> null
                    }

                    if (file != null && file.exists()) {
                        val mimeType = URLConnection.guessContentTypeFromName(file.name)
                            ?: "text/plain"
                        return WebResourceResponse(mimeType, "UTF-8", file.inputStream())
                    }

                    null // 404 Not Found
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
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
            "quty://themes/active/$themeName/index.html"
        }
        loadUrl(url)
    }
}