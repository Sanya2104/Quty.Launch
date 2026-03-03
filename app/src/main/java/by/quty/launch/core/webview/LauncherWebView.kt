// *** core/webview/LauncherWebView.kt *** //
package by.quty.launch.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled")
class LauncherWebView(context: Context) : WebView(context) {

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        // Используем аппаратное ускорение для современных CSS
        setLayerType(LAYER_TYPE_HARDWARE, null)

        setWebContentsDebuggingEnabled(true)
    }
}