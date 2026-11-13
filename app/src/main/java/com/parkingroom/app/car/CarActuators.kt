package com.parkingroom.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 이 차에서 앱이 바꿀 수 있는 것 (checkXXXCapabilities 결과 + 쓰기 실패 시 꺼짐) */
class CarCapabilities {
    @Volatile var hvacPower = false
    @Volatile var fan = false
    @Volatile var temp = false
    @Volatile var steeringHeat = false
    @Volatile var ambientSwitch = false
    @Volatile var ambientColor = false
    @Volatile var ambientBrightness = false
    @Volatile var wiper = false

    override fun toString() =
        "power=$hvacPower fan=$fan temp=$temp steer=$steeringHeat " +
            "ambient(sw=$ambientSwitch color=$ambientColor bright=$ambientBrightness) wiper=$wiper"
}

/**
 * 4D 연출용 차량 제어.
 * 처음 바꾸는 순간 원래 값을 기억해 두고, restoreAll()로 한 번에 되돌린다.
 * 안전 원칙: 창문·문 잠금은 이 클래스에 쓰기 함수를 두지 않는다.
 */
class CarActuators(vehicle: Vehicle) {

    private val hvac = vehicle.getHVAC()
    private val light = vehicle.getLight()
    private val wiper = vehicle.getWiper()
    private val steering = vehicle.getSteeringWheel()

    val can = CarCapabilities()

    // 공조는 속성마다 지원 영역이 다르다 (1단계 확인: 전원=전 좌석 묶음, 풍량=앞줄 묶음, 온도=좌석별)
    private var powerArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var fanArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var tempAreas = listOf(SeatArea(SeatValue.ROW_1_LEFT))

    /** 온도 금고 판정에 쓰는 영역 (운전석 온도, 앞줄 풍량) */
    val driverTempArea: SeatArea get() = tempAreas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: tempAreas.first()
    val fanSpeedArea: SeatArea get() = fanArea
    private var ambientArea = SeatArea(SeatValue.ROW_1_LEFT)
    private val frontWiper = WindowArea(WindowValue.FRONT_WINDSHIELD)

    var fanRange = 1..8
        private set
    var tempRange = 16f..30f
        private set
    var steeringMax = 3
        private set
    private var wiperOnceMode = WiperSwitch.CONTINUOUS_LEVEL_1

    private val originals = LinkedHashMap<String, suspend () -> Any?>()
    private val keeper = Mutex()

    private fun writable(t: AccessType) = t == AccessType.READ_WRITE || t == AccessType.WRITE

