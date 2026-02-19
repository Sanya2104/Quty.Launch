// *** core/webview/LauncherWebView.kt *** //
package by.quty.launch.core.webview

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

class LauncherWebView(context: Context) : WebView(context) {

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        WebView.setWebContentsDebuggingEnabled(true)
    }
}
