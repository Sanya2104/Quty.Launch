// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import by.quty.launch.core.Core

class JsBridge(
    private val core: Core
) {

    @JavascriptInterface
    fun call(method: String, params: String?): String {
        return runBlocking(Dispatchers.IO) {
            try {
                withTimeout(10_000) {
                    core.execute(method, params)
                }
            } catch (e: Exception) {
                """{"success": false, "error": "${e.message}"}"""
            }
        }
    }
}
