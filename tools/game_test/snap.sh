#!/usr/bin/env bash
# 에뮬레이터 화면을 찍어 앱 창 부분만 잘라 저장: snap.sh <out.png> [scale]
set -e
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
full="${1%.png}_full.png"
adb exec-out screencap -p > "$full"
python -X utf8 - "$full" "$1" "${2:-0.66}" <<'PY'
import sys
from PIL import Image
src, out, sc = sys.argv[1], sys.argv[2], float(sys.argv[3])
im = Image.open(src).crop((870, 90, 2540, 1300))
im.resize((int(im.width * sc), int(im.height * sc)), Image.LANCZOS).save(out)
PY
echo "$1"
