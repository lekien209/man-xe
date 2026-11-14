set -x
mkdir -p /tmp/shots
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 20
adb shell input keyevent 82
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb emu geo fix 105.8542 21.0285
adb logcat -c
adb shell am start -n com.daica.carhud/.MainActivity
sleep 15
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1)
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
adb exec-out screencap -p > /tmp/shots/1_home.png
adb shell input tap $((W*160/1024)) $((H*84/600))
sleep 4
adb exec-out screencap -p > /tmp/shots/2_search.png
adb shell input text "ho%sguom"
sleep 1
adb shell input keyevent 66
sleep 8
adb exec-out screencap -p > /tmp/shots/3_search_result.png
adb shell input keyevent 4
sleep 2
adb logcat -d | grep -iE "carhud|AndroidRuntime|WindowManager.*BadToken" | tail -60 > /tmp/shots/log.txt || true
true
