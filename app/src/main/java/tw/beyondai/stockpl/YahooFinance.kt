package tw.beyondai.stockpl

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 一個 process 共用一個 session、一顆 cookie、一顆 crumb。
 * 現價走 v7 quote；crumb 拿不到，或報價不是 429 的失敗，改打 chart。
 * 429 不重試。cookie 與 crumb 只留在記憶體。
 */
internal object YahooFinance {

    private const val QUERY1 = "https://query1.finance.yahoo.com"
    private const val QUERY2 = "https://query2.finance.yahoo.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private const val ACCEPT_LANGUAGE = "en-US,en;q=0.5"

    private val cookies = LinkedHashMap<String, String>()
    private val suffixCache = HashMap<String, String>()
    private var crumb: String? = null
    private var csrf = false

    private val suffixes = listOf(".TW", ".TWO")
    private val SEARCH_CODE_RE = Regex("^\\d{4,6}[A-Z]?$")

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).apply {
            timeZone = TimeZone.getTimeZone("Asia/Taipei")
        }
    }

    /** [code] 是畫面上的代號，例如 2330。先試上市 .TW，沒有再試上櫃 .TWO。 */
    @Synchronized
    fun price(code: String): JSONObject {
        val order = orderedSuffixes(code)
        var switched = false
        for (suffix in order) {
            var step = readQuote(code + suffix, code, suffix)
            if (step is Step.Miss && step.retry && !switched) {
                switched = true
                crumb = null
                csrf = !csrf
                step = readQuote(code + suffix, code, suffix)
                if (step is Step.Miss) {
                    crumb = null
                    csrf = false
                }
            }
            when (step) {
                is Step.Hit -> {
                    suffixCache[code] = suffix
                    return step.json
                }
                is Step.Stop -> return step.json
                is Step.Miss -> Unit
            }
        }
        for (suffix in order) {
            val charted = chart(code, code + suffix, suffix) ?: continue
            if (charted.has("error")) return charted
            suffixCache[code] = suffix
            return charted
        }
        return errorJson("查無此代號，請確認是否為上市或上櫃股票")
    }

    /**
     * 代號自動完成。[query] 是使用者打到一半的代號，例如 233。
     * 回傳 {"items":[{code,name,market,type}]}，只留上市 .TW 與上櫃 .TWO。
     * Yahoo 搜尋只給英文名，有 crumb 時再用一次批次報價換成中文名；換不到就用英文名。
     */
    @Synchronized
    fun search(query: String): JSONObject {
        val res = http(
            "GET",
            "$QUERY2/v1/finance/search?q=${enc(query)}&quotesCount=15&newsCount=0&listsCount=0" +
                "&enableFuzzyQuery=false&quotesQueryId=tss_match_phrase_query&lang=zh-TW&region=TW"
        )
        if (res.body.contains("Will be right back")) return errorJson("Yahoo 正在維護，請稍後再試")
        if (res.status == 429 || res.body.contains("Too Many Requests")) return rateLimit()
        if (res.status !in 200..299) return errorJson("搜尋失敗（HTTP ${res.status}）")
        val quotes = try {
            JSONObject(res.body).optJSONArray("quotes")
        } catch (_: Exception) {
            null
        } ?: return JSONObject().put("items", JSONArray())

        val items = ArrayList<JSONObject>()
        for (i in 0 until quotes.length()) {
            val row = quotes.optJSONObject(i) ?: continue
            val symbol = row.optString("symbol")
            val suffix = suffixes.firstOrNull { symbol.endsWith(it) } ?: continue
            val code = symbol.removeSuffix(suffix)
            if (!SEARCH_CODE_RE.matches(code) || items.any { it.optString("code") == code }) continue
            suffixCache[code] = suffix
            val item = JSONObject()
                .put("code", code)
                .put("symbol", symbol)
                .put("name", row.optString("longname").ifEmpty { row.optString("shortname") })
                .put("market", if (suffix == ".TWO") "上櫃" else "上市")
            typeLabel(row).takeIf { it.isNotEmpty() }?.let { item.put("type", it) }
            items.add(item)
            if (items.size >= 8) break
        }
        if (items.isNotEmpty()) chineseNames(items.map { it.optString("symbol") })?.let { names ->
            for (item in items) names[item.optString("symbol")]?.let { item.put("name", it) }
        }
        items.forEach { it.remove("symbol") }
        return JSONObject().put("items", JSONArray(items))
    }

    /**
     * K 線。[range] 例如 1y，[interval] 例如 1d。
     * 回傳 {"code","range","interval","candles":[[epochSec,open,high,low,close,volume],...]}。
     * 先試記得的市場，查不到（404 或沒有資料）再試另一個。
     */
    @Synchronized
    fun history(code: String, range: String, interval: String): JSONObject {
        for (suffix in orderedSuffixes(code)) {
            val token = crumb?.let { "&crumb=${enc(it)}" }.orEmpty()
            val res = http("GET", "$QUERY2/v8/finance/chart/$code$suffix?range=$range&interval=$interval$token")
            if (res.body.contains("Will be right back")) return errorJson("Yahoo 正在維護，請稍後再試")
            if (res.status == 429 || res.body.contains("Too Many Requests")) return rateLimit()
            if (res.status == 404) continue
            if (res.status !in 200..299) return errorJson("抓取 K 線失敗（HTTP ${res.status}），請稍後再試")
            val candles = parseCandles(res.body) ?: continue
            suffixCache[code] = suffix
            return JSONObject()
                .put("code", code)
                .put("range", range)
                .put("interval", interval)
                .put("candles", candles)
        }
        return errorJson("查無此代號的 K 線資料")
    }

    /** 跳過任何欄位是 null 的那一根（停牌日、盤中尚未成交）。沒有任何一根就回 null。 */
    private fun parseCandles(body: String): JSONArray? {
        val result = try {
            JSONObject(body).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
        } catch (_: Exception) {
            null
        } ?: return null
        val times = result.optJSONArray("timestamp") ?: return null
        val q = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
        val cols = listOf("open", "high", "low", "close").map { q.optJSONArray(it) ?: return null }
        val volume = q.optJSONArray("volume")
        val out = JSONArray()
        for (i in 0 until times.length()) {
            val ohlc = cols.map { col -> col.optDouble(i, Double.NaN) }
            if (ohlc.any { it.isNaN() }) continue
            val row = JSONArray().put(times.optLong(i))
            ohlc.forEach { row.put(Math.round(it * 100) / 100.0) }
            row.put(volume?.optLong(i, 0L) ?: 0L)
            out.put(row)
        }
        return if (out.length() == 0) null else out
    }

    /** 盡力而為：任何失敗都回 null，不影響搜尋結果，也不切換 crumb 策略。 */
    private fun chineseNames(symbols: List<String>): Map<String, String>? {
        val token = (ensureCrumb() as? Crumb.Token)?.value ?: return null
        val res = try {
            http(
                "GET",
                "$QUERY1/v7/finance/quote?symbols=${symbols.joinToString(",")}&formatted=false" +
                    "&lang=zh-TW&region=TW&fields=longName,shortName&crumb=${enc(token)}"
            )
        } catch (_: IOException) {
            return null
        }
        if (res.status !in 200..299) return null
        val rows = try {
            JSONObject(res.body).optJSONObject("quoteResponse")?.optJSONArray("result")
        } catch (_: Exception) {
            null
        } ?: return null
        val names = HashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = displayName(row)
            if (name.isNotEmpty()) names[row.optString("symbol")] = name
        }
        return names
    }

    private fun orderedSuffixes(code: String): List<String> {
        val remembered = suffixCache[code] ?: return suffixes
        return listOf(remembered) + suffixes.filter { it != remembered }
    }

    private fun readQuote(symbol: String, code: String, suffix: String): Step {
        when (val crumbStep = ensureCrumb()) {
            Crumb.RateLimited, Crumb.Unavailable -> return Step.Miss(retry = false)
            Crumb.Empty -> return Step.Miss(retry = true)
            is Crumb.Token -> {
                val res = http(
                    "GET",
                    "$QUERY1/v7/finance/quote?symbols=$symbol&formatted=false&lang=zh-TW&region=TW&crumb=${enc(crumbStep.value)}"
                )
                if (res.body.contains("Will be right back")) return Step.Stop(errorJson("Yahoo 正在維護，請稍後再試"))
                if (res.status == 429 || res.body.contains("Too Many Requests")) return Step.Stop(rateLimit())
                if (res.status >= 400) return Step.Miss(retry = true)
                val parsed = parseQuote(code, res.body, suffix)
                return if (parsed == null) Step.Miss(retry = false) else Step.Hit(parsed)
            }
        }
    }

    private fun ensureCrumb(): Crumb {
        crumb?.let { return Crumb.Token(it) }
        val got = if (csrf) fetchCsrf() else fetchBasic()
        if (got is Crumb.Token) crumb = got.value
        return got
    }

    private fun fetchBasic(): Crumb {
        try {
            http("GET", "https://fc.yahoo.com")
        } catch (_: IOException) {
            return Crumb.Unavailable
        }
        val res = try {
            http("GET", "$QUERY1/v1/test/getcrumb")
        } catch (_: IOException) {
            return Crumb.Unavailable
        }
        if (res.status == 429 || res.body.contains("Too Many Requests")) return Crumb.RateLimited
        val token = cleanCrumb(res.body) ?: return Crumb.Empty
        return Crumb.Token(token)
    }

    private fun fetchCsrf(): Crumb {
        val page = try {
            http("GET", "https://guce.yahoo.com/consent")
        } catch (_: IOException) {
            return Crumb.Unavailable
        }
        val csrfToken = formValue(page.body, "csrfToken") ?: return Crumb.Empty
        val sessionId = formValue(page.body, "sessionId") ?: return Crumb.Empty
        val form = formBody(sessionId, csrfToken)
        try {
            http("POST", "https://consent.yahoo.com/v2/collectConsent?sessionId=${enc(sessionId)}", form)
            val copyUrl = "https://guce.yahoo.com/copyConsent?sessionId=${enc(sessionId)}"
            try {
                http("GET", copyUrl, form)
            } catch (_: ProtocolException) {
                // HttpURLConnection 不能在 GET 帶 body，改以 POST 送同一份 consent form。
                http("POST", copyUrl, form)
            }
        } catch (_: IOException) {
            return Crumb.Unavailable
        }
        val res = try {
            http("GET", "$QUERY2/v1/test/getcrumb")
        } catch (_: IOException) {
            return Crumb.Unavailable
        }
        if (res.status == 429 || res.body.contains("Too Many Requests")) return Crumb.RateLimited
        val token = cleanCrumb(res.body) ?: return Crumb.Empty
        return Crumb.Token(token)
    }

    /** 查不到這檔時回 null，讓呼叫端改試下一個市場。429 與其他 HTTP 錯誤直接回傳。 */
    private fun chart(code: String, symbol: String, suffix: String): JSONObject? {
        val token = crumb?.let { "&crumb=${enc(it)}" }.orEmpty()
        val res = http("GET", "$QUERY2/v8/finance/chart/$symbol?range=1d&interval=1d$token")
        if (res.body.contains("Will be right back")) return errorJson("Yahoo 正在維護，請稍後再試")
        if (res.status == 429 || res.body.contains("Too Many Requests")) return rateLimit()
        if (res.status == 404) return null
        if (res.status !in 200..299) return errorJson("抓取報價失敗（HTTP ${res.status}），請稍後再試")
        return parseChart(code, res.body, suffix)
    }

    private fun parseQuote(code: String, body: String, suffix: String): JSONObject? {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val row = root.optJSONObject("quoteResponse")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val price = number(row, "regularMarketPrice") ?: return null
        return priceJson(code, row, price, row.optLong("regularMarketTime", 0L), suffix)
    }

    private fun parseChart(code: String, body: String, suffix: String): JSONObject? {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val meta = root.optJSONObject("chart")
            ?.optJSONArray("result")
            ?.optJSONObject(0)
            ?.optJSONObject("meta")
            ?: return null
        val price = number(meta, "regularMarketPrice") ?: return null
        return priceJson(code, meta, price, meta.optLong("regularMarketTime", 0L), suffix)
    }

    private fun priceJson(code: String, row: JSONObject, price: Double, epochSec: Long, suffix: String): JSONObject {
        val time = if (epochSec > 0) timeFormat.get()!!.format(Date(epochSec * 1000)) else "—"
        val data = JSONObject()
            .put("code", code)
            .put("name", displayName(row))
            .put("price", Math.round(price * 100) / 100.0)
            .put("time", time)
            .put("source", "Yahoo Finance")
            .put("market", if (suffix == ".TWO") "上櫃" else "上市")
        val exchange = exchangeLabel(row)
        if (exchange.isNotEmpty()) data.put("exchange", exchange)
        val type = typeLabel(row)
        if (type.isNotEmpty()) data.put("type", type)
        taxHint(quoteType(row))?.let { data.put("taxHint", it) }
        return data
    }

    private fun exchangeLabel(row: JSONObject): String {
        return when (row.optString("exchange")) {
            "TAI" -> "台灣證交所"
            "TWO" -> "櫃買中心"
            else -> when (row.optString("fullExchangeName").ifEmpty { row.optString("exchangeName") }) {
                "Taiwan" -> "台灣證交所"
                "Taipei Exchange" -> "櫃買中心"
                else -> row.optString("fullExchangeName").ifEmpty { row.optString("exchangeName") }
            }
        }
    }

    private fun quoteType(row: JSONObject): String {
        val raw = row.optString("quoteType").ifEmpty { row.optString("instrumentType") }
        if (raw.isNotEmpty()) return raw
        return when (row.optString("typeDisp")) {
            "ETF" -> "ETF"
            "股票" -> "EQUITY"
            else -> ""
        }
    }

    private fun typeLabel(row: JSONObject): String {
        val disp = row.optString("typeDisp")
        if (disp.isNotEmpty()) return disp
        return when (quoteType(row)) {
            "EQUITY" -> "股票"
            "ETF" -> "ETF"
            else -> ""
        }
    }

    private fun taxHint(quoteType: String): JSONObject? {
        val (rate, label) = when (quoteType) {
            "ETF" -> 0.001 to "股票型 ETF"
            "EQUITY" -> 0.003 to "一般股票"
            else -> return null
        }
        return JSONObject().put("rate", rate).put("label", label)
    }

    private fun displayName(obj: JSONObject): String {
        val longName = obj.optString("longName")
        if (longName.isNotEmpty()) return longName
        return obj.optString("shortName")
    }

    private fun number(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.optDouble(key, Double.NaN)
        if (value.isNaN()) return null
        return value
    }

    private fun cleanCrumb(body: String): String? {
        val token = body.trim()
        if (token.isEmpty() || token.length > 200 || token.any { it.isWhitespace() }) return null
        if (token.contains("Will be right back")) return null
        if (token.startsWith("<") || token.startsWith("{") || token.startsWith("[")) return null
        return token
    }

    private fun formValue(html: String, name: String): String? {
        val tags = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (tag in tags) {
            val text = tag.value
            val found = Regex("""\bname\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
            if (!name.equals(found, ignoreCase = true)) continue
            return Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
        }
        return null
    }

    private fun formBody(sessionId: String, csrfToken: String): String {
        val pairs = listOf(
            "agree" to "agree",
            "agree" to "agree",
            "consentUUID" to "default",
            "sessionId" to sessionId,
            "csrfToken" to csrfToken,
            "originalDoneUrl" to "https://finance.yahoo.com/",
            "namespace" to "yahoo"
        )
        return pairs.joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }
    }

    private fun http(method: String, url: String, form: String? = null): HttpResult {
        var currentUrl = url
        var currentMethod = method
        var currentForm = form
        for (hop in 0 until 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = currentMethod
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", ACCEPT)
                conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
                cookieHeader()?.let { conn.setRequestProperty("Cookie", it) }
                if (currentForm != null) {
                    val bytes = currentForm.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                storeCookies(conn)
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return HttpResult(status, "")
                    val next = URL(URL(currentUrl), location).toExternalForm()
                    if (!isYahoo(next)) return HttpResult(status, "")
                    currentUrl = next
                    if (status != 307 && status != 308) {
                        currentMethod = "GET"
                        currentForm = null
                    }
                    continue
                }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                return HttpResult(status, body)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("重新導向次數過多")
    }

    private fun storeCookies(conn: HttpURLConnection) {
        val fields = conn.headerFields ?: return
        for ((name, values) in fields) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
            for (raw in values) {
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (key.isEmpty()) continue
                if (value.isEmpty()) cookies.remove(key) else cookies[key] = value
            }
        }
    }

    private fun cookieHeader(): String? {
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun isYahoo(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val host = parsed.host.lowercase(Locale.ROOT)
            parsed.protocol == "https" && (host == "yahoo.com" || host.endsWith(".yahoo.com"))
        } catch (_: Exception) {
            false
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun rateLimit() = errorJson("Yahoo 暫時限制請求次數，請稍後再試")

    private fun errorJson(message: String) = JSONObject().put("error", message)

    private class HttpResult(val status: Int, val body: String)

    private sealed interface Crumb {
        data class Token(val value: String) : Crumb
        data object RateLimited : Crumb
        data object Empty : Crumb
        data object Unavailable : Crumb
    }

    private sealed interface Step {
        class Hit(val json: JSONObject) : Step
        class Stop(val json: JSONObject) : Step
        class Miss(val retry: Boolean) : Step
    }
}
