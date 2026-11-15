#!/usr/bin/env bash
# 제출용 릴리즈 APK — 운영 서버(기본값)에 붙는 서명된 APK를 apk/reopt_DJTrot_release.apk로 만든다.
# 🔴 서명 키와 Pleos Client ID/Secret은 ~/.gradle/gradle.properties(djtrot.release*, djtrot.pleos*)에 있어야 한다.
set -euo pipefail
cd "$(dirname "$0")/.."
props="$HOME/.gradle/gradle.properties"
for key in djtrot.releaseStoreFile djtrot.pleosClientId djtrot.pleosClientSecret; do
  grep -q "^$key=" "$props" 2>/dev/null || { echo "~/.gradle/gradle.properties에 $key 가 없어요." >&2; exit 1; }
done
[ -z "$(git status --porcelain)" ] || echo "⚠️ 커밋하지 않은 변경이 있어요 — 빌드 정보에 '+수정'이 붙어요." >&2
CI=true ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
out="apk/reopt_DJTrot_release.apk"
cp app/build/outputs/apk/release/app-release.apk "$out"
ls -lh "$out"
