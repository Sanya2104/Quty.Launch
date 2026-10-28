// *** core/webview/JsBridge.kt *** //
package by.quty.launch.core.webview

import android.content.Context
import android.webkit.JavascriptInterface
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.Core
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Мост JS ↔ Kotlin: асинхронные вызовы API и push-уведомления оболочке.
 */
class JsBridge(
    private val core: Core,
    private val context: Context
) {

    private var webViewRef: WeakReference<LauncherWebView>? = null

    private val timeoutMs = CoreConfig.JS_BRIDGE_TIMEOUT_MS

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setWebView(webView: LauncherWebView) {
        webViewRef = WeakReference(webView)
    }

    /** Вызов API из JS: результат уходит в callbackId. */
    @JavascriptInterface
    fun call(method: String, params: String?, callbackId: String) {
        LoggerManager.debug(
            LoggerTagModel.JSBRIDGE,
            "JSBRIDGE.call  $method"
        )

        bridgeScope.launch {
            try {
                val result = withTimeout(timeoutMs.milliseconds) {
                    core.execute(method, params)
                }

                sendResultToJs(callbackId, result, null)

            } catch (e: TimeoutCancellationException) {
                LoggerManager.warn(
                    LoggerTagModel.JSBRIDGE,
                    "JSBRIDGE.timeout  $method  ${timeoutMs}ms"
                )
                val error = buildErrorJson(context.getString(R.string.js_bridge_timeout_error))
                sendResultToJs(callbackId, error, e)

            } catch (e: CancellationException) {
                LoggerManager.warn(LoggerTagModel.JSBRIDGE, "JSBRIDGE.cancelled  $method")
                val error = buildErrorJson(context.getString(R.string.js_bridge_cancelled_error))
                sendResultToJs(callbackId, error, e)

            } catch (e: Exception) {
                LoggerManager.error(LoggerTagModel.JSBRIDGE, "JSBRIDGE.error  $method", e)
                val error = buildErrorJson(e.message ?: "Unknown error")
                sendResultToJs(callbackId, error, e)
            }
        }
    }

    /** Формирует JSON `{success:false, error:…}` через JSONObject.quote. */
    private fun buildErrorJson(message: String): String {
        return try {
            JSONObject().apply {
                put("success", false)
                put("error", message)
            }.toString()
        } catch (_: Exception) {
            """{"success":false,"error":"Unknown error"}"""
        }
    }

    /** Отправляет результат в JS через evaluateJavascript. */
    private fun sendResultToJs(callbackId: String, result: String, error: Throwable?) {
        val webView = webViewRef?.get() ?: return

        val jsCode = if (error == null) {
            val safeResult = JSONObject.quote(result)
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId']($safeResult);"
        } else {
            val safeError = JSONObject.quote(error.message ?: "Unknown error")
            "window._callbacks && window._callbacks['$callbackId'] && window._callbacks['$callbackId'](null, $safeError);"
        }

        webView.post {
            try {
                webView.evaluateJavascript(jsCode, null)
            } catch (_: Exception) {
            }
        }
    }

    // ============================================================
    // PUSH: СООБЩЕНИЕ ОБОЛОЧКЕ ОБ ИЗМЕНЕНИИ СОСТОЯНИЯ
    // ============================================================

    /** Вызывает window.onCoreStateChanged(), если он определён. */
    fun notifyCoreStateChanged() {
        val webView = webViewRef?.get() ?: return

        val jsCode = """
            (function() {
                if (typeof window.onCoreStateChanged === 'function') {
                    window.onCoreStateChanged();
                }
            })();
        """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(jsCode, null)
            } catch (_: Exception) {
            }
        }
    }
}