    suspend fun loadCapabilities() {
        awaitCar<Unit> { ok, err ->
            hvac.checkHvacPowerCapabilities({ c, l ->
                can.hvacPower = writable(c.accessType) && l.isNotEmpty()
                l.firstOrNull()?.let { powerArea = it }
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            hvac.checkHvacFanSpeedCapabilities({ c, m ->
                can.fan = writable(c.accessType) && m.isNotEmpty()
                m.entries.firstOrNull()?.let { (a, b) ->
                    fanArea = a
                    fanRange = maxOf(1, b.minValue)..b.maxValue
                }
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ c, m, _ ->
                can.temp = writable(c.accessType) && m.isNotEmpty()
                if (m.isNotEmpty()) {
                    tempAreas = m.keys.toList()
                    val b = m.values.first()
                    tempRange = b.minValue..b.maxValue
                }
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            steering.checkHvacSteeringWheelHeatCapabilities({ c, b ->
                can.steeringHeat = writable(c.accessType)
                steeringMax = b.maxValue
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            light.checkAmbientLightSwitchCapabilities({ c, m ->
                can.ambientSwitch = writable(c.accessType) && m.isNotEmpty()
                m.keys.firstOrNull()?.let { ambientArea = it }
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            light.checkAmbientLightBrightnessColorCapabilities({ c, l ->
                can.ambientColor = writable(c.accessType) && l.isNotEmpty()
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            light.checkAmbientLightBrightnessCapabilities({ c, m ->
                can.ambientBrightness = writable(c.accessType) && m.isNotEmpty()
                ok(Unit)
            }, err)
        }
        awaitCar<Unit> { ok, err ->
            wiper.checkWiperSwitchCapabilities({ c, m ->
                val modes = m[frontWiper].orEmpty()
                can.wiper = writable(c.accessType) && modes.isNotEmpty()
                // 1회 닦기(MIST)가 없으면 가장 느린 연속 모드를 잠깐 켰다 끈다
                wiperOnceMode = when {
                    WiperSwitch.MIST in modes -> WiperSwitch.MIST
                    WiperSwitch.CONTINUOUS_LEVEL_1 in modes -> WiperSwitch.CONTINUOUS_LEVEL_1
                    else -> modes.firstOrNull { it != WiperSwitch.OFF && it != WiperSwitch.AUTO } ?: WiperSwitch.OFF
                }
                ok(Unit)
            }, err)
        }
        Log.i(TAG, "capabilities: $can")
    }

    private fun failed(key: String, e: Throwable) {
        Log.w(TAG, "$key write failed: ${e.message}")
        if (!e.isPermanentFailure()) return
        when (key) {
            "power" -> can.hvacPower = false
            "fan" -> can.fan = false
            "temp" -> can.temp = false
            "steering" -> can.steeringHeat = false
            "ambientSwitch" -> can.ambientSwitch = false
            "ambientColor" -> can.ambientColor = false
            "ambientBrightness" -> can.ambientBrightness = false
            "wiper" -> can.wiper = false
        }
    }

    /** key를 처음 바꿀 때만 현재 값을 읽어 되돌리기 동작을 저장한다 */
    private suspend fun remember(key: String, snapshot: suspend () -> (suspend () -> Any?)?) {
        keeper.withLock {
            if (key in originals) return
            snapshot()?.let { originals[key] = it }
        }
    }

    // ───────────────────────── 공조 ─────────────────────────

    suspend fun hvacPower(on: Boolean): Boolean {
        if (!can.hvacPower) return false
        remember("power") {
            awaitCar<Boolean?> { ok, err -> hvac.getHvacPowerState(powerArea, ok, err) }.getOrNull()
                ?.let { v -> suspend { rawPower(v) } }
        }
        return rawPower(on)
    }

    private suspend fun rawPower(on: Boolean) = awaitCar<Boolean?> { ok, err ->
        hvac.setHvacPower(powerArea, on, { _, r -> ok(r) }, err)
    }.onFailure { failed("power", it) }.isSuccess

    suspend fun fan(level: Int): Boolean {
        if (!can.fan) return false
        remember("fan") {
            awaitCar<Int?> { ok, err -> hvac.getHvacFanSpeed(fanArea, ok, err) }.getOrNull()
                ?.let { v -> suspend { rawFan(v) } }
        }
        return rawFan(level.coerceIn(fanRange))
    }

    private suspend fun rawFan(v: Int) = awaitCar<Int?> { ok, err ->
        hvac.setHvacFanSpeed(fanArea, v, { _, r -> ok(r) }, err)
    }.onFailure { failed("fan", it) }.isSuccess

    suspend fun temperature(celsius: Float): Boolean {
        if (!can.temp) return false
        val target = celsius.coerceIn(tempRange)
        var ok = true
        for (area in tempAreas) {
            remember("temp:${area.values.joinToString()}") {
                awaitCar<Float?> { o, e -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, o, e) }.getOrNull()
                    ?.let { v -> suspend { rawTemp(area, v) } }
            }
            ok = rawTemp(area, target) && ok
        }
        return ok
    }

    private suspend fun rawTemp(area: SeatArea, v: Float) = awaitCar<Float?> { ok, err ->
        hvac.setHvacTemperature(area, v, TemperatureUnit.CELSIUS, { _, r -> ok(r) }, err)
    }.onFailure { failed("temp", it) }.isSuccess

    suspend fun steeringHeat(level: Int): Boolean {
        if (!can.steeringHeat) return false
        remember("steering") {
            awaitCar<Int?> { ok, err -> steering.getHvacSteeringWheelHeatState(ok, err) }.getOrNull()
                ?.let { v -> suspend { rawSteering(v) } }
        }
        return rawSteering(level.coerceIn(0, steeringMax))
    }

    private suspend fun rawSteering(v: Int) = awaitCar<Int?> { ok, err ->
        steering.setHvacSteeringWheelHeat(v, ok, err)
    }.onFailure { failed("steering", it) }.isSuccess

    // ───────────────────────── 무드등 ─────────────────────────

    /** 색(ARGB)·밝기(0..100)를 바꾼다. 꺼져 있으면 먼저 켠다. 반환: 색이 실제로 바뀌었는지 */
    suspend fun ambient(argb: Int?, brightness: Int?): Boolean {
        if (can.ambientSwitch) {
            remember("ambientSwitch") {
                awaitCar<LightSwitch?> { ok, err -> light.getAmbientLightSwitch(ambientArea, ok, err) }.getOrNull()
                    ?.let { v -> suspend { rawAmbientSwitch(v) } }
            }
            rawAmbientSwitch(LightSwitch.ON)
        }
        var colorDone = false
        if (argb != null && can.ambientColor) {
            remember("ambientColor") {
                awaitCar<Int?> { ok, err -> light.getAmbientLightBrightnessColor(ambientArea, ok, err) }.getOrNull()
                    ?.let { v -> suspend { rawAmbientColor(v) } }
            }
            colorDone = rawAmbientColor(argb or 0xFF000000.toInt())
        }
        if (brightness != null) ambientBrightness(brightness)
        return colorDone
    }

    suspend fun ambientBrightness(v: Int): Boolean {
        if (!can.ambientBrightness) return false
        remember("ambientBrightness") {
            awaitCar<Int?> { ok, err -> light.getAmbientLightBrightness(ambientArea, ok, err) }.getOrNull()
                ?.let { b -> suspend { rawAmbientBrightness(b) } }
        }
        return rawAmbientBrightness(v.coerceIn(0, 100))
    }

    private var switchedOn = false

    private suspend fun rawAmbientSwitch(v: LightSwitch): Boolean {
        if (v == LightSwitch.ON && switchedOn) return true
        val r = awaitCar<LightSwitch?> { ok, err -> light.setAmbientLightSwitch(ambientArea, v, { _, x -> ok(x) }, err) }
            .onFailure { failed("ambientSwitch", it) }.isSuccess
        switchedOn = r && v == LightSwitch.ON
        return r
    }

    private suspend fun rawAmbientColor(v: Int) = awaitCar<Int> { ok, err ->
        light.setAmbientLightBrightnessColor(ambientArea, v, { _, x -> ok(x) }, err)
    }.onFailure { failed("ambientColor", it) }.isSuccess

    private suspend fun rawAmbientBrightness(v: Int) = awaitCar<Int> { ok, err ->
        light.setAmbientLightBrightness(ambientArea, v, { _, x -> ok(x) }, err)
    }.onFailure { failed("ambientBrightness", it) }.isSuccess

    // ───────────────────────── 와이퍼 ─────────────────────────

    /** 와이퍼 한 번 지나가기 */
    suspend fun wipeOnce(holdMs: Long = 1200) {
        if (!can.wiper) return
        rememberWiper()
        rawWiper(wiperOnceMode)
        delay(holdMs)
        rawWiper(WiperSwitch.OFF)
    }

    /** 글리치: 박자를 놓친 듯 멈칫하는 와이퍼 */
    suspend fun wipeStutter() {
        if (!can.wiper) return
        rememberWiper()
        for ((mode, ms) in listOf(wiperOnceMode to 450L, WiperSwitch.OFF to 700L, wiperOnceMode to 900L)) {
            rawWiper(mode)
            delay(ms)
        }
        rawWiper(WiperSwitch.OFF)
    }

    private suspend fun rememberWiper() = remember("wiper") {
        awaitCar<WiperSwitch?> { ok, err -> wiper.getCurrentWiperSwitch(frontWiper, ok, err) }.getOrNull()
            ?.let { v -> suspend { rawWiper(v) } }
    }

    private suspend fun rawWiper(v: WiperSwitch) = awaitCar<WiperSwitch?> { ok, err ->
        wiper.setWiperSwitch(frontWiper, v, { _, r -> ok(r) }, err)
    }.onFailure { failed("wiper", it) }.isSuccess

    // ───────────────────────── 복구 ─────────────────────────

    /** 앱이 바꾼 모든 값을 바꾸기 전 값으로 되돌린다 (취소 중에도 끝까지 실행) */
    suspend fun restoreAll() = withContext(NonCancellable) {
        keeper.withLock {
            val list = originals.entries.reversed()
            originals.clear()
            for ((key, restore) in list) {
                runCatching { restore() }.onFailure { Log.w(TAG, "restore $key: ${it.message}") }
            }
            switchedOn = false
            Log.i(TAG, "restored ${list.size} values")
        }
    }

    val hasChanges: Boolean get() = originals.isNotEmpty()

    companion object {
        private const val TAG = "CarActuators"
    }
}
