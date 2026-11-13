package com.parkingroom.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import android.car.drivingstate.CarUxRestrictionsManager
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.VehiclePropertyIds
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 조작 입력 이벤트. atMs = SystemClock.elapsedRealtime() 기준 도착 시각 (리듬 판정용) */
sealed interface CarInput {
    val atMs: Long

    data class Turn(val dir: TurnSignalValue, override val atMs: Long) : CarInput
    data class Brake(override val atMs: Long) : CarInput
    data class WiperOn(override val atMs: Long) : CarInput
}

/**
 * 차량에서 읽는 값 모음.
 * - 기어·속도·주차 브레이크: AAOS CarPropertyManager (Vehicle SDK에 없음)
 * - 나머지: Pleos Vehicle SDK 리스너
 */
class CarSignals(private val context: Context, vehicle: Vehicle) {

    private val turnSignal = vehicle.getTurnSignal()
    private val brake = vehicle.getBrake()
    private val wiper = vehicle.getWiper()
    private val window = vehicle.getWindow()
    private val steering = vehicle.getSteeringWheel()
    private val safety = vehicle.getSafety()
    private val hvac = vehicle.getHVAC()

    private val _gear = MutableStateFlow<Int?>(null)
    private val _speedMps = MutableStateFlow<Float?>(null)
    private val _parkingBrakeAaos = MutableStateFlow<Boolean?>(null)
    private val _parkingBrakeTelltale = MutableStateFlow<Boolean?>(null)
    private val _turn = MutableStateFlow<TurnSignalValue?>(null)
    private val _brakePressed = MutableStateFlow<Boolean?>(null)
    private val _wiperOn = MutableStateFlow<Boolean?>(null)
    private val _windows = MutableStateFlow<Map<WindowValue, Int>>(emptyMap())
    private val _steeringHeat = MutableStateFlow<Int?>(null)
    private val _hvacTemp = MutableStateFlow<Float?>(null)
    private val _fanSpeed = MutableStateFlow<Int?>(null)
    private val _uxRestricted = MutableStateFlow<Boolean?>(null)
    private val _inputs = MutableSharedFlow<CarInput>(extraBufferCapacity = 64)

    /** AAOS VehicleGear 값 (GEAR_PARK = 4) */
    val gear: StateFlow<Int?> = _gear.asStateFlow()
    val speedMps: StateFlow<Float?> = _speedMps.asStateFlow()
    val parkingBrakeAaos: StateFlow<Boolean?> = _parkingBrakeAaos.asStateFlow()
    val parkingBrakeTelltale: StateFlow<Boolean?> = _parkingBrakeTelltale.asStateFlow()
    val turn: StateFlow<TurnSignalValue?> = _turn.asStateFlow()
    val brakePressed: StateFlow<Boolean?> = _brakePressed.asStateFlow()
    val wiperOn: StateFlow<Boolean?> = _wiperOn.asStateFlow()

    /** 창문 위치 0(닫힘)..10(완전히 열림), 10% 단위 */
    val windows: StateFlow<Map<WindowValue, Int>> = _windows.asStateFlow()
    val steeringHeat: StateFlow<Int?> = _steeringHeat.asStateFlow()

    /** 운전석 설정 온도(°C)·풍량 — 온도 금고 판정용 */
    val hvacTemp: StateFlow<Float?> = _hvacTemp.asStateFlow()
    val fanSpeed: StateFlow<Int?> = _fanSpeed.asStateFlow()

    /** 주행 규제(CarUxRestrictions) 활성 여부 */
    val uxRestricted: StateFlow<Boolean?> = _uxRestricted.asStateFlow()
    val inputs: SharedFlow<CarInput> = _inputs.asSharedFlow()

    private val windowValues = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val unregister = mutableListOf<() -> Unit>()

    private var car: Car? = null
    private var cpm: CarPropertyManager? = null

    private fun now() = SystemClock.elapsedRealtime()

    private fun warn(what: String): (Exception) -> Unit = { e -> Log.w(TAG, "$what: ${e.message}") }

    // ───────────────────────── AAOS: 기어·속도·주차 브레이크 ─────────────────────────

