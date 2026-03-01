#!/bin/bash
set -euo pipefail

# Only run in Claude Code Web remote environment
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

echo "[session-start] Setting up Gradle and Android environment..."

python3 - <<'PYEOF'
import os, sys, re, subprocess, tempfile, zipfile, shutil, urllib.request, urllib.parse

proxy_url = os.environ.get('HTTPS_PROXY', '')
if not proxy_url:
    print("[session-start] HTTPS_PROXY is not set; skipping proxy configuration")
    sys.exit(0)

parsed   = urllib.parse.urlparse(proxy_url)
host     = parsed.hostname
port     = str(parsed.port)
user     = parsed.username or ''
password = parsed.password or ''

gradle_home = os.path.expanduser('~/.gradle')
os.makedirs(gradle_home, exist_ok=True)

# ── Gradle init script: Authenticator を設定してプロキシ認証を有効化 ───────────
init_d = os.path.join(gradle_home, 'init.d')
os.makedirs(init_d, exist_ok=True)
init_script = os.path.join(init_d, 'proxy-auth.gradle')
with open(init_script, 'w') as f:
    f.write(f"""import java.net.Authenticator
import java.net.PasswordAuthentication

def proxyUser = System.getProperty("https.proxyUser") ?: System.getProperty("http.proxyUser")
def proxyPassword = System.getProperty("https.proxyPassword") ?: System.getProperty("http.proxyPassword")

if (proxyUser && proxyPassword) {{
    Authenticator.setDefault(new Authenticator() {{
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {{
            if (getRequestorType() == Authenticator.RequestorType.PROXY) {{
                return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray())
            }}
            return null
        }}
    }})
}}
""")
print(f"[session-start] Gradle init script written: {init_script}")

# ── proxy_opener: Python 経由でプロキシを通じてダウンロード ─────────────────────
proxy_handler = urllib.request.ProxyHandler({'https': proxy_url, 'http': proxy_url})
opener = urllib.request.build_opener(proxy_handler)

def download(url, dest_path):
    print(f"[session-start] Downloading {url} ...")
    with opener.open(url) as resp:
        total = int(resp.headers.get('Content-Length', 0))
        downloaded = 0
        with open(dest_path, 'wb') as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total:
                    pct = downloaded * 100 // total
                    print(f"\r  {pct}% ({downloaded}/{total} bytes)", end='', flush=True)
    print()

# ── JDK の net.properties で Basic 認証トンネリングを有効化 ─────────────────────
def enable_basic_auth_tunneling(jdk_path, label):
    net_props = os.path.join(jdk_path, 'conf', 'net.properties')
    if not os.path.exists(net_props):
        return
    with open(net_props) as f:
        content = f.read()
    if 'jdk.http.auth.tunneling.disabledSchemes=Basic' in content:
        content = content.replace(
            'jdk.http.auth.tunneling.disabledSchemes=Basic',
            'jdk.http.auth.tunneling.disabledSchemes='
        )
        with open(net_props, 'w') as f:
            f.write(content)
        print(f"[session-start] Enabled Basic auth tunneling in {label} net.properties")

# ── JDK truststore にプロキシ CA をインポート ───────────────────────────────────
def import_ca_into_jdk(jdk_path, label):
    cacerts = os.path.join(jdk_path, 'lib', 'security', 'cacerts')
    keytool = os.path.join(jdk_path, 'bin', 'keytool')
    cacerts_real = os.path.realpath(cacerts)
    sys_ca_bundle = '/etc/ssl/certs/ca-certificates.crt'
    if not (os.path.exists(sys_ca_bundle) and os.path.exists(keytool)):
        return
    with open(sys_ca_bundle) as f:
        bundle = f.read()
    pem_blocks = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', bundle, re.DOTALL)
    for pem in pem_blocks:
        result = subprocess.run(['openssl', 'x509', '-noout', '-subject'], input=pem, capture_output=True, text=True)
        if 'Anthropic' not in result.stdout:
            continue
        cn_match = re.search(r'CN\s*=\s*([^\n,]+)', result.stdout)
        alias = cn_match.group(1).strip().lower().replace(' ', '-') if cn_match else 'anthropic-ca'
        check = subprocess.run([keytool, '-list', '-alias', alias, '-keystore', cacerts_real, '-storepass', 'changeit'],
                               capture_output=True, text=True)
        if check.returncode == 0:
            print(f"[session-start] CA already imported into {label}: {alias}")
            continue
        with tempfile.NamedTemporaryFile(mode='w', suffix='.pem', delete=False) as tmp:
            tmp.write(pem)
            tmp_path = tmp.name
        r = subprocess.run([keytool, '-import', '-trustcacerts', '-noprompt',
                            '-alias', alias, '-file', tmp_path,
                            '-keystore', cacerts_real, '-storepass', 'changeit'],
                           capture_output=True, text=True)
        os.unlink(tmp_path)
        if r.returncode == 0:
            print(f"[session-start] CA imported into {label} truststore: {alias}")
        else:
            print(f"[session-start] Failed to import CA into {label}: {alias} ({r.stderr.strip()})")

