#!/usr/bin/env bash
# Puts Pretendard GOV (OFL-1.1) into app/src/main/res/font with Android resource names.
set -euo pipefail
cd "$(dirname "$0")"
OUT=app/src/main/res/font; mkdir -p "$OUT"
TMP=$(mktemp -d)
curl -fsSL -o "$TMP/gov.zip" "https://github.com/orioncactus/pretendard/releases/download/v1.3.9/PretendardGOV-1.3.9.zip" && (cd "$TMP" && unzip -q gov.zip) || true
if ! find "$TMP" -name 'PretendardGOV-Regular.otf' | grep -q .; then
  (cd "$TMP" && npm pack pretendard-gov@1.3.9 --silent && tar xzf pretendard-gov-*.tgz)
fi
for w in Regular Medium SemiBold Bold ExtraBold; do
  f=$(find "$TMP" -name "PretendardGOV-$w.otf" | head -1)
  [ -n "$f" ] || { echo "missing PretendardGOV-$w.otf"; exit 1; }
  cp "$f" "$OUT/pretendardgov_$(echo $w | tr 'A-Z' 'a-z').otf"
done
ls -la "$OUT"