    private val aaosCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> _gear.value = value.value as? Int
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> _speedMps.value = value.value as? Float
                VehiclePropertyIds.PARKING_BRAKE_ON -> _parkingBrakeAaos.value = value.value as? Boolean
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "AAOS property error $propertyId")
        }
    }

    /** 차속 권한(CAR_SPEED)을 나중에 허락받으면 다시 불러 구독을 새로 건다 */
    fun startAaos() {
        stopAaos()
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: return
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        car = c
        cpm = m
        fun sub(id: Int, rate: Float, sink: MutableStateFlow<*>) {
            runCatching {
                if (m.getCarPropertyConfig(id) == null) return
                @Suppress("UNCHECKED_CAST")
                (sink as MutableStateFlow<Any?>).value = m.getProperty<Any>(id, 0).value
                m.registerCallback(aaosCallback, id, rate)
            }.onFailure { Log.w(TAG, "AAOS $id: ${it.message}") }
        }
        sub(VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, _gear)
        sub(VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE, _parkingBrakeAaos)
        sub(VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST, _speedMps)
        // 주행 규제: 플랫폼이 지역별 규제에 맞춰 내려주는 값을 그대로 따른다 (주행 규제 개발 가이드)
        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return@runCatching
            _uxRestricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization
            ux.registerListener { r -> _uxRestricted.value = r.isRequiresDistractionOptimization }
            uxManager = ux
        }.onFailure { Log.w(TAG, "ux restrictions: ${it.message}") }
    }

    private var uxManager: CarUxRestrictionsManager? = null

    /** 공조 리스너는 capability로 정한 영역(온도=좌석별, 풍량=앞줄 묶음)이 필요해 따로 시작한다 */
    fun startClimate(tempArea: SeatArea, fanArea: SeatArea) {
        val tempL = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(temperature: Float?) {
                _hvacTemp.value = temperature
            }

            override fun onFailed(e: Exception) = warn("hvac temp")(e)
        }
        val fanL = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) {
                _fanSpeed.value = speed
            }

            override fun onFailed(e: Exception) = warn("fan")(e)
        }
        runCatching {
            hvac.registerHvacTemperature(tempArea, tempL)
            hvac.registerHvacFanSpeed(fanArea, fanL)
            unregister += {
                hvac.unregisterHvacTemperature(tempArea, tempL)
                hvac.unregisterHvacFanSpeed(fanArea, fanL)
            }
            hvac.getHvacTemperature(tempArea, TemperatureUnit.CELSIUS, { _hvacTemp.value = it }, warn("hvac temp get"))
            hvac.getHvacFanSpeed(fanArea, { _fanSpeed.value = it }, warn("fan get"))
        }.onFailure { Log.w(TAG, "climate: ${it.message}") }
    }

    private fun stopAaos() {
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        runCatching { cpm?.unregisterCallback(aaosCallback) }
        runCatching { car?.disconnect() }
        cpm = null
        car = null
    }

    // ───────────────────────── Vehicle SDK 리스너 ─────────────────────────

    fun start() {
        startAaos()
        if (unregister.isNotEmpty()) return

        val turnL = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
                val prev = _turn.value
                _turn.value = state
                if (state != null && state != TurnSignalValue.OFF && state != prev) _inputs.tryEmit(CarInput.Turn(state, now()))
            }

            override fun onFailed(e: Exception) = warn("turn")(e)
        }
        turnSignal.registerTurnSignalState(turnL)
        unregister += { turnSignal.unregisterTurnSignalState(turnL) }
        turnSignal.getCurrentTurnSignalState({ _turn.value = it }, warn("turn get"))

        val brakeL = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                val prev = _brakePressed.value
                _brakePressed.value = status
                if (status == true && prev != true) _inputs.tryEmit(CarInput.Brake(now()))
            }

            override fun onFailed(e: Exception) = warn("brake")(e)
        }
        brake.registerBrakePedalStatus(brakeL)
        unregister += { brake.unregisterBrakePedalStatus(brakeL) }
        brake.getCurrentBrakePedalStatus({ _brakePressed.value = it }, warn("brake get"))

        val wiperL = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                val on = state == WiperState.ON
                val prev = _wiperOn.value
                _wiperOn.value = on
                if (on && prev != true) _inputs.tryEmit(CarInput.WiperOn(now()))
            }

            override fun onFailed(e: Exception) = warn("wiper")(e)
        }
        wiper.registerWiperState(front, wiperL)
        unregister += { wiper.unregisterWiperState(front, wiperL) }
        wiper.getCurrentWiperState(front, { _wiperOn.value = it == WiperState.ON }, warn("wiper get"))

        for (v in windowValues) {
            val area = WindowArea(v)
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) {
                    if (position != null) _windows.update { it + (v to position) }
                }

                override fun onFailed(e: Exception) = warn("window $v")(e)
            }
            window.registerWindowPosition(area, l)
            unregister += { window.unregisterWindowPosition(area, l) }
            window.getCurrentWindowPosition(area, { p -> if (p != null) _windows.update { it + (v to p) } }, warn("window get"))
        }

        val steerL = object : HvacSteeringWheelHeatStateListener {
            override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) {
                _steeringHeat.value = state
            }

            override fun onFailed(e: Exception) = warn("steering heat")(e)
        }
        steering.registerHvacSteeringWheelHeatState(steerL)
        unregister += { steering.unregisterHvacSteeringWheelHeatState(steerL) }
        steering.getHvacSteeringWheelHeatState({ _steeringHeat.value = it }, warn("steering get"))

        val tellL = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) = applyTelltales(telltales)
            override fun onFailed(e: Exception) = warn("telltales")(e)
        }
        runCatching {
            safety.registerTelltales(tellL)
            unregister += { safety.unregisterTelltales(tellL) }
            safety.getTelltalesInfo({ applyTelltales(it) }, warn("telltales get"))
        }.onFailure { Log.w(TAG, "telltales: ${it.message}") }
    }

    // 주차 브레이크 경고등: 켜짐(ON/BLINK)이면 목록에 있고, 꺼지면 목록에서 빠진다
    private fun applyTelltales(t: Telltales?) {
        val list = t?.telltales ?: return
        _parkingBrakeTelltale.value = list.any {
            (it.type == TelltaleType.PARKING_BRAKE || it.type == TelltaleType.ELECTRONIC_PARKING_BRAKE) &&
                it.state != TelltaleState.OFF
        }
    }

    fun stop() {
        unregister.forEach { runCatching(it) }
        unregister.clear()
        stopAaos()
    }

    companion object {
        private const val TAG = "CarSignals"
    }
}
