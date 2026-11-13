"""1단계 자동 확인: Virtual Console로 차량 상태를 바꾸고, 앱(PRCHECK 로그)이 알아채기까지 걸린 시간을 잰다."""
import json, os, subprocess, sys, threading, time, urllib.request
from urllib.parse import urlencode

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
APK = r"C:\hyapp\app\build\outputs\apk\debug\app-debug.apk"
PKG = "com.parkingroom.app"
B = "http://127.0.0.1:8088"
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def now():
    return time.time() * 1000


def adb(*args, check=False):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def put(path, **params):
    t0 = now()
    try:
        urllib.request.urlopen(urllib.request.Request(f"{B}{path}?{urlencode(params)}", method="PUT"), timeout=10).read()
    except urllib.error.HTTPError as e:
        print(f"PUT {path} {params} -> {e.code} {e.read()[:200]!r}")
    return t0, now()


def heartbeat(stop):
    """콘솔은 브라우저 세션 신호가 살아 있을 때만 차량(DDS)으로 값을 보낸다"""
    while not stop.is_set():
        try:
            urllib.request.urlopen(urllib.request.Request(f"{B}/browser/session?client=step1-script", method="POST"),
                                   timeout=5).read()
        except Exception:  # noqa: BLE001
            pass
        time.sleep(1)


def state():
    return json.load(urllib.request.urlopen(f"{B}/vehicle/state", timeout=10))


