package tw.beyondai.stockpl

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 用 WebView 顯示 assets/index.html。
 * 報價仍走 YahooFinance（cookie、crumb、v7 quote），畫面用新版介面。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只有 debug 版開放 WebView 偵錯，供自動化測試操作網頁
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()          // 所有頁面都留在 App 內
        webView.addJavascriptInterface(QuoteBridge(), "AndroidQuote")

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    /** 網頁呼叫 window.AndroidQuote.fetch(code, id)，結果透過 window.__quoteCallback(id, data) 回傳 */
    inner class QuoteBridge {
        @JavascriptInterface
        fun fetch(code: String, id: Int) {
            executor.execute {
                val result = try {
                    fetchQuote(code)
                } catch (e: UnknownHostException) {
                    errorJson("沒有網路連線，請確認手機已連上網路")
                } catch (e: IOException) {
                    errorJson("連線 Yahoo 失敗，請稍後再試")
                } catch (e: Exception) {
                    errorJson("抓取報價失敗，請稍後再試")
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        webView.evaluateJavascript("window.__quoteCallback($id, $result)", null)
                    }
                }
            }
        }
    }

    private fun fetchQuote(raw: String): JSONObject {
        val code = raw.trim().uppercase(Locale.ROOT)
        if (!CODE_RE.matches(code)) return errorJson("股票代號格式不正確")

        val now = System.currentTimeMillis()
        cache[code]?.let { (at, data) -> if (now - at < CACHE_MS) return data }

        val data = YahooFinance.price(code)
        if (!data.has("error")) cache[code] = now to data
        return data
    }

    private fun errorJson(message: String) = JSONObject().put("error", message)

    companion object {
        private const val CACHE_MS = 10_000L     // 同一檔 10 秒內不重抓，避免被限流
        private val CODE_RE = Regex("^\\d{4,6}[A-Z]?$")
    }
}
