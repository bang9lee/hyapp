"""step1 결과 재분석: 정밀 시계 보정값으로 자극→앱 반응 시간을 다시 계산하고, 항목별 타임라인을 뽑는다."""
import json, statistics, sys

d, offset = sys.argv[1], float(sys.argv[2])
st = json.load(open(f"{d}/stimuli.json", encoding="utf-8"))["stimuli"]
rows = json.load(open(f"{d}/app_log.json", encoding="utf-8"))
for r in rows:
    r["tc"] = r["t"] - offset  # PC 시계 기준

groups = {}
print("== 자극 → 앱 업데이트 (PC 시계 기준)")
for s in st:
    hit = next((r for r in rows if r["item"] == s["item"] and r["event"] == "update"
                and s["expect"].lower() in r["detail"].lower() and r["tc"] >= s["t0"] - 15), None)
    lat = None if hit is None else hit["tc"] - s["t0"]
    if lat is not None and lat > 1100:
        lat = None  # 다음 자극의 업데이트를 잘못 잡은 경우
    key = s["label"].split("-")[0]
    if lat is not None:
        groups.setdefault(key, []).append(lat)
    print(f'{s["label"]:28} {"-" if lat is None else f"{lat:7.1f}ms"}  got={hit["detail"][:40] if hit and lat is not None else ""}')

print("\n== 그룹별 지연 (ms)")
for k, v in groups.items():
    print(f"{k:10} n={len(v):2} median={statistics.median(v):6.1f} min={min(v):6.1f} max={max(v):6.1f}")


def timeline(prefixes, t_from=None, t_to=None):
    base = rows[0]["tc"]
    for r in rows:
        if any(r["item"].startswith(p) for p in prefixes) and (t_from is None or t_from <= r["tc"] <= t_to):
            print(f'{(r["tc"] - base) / 1000:8.2f}s {r["item"]:26} {r["event"]:18} {r["detail"][:110]}')


win = [s for s in st if s["label"].startswith("window")]
print("\n== 창문 타임라인 (콘솔 % → 앱 값)")
for s in win:
    print(f'  PC {s["label"]}')
timeline(["windowPos"], win[0]["t0"] - 100, win[-1]["t0"] + 1500)

for label in ("wiper-low", "pbrake-telltale-on", "rear-left-occupied"):
    s = next(x for x in st if x["label"] == label)
    print(f"\n== {label} 주변")
    timeline(["wiperState", "telltales", "aaos.parkingBrake", "seatOccupancy"], s["t0"] - 200, s["t0"] + 3000)

print("\n== 쓰기 테스트")
timeline(["fanSpeed", "hvacPower", "hvacTemp", "steeringHeat", "mirrorHeat", "ambient", "wiper", "seatBackrest", "write"],
         next(r["tc"] for r in rows if r["item"] == "command" and r["event"] == "write") - 10,
         next(r["tc"] for r in rows if r["item"] == "write" and r["event"] == "done") + 10)

print("\n== 음성/위치")
timeline(["tts", "stt", "location", "geocoder", "permission"])
