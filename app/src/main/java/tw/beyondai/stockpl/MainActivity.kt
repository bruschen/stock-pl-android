package tw.beyondai.stockpl

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 用 WebView 顯示 assets/index.html（與網頁版同一份），
 * 報價由這裡直接向 Yahoo Finance 抓取，再回傳給網頁。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // yfinance 背後用的是同一個 Yahoo 資料來源；上市股票代號加 .TW
        val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$code.TW?interval=1m&range=1d")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) StockPL/1.0")
        }
        try {
            val status = conn.responseCode
            if (status == 429) return errorJson("Yahoo 暫時限制請求次數，請稍後再試")
            if (status == 404) return errorJson("查無此代號，或不是上市股票")
            if (status !in 200..299) return errorJson("抓取報價失敗（HTTP $status），請稍後再試")

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val meta = JSONObject(body)
                .optJSONObject("chart")
                ?.optJSONArray("result")
                ?.optJSONObject(0)
                ?.optJSONObject("meta")
                ?: return errorJson("查無此代號，或不是上市股票")

            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            if (price.isNaN()) return errorJson("目前查不到報價")

            val epochSec = meta.optLong("regularMarketTime", 0L)
            val time = if (epochSec > 0) TIME_FMT.get()!!.format(Date(epochSec * 1000)) else "—"
            val name = meta.optString("longName").ifEmpty { meta.optString("shortName") }

            val data = JSONObject()
                .put("code", code)
                .put("name", name)
                .put("price", Math.round(price * 100) / 100.0)
                .put("time", time)
                .put("source", "Yahoo Finance")
            cache[code] = now to data
            return data
        } finally {
            conn.disconnect()
        }
    }

    private fun errorJson(message: String) = JSONObject().put("error", message)

    companion object {
        private const val CACHE_MS = 10_000L     // 同一檔 10 秒內不重抓，避免被限流
        private val CODE_RE = Regex("^\\d{4,6}[A-Z]?$")
        private val TIME_FMT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }
        }
    }
}
