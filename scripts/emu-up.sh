#!/usr/bin/env bash
# 在这台没有可用 KVM 的盒子上，用纯软件 CPU（TCG）启动一个轻量的安卓 11 ATD 模拟器。
# 用法: scripts/emu-up.sh [avd名, 默认 atd30]      结束: adb emu kill
#
# 为什么这样配（详见 NOTES.md「运行时测试」）：
#  - /dev/kvm 存在但 KVM_CREATE_VCPU 会触发宿主内核 BUG (kvm_spurious_fault)，qemu 卡死在内核里 => 只能 -accel off。
#  - 没有 binder 内核模块 => Redroid / Waydroid 不可用。
#  - API 30 google_atd（Automated Test Device，去掉了大部分系统 App）在 TCG 下比 API 34 google_apis 轻得多，
#    720x1280 小屏 + 关动画 + 关锁屏后 system_server 稳定。
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/workspace/tools/android}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=${JAVA_HOME:-/workspace/tools/jdk17}
export ANDROID_I_WANT_MY_TCG=yes
ADB=$ANDROID_HOME/platform-tools/adb
AVD=${1:-atd30}
IMG="system-images;android-30;google_atd;x86_64"
LOG=${EMU_LOG:-/workspace/stab-logs/emu-$AVD.log}
mkdir -p "$(dirname "$LOG")"

if ! [ -d "$HOME/.android/avd/$AVD.avd" ]; then
  [ -d "$ANDROID_HOME/system-images/android-30/google_atd/x86_64" ] || yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$IMG"
  echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMG" -d pixel_4 --force
  python3 - "$HOME/.android/avd/$AVD.avd/config.ini" <<'PY'
import sys, collections
p = sys.argv[1]; d = collections.OrderedDict()
for l in open(p).read().splitlines():
    if '=' in l:
        k, v = l.split('=', 1); d[k.strip()] = v.strip()
d.update({'hw.lcd.width': '720', 'hw.lcd.height': '1280', 'hw.lcd.density': '320', 'hw.ramSize': '3072',
          'hw.cpu.ncore': '4', 'hw.audioInput': 'no', 'hw.audioOutput': 'no', 'hw.camera.back': 'none',
          'hw.camera.front': 'none', 'hw.gps': 'no', 'hw.keyboard': 'yes', 'disk.dataPartition.size': '4G',
          'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader_indirect',
          'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no'})
open(p, 'w').write('\n'.join(f'{k}={v}' for k, v in d.items()) + '\n')
PY
fi

if "$ADB" devices | grep -q '^emulator-.*device$'; then
  echo "emulator already running"
else
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -gpu swiftshader_indirect -no-snapshot \
    -no-boot-anim -no-audio -memory 3072 -cores 4 -accel off -no-metrics ${EMU_WIPE:+-wipe-data} >"$LOG" 2>&1 &
  echo "emulator pid $! log $LOG"
fi

# TCG 冷启动约 2–5 分钟
"$ADB" wait-for-device
for i in $(seq 1 180); do
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 5
done
[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo "boot timeout"; exit 1; }
# 等 PackageManager/ActivityManager 真正可用
for i in $(seq 1 60); do "$ADB" shell pm path android >/dev/null 2>&1 && break; sleep 3; done

# 关动画、关锁屏、屏幕常亮，避免 UI 测试卡在锁屏或动画上
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell settings put secure lockscreen.disabled 1 || true
"$ADB" shell locksettings set-disabled true >/dev/null 2>&1 || true
"$ADB" shell settings put system screen_off_timeout 2147483647
"$ADB" shell svc power stayon true
"$ADB" shell settings put secure show_ime_with_hard_keyboard 0 || true
"$ADB" shell settings put global package_verifier_enable 0 || true
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard || true
"$ADB" shell input keyevent 82
# 宿主上的测试站点（/workspace/umu-testsite）在模拟器里是 http://10.0.2.2:8000/
"$ADB" shell dumpsys window | grep -E "mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen" | head -3 || true
echo "ready: $("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"
