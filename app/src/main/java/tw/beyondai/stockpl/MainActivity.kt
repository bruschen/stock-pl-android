package tw.beyondai.stockpl

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val historyCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val searchCache =ConcurrentHashMap<String, Pair<Long, JSONObject>>()

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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
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

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = fileChooserCallback
            fileChooserCallback = null
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
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

        /** K 線：window.AndroidQuote.history(code, range, id)，結果透過 window.__historyCallback(id, data) 回傳 */
        @JavascriptInterface
        fun history(code: String, range: String, id: Int) {
            executor.execute {
                val result = try {
                    loadHistory(code, range)
                } catch (e: UnknownHostException) {
                    errorJson("沒有網路連線，請確認手機已連上網路")
                } catch (e: IOException) {
                    errorJson("連線 Yahoo 失敗，請稍後再試")
                } catch (e: Exception) {
                    errorJson("抓取 K 線失敗，請稍後再試")
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        webView.evaluateJavascript("window.__historyCallback($id, $result)", null)
                    }
                }
            }
        }

        /** 代號自動完成：window.AndroidQuote.search(q, id)，結果透過 window.__searchCallback(id, data) 回傳 */
        @JavascriptInterface
        fun search(query: String, id: Int) {
            executor.execute {
                val result = try {
                    searchCodes(query)
                } catch (e: UnknownHostException) {
                    errorJson("沒有網路連線")
                } catch (e: Exception) {
                    errorJson("搜尋失敗")
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        webView.evaluateJavascript("window.__searchCallback($id, $result)", null)
                    }
                }
            }
        }
    }

    private fun loadHistory(raw: String, range: String): JSONObject {
        val code = raw.trim().uppercase(Locale.ROOT)
        if (!CODE_RE.matches(code)) return errorJson("股票代號格式不正確")
        val interval = RANGES[range] ?: return errorJson("不支援的期間")

        val key = "$code|$range"
        val now = System.currentTimeMillis()
        historyCache[key]?.let { (at, data) -> if (now - at < HISTORY_CACHE_MS) return data }

        val data = YahooFinance.history(code, range, interval)
        if (!data.has("error")) historyCache[key] = now to data
        return data
    }

    private fun searchCodes(raw: String): JSONObject {
        val q = raw.trim().uppercase(Locale.ROOT)
        if (!QUERY_RE.matches(q)) return errorJson("搜尋字串格式不正確")

        val now = System.currentTimeMillis()
        searchCache[q]?.let { (at, data) -> if (now - at < SEARCH_CACHE_MS) return data }

        val data = YahooFinance.search(q)
        if (!data.has("error")) searchCache[q] = now to data
        return data
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
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val CACHE_MS = 10_000L     // 同一檔 10 秒內不重抓，避免被限流
        private val CODE_RE = Regex("^\\d{4,6}[A-Z]?$")
        private const val HISTORY_CACHE_MS = 5 * 60_000L  // 日線一天只多一根，5 分鐘內不重抓
        // 期間 → K 棒週期。兩年改用週線，免得一根太細看不清楚
        private val RANGES = mapOf("3mo" to "1d", "6mo" to "1d", "1y" to "1d", "2y" to "1wk")
        private const val SEARCH_CACHE_MS = 5 * 60_000L   // 搜尋結果變動很少，留 5 分鐘
        private val QUERY_RE = Regex("^[0-9A-Z]{2,6}$")
    }
}
