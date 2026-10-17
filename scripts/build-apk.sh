#!/usr/bin/env bash
# 构建 IceType 发布版 APK。
#
# 为什么要先同步到 Linux 工作区：/sdcard 是 FUSE，Gradle 在其上做文件锁会失败。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$HERE")"
WS="${ICETYPE_WS:-/opt/ice-build}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/opt/pumi-tools/gradle-home}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE="${GRADLE_BIN:-$GRADLE_USER_HOME/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle}"

[ -x "$GRADLE" ] || { echo "找不到 Gradle：$GRADLE"; exit 1; }

echo "==> 同步到工作区 $WS"
mkdir -p "$WS"
# 先清掉旧源码树，避免已删除的文件残留（tar 只覆盖不删除）
rm -rf "$WS/app/src"
(cd "$PROJ" && tar cf - \
  --exclude='./.gradle' \
  --exclude='./build' \
  --exclude='./app/build' \
  --exclude='./.git' \
  .) | (cd "$WS" && tar xf - --no-same-permissions)

echo "==> 构建 release APK"
"$GRADLE" -p "$WS" :app:assembleRelease

APK="$WS/app/build/outputs/apk/release/app-release.apk"
OUT="${ICETYPE_APK_OUT:-/sdcard/Download/IceType.apk}"
cp "$APK" "$OUT"
echo "OK: $APK"
echo "OK: $OUT ($(du -h "$APK" | cut -f1))"