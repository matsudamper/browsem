#!/bin/bash

set -e

# Only run in Claude Code remote environment
if [ -z "$CLAUDE_CODE_REMOTE" ]; then
  exit 0
fi

echo "[session-start] Setting up Gradle and Android environment..."

# Parse HTTPS_PROXY to extract credentials
if [ -n "$HTTPS_PROXY" ]; then
  # HTTPS_PROXY format: http://user:password@host:port
  PROXY_URL="${HTTPS_PROXY#http://}"
  PROXY_URL="${PROXY_URL#https://}"

  if [[ "$PROXY_URL" =~ ^([^:]+):([^@]+)@([^:]+):([0-9]+)$ ]]; then
    PROXY_USER="${BASH_REMATCH[1]}"
    PROXY_PASS="${BASH_REMATCH[2]}"
    PROXY_HOST="${BASH_REMATCH[3]}"
    PROXY_PORT="${BASH_REMATCH[4]}"
  elif [[ "$HTTPS_PROXY" =~ ^http://([^:]+):([0-9]+)$ ]]; then
    PROXY_HOST="${BASH_REMATCH[1]}"
    PROXY_PORT="${BASH_REMATCH[2]}"
  fi
fi

# Configure Gradle proxy settings
if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ]; then
  echo "[session-start] Configuring Gradle proxy: $PROXY_HOST:$PROXY_PORT"

  mkdir -p ~/.gradle
  cat > ~/.gradle/gradle.properties << EOF
# Gradle proxy configuration
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1
EOF

  if [ -n "$PROXY_USER" ] && [ -n "$PROXY_PASS" ]; then
    cat >> ~/.gradle/gradle.properties << EOF
systemProp.http.proxyUser=$PROXY_USER
systemProp.http.proxyPassword=$PROXY_PASS
systemProp.https.proxyUser=$PROXY_USER
systemProp.https.proxyPassword=$PROXY_PASS
EOF
  fi
fi

# Setup Android SDK
if [ ! -d "$HOME/android-sdk" ]; then
  echo "[session-start] Setting up Android SDK..."

  mkdir -p ~/android-sdk
  cd ~/android-sdk

  # Download Android SDK command-line tools
  SDK_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  echo "[session-start] Downloading Android SDK tools from $SDK_URL"
  curl -fL "$SDK_URL" -o cmdline-tools.zip 2>&1 || echo "Failed to download Android SDK"

  if [ -f cmdline-tools.zip ]; then
    unzip -q cmdline-tools.zip
    rm cmdline-tools.zip

    # Create the required directory structure
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true

    # Accept licenses
    echo "[session-start] Accepting Android SDK licenses..."
    yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true
  fi
fi

# Install required Android SDK components
if [ -f ~/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "[session-start] Installing Android SDK components..."
  # Install platform and build-tools (use stable version 34)
  yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    > /dev/null 2>&1 || echo "[session-start] Note: Some SDK components may require manual acceptance of licenses"
fi

# Write local.properties with Android SDK path
if [ -f ~/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "[session-start] Writing local.properties with Android SDK path..."
  echo "sdk.dir=$HOME/android-sdk" > /home/user/browsem/local.properties
fi

# Install protobuf compiler if not already present
if ! command -v protoc &> /dev/null; then
  echo "[session-start] Installing protobuf compiler..."
  apt-get update > /dev/null 2>&1 && \
  apt-get install -y protobuf-compiler > /dev/null 2>&1 && \
  echo "[session-start] Protobuf compiler installed" || \
  echo "[session-start] Could not install protobuf compiler"
fi

# Pre-download Gradle to avoid network issues
if [ ! -d "$HOME/.gradle/wrapper/dists" ] || [ -z "$(find $HOME/.gradle/wrapper/dists -name 'gradle-9.1.0*' 2>/dev/null)" ]; then
  echo "[session-start] Pre-downloading Gradle 9.1.0..."
  cd /home/user/browsem
  # The gradlew script will download it, but we can try to cache it
  ./gradlew -v > /dev/null 2>&1 || true
fi

echo "[session-start] Environment setup complete!"
