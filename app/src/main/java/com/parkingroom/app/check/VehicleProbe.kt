package com.parkingroom.app.check

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import ai.pleos.playground.vehicle.listener.SeatHeightPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** 1단계: Vehicle SDK 도메인별 지원 여부·읽기·구독·쓰기 확인 */
class VehicleProbe(vehicle: Vehicle) {

    private val brake = vehicle.getBrake()
    private val turnSignal = vehicle.getTurnSignal()
    private val wiper = vehicle.getWiper()
    private val window = vehicle.getWindow()
    private val seat = vehicle.getSeat()
    private val hvac = vehicle.getHVAC()
    private val steering = vehicle.getSteeringWheel()
    private val mirror = vehicle.getSideMirror()
    private val light = vehicle.getLight()
    private val odometer = vehicle.getOdometer()
    private val evBattery = vehicle.getEvBattery()
    private val fuel = vehicle.getFuel()
    private val safety = vehicle.getSafety()

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val windows = listOf(
        WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT,
    ).map { WindowArea(it) }
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val rearSeats = listOf(SeatArea(SeatValue.ROW_2_LEFT), SeatArea(SeatValue.ROW_2_RIGHT))
    private val mirrors = listOf(MirrorArea(MirrorValue.DRIVER_LEFT), MirrorArea(MirrorValue.DRIVER_RIGHT))

    // capability 결과로 갱신되는 값들 — 공조는 속성마다 지원 영역이 다르다(전원: 전 좌석 묶음, 풍량: 앞줄 묶음, 온도: 좌석별)
    private var powerArea = driver
    private var fanArea = driver
    private var tempArea = driver
    private var ambientArea = driver
    private var fanBounds: AreaBounds<Int>? = null
    private var tempBounds: AreaBounds<Float>? = null
    private var steeringHeatBounds: AreaBounds<Int>? = null
    private var mirrorHeatBounds: AreaBounds<Int>? = null

    /** set 호출 시각 — 리스너가 값을 받기까지 걸린 시간을 재기 위함 */
    private val pendingSet = ConcurrentHashMap<String, Long>()

    private fun mark(item: String) {
        pendingSet[item] = SystemClock.elapsedRealtime()
    }

    private fun update(item: String, value: Any?) {
        val t0 = pendingSet.remove(item)
        val lag = if (t0 != null) " (set→listener ${SystemClock.elapsedRealtime() - t0}ms)" else ""
        CheckLog.log(item, "update", fmt(value) + lag)
    }

