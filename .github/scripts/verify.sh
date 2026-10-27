#!/usr/bin/env bash
# 在模擬器上安裝 APK、打開 App、截圖並收集 logcat
set -x
OUT=out
mkdir -p $OUT
PKG=tw.beyondai.stockpl
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size > $OUT/screen.txt
adb shell wm density >> $OUT/screen.txt
adb logcat -c

# 1. 開啟 App，等即時報價
adb shell am start -W -n $PKG/.MainActivity
sleep 30
shot 1-top
# 2. 往下捲，看計算結果
adb shell input swipe 300 1500 300 300 600; sleep 2; shot 2-mid
adb shell input swipe 300 1500 300 300 600; sleep 2; shot 3-bottom
adb shell input swipe 300 1500 300 300 600; sleep 2; shot 4-end

# 3. 換成不存在的代號 9999
adb shell input swipe 300 300 300 2000 300; adb shell input swipe 300 300 300 2000 300; sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml $OUT/ui.xml
adb shell input tap $(python3 .github/scripts/find_field.py $OUT/ui.xml 2330)
sleep 1
adb shell input keyevent KEYCODE_MOVE_END
for i in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DEL; done
adb shell input text 9999
sleep 12
adb shell input keyevent KEYCODE_BACK
sleep 2
shot 5-invalid-code

# 4. 深色模式
adb shell cmd uimode night yes
adb shell cmd uimode night > $OUT/uimode.txt
adb shell am force-stop $PKG
sleep 2
adb shell am start -W -n $PKG/.MainActivity
sleep 25
shot 6-dark

adb shell pidof $PKG > $OUT/pid.txt || echo "not running" > $OUT/pid.txt
adb logcat -d > $OUT/logcat.txt
exit 0
