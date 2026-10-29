#!/usr/bin/env bash
# Bygger appen i cleanyourtv:s Docker-avbildning, med samma Gradle-volym och samma debug-nyckel som launchern.
# amd64-avbildningen: arm64-varianten kraschar i aapt2 (rosetta) på en ARM-Mac.
# Usage: ./docker-build.sh [gradle-uppgifter ...]   (standard: assembleRelease)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${IMAGE:-cleanyourtv-android-build-amd64}"
GRADLE_VOLUME="${GRADLE_VOLUME:-cleanyourtv-gradle}"
KEY_DIR="${KEY_DIR:-/gradle/.android}"
TASKS=("$@"); [ ${#TASKS[@]} -eq 0 ] && TASKS=(assembleRelease)
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$HERE:/repo" -v "$GRADLE_VOLUME:/gradle" -w /repo \
  -e ANDROID_USER_HOME="$KEY_DIR" \
  "$IMAGE" sh ./gradlew --no-daemon --console=plain "${TASKS[@]}"
APK="$HERE/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] && echo "APK: $APK ($(wc -c < "$APK" | tr -d ' ') B)"