def clock_offset():
    """device_ms - pc_ms. adb shell 세션을 열어 둔 채 재야 왕복이 ~7ms로 짧아 오차가 ±4ms 안쪽이 된다"""
    p = subprocess.Popen([ADB, "shell"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    best = None
    for _ in range(40):
        t1 = now()
        p.stdin.write("date +%s%N\n")
        p.stdin.flush()
        dev = p.stdout.readline().strip()
        t2 = now()
        if dev.isdigit() and (best is None or t2 - t1 < best[1]):
            best = (int(dev) / 1e6 - (t1 + t2) / 2, t2 - t1)
    p.stdin.write("exit\n")
    p.stdin.flush()
    return best or (0.0, -1)


def logcat_lines():
    raw = adb("logcat", "-d", "-v", "raw", "-s", "PRCHECK:I")
    rows = []
    for line in raw.splitlines():
        p = line.split("|", 3)
        if len(p) == 4 and p[2].isdigit():
            rows.append({"item": p[0], "event": p[1], "t": int(p[2]), "detail": p[3]})
    return rows


def wait_for(item, event, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if any(r["item"] == item and r["event"] == event for r in logcat_lines()):
            return True
        time.sleep(0.5)
    return False


def main():
    hb_stop = threading.Event()
    threading.Thread(target=heartbeat, args=(hb_stop,), daemon=True).start()
    time.sleep(3)
    s0 = state()
    json.dump(s0, open(f"{OUT}/console_state_before.json", "w"), indent=1)
    adb("logcat", "-G", "16M")
    adb("install", "-r", APK, check=True)
    for p in ("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION"):
        adb("shell", "pm", "grant", PKG, p)
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")

    # 기준 상태: P, 정지, 페달 뗌, 방향지시등 끔
    put("/vehicle/gear", gear="Park")
    put("/vehicle/controls", speed=0, accelerator=0, brake=0)
    put("/vehicle/turn-signal", mode="Off")
    time.sleep(1)

    time.sleep(3)  # 설치 직후 첫 실행 명령이 묻히는 경우가 있어 잠시 기다린 뒤, 시작을 확인하며 재시도
    for attempt in range(3):
        print(adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", "--es", "run", "all").strip().splitlines()[-1:])
        if wait_for("command", "all", 15):
            break
    ok = wait_for("command", "all-done", 120)
    print("all-done", ok)
    time.sleep(2)

    offset, rtt = clock_offset()
    print(f"clock offset device-pc = {offset:.1f}ms (rtt {rtt:.1f}ms)")

    stimuli = []

    def stim(label, item, expect, path, wait=1.2, **params):
        t0, t1 = put(path, **params)
        stimuli.append({"label": label, "item": item, "expect": expect, "t0": t0, "t1": t1})
        time.sleep(wait)

    for _ in range(3):
        stim("turn-left", "turnSignal", "LEFT", "/vehicle/turn-signal", mode="BlinkLeft")
        stim("turn-off", "turnSignal", "OFF", "/vehicle/turn-signal", mode="Off")
        stim("turn-right", "turnSignal", "RIGHT", "/vehicle/turn-signal", mode="BlinkRight")
        stim("turn-off", "turnSignal", "OFF", "/vehicle/turn-signal", mode="Off")
    # 레버 살짝 치기(3번 깜빡임)가 어떻게 들어오는지 6초 관찰
    stim("turn-flash-left", "turnSignal", "LEFT", "/vehicle/turn-signal", wait=6, mode="FlashLeft")
    stim("turn-off", "turnSignal", "OFF", "/vehicle/turn-signal", mode="Off")

    for _ in range(3):
        stim("brake-press", "brakePedal", "true", "/vehicle/controls", speed=0, accelerator=0, brake=60)
        stim("brake-release", "brakePedal", "false", "/vehicle/controls", speed=0, accelerator=0, brake=0)

    for _ in range(2):
        stim("gear-D", "aaos.gearSelection", "D", "/vehicle/gear", gear="Drive")
        stim("gear-P", "aaos.gearSelection", "P", "/vehicle/gear", gear="Park")
    put("/vehicle/gear", gear="Drive")
    time.sleep(1)
    stim("speed-10", "aaos.speed", "(10.0km/h)", "/vehicle/controls", wait=2, speed=10, accelerator=0, brake=0)
    stim("speed-0", "aaos.speed", "(0.0km/h)", "/vehicle/controls", wait=2, speed=0, accelerator=0, brake=0)
    put("/vehicle/gear", gear="Park")
    time.sleep(1)

    tt0 = s0.get("telltales", {}).get("actual", {}).get("ParkingBrake", {}).get("state", "Off")
    stim("pbrake-telltale-on", "telltales", "PARKING_BRAKE=ON", "/vehicle/telltales/simulator", wait=1.5,
         color="Red", type="ParkingBrake", state="On")
    stim("pbrake-telltale-off", "telltales", "PARKING_BRAKE=OFF", "/vehicle/telltales/simulator", wait=1.5,
         color="Red", type="ParkingBrake", state="Off")

    win0 = s0["body"]["windows"]
    fields = {"front_left": ("window_fl", "ROW_1_LEFT"), "front_right": ("window_fr", "ROW_1_RIGHT"),
              "rear_left": ("window_rl", "ROW_2_LEFT"), "rear_right": ("window_rr", "ROW_2_RIGHT")}
    # 앱 쪽 범위가 0..10이라 콘솔 %와 값이 다를 수 있다 → 아무 업데이트나 잡고 값을 기록
    for pct in (50, 30, 100):
        for area, (field, value) in fields.items():
            stim(f"window-{area}-{pct}", f"windowPos.{value}", "", "/vehicle/body", wait=1.2, **{field: pct})
    for area, (field, value) in fields.items():
        stim(f"window-{area}-restore", f"windowPos.{value}", "", "/vehicle/body", wait=1.2, **{field: win0[area]})

    wiper0 = s0["body"]["wipers"]["front"]["mode"]
    stim("wiper-low", "wiperState", "ON", "/vehicle/body", wait=2, front_wiper="Low")
    stim("wiper-off", "wiperState", "OFF", "/vehicle/body", wait=2, front_wiper="Off")
    if wiper0 != "Off":
        put("/vehicle/body", front_wiper=wiper0)

    occ0 = s0["seat"]["occupied"]["rear_left"]
    stim("rear-left-occupied", "seatOccupancy.ROW_2_LEFT", str(not occ0).lower(), "/vehicle/seat", wait=1.5,
         kind="occupied", area="rear_left", value=str(not occ0).lower())
    stim("rear-left-restore", "seatOccupancy.ROW_2_LEFT", str(occ0).lower(), "/vehicle/seat", wait=1.5,
         kind="occupied", area="rear_left", value=str(occ0).lower())

    odo0 = int(s0["odometer"]["traveled_distance_km"])
    stim("odometer-set", "odometer", "12345", "/vehicle/odometer", wait=1.5, traveled_distance_km=12345)
    stim("odometer-restore", "odometer", str(int(odo0)), "/vehicle/odometer", wait=1.5, traveled_distance_km=odo0)

    # 쓰기 테스트: 앱이 바꾼 값이 차량(콘솔) 상태에 실제로 반영되는지 100ms마다 기록
    watch = []
    stop = threading.Event()

    def poll():
        last = None
        while not stop.is_set():
            try:
                s = state()
                snap = {
                    "fan": s["hvac"].get("fan_speed"),
                    "power": s["hvac"].get("power"),
                    "temp": json.dumps(s["hvac"].get("temperature_set", s["hvac"].get("temperature"))),
                    "steerHeat": s["steering_wheel_heat"].get("level"),
                    "ambient": json.dumps(s["body"].get("ambient_light")),
                    "wiper": s["body"]["wipers"]["front"]["mode"],
                    "recline": json.dumps(s["seat"].get("positions", {}).get("recline")),
                }
                if snap != last:
                    watch.append({"t": now(), **snap})
                    last = snap
            except Exception as e:  # noqa: BLE001
                watch.append({"t": now(), "error": str(e)})
            time.sleep(0.1)

    th = threading.Thread(target=poll, daemon=True)
    th.start()
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "run", "write")
    print("write-done", wait_for("write", "done", 120))
    time.sleep(2)
    stop.set()
    th.join()

    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "run", "tts")
    time.sleep(8)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "run", "loc")
    time.sleep(5)

    rows = logcat_lines()
    json.dump(rows, open(f"{OUT}/app_log.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    json.dump(watch, open(f"{OUT}/console_watch.json", "w"), indent=0)
    json.dump(state(), open(f"{OUT}/console_state_after.json", "w"), indent=1)

    # 자극 → 앱 업데이트 매칭
    results = []
    for st in stimuli:
        hit = next((r for r in rows if r["item"] == st["item"] and r["event"] == "update"
                    and st["expect"].lower() in r["detail"].lower() and r["t"] - offset >= st["t0"] - 5), None)
        lat = (hit["t"] - offset - st["t0"]) if hit else None
        follow = [r["detail"] for r in rows if r["item"] == st["item"] and r["event"] == "update"
                  and st["t0"] - 5 <= r["t"] - offset <= st["t0"] + 6500]
        results.append({**st, "latency_ms": None if lat is None else round(lat, 1),
                        "http_ms": round(st["t1"] - st["t0"], 1), "updates": follow[:12]})
    json.dump({"offset_ms": offset, "rtt_ms": rtt, "stimuli": results},
              open(f"{OUT}/stimuli.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for r in results:
        print(f'{r["label"]:28} {str(r["latency_ms"]):>8} ms  http {r["http_ms"]:>6}  {r["updates"][:4]}')


if __name__ == "__main__":
    main()
