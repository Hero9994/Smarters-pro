#!/usr/bin/env bash
set -eu
api_level="$1"
benchmark=false
if [ "$api_level" = "36" ]; then benchmark=true; fi
gradle :app:assembleDebug --stacktrace
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell run-as app.masahati.mobile.v07 touch files-preserved-marker
test_result=0
gradle :app:connectedPreviewAndroidTest -PpreviewCiTest=true -Pandroid.testInstrumentationRunnerArguments.class=app.masahati.mobile.scanner.NativePdfPlatformInstrumentedTest --stacktrace
adb logcat -d -s ScannerPdfPlatform:I
gradle :app:connectedPreviewAndroidTest -PpreviewCiTest=true -Pandroid.testInstrumentationRunnerArguments.scannerBenchmark="$benchmark" --stacktrace || test_result=$?
mkdir -p scanner-diagnostics
adb pull /data/local/tmp/masahati-scanner-diagnostics scanner-diagnostics/report || true
benchmark_result=0
if [ "$benchmark" = true ]; then
  python3 - scanner-diagnostics/report <<'PY' || benchmark_result=$?
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
try:
    summary = json.loads((root / 'summary.json').read_text())
    records = json.loads((root / 'records.json').read_text())
except (OSError, ValueError) as error:
    raise SystemExit('Real-frame benchmark did not produce valid diagnostics: ' + str(error))
print('SCANNER_BENCHMARK_SUMMARY=' + json.dumps(summary, ensure_ascii=False, sort_keys=True))
worst = sorted(records, key=lambda r: r.get('max_inward_px', float('inf')), reverse=True)[:20]
print('SCANNER_BENCHMARK_WORST_INWARD=' + json.dumps(worst, ensure_ascii=False, sort_keys=True))
if summary.get('real_frames') != 300 or len(records) != 300:
    raise SystemExit('The required 300 real-frame benchmark was incomplete')
PY
fi
adb shell run-as app.masahati.mobile.v07 cat files-preserved-marker
adb shell pm path app.masahati.mobile.v07
adb logcat -d -s ScannerMemory:I
adb logcat -d -s ScannerPdfPlatform:I
if [ "$test_result" -ne 0 ]; then
  adb logcat -d -s MasahatiOCR:W ScannerRegression:I AndroidRuntime:E
  exit "$test_result"
fi
exit "$benchmark_result"
