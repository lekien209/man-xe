set -x
mkdir -p /tmp/shots
adb shell wm size 1024x600; adb shell wm density 160
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global window_animation_scale 0
adb emu geo fix 105.8542 21.0285
adb shell am start -n com.daica.carhud/.MainActivity
sleep 12
adb exec-out screencap -p > /tmp/shots/1_home.png
# mở Cài đặt: bấm bánh răng (góc trên phải)
adb shell input tap 995 20
sleep 3
adb exec-out screencap -p > /tmp/shots/2_settings.png
adb shell input swipe 512 450 512 150 300
sleep 2
adb exec-out screencap -p > /tmp/shots/3_settings_scroll.png
adb logcat -d -s AndroidRuntime:E > /tmp/shots/crash.txt || true
true
