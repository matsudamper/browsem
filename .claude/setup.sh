#!/usr/bin/env bash
set -euo pipefail

# セットアップスクリプト欄から呼ぶときは cwd がリポジトリ外なので、自分の位置から解決する
cd "${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

# 構築済みの SDK を無視して入れ直さないよう、local.properties の sdk.dir も候補にする
sdk_dir_in_local_properties=""
if [ -f local.properties ]; then
  sdk_dir_in_local_properties="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*\(.*\)$/\1/p' local.properties | tail -n 1)"
fi
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${sdk_dir_in_local_properties:-${HOME}/android-sdk}}}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
# AGP 9.4.0 の DEFAULT_BUILD_TOOLS_REVISION。compileSdk とは独立していて、
# 揃えないと AGP がビルド中に別バージョンを取りに行く
BUILD_TOOLS_VERSION="36.0.0"
SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

convention_plugins="build-logic/src/main/kotlin/net/matsudamper/browser/buildlogic/BrowserLibraryConventionPlugins.kt"
compile_sdk="$(sed -n 's/.*const val COMPILE_SDK = \([0-9]*\)/\1/p' "${convention_plugins}" | head -n 1)"
compile_sdk_minor="$(sed -n 's/.*const val COMPILE_SDK_MINOR = \([0-9]*\)/\1/p' "${convention_plugins}" | head -n 1)"
if [ -z "${compile_sdk}" ] || [ -z "${compile_sdk_minor}" ]; then
  echo "${convention_plugins} から COMPILE_SDK / COMPILE_SDK_MINOR を読めなかった" >&2
  exit 1
fi
platform_id="android-${compile_sdk}.${compile_sdk_minor}"

# SessionStart フックからも呼ぶので、揃っているときは sdkmanager を起動しない。
# sdkmanager は何もすることが無くてもリモートのリポジトリを引きに行って数秒かかる
if [ ! -f "${ANDROID_SDK_ROOT}/platforms/${platform_id}/android.jar" ] ||
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
  # yes だと SIGPIPE で 141 を返し pipefail に引っかかるので、有限個の y を流す
  { for _ in $(seq 1 200); do printf 'y\n'; done; } | "${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" --licenses > /dev/null
  "${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" --install \
    "platform-tools" \
    "platforms;${platform_id}" \
    "build-tools;${BUILD_TOOLS_VERSION}" > /dev/null
fi

# 環境変数(ANDROID_HOME)はセットアップ後のシェルに残らないので local.properties に書く。
# cmake.dir など他のローカル設定を消さないよう sdk.dir の行だけ差し替える
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
  # 既定の umask だと 0644 になり、秘密鍵を同一ホストの別ユーザーにコピーされる
  chmod 600 "${android_user_home}/debug.keystore"
fi

echo "[setup] 完了"
