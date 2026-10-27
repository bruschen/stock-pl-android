#!/usr/bin/env bash
# 在模擬器上安裝 APK、打開 App、截圖並收集 logcat
set -x
OUT=out
mkdir -p $OUT
PKG=tw.beyondai.stockpl
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package $PKG | grep -E "versionName|minSdk|targetSdk" > $OUT/package.txt
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 30
adb exec-out screencap -p > $OUT/1-launch-light.png
sleep 20
adb exec-out screencap -p > $OUT/2-after-refresh.png
adb shell cmd uimode night yes
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 30
adb exec-out screencap -p > $OUT/3-launch-dark.png
adb shell pidof $PKG > $OUT/pid.txt || echo "not running" > $OUT/pid.txt
adb logcat -d > $OUT/logcat.txt
exit 0
