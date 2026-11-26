#!/usr/bin/env bash
# 在模拟器上跑冒烟测试。用法：
#   scripts/smoke.sh debug|release [测试类或方法，如 com.yuka.cutebrowser.smoke.SmokeTest#t04_incognitoToggleNoPileUp]
# 先 scripts/emu-up.sh。结果、截图、logcat 在 /workspace/stab-logs/smoke-<variant>-<时间>/
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/workspace/tools/jdk17} ANDROID_HOME=${ANDROID_HOME:-/workspace/tools/android}
ADB=$ANDROID_HOME/platform-tools/adb
VARIANT=${1:-debug}
FILTER=${2:-com.yuka.cutebrowser.smoke.SmokeTest}
SCALE=${SMOKE_TIMEOUT_SCALE:-2}
OUT=/workspace/stab-logs/smoke-$VARIANT-$(date +%m%d-%H%M%S); mkdir -p "$OUT"
PCD="$PWD/.gradle-pc"

if [ -z "${SKIP_BUILD:-}" ]; then
  ./gradlew -q --project-cache-dir "$PCD" ":app:assemble${VARIANT^}" :smoketest:assembleDebug || exit 1
fi
APP=app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
TEST=smoketest/build/outputs/apk/debug/smoketest-debug.apk

# 测试站点（宿主 8000 端口）；模拟器里用 adb reverse 让 localhost:8000 指向宿主
curl -s -o /dev/null http://localhost:8000/ || (cd /workspace/umu-testsite && nohup python3 server.py >"$OUT/site.log" 2>&1 &)
"$ADB" reverse tcp:8000 tcp:8000

# debug/release 签名不同，切换时得先卸载
"$ADB" install -r -g "$APP" >/dev/null 2>&1 || { "$ADB" uninstall com.yuka.cutebrowser >/dev/null; "$ADB" install -g "$APP"; } || exit 1
"$ADB" install -r -g "$TEST" >/dev/null || exit 1
"$ADB" shell rm -rf /sdcard/Download/umu-smoke
"$ADB" logcat -c; "$ADB" logcat -b crash -c
"$ADB" logcat -v threadtime > "$OUT/logcat.txt" 2>&1 & LC=$!

"$ADB" shell am instrument -w -r -e class "$FILTER" -e timeoutScale "$SCALE" -e testHost http://localhost:8000 \
  com.yuka.cutebrowser.smoke/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"

kill $LC 2>/dev/null
"$ADB" logcat -d -b crash > "$OUT/crash.txt"
"$ADB" pull /sdcard/Download/umu-smoke "$OUT/device" >/dev/null 2>&1
python3 - "$OUT/instrument.txt" <<'PY'
import re, sys
t = open(sys.argv[1], errors='replace').read()
tests = re.findall(r'INSTRUMENTATION_STATUS: test=(\S+)\s*\nINSTRUMENTATION_STATUS_CODE: (-?\d+)', t)
res = {}
for name, code in tests:
    if code in ('0', '-2', '-3', '-4'): res[name] = {'0': 'PASS', '-2': 'FAIL', '-3': 'IGNORED', '-4': 'ASSUMPTION'}[code]
for k, v in res.items(): print(f"{v:5} {k}")
print(f"{sum(v=='PASS' for v in res.values())}/{len(res)} passed")
PY
echo "crash buffer: $(grep -c 'FATAL' "$OUT/crash.txt") fatal; logs in $OUT"
