#!/usr/bin/env bash
# Runs as root inside the arm64 Debian rootfs, under qemu-user on the CI host.
# Installs the Phase 1 toolchain, runs the real aapt2/zipalign/apksigner checks,
# and writes the reports that the host packs into the artifact.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export HOME=/root
export LANG=C.UTF-8
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

INPUT=/tmp/toolchain-input
SDK=/opt/android-sdk
BT="$SDK/build-tools/35.0.0"
GRADLE_HOME=/opt/gradle/gradle-8.14.3
EVIDENCE=/opt/kilocoder/evidence
REPORT=/opt/kilocoder/toolchain-report.txt
ENVF=/opt/kilocoder/toolchain.env

mkdir -p /opt/kilocoder "$EVIDENCE" "$SDK/platforms" "$BT/lib"
: >"$ENVF"
exec > >(tee "$REPORT") 2>&1

log() { printf '\n== %s ==\n' "$*"; }
record() {
    local key=$1 value=$2
    value=$(printf '%s' "$value" | tr '\n' ' ' | sed 's/  */ /g' | cut -c1-300)
    printf '%s=%s\n' "$key" "$value" >>"$ENVF"
}

log "install current Debian archive keyring"
dpkg -i /tmp/toolchain-input/debian-archive-keyring.deb
record KEYRING_VERSION "$(dpkg-query -W -f='${Version}' debian-archive-keyring)"

log "apt update and full-upgrade"
apt-get -y update
apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold full-upgrade
apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
    install --no-install-recommends \
    openjdk-17-jdk-headless ca-certificates curl unzip zip file binutils xz-utils zstd less

JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

log "install Gradle 8.14.3"
mkdir -p /opt/gradle
unzip -q "$INPUT/gradle-8.14.3-bin.zip" -d /opt/gradle
ln -sfn "$GRADLE_HOME/bin/gradle" /usr/local/bin/gradle

log "install Android platforms 35 and 36"
for api in 35 36; do
    rm -rf "/tmp/platform-$api"
    unzip -q "$INPUT/platform-$api.zip" -d "/tmp/platform-$api"
    src=$(find "/tmp/platform-$api" -mindepth 1 -maxdepth 1 -type d | head -1)
    rm -rf "$SDK/platforms/android-$api"
    mv "$src" "$SDK/platforms/android-$api"
done

