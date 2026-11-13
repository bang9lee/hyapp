"""파킹룸 '해킹된 차' 자동 플레이 테스트 (에뮬레이터 + Virtual Console).

앱(디버그 빌드)이 남기는 PRGAME 로그(stage/blink/climate/windows/pattern/master/solved/wrong/abort/end)를 보면서,
Virtual Console HTTP API로 공조·창문·방향지시등·브레이크·기어를 조작해 게임을 끝까지 진행한다.

사용: python -X utf8 tools/game_test/e2e.py <결과폴더> [테스트이름 ...]
테스트: gate clear_easy clear_hard wrong abort_gear abort_brake stop
"""
import json, os, subprocess, sys, threading, time, urllib.error, urllib.request
from urllib.parse import urlencode

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "com.parkingroom.app"
B = "http://127.0.0.1:8088"
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
RESULTS = []
OFFSET = 0.0  # device_ms - pc_ms


def now():
    return time.time() * 1000


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def put(path, **params):
    t0 = now()
    try:
        urllib.request.urlopen(urllib.request.Request(f"{B}{path}?{urlencode(params)}", method="PUT"), timeout=10).read()
    except urllib.error.HTTPError as e:
        print(f"  PUT {path} {params} -> {e.code}")
    return t0


def state():
    return json.load(urllib.request.urlopen(f"{B}/vehicle/state", timeout=10))


def heartbeat(stop):
    while not stop.is_set():
        try:
            urllib.request.urlopen(urllib.request.Request(f"{B}/browser/session?client=e2e", method="POST"), timeout=5).read()
        except Exception:  # noqa: BLE001
            pass
        time.sleep(1)


def clock_offset():
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
    return best[0]


def logs():
    """[(device_epoch_ms, message)] — logcat -v epoch 시간과 메시지"""
    out = []
    for line in adb("logcat", "-d", "-v", "epoch", "-s", "PRGAME:I").splitlines():
        parts = line.split(None, 6)
        if len(parts) >= 7 and parts[0].replace(".", "").isdigit():
            msg = line.split("PRGAME", 1)[-1].split(":", 1)[-1].strip()
            out.append((float(parts[0]) * 1000, msg))
    return out


def wait_log(prefix, timeout=60, after=0.0):
    """after(PC ms) 이후에 찍힌, prefix로 시작하는 로그를 기다린다"""
    end = time.time() + timeout
    while time.time() < end:
        for t, m in logs():
            if t - OFFSET >= after and m.startswith(prefix):
                return t - OFFSET, m
        time.sleep(0.3)
    return None, None


def app(**extras):
    args = ["shell", "am", "start", "-n", f"{PKG}/.MainActivity"]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ["--ez", k, str(v).lower()]
        else:
            args += ["--es", k, str(v)]
    adb(*args)


