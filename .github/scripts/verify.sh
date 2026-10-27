#!/usr/bin/env bash
# 在模擬器上安裝 APK，透過 WebView 偵錯通道操作網頁並驗證
set -x
OUT=out
mkdir -p $OUT
PKG=tw.beyondai.stockpl
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 15
adb exec-out screencap -p > $OUT/0-launch.png

# 找到這個 App 的 WebView 偵錯 socket 並轉到本機 9222
PID=$(adb shell pidof $PKG | tr -d '\r')
SOCK=$(adb shell cat /proc/net/unix | tr -d '\r' | grep -o "webview_devtools_remote_${PID}" | head -1)
echo "pid=$PID sock=$SOCK"
adb forward tcp:9222 localabstract:$SOCK
python3 .github/scripts/cdp_test.py $OUT 2>&1 | tee $OUT/test-log.txt

# 深色模式（只截圖目視）
adb shell cmd uimode night yes
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n $PKG/.MainActivity
sleep 15
adb exec-out screencap -p > $OUT/6-dark.png

adb logcat -d > $OUT/logcat.txt
grep -E "chromium.*(Uncaught|CONSOLE.*[Ee]rror)|FATAL EXCEPTION" $OUT/logcat.txt > $OUT/js-errors.txt || true
exit 0
