# 股票盈虧試算（Android App）

把 `stock-pl-app/index.html` 用 WebView 包成 Android App。報價由 App 直接向 Yahoo Finance 抓取，不需要 Python 伺服器。

## 安裝到手機

1. 用 Android Studio 選 **Open**，打開 `stock-pl-android` 資料夾。第一次打開會自動下載 Gradle 和 Android SDK，需要幾分鐘。
2. 接上 Android 手機（要先在手機的「開發人員選項」開啟 USB 偵錯），或在 Android Studio 建立一個模擬器。
3. 按上方的綠色 ▶ Run，App 會裝到手機上並自動打開。

要產出 APK 檔分享給別人：選單 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**，產出的檔案在 `app/build/outputs/apk/debug/`。

## 架構

- `app/src/main/assets/index.html`：畫面和計算邏輯，和網頁版是同一份檔案。網頁版改了之後，複製一份過來就好。
- `MainActivity.kt`：開啟 WebView，並提供 `AndroidQuote` 給網頁呼叫，負責抓 Yahoo 報價。
- 網頁會自己判斷：在 App 裡用 `AndroidQuote` 抓報價，在瀏覽器裡改走 `server.py`。

## 規格

- 最低支援 Android 8.0（API 26）
- 報價每 15 秒更新一次，同一檔股票 10 秒內不會重抓
- 會跟著手機切換深色或淺色模式

## 已知限制

- Yahoo 的資料網址不是官方 API，Yahoo 改版可能會失效。
- 台股報價可能有延遲，請以畫面上的報價時間為準。
- 字型從 Google Fonts 載入，沒有網路時會改用手機內建字型。
