// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.webkit.JavascriptInterface
import kotlinx.coroutines.*
import by.quty.launch.core.Core

class JsBridge(
    private val core: Core
) {

    @JavascriptInterface
    fun call(method: String, params: String?) {

        CoroutineScope(Dispatchers.Main).launch {

            val result = withTimeoutOrNull(10_000) {
                core.execute(method, params)
            }

            core.execute(method, params)
        }
    }
}
