#!/usr/bin/env bash
set -euo pipefail

if [ -n "${CLAUDE_PROJECT_DIR:-}" ] && [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

sdk_dir_in_local_properties=""
if [ -f local.properties ]; then
  sdk_dir_in_local_properties="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*\(.*\)$/\1/p' local.properties | tail -n 1)"
fi
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${sdk_dir_in_local_properties:-${HOME}/android-sdk}}}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
BUILD_TOOLS_VERSION="36.0.0"
SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

versions_toml="gradle/libs.versions.toml"
compile_sdk="$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\)".*/\1/p' "${versions_toml}" | head -n 1)"
compile_sdk_minor="$(sed -n 's/^[[:space:]]*compileSdkMinor[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\)".*/\1/p' "${versions_toml}" | head -n 1)"
if [ -z "${compile_sdk}" ] || [ -z "${compile_sdk_minor}" ]; then
  echo "${versions_toml} から compileSdk / compileSdkMinor を読めなかった" >&2
  exit 1
fi
platform_package="platforms;android-${compile_sdk}.${compile_sdk_minor}"
platform_dir="${ANDROID_SDK_ROOT}/platforms/android-${compile_sdk}.${compile_sdk_minor}"

if [ ! -f "${platform_dir}/android.jar" ] ||
  [ ! -d "${ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}" ] ||
  [ ! -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]; then
  if [ ! -x "${SDKMANAGER}" ]; then
    echo "[setup] cmdline-tools を導入する"
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
    tmp="$(mktemp -d)"
    trap 'rm -rf "${tmp}"' EXIT
    curl -fsSL -o "${tmp}/cmdline-tools.zip" "${CMDLINE_TOOLS_URL}"
    unzip -q "${tmp}/cmdline-tools.zip" -d "${tmp}"
    rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
    mv "${tmp}/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  fi

  echo "[setup] Android SDK パッケージを導入する (compileSdk=${compile_sdk}.${compile_sdk_minor})"
  { for _ in $(seq 1 200); do printf 'y\n'; done; } | "${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" --licenses > /dev/null
  "${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" --install \
    "platform-tools" \
    "${platform_package}" \
    "build-tools;${BUILD_TOOLS_VERSION}" > /dev/null
fi

tmp_local_properties="$(mktemp ./.local.properties.XXXXXX)"
if [ -f local.properties ]; then
  grep -v -E '^[[:space:]]*sdk\.dir[[:space:]]*=' local.properties > "${tmp_local_properties}" || true
fi
echo "sdk.dir=${ANDROID_SDK_ROOT}" >> "${tmp_local_properties}"
mv "${tmp_local_properties}" local.properties

android_user_home="${ANDROID_USER_HOME:-${HOME}/.android}"
if [ ! -f "${android_user_home}/debug.keystore" ]; then
  echo "[setup] debug.keystore を作る"
  mkdir -p "${android_user_home}"
  keytool -genkeypair -keystore "${android_user_home}/debug.keystore" \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null
  chmod 600 "${android_user_home}/debug.keystore"
fi

if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  bash "$(dirname "$0")/hooks/session-start.sh"
fi

echo "[setup] 完了"
