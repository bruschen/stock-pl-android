#!/usr/bin/env bash
# 在模擬器上安裝 APK、操作 App、截圖並收集 logcat（座標以 1080x2400 為準）
set -x
OUT=out
mkdir -p $OUT
PKG=tw.beyondai.stockpl
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
top() { adb shell input swipe 540 600 540 2100 300; adb shell input swipe 540 600 540 2100 300; sleep 1; }
down() { adb shell input swipe 540 1900 540 400 600; sleep 2; }
type_code() {   # 清空股票代號欄位後輸入新代號
  top
  adb shell input tap 258 752; sleep 1
  adb shell input keyevent KEYCODE_MOVE_END
  for i in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "$1"
  sleep 12
  adb shell input keyevent KEYCODE_BACK; sleep 2
}
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

# 1. 開啟 App，等即時報價（台積電）
adb shell am start -W -n $PKG/.MainActivity
sleep 30
shot 1-launch
down; shot 2-launch-result

# 2. 示範情境「零股小額」：股價漲了卻虧
top; adb shell input tap 840 550; sleep 2
shot 3-scenario-oddlot
down; shot 4-scenario-oddlot-result

# 3. 不存在的代號
type_code 9999
shot 5-invalid-code

# 4. 換一檔股票：鴻海
type_code 2317
shot 6-other-stock

# 5. 深色模式
adb shell cmd uimode night yes
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n $PKG/.MainActivity
sleep 25
shot 7-dark

adb shell pidof $PKG > $OUT/pid.txt || echo "not running" > $OUT/pid.txt
adb logcat -d > $OUT/logcat.txt
exit 0
