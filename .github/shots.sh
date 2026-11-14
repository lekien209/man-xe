set -x
mkdir -p /tmp/shots
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 20
adb shell input keyevent 82
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb emu geo fix 105.8542 21.0285
adb shell am start -n com.daica.carhud/.MainActivity
sleep 15
adb shell wm size > /tmp/shots/size.txt
adb exec-out screencap -p > /tmp/shots/1_home.png
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1)
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
adb shell input tap $((W*995/1024)) $((H*20/600))
sleep 4
adb exec-out screencap -p > /tmp/shots/2_settings.png
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400
sleep 3
adb exec-out screencap -p > /tmp/shots/3_settings_scroll.png
adb logcat -d -s AndroidRuntime:E > /tmp/shots/crash.txt || true
true
