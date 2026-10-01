#!/usr/bin/env bash
set -eu
api_level="$1"
benchmark=false
if [ "$api_level" = "36" ]; then benchmark=true; fi
gradle :app:assembleDebug --stacktrace
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell run-as app.masahati.mobile.v07 touch files-preserved-marker
test_result=0
gradle :app:connectedPreviewAndroidTest -PpreviewCiTest=true -Pandroid.testInstrumentationRunnerArguments.scannerBenchmark="$benchmark" --stacktrace || test_result=$?
mkdir -p scanner-diagnostics
adb exec-out run-as app.masahati.mobile.preview sh -c 'cd files && tar cf - scanner-benchmark-report' > scanner-diagnostics/results.tar || true
adb shell run-as app.masahati.mobile.v07 cat files-preserved-marker
adb shell pm path app.masahati.mobile.v07
if [ "$test_result" -ne 0 ]; then
  adb logcat -d -s MasahatiOCR:W ScannerRegression:I AndroidRuntime:E
  exit "$test_result"
fi
