#!/usr/bin/env bash
# The device tests of the connection service on one emulator.
#
# The script runs the instrumented tests of app:android, and then the two tests that need the
# shell: the sticky restart after a stop of the process, and the start at boot.
#
# Usage: ANDROID_SERIAL=emulator-5576 app/android/scripts/service-device-test.sh
#
# Give the serial of an emulator. The script refuses to run without ANDROID_SERIAL, so that it
# never installs on a physical device. Build the APKs first:
#   ./gradlew :app:android:assembleDebug :app:android:assembleDebugAndroidTest
set -euo pipefail

if [[ -z "${ANDROID_SERIAL:-}" || "${ANDROID_SERIAL}" != emulator-* ]]; then
    echo "Set ANDROID_SERIAL to the serial of an emulator, for example emulator-5576." >&2
    exit 2
fi
S="${ANDROID_SERIAL}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
APP_APK="${ROOT}/app/android/build/outputs/apk/debug/android-debug.apk"
TEST_APK="${ROOT}/app/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"
PKG=org.superdro
RUNNER=org.superdro.test/androidx.test.runner.AndroidJUnitRunner
SERVICE=org.superdro/.platform.android.service.ConnectionService

adb_() { adb -s "${S}" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
# 'am instrument' exits with 0 also when a test fails. Read its output.
instrument() {
    local output
    output="$(adb_ shell am instrument -w "$@" "${RUNNER}" 2>&1)"
    echo "${output}"
    grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" <<<"${output}" && fail "Instrumented tests failed."
    grep -q "^OK (" <<<"${output}" || fail "No result of the instrumented tests."
}
wait_boot() {
    adb_ wait-for-device
    for _ in $(seq 1 120); do
        [[ "$(adb_ shell getprop sys.boot_completed | tr -d '\r')" == "1" ]] && return 0
        sleep 2
    done
    fail "The emulator did not boot."
}
wait_foreground() {
    for _ in $(seq 1 30); do
        adb_ shell dumpsys activity services "${PKG}" | grep -q "isForeground=true" && return 0
        sleep 1
    done
    return 1
}
notification_text() {
    adb_ shell dumpsys notification --noredact | grep -A40 "pkg=${PKG}" | grep -m1 "android.text=" | sed 's/.*String (\(.*\))/\1/'
}

adb_ install -r "${APP_APK}" >/dev/null
adb_ install -r "${TEST_APK}" >/dev/null

echo "== A TCP-only machine with no Bluetooth permission"
adb_ shell pm revoke "${PKG}" android.permission.BLUETOOTH_CONNECT || true
instrument -e class org.superdro.app.android.TcpOnlyServiceDeviceTest

echo "== The instrumented tests"
adb_ shell pm grant "${PKG}" android.permission.BLUETOOTH_CONNECT
adb_ shell pm grant "${PKG}" android.permission.POST_NOTIFICATIONS
instrument -e notClass org.superdro.app.android.TcpOnlyServiceDeviceTest

echo "== The sticky restart after a stop of the process in the background"
instrument -e serviceLifecycle true -e class org.superdro.app.android.ServiceLifecycleSetup#rememberOneFakeMachine
adb_ shell am start -n "${PKG}/org.superdro.app.android.MainActivity" >/dev/null
wait_foreground || fail "The restore at the start of MainActivity did not start the service."
adb_ shell input keyevent KEYCODE_HOME
sleep 2
OLD_PID="$(adb_ shell pidof "${PKG}" | tr -d '\r')"
# 'am kill' does not stop a process with a foreground service. A SIGKILL of the app user acts as
# a stop for low memory: Android restarts the sticky service with a null intent.
adb_ shell run-as "${PKG}" kill -9 "${OLD_PID}"
NEW_PID=""
for _ in $(seq 1 30); do
    NEW_PID="$(adb_ shell pidof "${PKG}" | tr -d '\r' || true)"
    [[ -n "${NEW_PID}" && "${NEW_PID}" != "${OLD_PID}" ]] && break
    sleep 1
done
[[ -n "${NEW_PID}" && "${NEW_PID}" != "${OLD_PID}" ]] || fail "Android did not start the process again."
wait_foreground || fail "The sticky restart did not start the service in the foreground."
sleep 3
TEXT="$(notification_text)"
[[ "${TEXT}" == "Sticky mill: Connected (Demo)" ]] || fail "The sticky restart did not restore the link: '${TEXT}'"
echo "PASS: sticky restart (pid ${OLD_PID} -> ${NEW_PID}), notification '${TEXT}'"

echo "== The start at boot"
# The shell cannot send BOOT_COMPLETED on a user build (a protected broadcast). Reboot.
adb_ reboot
sleep 10
wait_boot
wait_foreground || fail "The service did not start at boot."
sleep 3
TEXT="$(notification_text)"
[[ "${TEXT}" == "Sticky mill: Connected (Demo)" ]] || fail "The boot start did not restore the link: '${TEXT}'"
adb_ logcat -d | grep -q "Background started FGS: Allowed.*code:BOOT_COMPLETED" || echo "NOTE: no 'code:BOOT_COMPLETED' line in the log."
echo "PASS: start at boot, notification '${TEXT}'"

echo "== Cleanup"
instrument -e serviceLifecycleCleanup true -e class org.superdro.app.android.ServiceLifecycleSetup#forgetTheFakeMachine
echo "PASS: all service device tests"