def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def record(name, ok, detail=""):
    RESULTS.append({"test": name, "ok": bool(ok), "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")


def baseline():
    put("/vehicle/gear", gear="Park")
    put("/vehicle/controls", speed=0, accelerator=0, brake=0)
    put("/vehicle/turn-signal", mode="Off")
    put("/vehicle/telltales/simulator", color="Red", type="ParkingBrake", state="On")
    put("/vehicle/body", window_fl=0, window_fr=0, window_rl=0, window_rr=0, front_wiper="Off")
    put("/vehicle/hvac", power="true", fan_speed=3, driver_temp=22)
    time.sleep(1.5)


def car_snapshot():
    s = state()
    return {
        "fan": s["hvac"].get("fan_speed"),
        "power": s["hvac"].get("power_on"),
        "temp": s["hvac"]["driver"].get("temp_c"),
        "ambient": s["body"].get("ambient_light"),
        "wiper": s["body"]["wipers"]["front"]["mode"],
        "steer": s["steering_wheel_heat"].get("level"),
    }


def go_home():
    app(cmd="home")
    time.sleep(1)


def start(level, **extra):
    adb("logcat", "-c")
    t = now()
    app(start=level, **extra)
    return t


# ───────────────────────── 자물쇠 자동 풀이 ─────────────────────────

def press_turn(direction, hold=0.35):
    put("/vehicle/turn-signal", mode="BlinkLeft" if direction == "LEFT" else "BlinkRight")
    time.sleep(hold)
    put("/vehicle/turn-signal", mode="Off")


def press_brake(hold=0.3):
    put("/vehicle/controls", speed=0, accelerator=0, brake=60)
    time.sleep(hold)
    put("/vehicle/controls", speed=0, accelerator=0, brake=0)


def solve_light(after, prefix):
    t, m = wait_log("blink|answer=", 40, after)
    if not m:
        return False
    answer = m.split("|")[1].split("=")[1]
    time.sleep(3)
    shot(f"{prefix}_1_light")
    app(type=answer)
    return True


def solve_climate(after, prefix):
    t = after
    while True:
        t, m = wait_log("climate|round=", 40, t + 1)
        if not m:
            return False
        parts = dict(p.split("=") for p in m.split("|")[1:])
        if parts["round"] == "1":
            time.sleep(1.5)
            shot(f"{prefix}_2_climate")
        put("/vehicle/hvac", driver_temp=parts["temp"], fan_speed=parts["fan"])
        done, _ = wait_log("solved|climate", 8, t)
        if done:
            return True


def solve_window(after, prefix):
    t, m = wait_log("windows|", 40, after)
    if not m:
        return False
    v = [int(x) * 10 for x in m.split("|")[1].split(",")]
    time.sleep(2)
    shot(f"{prefix}_3_window")
    put("/vehicle/body", window_fl=v[0], window_fr=v[1], window_rl=v[2], window_rr=v[3])
    return True


def solve_drive(after, prefix):
    t = after
    seqs = {}
    while True:
        t, m = wait_log("pattern", 60, t + 1)
        if not m:
            return False
        if m.startswith("pattern|"):
            _, rnd, seq = m.split("|")
            seqs[rnd] = seq.split(",")
            if rnd == "round=1":
                time.sleep(1.2)
                shot(f"{prefix}_4_drive")
            continue
        rnd = m.split("|")[1]
        time.sleep(0.4)
        for s in seqs[rnd]:
            press_brake() if s == "BRAKE" else press_turn(s)
            time.sleep(0.5)
        done, _ = wait_log("solved|drive", 3, t)
        if done:
            return True


def solve_master(after, prefix):
    t, m = wait_log("master|code=", 40, after)
    if not m:
        return False
    time.sleep(1.5)
    shot(f"{prefix}_5_master")
    app(type=m.split("=")[1])
    return True


# ───────────────────────── 테스트 ─────────────────────────

def t_gate():
    """안전 잠금: 기어 D·주차 브레이크 풀림이면 시작 안 됨"""
    go_home()
    put("/vehicle/gear", gear="Drive")
    time.sleep(1.5)
    t = start("easy")
    time.sleep(3)
    shot("gate_safety_locked")
    started, _ = wait_log("stage|INTRO", 2, t)
    record("gate: 기어 D에서는 시작 안 됨", started is None)
    put("/vehicle/gear", gear="Park")
    put("/vehicle/telltales/simulator", color="Red", type="ParkingBrake", state="Off")
    time.sleep(1.5)
    t = start("easy")
    time.sleep(2)
    started, _ = wait_log("stage|INTRO", 2, t)
    record("gate: 주차 브레이크 풀림이면 시작 안 됨", started is None)
    baseline()
    app(start="easy", go=False)
    time.sleep(2)
    shot("gate_safety_ok")
    go_home()


def t_clear(level):
    name = f"clear_{level}"
    go_home()
    baseline()
    before = car_snapshot()
    t = start(level)
    time.sleep(2.5)
    shot(f"{name}_0_intro")
    play_t, _ = wait_log("stage|PLAY|light", 60, t)
    record(f"{name}: 해킹 인트로 → 플레이", play_t is not None)
    if not play_t:
        return
    steps = [("light", solve_light), ("climate", solve_climate), ("window", solve_window), ("drive", solve_drive), ("master", solve_master)]
    after = play_t
    for zone, fn in steps:
        z0 = time.time()
        ok = fn(after, name)
        solved_t, m = wait_log(f"solved|{zone}" if zone != "master" else "end|", 45, after)
        record(f"{name}: {zone} 해제", ok and solved_t is not None, f"{time.time() - z0:.1f}s {m or ''}")
        if not solved_t:
            shot(f"{name}_fail_{zone}")
            return
        after = solved_t
    time.sleep(3)
    shot(f"{name}_6_result")
    end_t, m = wait_log("end|Cleared", 5, play_t)
    record(f"{name}: 탈출 성공 기록", end_t is not None)
    time.sleep(1)
    after_state = car_snapshot()
    diffs = {k: (before[k], after_state[k]) for k in before if before[k] != after_state[k]}
    record(f"{name}: 끝나면 공조·무드등·와이퍼·열선 원래대로", not diffs, json.dumps(diffs, ensure_ascii=False))


def t_wrong():
    go_home()
    baseline()
    t = start("normal")
    play_t, _ = wait_log("stage|PLAY|light", 60, t)
    _, m = wait_log("blink|answer=", 20, t)
    answer = int(m.split("|")[1].split("=")[1])
    time.sleep(2)
    app(type=str((answer % 5) + 1 if answer != 5 else 1))
    w, m = wait_log("wrong|", 5, play_t)
    time.sleep(0.8)
    shot("wrong_counter_attack")
    record("wrong: 틀리면 벌점 + NULL 반격", w is not None, m or "")
    time.sleep(6)
    shot("wrong_after")
    app(cmd="stop")
    a, m = wait_log("abort|", 5, play_t)
    record("wrong: 그만 버튼으로 중단", a is not None, m or "")
    time.sleep(1.5)
    shot("stopped_popup")


def t_abort(kind):
    go_home()
    baseline()
    before = car_snapshot()
    t = start("easy")
    # 인트로 연출(무드등·찬바람) 한창일 때 위험 조건을 만든다
    time.sleep(4)
    if kind == "gear":
        t0 = put("/vehicle/gear", gear="Reverse")
    elif kind == "brake":
        t0 = put("/vehicle/telltales/simulator", color="Red", type="ParkingBrake", state="Off")
    else:
        t0 = now()
        app(cmd="stop")
    a, m = wait_log("abort|", 5, t0)
    record(f"abort_{kind}: 즉시 중단", a is not None, f"{(a - t0):.0f}ms {m}" if a else "")
    time.sleep(2)
    shot(f"abort_{kind}")
    baseline()
    after_state = car_snapshot()
    diffs = {k: (before[k], after_state[k]) for k in before if before[k] != after_state[k]}
    record(f"abort_{kind}: 원래대로 복구", not diffs, json.dumps(diffs, ensure_ascii=False))


TESTS = {
    "gate": t_gate,
    "clear_easy": lambda: t_clear("easy"),
    "clear_hard": lambda: t_clear("hard"),
    "wrong": t_wrong,
    "abort_gear": lambda: t_abort("gear"),
    "abort_brake": lambda: t_abort("brake"),
    "stop": lambda: t_abort("stop"),
}

if __name__ == "__main__":
    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    time.sleep(1.5)
    OFFSET = clock_offset()
    print(f"clock offset {OFFSET:.0f}ms")
    names = sys.argv[2:] or list(TESTS)
    try:
        for n in names:
            print(f"── {n}")
            TESTS[n]()
    finally:
        baseline()
        go_home()
        stop.set()
        with open(f"{OUT}/results.json", "w", encoding="utf-8") as f:
            json.dump(RESULTS, f, ensure_ascii=False, indent=1)
        ok = sum(r["ok"] for r in RESULTS)
        print(f"{ok}/{len(RESULTS)} passed")