# JDK 21 のセットアップ
java_home = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64')
import_ca_into_jdk(java_home, 'JDK 21')
enable_basic_auth_tunneling(java_home, 'JDK 21')

# ── gradle.properties にプロキシ設定を書き込む ───────────────────────────────
props = (
    f"systemProp.https.proxyHost={host}\n"
    f"systemProp.https.proxyPort={port}\n"
    f"systemProp.https.proxyUser={user}\n"
    f"systemProp.https.proxyPassword={password}\n"
    f"systemProp.http.proxyHost={host}\n"
    f"systemProp.http.proxyPort={port}\n"
    f"systemProp.http.proxyUser={user}\n"
    f"systemProp.http.proxyPassword={password}\n"
    f"systemProp.https.nonProxyHosts=localhost|127.0.0.1\n"
    f"systemProp.http.nonProxyHosts=localhost|127.0.0.1\n"
    f"systemProp.jdk.http.auth.tunneling.disabledSchemes=\n"
    f"systemProp.jdk.http.auth.proxying.disabledSchemes=\n"
)
with open(os.path.join(gradle_home, 'gradle.properties'), 'w') as f:
    f.write(props)
print(f"[session-start] gradle.properties written (proxy={host}:{port})")

# ── Android SDK セットアップ ──────────────────────────────────────────────────
project_dir = os.environ.get('CLAUDE_PROJECT_DIR', '/home/user/browsem')
android_home = os.path.expanduser('~/android-sdk')
local_props = os.path.join(project_dir, 'local.properties')

os.makedirs(android_home, exist_ok=True)

# cmdline-tools のインストール（sdkmanager を使えるようにする）
cmdline_tools_dir = os.path.join(android_home, 'cmdline-tools', 'latest')
sdkmanager_bin = os.path.join(cmdline_tools_dir, 'bin', 'sdkmanager')
if not os.path.exists(sdkmanager_bin):
    print("[session-start] Installing Android SDK command-line tools...")
    cmdline_url = 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    cmdline_zip = os.path.join(android_home, 'commandlinetools.zip')
    download(cmdline_url, cmdline_zip)
    with zipfile.ZipFile(cmdline_zip, 'r') as zf:
        zf.extractall(android_home)
    os.unlink(cmdline_zip)
    extracted = os.path.join(android_home, 'cmdline-tools')
    temp_dir  = os.path.join(android_home, '_cmdline-tools-temp')
    os.rename(extracted, temp_dir)
    os.makedirs(extracted, exist_ok=True)
    shutil.move(temp_dir, cmdline_tools_dir)
    os.chmod(sdkmanager_bin, 0o755)
    print(f"[session-start] cmdline-tools installed: {cmdline_tools_dir}")

# ライセンスファイルを直接書き込む（ネットワーク不要）
licenses_dir = os.path.join(android_home, 'licenses')
os.makedirs(licenses_dir, exist_ok=True)
with open(os.path.join(licenses_dir, 'android-sdk-license'), 'w') as f:
    f.write('\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n')
print("[session-start] Android SDK license accepted")

# local.properties に sdk.dir を書き込む
with open(local_props, 'w') as f:
    f.write(f"sdk.dir={android_home}\n")
print(f"[session-start] local.properties written: sdk.dir={android_home}")

# ── protobuf compiler のインストール ──────────────────────────────────────────
result = subprocess.run(['which', 'protoc'], capture_output=True)
if result.returncode != 0:
    print("[session-start] Installing protobuf compiler...")
    r = subprocess.run(['apt-get', 'install', '-y', 'protobuf-compiler'],
                       capture_output=True, text=True)
    if r.returncode == 0:
        print("[session-start] Protobuf compiler installed")
    else:
        print(f"[session-start] Could not install protobuf compiler: {r.stderr.strip()}")

print("[session-start] Environment setup complete!")
PYEOF
