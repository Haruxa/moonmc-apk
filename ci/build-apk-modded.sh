#!/usr/bin/env bash
# APK с фичей `mods` — копия android/build-apk.sh, но:
#   * окружение берётся из CI-раннера (android/env.sh не нужен);
#   * cargo получает --features mods (MODS=1, по умолчанию);
#   * подпись: CI_KEYSTORE_B64/CI_KEYSTORE_PASS, иначе одноразовый ключ;
#   * результат дублируется в ci-out/ вместе с sha256 и ключом.
# PROFILE=fast|release, MODS=1|0. Требует .local/exporters/<ключ> (см. ci/README).
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE=${PROFILE:-fast}
MODS=${MODS:-1}
ANDROID_API=${ANDROID_API:-29}
OUT=android/out
CIOUT=${CIOUT:-ci-out}
mkdir -p "$OUT" android/keys "$CIOUT"

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?нет ANDROID_HOME/ANDROID_SDK_ROOT}}
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}}
NDK_BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)

# javac -source 8, d8 и keytool — JDK 17; horizon-prepare в этом скрипте не участвует.
if ! "${JAVA_HOME:-/no}/bin/javac" -version 2>&1 | grep -q '^javac 17'; then
  for c in /usr/lib/jvm/*17* /usr/lib/jvm/*-17-*; do
    if [ -x "$c/bin/javac" ]; then JAVA_HOME=$c; break; fi
  done
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=$SDK ANDROID_NDK_HOME=$NDK ANDROID_NDK_ROOT=$NDK
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android$ANDROID_API-clang"
export CXX_aarch64_linux_android="$NDK_BIN/aarch64-linux-android$ANDROID_API-clang++"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
export RANLIB_aarch64_linux_android="$NDK_BIN/llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR_aarch64_linux_android"

echo "== окружение"
echo "   NDK=$NDK"
echo "   BUILD_TOOLS=$BUILD_TOOLS"
echo "   JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"
echo "   PROFILE=$PROFILE MODS=$MODS"
[ -f "$SDK/platforms/android-35/android.jar" ] || { echo "нет $SDK/platforms/android-35/android.jar"; exit 1; }

if [ -z "$(ls -A .local/exporters 2>/dev/null || true)" ]; then
  echo "::error::нет .local/exporters — prepare.zip собрать не из чего (нужен прогон horizon-prepare --prepare)"
  exit 1
fi

FEATURES=()
if [ "$MODS" = 1 ]; then FEATURES=(--features mods); fi

echo "== cargo build${FEATURES[*]:+ ${FEATURES[*]}}"
cargo build --locked --profile "$PROFILE" --target aarch64-linux-android -p horizon-android ${FEATURES[@]+"${FEATURES[@]}"}
SO=target/aarch64-linux-android/$PROFILE/libhorizon.so

rm -rf "$OUT/stage" "$OUT/res.zip" "$OUT/assets" && mkdir -p "$OUT/stage/lib/arm64-v8a"
"$NDK_BIN/llvm-strip" --strip-debug -o "$OUT/stage/lib/arm64-v8a/libhorizon.so" "$SO"

# HorizonActivity (софт-клавиатура): javac, затем d8 в classes.dex
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -Xlint:-options \
    -classpath "$SDK/platforms/android-35/android.jar" -d "$OUT/classes" \
    $(find android/java -name '*.java')
PATH="$JAVA_HOME/bin:$PATH" "$BUILD_TOOLS/d8" --min-api 29 --lib "$SDK/platforms/android-35/android.jar" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# java и javac для подготовки ресурсов на телефоне: программа в папке библиотек
(cd android/java-launcher && cargo build --locked --release --target aarch64-linux-android -q)
LAUNCHER=android/java-launcher/target/aarch64-linux-android/release/horizon-java-launcher
cp "$LAUNCHER" "$OUT/stage/lib/arm64-v8a/libhorizonjava.so"
cp "$LAUNCHER" "$OUT/stage/lib/arm64-v8a/libhorizonjavac.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
    "$OUT/stage/lib/arm64-v8a/"

# assets: Java-рантайм Zalith Launcher 2 (OpenJDK 25, GPLv2 + CPE) по коммиту и sha256,
# и экспортёры Horizon. Ничего от Mojang.
CACHE=android/cache
mkdir -p "$CACHE" "$OUT/assets/jre"
JRE_URL=https://raw.githubusercontent.com/ZalithLauncher/ZalithLauncher2/fa4103be9b69ab81d49b459b9477d38af397f038/ZalithLauncher/src/main/assets/runtimes/jre-25
for entry in "universal.tar.xz 23141bf4e0ed46ff0b751c6cc32b3863d39e6a37b720f3d86a4fe9318aeb8e9f" \
             "bin-arm64.tar.xz 16f09d1d163c008ae556be68c624057c4791c21ae6d31302334e61d75439567b"; do
    set -- $entry
    [ -f "$CACHE/$1" ] || curl -fsSL -o "$CACHE/$1" "$JRE_URL/$1"
    echo "$2  $CACHE/$1" | sha256sum -c --quiet
    cp "$CACHE/$1" "$OUT/assets/jre/$1"
done

KEY=$(ls .local/exporters | head -1)
echo "== prepare.zip из .local/exporters/$KEY"
python3 -I - "$OUT/assets/prepare.zip" ".local/exporters/$KEY" <<'PY'
import os, sys, zipfile
out, exporters = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.write("research-lock.json", "research-lock.json")
    parity = "mods/horizon/src/test/java/dev/horizonlab/parity"
    for base, _, files in os.walk(parity):
        for f in files:
            p = os.path.join(base, f)
            z.write(p, p)
    for base, _, files in os.walk(exporters):
        for f in files:
            if f.endswith(".log"):
                continue
            p = os.path.join(base, f)
            z.write(p, os.path.join("exporters", os.path.relpath(p, exporters)))
PY

"$BUILD_TOOLS/aapt2" compile --dir android/res -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$SDK/platforms/android-35/android.jar" \
    --manifest android/AndroidManifest.xml -A "$OUT/assets" "$OUT/res.zip"
# библиотеки сжаты: extractNativeLibs=true распаковывает их при установке (java нужен файлом)
python3 -I -c 'import sys, zipfile, os
with zipfile.ZipFile(sys.argv[1], "a") as z:
    stage = sys.argv[2]
    for f in sorted(os.listdir(stage)):
        z.write(os.path.join(stage, f), "lib/arm64-v8a/" + f, zipfile.ZIP_DEFLATED)
    z.write(sys.argv[3], "classes.dex", zipfile.ZIP_DEFLATED)' "$OUT/base.apk" "$OUT/stage/lib/arm64-v8a" "$OUT/dex/classes.dex"

GENERATED=0
if [ -n "${CI_KEYSTORE_B64:-}" ]; then
  printf '%s' "$CI_KEYSTORE_B64" | base64 -d > android/keys/horizon.jks
  printf '%s' "${CI_KEYSTORE_PASS:?CI_KEYSTORE_PASS не задан}" > android/keys/pass
  chmod 600 android/keys/pass android/keys/horizon.jks
elif [ ! -f android/keys/horizon.jks ]; then
  GENERATED=1
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > android/keys/pass
  chmod 600 android/keys/pass
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore android/keys/horizon.jks -alias horizon -keyalg RSA \
      -keysize 2048 -validity 10000 -storepass "$(cat android/keys/pass)" -dname "CN=horizon" >/dev/null 2>&1
fi

"$BUILD_TOOLS/zipalign" -f -P 16 4 "$OUT/base.apk" "$OUT/aligned.apk"
PATH="$JAVA_HOME/bin:$PATH" "$BUILD_TOOLS/apksigner" sign --ks android/keys/horizon.jks --ks-key-alias horizon \
    --ks-pass file:android/keys/pass --out "$OUT/horizon.apk" "$OUT/aligned.apk"
PATH="$JAVA_HOME/bin:$PATH" "$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/horizon.apk" > "$CIOUT/apk-certs.txt" || true
rm -f "$OUT/base.apk" "$OUT/aligned.apk" "$OUT/horizon.apk.idsig" "$OUT/res.zip"

cp "$OUT/horizon.apk" "$CIOUT/horizon.apk"
if [ "$GENERATED" = 1 ]; then
  cp android/keys/horizon.jks "$CIOUT/horizon.jks"
  cp android/keys/pass "$CIOUT/pass.txt"
fi
{
  echo "ref:      ${GITHUB_REF_NAME:-local} (${GITHUB_SHA:-?})"
  echo "profile:  $PROFILE"
  echo "mods:     $MODS"
  echo "pin:      ${ENGINE_PIN:-?}"
  echo "engine:   ${ENGINE_LABEL:-?}"
  echo "rustc:    $(rustc -V 2>/dev/null || echo '?')"
  echo "size:     $(stat -c%s "$CIOUT/horizon.apk") bytes"
  echo "sha256:   $(sha256sum "$CIOUT/horizon.apk" | cut -d' ' -f1)"
} | tee "$CIOUT/build-info.txt"

echo "== готово"
ls -la "$CIOUT"
