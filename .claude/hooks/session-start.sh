#!/usr/bin/env bash
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

echo "[session-start] Claude Code Remote 向けのプロキシ設定を適用する..."

python3 - <<'PYEOF'
import os, re, subprocess, tempfile, urllib.parse

proxy_url = os.environ.get('HTTPS_PROXY', '')
if not proxy_url:
    print("[session-start] HTTPS_PROXY is not set; skipping proxy configuration")
    raise SystemExit(0)

parsed = urllib.parse.urlparse(proxy_url)
host = parsed.hostname
port = str(parsed.port)
user = parsed.username or ''
password = parsed.password or ''

gradle_home = os.path.expanduser('~/.gradle')
os.makedirs(gradle_home, exist_ok=True)

init_d = os.path.join(gradle_home, 'init.d')
os.makedirs(init_d, exist_ok=True)
init_script = os.path.join(init_d, 'proxy-auth.gradle')
with open(init_script, 'w') as f:
    f.write("""import java.net.Authenticator
import java.net.PasswordAuthentication

def proxyUser = System.getProperty("https.proxyUser") ?: System.getProperty("http.proxyUser")
def proxyPassword = System.getProperty("https.proxyPassword") ?: System.getProperty("http.proxyPassword")

if (proxyUser && proxyPassword) {
    Authenticator.setDefault(new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() == Authenticator.RequestorType.PROXY) {
                return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray())
            }
            return null
        }
    })
}
""")
print(f"[session-start] Gradle init script written: {init_script}")

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

java_home = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64')
import_ca_into_jdk(java_home, 'JDK 21')
enable_basic_auth_tunneling(java_home, 'JDK 21')

gradle_jdks_dir = os.path.join(gradle_home, 'jdks')
if os.path.isdir(gradle_jdks_dir):
    for jdk_name in os.listdir(gradle_jdks_dir):
        jdk_path = os.path.join(gradle_jdks_dir, jdk_name)
        if not os.path.isdir(jdk_path):
            continue
        keytool = os.path.join(jdk_path, 'bin', 'keytool')
        if not os.path.exists(keytool):
            continue
        import_ca_into_jdk(jdk_path, f'Gradle JDK ({jdk_name})')
        enable_basic_auth_tunneling(jdk_path, f'Gradle JDK ({jdk_name})')

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
PYEOF