log "install static aarch64 build tools"
unzip -q "$INPUT/sdk-tools.zip" -d /tmp/sdk-tools
cp -a /tmp/sdk-tools/build-tools/. "$BT"/
chmod 0755 "$BT"/*

log "install apksigner and d8 from build-tools 35.0.0"
rm -rf /tmp/build-tools
unzip -q "$INPUT/build-tools.zip" -d /tmp/build-tools
src=$(find /tmp/build-tools -mindepth 1 -maxdepth 1 -type d | head -1)
cp "$src/apksigner" "$src/d8" "$BT/"
cp "$src/lib/apksigner.jar" "$src/lib/d8.jar" "$BT/lib/"
cp "$src/source.properties" "$BT/source.properties"
chmod 0755 "$BT/apksigner" "$BT/d8"

cat >"$BT/package.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/generic/01" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <localPackage path="build-tools;35.0.0" obsolete="false">
    <type-details xsi:type="ns2:buildTool">
      <revision><major>35</major><minor>0</minor><micro>0</micro></revision>
    </type-details>
    <display-name>Android SDK Build-Tools 35</display-name>
  </localPackage>
</ns2:repository>
EOF

log "write profile and Gradle properties"
cat >/etc/profile.d/kilocoder-toolchain.sh <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$BT:$SDK/platform-tools:\$PATH"
EOF
mkdir -p /root/.gradle
cat >/root/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$BT/aapt2
EOF

log "create debug keystore"
mkdir -p /root/.android
rm -f /root/.android/debug.keystore
keytool -genkeypair -v \
    -keystore /root/.android/debug.keystore \
    -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1

log "tool versions"
JAVA_OUT=$(java -version 2>&1 || true)
record JAVA_VERSION "$(printf '%s\n' "$JAVA_OUT" | head -1)"
GRADLE_OUT=$(gradle --version 2>&1)
record GRADLE_VERSION "$(printf '%s\n' "$GRADLE_OUT" | awk '/^Gradle /{print $2; exit}')"
record GRADLE_JVM "$(printf '%s\n' "$GRADLE_OUT" | awk '/^JVM /{print $2; exit}')"
AAPT2_OUT=$("$BT/aapt2" version 2>&1 || true)
record AAPT2_VERSION "$(printf '%s\n' "$AAPT2_OUT" | head -1)"
ZIPALIGN_OUT=$("$BT/zipalign" -h 2>&1 || true)
record ZIPALIGN_USAGE "$(printf '%s\n' "$ZIPALIGN_OUT" | head -1)"
APKSIGNER_OUT=$("$BT/apksigner" version 2>&1 || true)
record APKSIGNER_VERSION "$(printf '%s\n' "$APKSIGNER_OUT" | head -1)"
D8_OUT=$("$BT/d8" --version 2>&1 || true)
record D8_VERSION "$(printf '%s\n' "$D8_OUT" | head -1)"

log "CA bundle and Java truststore"
CA_BUNDLE=missing
[ -s /etc/ssl/certs/ca-certificates.crt ] && CA_BUNDLE=ok
record CA_BUNDLE "$CA_BUNDLE"
[ "$CA_BUNDLE" = ok ] || { echo "error: CA bundle missing"; exit 1; }
CA_COUNT=$(keytool -list -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit 2>/dev/null | grep -c 'trustedCertEntry' || true)
record CA_TRUSTSTORE_ENTRIES "${CA_COUNT:-0}"
[ "${CA_COUNT:-0}" -gt 0 ] || { echo "error: Java truststore is empty"; exit 1; }

log "aapt2 compile and link against API 36"
TEST=/tmp/aapt2-test
rm -rf "$TEST"
mkdir -p "$TEST/res/values"
cat >"$TEST/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.kilocoder.toolchain.test">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
  <application android:label="@string/app_name" />
</manifest>
EOF
cat >"$TEST/res/values/strings.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name">Toolchain</string></resources>
EOF
cd "$TEST"
"$BT/aapt2" compile --dir res -o compiled.zip
"$BT/aapt2" link -o unsigned.apk \
    -I "$SDK/platforms/android-36/android.jar" \
    --manifest AndroidManifest.xml compiled.zip
"$BT/aapt2" dump badging unsigned.apk >badging.txt
record AAPT2_LINK ok
record AAPT2_BADGING "$(head -1 badging.txt)"

log "zipalign and apksigner"
ZIPALIGN_MODE=4k
if "$BT/zipalign" -f -P 16 4 unsigned.apk aligned.apk >zipalign-align-16k.txt 2>&1; then
    ZIPALIGN_MODE=16k
else
    "$BT/zipalign" -f 4 unsigned.apk aligned.apk >zipalign-align-4k.txt 2>&1
fi
record ZIPALIGN_MODE "$ZIPALIGN_MODE"
ZIPALIGN_CHECK=failed
if "$BT/zipalign" -c -P 16 4 aligned.apk >zipalign-check-16k.txt 2>&1; then
    ZIPALIGN_CHECK=ok
fi
record ZIPALIGN_CHECK "$ZIPALIGN_CHECK"
"$BT/zipalign" -c 4 aligned.apk >zipalign-check-4k.txt 2>&1

"$BT/apksigner" sign \
    --ks /root/.android/debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --out signed.apk aligned.apk
"$BT/apksigner" verify --verbose --print-certs signed.apk >apksigner-verify.txt
record APKSIGNER_VERIFY ok

cp badging.txt zipalign-*.txt apksigner-verify.txt "$EVIDENCE"/

log "cleanup"
apt-get clean
rm -rf /var/lib/apt/lists/* "$INPUT" /tmp/platform-* /tmp/sdk-tools /tmp/build-tools /tmp/aapt2-test
record DONE ok
