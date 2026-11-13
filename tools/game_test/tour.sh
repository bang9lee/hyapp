#!/usr/bin/env bash
# 화면 둘러보기 캡처: tour.sh <결과폴더> [night]
# 홈 → 랭킹 → 설정 → 게임(조명·창문 구역) 순서로 찍는다. Virtual Console 하트비트는 e2e.py가 아닌 여기서 직접 보낸다.
set -e
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
out="$1"; mkdir -p "$out"
here="$(cd "$(dirname "$0")" && pwd)"
snap() { bash "$here/snap.sh" "$out/$1.png" 0.66 >/dev/null; }
app() { adb shell am start -n com.parkingroom.app/.MainActivity "$@" >/dev/null 2>&1; }
( while true; do curl -s -X POST "http://127.0.0.1:8088/browser/session?client=tour" >/dev/null; sleep 1; done ) &
hb=$!
trap 'kill $hb 2>/dev/null' EXIT
curl -s -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park" >/dev/null
curl -s -X PUT "http://127.0.0.1:8088/vehicle/telltales/simulator?color=Red&type=ParkingBrake&state=On" >/dev/null
app --es cmd home; sleep 2; snap 1_home
adb shell input tap 2378 154; sleep 2; snap 2_ranking
app --es cmd home; sleep 1
adb shell input tap 2470 154; sleep 2; snap 3_settings
app --es cmd home; sleep 1
app --es start easy; sleep 16; snap 4_game_light
app --es pick window; sleep 4; snap 5_game_window
app --es cmd stop; sleep 2; snap 6_stopped
app --es cmd home; sleep 1
