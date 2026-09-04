#!/usr/bin/env bash
# flaky だった android-test を連続実行して安定性を検証する。
set -euo pipefail

RUNS="${1:-10}"
FLAKY_TEST_CLASSES="net.matsudamper.browser.KeyboardBottomInputTest,net.matsudamper.browser.PageZoomTest"
GRADLE_COMMON_ARGS=(
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
  "-Pandroid.testInstrumentationRunnerArguments.class=${FLAKY_TEST_CLASSES}"
)

for run in $(seq 1 "${RUNS}"); do
  echo "=== Flaky android-test stress run ${run}/${RUNS} ==="
  ./gradlew :app:pixel6Api34DebugAndroidTest "${GRADLE_COMMON_ARGS[@]}"
done

echo "=== All ${RUNS} flaky android-test stress runs passed ==="