    private suspend fun <T> await(
        item: String,
        event: String,
        block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit,
    ): Result<T> {
        val t0 = SystemClock.elapsedRealtime()
        val r = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<T>> { c ->
                block({ if (c.isActive) c.resume(Result.success(it)) }, { if (c.isActive) c.resume(Result.failure(it)) })
            }
        } ?: Result.failure(TimeoutException("no callback in ${TIMEOUT_MS}ms"))
        val dt = SystemClock.elapsedRealtime() - t0
        r.onSuccess { CheckLog.log(item, event, "${fmt(it)} (${dt}ms)") }
            .onFailure { CheckLog.fail(item, event, it) }
        return r
    }

    // ───────────────────────── 1. 지원 여부 (checkXXXCapabilities) ─────────────────────────

    private var capsChecked = false

    suspend fun checkCapabilities() {
        capsChecked = true
        await<String>("brakePedal", "cap") { ok, err ->
            brake.checkBrakePedalStatusCapabilities({ c -> ok(c.accessType.name) }, err)
        }
        await<String>("turnSignal", "cap") { ok, err ->
            turnSignal.checkTurnSignalStateCapabilities({ c -> ok(c.accessType.name) }, err)
        }
        await<String>("wiperState", "cap") { ok, err ->
            wiper.checkWiperStateCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("wiperSwitch", "cap") { ok, err ->
            wiper.checkWiperSwitchCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("windowPos", "cap") { ok, err ->
            window.checkWindowPositionCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("seatBackrest", "cap") { ok, err ->
            seat.checkSeatBackrestAnglePositionCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("seatBackrestMove", "cap") { ok, err ->
            seat.checkMoveSeatBackrestAngleCapabilities({ c, l -> ok("${c.accessType} ${fmt(l)}") }, err)
        }
        await<String>("seatForeAft", "cap") { ok, err ->
            seat.checkSeatForeAftPositionCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("seatHeight", "cap") { ok, err ->
            seat.checkSeatHeightPositionCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("seatOccupancy", "cap") { ok, err ->
            seat.checkSeatOccupancyCapabilities({ c, l -> ok("${c.accessType} ${fmt(l)}") }, err)
        }
        await<String>("hvacPower", "cap") { ok, err ->
            hvac.checkHvacPowerCapabilities({ c, l ->
                l.firstOrNull()?.let { if (driver !in l) powerArea = it }
                ok("${c.accessType} ${fmt(l)}")
            }, err)
        }
        await<String>("fanSpeed", "cap") { ok, err ->
            hvac.checkHvacFanSpeedCapabilities({ c, m ->
                m.keys.firstOrNull()?.let { if (driver !in m) fanArea = it }
                fanBounds = m[fanArea]
                ok("${c.accessType} ${fmt(m)}")
            }, err)
        }
        await<String>("hvacTemp", "cap") { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ c, m, cfg ->
                m.keys.firstOrNull()?.let { if (driver !in m) tempArea = it }
                tempBounds = m[tempArea]
                ok("${c.accessType} ${fmt(m)} cfg=$cfg")
            }, err)
        }
        await<String>("fanDirection", "cap") { ok, err ->
            hvac.checkHvacFanDirectionCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("steeringHeat", "cap") { ok, err ->
            steering.checkHvacSteeringWheelHeatCapabilities({ c, b ->
                steeringHeatBounds = b
                ok("${c.accessType} ${fmt(b)}")
            }, err)
        }
        await<String>("mirrorHeat", "cap") { ok, err ->
            mirror.checkHvacSideMirrorHeatCapabilities({ c, m ->
                mirrorHeatBounds = m[mirrors[0]] ?: m.values.firstOrNull()
                ok("${c.accessType} ${fmt(m)}")
            }, err)
        }
        await<String>("ambientColor", "cap") { ok, err ->
            light.checkAmbientLightBrightnessColorCapabilities({ c, l ->
                l.firstOrNull()?.let { if (driver !in l) ambientArea = it }
                ok("${c.accessType} ${fmt(l)}")
            }, err)
        }
        await<String>("ambientBrightness", "cap") { ok, err ->
            light.checkAmbientLightBrightnessCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("ambientSwitch", "cap") { ok, err ->
            light.checkAmbientLightSwitchCapabilities({ c, m -> ok("${c.accessType} ${fmt(m)}") }, err)
        }
        await<String>("odometer", "cap") { ok, err ->
            odometer.checkCurrentDistanceDrivenCapabilities({ c -> ok(c.accessType.name) }, err)
        }
        await<String>("evSoc", "cap") { ok, err ->
            evBattery.checkEvBatteryStateOfChargeCapabilities({ c -> ok(c.accessType.name) }, err)
        }
        await<String>("fuel", "cap") { ok, err ->
            fuel.checkFuelPercentCapabilities({ c -> ok(c.accessType.name) }, err)
        }
        await<String>("telltales", "cap") { ok, err ->
            safety.checkTelltalesCapabilities({ c -> ok(c.accessType.name) }, err)
        }
    }

    // ───────────────────────── 2. 현재 값 읽기 ─────────────────────────

    suspend fun readAll() {
        await<Boolean?>("brakePedal", "get") { ok, err -> brake.getCurrentBrakePedalStatus(ok, err) }
        await<TurnSignalValue?>("turnSignal", "get") { ok, err -> turnSignal.getCurrentTurnSignalState(ok, err) }
        await<WiperState?>("wiperState", "get") { ok, err -> wiper.getCurrentWiperState(front, ok, err) }
        await<WiperSwitch?>("wiperSwitch", "get") { ok, err -> wiper.getCurrentWiperSwitch(front, ok, err) }
        for (w in windows) {
            await<Int?>("windowPos.${fmt(w)}", "get") { ok, err -> window.getCurrentWindowPosition(w, ok, err) }
        }
        await<Int?>("seatBackrest", "get") { ok, err -> seat.getCurrentSeatBackrestAnglePosition(driver, ok, err) }
        await<Int?>("seatForeAft", "get") { ok, err -> seat.getCurrentSeatForeAftPosition(driver, ok, err) }
        await<Int?>("seatHeight", "get") { ok, err -> seat.getCurrentSeatHeightPosition(driver, ok, err) }
        for (s in rearSeats) {
            await<Boolean?>("seatOccupancy.${fmt(s)}", "get") { ok, err -> seat.getCurrentSeatOccupancy(s, ok, err) }
        }
        await<Boolean?>("hvacPower", "get") { ok, err -> hvac.getHvacPowerState(powerArea, ok, err) }
        await<Int?>("fanSpeed", "get") { ok, err -> hvac.getHvacFanSpeed(fanArea, ok, err) }
        await<Float?>("hvacTemp", "get") { ok, err ->
            hvac.getHvacTemperature(tempArea, TemperatureUnit.CELSIUS, ok, err)
        }
        await<Any?>("fanDirection", "get") { ok, err -> hvac.getHvacFanDirection(fanArea, ok, err) }
        await<Int?>("steeringHeat", "get") { ok, err -> steering.getHvacSteeringWheelHeatState(ok, err) }
        for (m in mirrors) {
            await<Int?>("mirrorHeat.${fmt(m)}", "get") { ok, err -> mirror.getHvacSideMirrorHeatState(m, ok, err) }
        }
        await<String>("ambientColor", "get") { ok, err ->
            light.getAmbientLightBrightnessColor(ambientArea, { ok(it?.let(::hex) ?: "null") }, err)
        }
        await<Int?>("ambientBrightness", "get") { ok, err -> light.getAmbientLightBrightness(ambientArea, ok, err) }
        await<LightSwitch?>("ambientSwitch", "get") { ok, err -> light.getAmbientLightSwitch(ambientArea, ok, err) }
        await<Float?>("odometer", "get") { ok, err -> odometer.getCurrentDistanceDriven(ok, err) }
        await<Int?>("evSoc", "get") { ok, err -> evBattery.getEvBatteryStateOfCharge(ok, err) }
        await<Int?>("fuel", "get") { ok, err -> fuel.getFuelPercent(ok, err) }
        await<Telltales>("telltales", "get") { ok, err -> safety.getTelltalesInfo(ok, err) }
    }

    // ───────────────────────── 3. 변화 구독 (반응 시간 측정용) ─────────────────────────

    private val listeners = mutableListOf<() -> Unit>()

    fun registerAll() {
        if (listeners.isNotEmpty()) return
        fun onFail(item: String) = { e: Exception -> CheckLog.fail(item, "listener", e) }

        val brakeL = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = update("brakePedal", status)
            override fun onFailed(e: Exception) = onFail("brakePedal")(e)
        }
        brake.registerBrakePedalStatus(brakeL)
        listeners += { brake.unregisterBrakePedalStatus(brakeL) }

        val turnL = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = update("turnSignal", state)
            override fun onFailed(e: Exception) = onFail("turnSignal")(e)
        }
        turnSignal.registerTurnSignalState(turnL)
        listeners += { turnSignal.unregisterTurnSignalState(turnL) }

        val wiperL = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = update("wiperState", state)
            override fun onFailed(e: Exception) = onFail("wiperState")(e)
        }
        wiper.registerWiperState(front, wiperL)
        listeners += { wiper.unregisterWiperState(front, wiperL) }

        for (w in windows) {
            val item = "windowPos.${fmt(w)}"
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) = update(item, position)
                override fun onFailed(e: Exception) = onFail(item)(e)
            }
            window.registerWindowPosition(w, l)
            listeners += { window.unregisterWindowPosition(w, l) }
        }

        val backL = object : SeatBackrestAnglePositionListener {
            override fun onSeatBackrestAnglePositionUpdated(position: Int?) = update("seatBackrest", position)
            override fun onFailed(e: Exception) = onFail("seatBackrest")(e)
        }
        seat.registerSeatBackrestAnglePosition(driver, backL)
        listeners += { seat.unregisterSeatBackrestAnglePosition(driver, backL) }

        val foreL = object : SeatForeAftPositionListener {
            override fun onSeatForeAftPositionUpdated(position: Int?) = update("seatForeAft", position)
            override fun onFailed(e: Exception) = onFail("seatForeAft")(e)
        }
        seat.registerSeatForeAftPosition(driver, foreL)
        listeners += { seat.unregisterSeatForeAftPosition(driver, foreL) }

        val heightL = object : SeatHeightPositionListener {
            override fun onSeatHeightPositionUpdated(position: Int?) = update("seatHeight", position)
            override fun onFailed(e: Exception) = onFail("seatHeight")(e)
        }
        seat.registerSeatHeightPosition(driver, heightL)
        listeners += { seat.unregisterSeatHeightPosition(driver, heightL) }

        for (s in rearSeats) {
            val item = "seatOccupancy.${fmt(s)}"
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = update(item, occupancy)
                override fun onFailed(e: Exception) = onFail(item)(e)
            }
            seat.registerSeatOccupancy(s, l)
            listeners += { seat.unregisterSeatOccupancy(s, l) }
        }

        val powerL = object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(state: Boolean?) = update("hvacPower", state)
            override fun onFailed(e: Exception) = onFail("hvacPower")(e)
        }
        hvac.registerHvacPowerState(powerArea, powerL)
        listeners += { hvac.unregisterHvacPowerState(powerArea, powerL) }

        val fanL = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) = update("fanSpeed", speed)
            override fun onFailed(e: Exception) = onFail("fanSpeed")(e)
        }
        hvac.registerHvacFanSpeed(fanArea, fanL)
        listeners += { hvac.unregisterHvacFanSpeed(fanArea, fanL) }

        val tempL = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(temperature: Float?) = update("hvacTemp", temperature)
            override fun onFailed(e: Exception) = onFail("hvacTemp")(e)
        }
        hvac.registerHvacTemperature(tempArea, tempL)
        listeners += { hvac.unregisterHvacTemperature(tempArea, tempL) }

        val steerL = object : HvacSteeringWheelHeatStateListener {
            override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) = update("steeringHeat", state)
            override fun onFailed(e: Exception) = onFail("steeringHeat")(e)
        }
        steering.registerHvacSteeringWheelHeatState(steerL)
        listeners += { steering.unregisterHvacSteeringWheelHeatState(steerL) }

        for (m in mirrors) {
            val item = "mirrorHeat.${fmt(m)}"
            val l = object : HvacSideMirrorHeatStateListener {
                override fun onHvacSideMirrorHeatStateUpdated(state: Int?) = update(item, state)
                override fun onFailed(e: Exception) = onFail(item)(e)
            }
            mirror.registerHvacSideMirrorHeatState(m, l)
            listeners += { mirror.unregisterHvacSideMirrorHeatState(m, l) }
        }

        val colorL = object : AmbientLightBrightnessColorListener {
            override fun onBrightnessColorUpdated(argbColor: Int?) = update("ambientColor", argbColor?.let(::hex))
            override fun onFailed(e: Exception) = onFail("ambientColor")(e)
        }
        light.registerAmbientLightBrightnessColorListener(ambientArea, colorL)
        listeners += { light.unregisterAmbientLightBrightnessColorListener(ambientArea, colorL) }

        val brightL = object : AmbientLightBrightnessListener {
            override fun onBrightnessUpdated(brightness: Int?) = update("ambientBrightness", brightness)
            override fun onFailed(e: Exception) = onFail("ambientBrightness")(e)
        }
        light.registerAmbientLightBrightnessListener(ambientArea, brightL)
        listeners += { light.unregisterAmbientLightBrightnessListener(ambientArea, brightL) }

        val odoL = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = update("odometer", value)
            override fun onFailed(e: Exception) = onFail("odometer")(e)
        }
        odometer.registerDistanceDriven(odoL)
        listeners += { odometer.unregisterDistanceDriven(odoL) }

        val socL = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) = update("evSoc", value)
            override fun onFailed(e: Exception) = onFail("evSoc")(e)
        }
        evBattery.registerEvBatteryStateOfCharge(socL)
        listeners += { evBattery.unregisterEvBatteryStateOfCharge(socL) }

        val tellL = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) = update("telltales", telltales)
            override fun onFailed(e: Exception) = onFail("telltales")(e)
        }
        safety.registerTelltales(tellL)
        listeners += { safety.unregisterTelltales(tellL) }

        CheckLog.log("listeners", "registered", "${listeners.size}")
    }

    fun unregisterAll() {
        listeners.forEach { runCatching(it) }
        listeners.clear()
    }

    // ───────────────────────── 4. 쓰기 테스트 (끝나면 원래 값으로 복구) ─────────────────────────

    suspend fun writeTests() {
        if (!capsChecked) checkCapabilities() // 쓰기 대상 영역은 capability 결과로 정해진다
        testHvac()
        testSteeringHeat()
        testMirrorHeat()
        testAmbient()
        testWiper()
        testSeatBackrest()
        CheckLog.log("write", "done")
    }

    private suspend fun testHvac() {
        val power0 = await<Boolean?>("hvacPower", "get") { ok, err -> hvac.getHvacPowerState(powerArea, ok, err) }.getOrNull()
        if (power0 != true) setHvacPower(true)

        val fan0 = await<Int?>("fanSpeed", "get") { ok, err -> hvac.getHvacFanSpeed(fanArea, ok, err) }.getOrNull()
        val lo = fanBounds?.minValue ?: 1
        val hi = fanBounds?.maxValue ?: 8
        // 한 단계씩: set→onComplete, set→listener 시간
        for (v in (maxOf(lo, 1)..hi) + (hi - 1 downTo maxOf(lo, 1))) {
            setFan(v)
            delay(400)
        }
        // 빠른 연속 변경(파도 박자용): 150ms 간격으로 바꿨을 때 놓치는 값이 있는지
        CheckLog.log("fanSpeed", "burst-start", "150ms interval")
        for (v in listOf(maxOf(lo, 1), hi, maxOf(lo, 1), hi, maxOf(lo, 1), hi)) {
            mark("fanSpeed")
            hvac.setHvacFanSpeed(fanArea, v, { _, _ -> }, { CheckLog.fail("fanSpeed", "burst-set=$v", it) })
            delay(150)
        }
        delay(1500)
        CheckLog.log("fanSpeed", "burst-end")
        fan0?.let { setFan(it) }

        val t0 = await<Float?>("hvacTemp", "get") { ok, err ->
            hvac.getHvacTemperature(tempArea, TemperatureUnit.CELSIUS, ok, err)
        }.getOrNull()
        if (t0 != null) {
            val target = (t0 + 2f).coerceAtMost(tempBounds?.maxValue ?: 30f)
            setTemp(target)
            delay(800)
            setTemp(t0)
        }
        if (power0 == false) setHvacPower(false)
    }

    private suspend fun setHvacPower(on: Boolean) {
        mark("hvacPower")
        await<Boolean?>("hvacPower", "set=$on") { ok, err -> hvac.setHvacPower(powerArea, on, { _, v -> ok(v) }, err) }
    }

    private suspend fun setFan(v: Int) {
        mark("fanSpeed")
        await<Int?>("fanSpeed", "set=$v") { ok, err -> hvac.setHvacFanSpeed(fanArea, v, { _, r -> ok(r) }, err) }
    }

    private suspend fun setTemp(v: Float) {
        mark("hvacTemp")
        await<Float?>("hvacTemp", "set=$v") { ok, err ->
            hvac.setHvacTemperature(tempArea, v, TemperatureUnit.CELSIUS, { _, r -> ok(r) }, err)
        }
    }

    private suspend fun testSteeringHeat() {
        val h0 = await<Int?>("steeringHeat", "get") { ok, err -> steering.getHvacSteeringWheelHeatState(ok, err) }.getOrNull()
        val target = steeringHeatBounds?.maxValue ?: 1
        setSteeringHeat(target)
        delay(800)
        setSteeringHeat(h0 ?: 0)
    }

    private suspend fun setSteeringHeat(v: Int) {
        mark("steeringHeat")
        await<Int?>("steeringHeat", "set=$v") { ok, err -> steering.setHvacSteeringWheelHeat(v, ok, err) }
    }

    private suspend fun testMirrorHeat() {
        val m = mirrors[0]
        val item = "mirrorHeat.${fmt(m)}"
        val h0 = await<Int?>(item, "get") { ok, err -> mirror.getHvacSideMirrorHeatState(m, ok, err) }.getOrNull()
        val target = mirrorHeatBounds?.maxValue ?: 1
        for (v in listOf(target, h0 ?: 0)) {
            mark(item)
            await<Int?>(item, "set=$v") { ok, err -> mirror.setHvacSideMirrorHeat(m, v, { _, r -> ok(r) }, err) }
            delay(800)
        }
    }

    suspend fun testAmbient() {
        if (!capsChecked) checkCapabilities()
        val sw0 = await<LightSwitch?>("ambientSwitch", "get") { ok, err ->
            light.getAmbientLightSwitch(ambientArea, ok, err)
        }.getOrNull()
        val c0 = await<Int?>("ambientColor", "get") { ok, err ->
            light.getAmbientLightBrightnessColor(ambientArea, ok, err)
        }.getOrNull()
        val b0 = await<Int?>("ambientBrightness", "get") { ok, err ->
            light.getAmbientLightBrightness(ambientArea, ok, err)
        }.getOrNull()

        if (sw0 != LightSwitch.ON) setAmbientSwitch(LightSwitch.ON)
        // 콘솔 프리셋(시안·파랑·보라·흰색)에 없는 색도 받아들이는지 되읽어 본다
        for (color in listOf(RED, ORANGE, SEA_BLUE, WHITE)) {
            setAmbientColor(color)
            delay(500)
            await<Int?>("ambientColor", "readback") { ok, err ->
                light.getAmbientLightBrightnessColor(ambientArea, ok, err)
            }
        }
        for (b in listOf(10, 100)) {
            setAmbientBrightness(b)
            delay(400)
        }
        // 박동·번개 연출용: 밝기를 250ms 간격으로 깜빡일 때 따라오는지
        CheckLog.log("ambientBrightness", "pulse-start", "250ms interval")
        repeat(6) { i ->
            mark("ambientBrightness")
            light.setAmbientLightBrightness(ambientArea, if (i % 2 == 0) 10 else 100, { _, _ -> }, {
                CheckLog.fail("ambientBrightness", "pulse", it)
            })
            delay(250)
        }
        delay(1000)
        CheckLog.log("ambientBrightness", "pulse-end")

        c0?.let { setAmbientColor(it) }
        b0?.let { setAmbientBrightness(it) }
        if (sw0 != null && sw0 != LightSwitch.ON) setAmbientSwitch(sw0)
    }

    private suspend fun setAmbientSwitch(v: LightSwitch) {
        await<LightSwitch?>("ambientSwitch", "set=$v") { ok, err ->
            light.setAmbientLightSwitch(ambientArea, v, { _, r -> ok(r) }, err)
        }
    }

    private suspend fun setAmbientColor(argb: Int) {
        mark("ambientColor")
        await<String>("ambientColor", "set=${hex(argb)}") { ok, err ->
            light.setAmbientLightBrightnessColor(ambientArea, argb, { _, r -> ok(hex(r)) }, err)
        }
    }

    private suspend fun setAmbientBrightness(v: Int) {
        mark("ambientBrightness")
        await<Int>("ambientBrightness", "set=$v") { ok, err ->
            light.setAmbientLightBrightness(ambientArea, v, { _, r -> ok(r) }, err)
        }
    }

    private suspend fun testWiper() {
        val sw0 = await<WiperSwitch?>("wiperSwitch", "get") { ok, err -> wiper.getCurrentWiperSwitch(front, ok, err) }.getOrNull()
        // 이 차는 MIST(1회 닦기)를 지원하지 않아, 가장 느린 연속 모드를 잠깐 켰다 끄는 방식으로 1회를 흉내 낸다
        for ((v, hold) in listOf(WiperSwitch.CONTINUOUS_LEVEL_1 to 1200L, WiperSwitch.OFF to 2500L)) {
            mark("wiperState")
            await<WiperSwitch?>("wiperSwitch", "set=$v") { ok, err ->
                wiper.setWiperSwitch(front, v, { _, r -> ok(r) }, err)
            }
            await<WiperSwitch?>("wiperSwitch", "readback") { ok, err -> wiper.getCurrentWiperSwitch(front, ok, err) }
            delay(hold)
        }
        if (sw0 != null && sw0 != WiperSwitch.OFF) {
            await<WiperSwitch?>("wiperSwitch", "set=$sw0") { ok, err ->
                wiper.setWiperSwitch(front, sw0, { _, r -> ok(r) }, err)
            }
        }
    }

    /** 4개 좌석 등받이·앞뒤 위치를 모두 읽는다 (어느 영역 값이 실제로 바뀌는지 확인용) */
    suspend fun readSeats() {
        for (v in listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
            val a = SeatArea(v)
            await<Int?>("seatBackrest.$v", "get") { ok, err -> seat.getCurrentSeatBackrestAnglePosition(a, ok, err) }
            await<Int?>("seatForeAft.$v", "get") { ok, err -> seat.getCurrentSeatForeAftPosition(a, ok, err) }
        }
    }

    suspend fun testSeatBackrest() {
        await<Int?>("seatBackrest", "get") { ok, err -> seat.getCurrentSeatBackrestAnglePosition(driver, ok, err) }
        // 등받이를 0.8초 세웠다가 같은 시간만큼 되돌린다
        for (dir in listOf(MoveControl.FORWARD, MoveControl.BACKWARD)) {
            await<MoveControl?>("seatBackrestMove", "move=$dir") { ok, err ->
                seat.moveSeatBackrestAngle(driver, dir, { _, r -> ok(r) }, err)
            }
            delay(800)
            await<MoveControl?>("seatBackrestMove", "move=STOP") { ok, err ->
                seat.moveSeatBackrestAngle(driver, MoveControl.STOP, { _, r -> ok(r) }, err)
            }
            await<Int?>("seatBackrest", "get") { ok, err -> seat.getCurrentSeatBackrestAnglePosition(driver, ok, err) }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3000L
        private val RED = 0xFFFF0000.toInt()
        private val ORANGE = 0xFFFF8C00.toInt()
        private val SEA_BLUE = 0xFF2979FF.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()

        fun hex(argb: Int) = "#%08X".format(argb)

        fun fmt(x: Any?): String = when (x) {
            null -> "null"
            is WindowArea -> x.values.joinToString("+")
            is SeatArea -> x.values.joinToString("+")
            is MirrorArea -> x.values.joinToString("+")
            is AreaBounds<*> -> "${x.minValue}..${x.maxValue}"
            is Telltales -> x.telltales
                .filter { it.type in PARKING_TYPES }
                .joinToString(",", "parking[", "]") { "${it.type}=${it.state}" } + " total=${x.telltales.size}"
            is Map<*, *> -> x.entries.joinToString(", ", "{", "}") { "${fmt(it.key)}=${fmt(it.value)}" }
            is Collection<*> -> x.joinToString(",", "[", "]") { fmt(it) }
            else -> x.toString()
        }

        private val PARKING_TYPES = setOf(TelltaleType.PARKING_BRAKE, TelltaleType.ELECTRONIC_PARKING_BRAKE)
    }
}
