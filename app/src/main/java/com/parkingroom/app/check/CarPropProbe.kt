package com.parkingroom.app.check

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock

/** 1단계: Vehicle SDK에 없는 기어·차속·주차 브레이크를 AAOS 표준 CarPropertyManager로 확인 */
class CarPropProbe(private val context: Context) {

    private var car: Car? = null
    private var cpm: CarPropertyManager? = null

    private val props = linkedMapOf(
        VehiclePropertyIds.GEAR_SELECTION to "aaos.gearSelection",
        VehiclePropertyIds.CURRENT_GEAR to "aaos.currentGear",
        VehiclePropertyIds.PARKING_BRAKE_ON to "aaos.parkingBrake",
        VehiclePropertyIds.PERF_VEHICLE_SPEED to "aaos.speed",
    )

    private val lastValue = mutableMapOf<Int, Any?>()

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val name = props[value.propertyId] ?: return
            // 차속은 초당 10회 들어오므로 값이 바뀔 때만 기록한다
            if (lastValue.put(value.propertyId, value.value) == value.value &&
                value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED
            ) return
            // CarPropertyValue.timestamp = 차량 신호가 발생한 elapsedRealtimeNanos → 앱 도착까지 지연
            val lagMs = (SystemClock.elapsedRealtimeNanos() - value.timestamp) / 1_000_000
            CheckLog.log(name, "update", "${format(value.propertyId, value.value)} status=${value.status} lag=${lagMs}ms")
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            CheckLog.log(props[propertyId] ?: "aaos.$propertyId", "listener-FAIL", "area=$areaId")
        }
    }

    fun start() {
        if (car != null) return
        val c = runCatching { Car.createCar(context) }
            .onFailure { CheckLog.fail("aaos", "connect", it) }
            .getOrNull() ?: return
        car = c
        val manager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (manager == null) {
            CheckLog.log("aaos", "connect-FAIL", "no CarPropertyManager")
            return
        }
        cpm = manager
        CheckLog.log("aaos", "connect", "ok")

        for ((id, name) in props) {
            val cfg = runCatching { manager.getCarPropertyConfig(id) }
                .onFailure { CheckLog.fail(name, "config", it) }
                .getOrNull()
            if (cfg == null) {
                CheckLog.log(name, "config", "null (미지원 또는 권한 없음)")
                continue
            }
            CheckLog.log(
                name, "config",
                "access=${cfg.access} changeMode=${cfg.changeMode} rate=${cfg.minSampleRate}..${cfg.maxSampleRate}",
            )
            runCatching { manager.getProperty<Any>(id, 0) }
                .onSuccess { CheckLog.log(name, "get", "${format(id, it.value)} status=${it.status}") }
                .onFailure { CheckLog.fail(name, "get", it) }
            val rate = if (cfg.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
                minOf(cfg.maxSampleRate, CarPropertyManager.SENSOR_RATE_FAST)
            } else {
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            }
            runCatching { manager.registerCallback(callback, id, rate) }
                .onSuccess { CheckLog.log(name, "register", "$it rate=$rate") }
                .onFailure { CheckLog.fail(name, "register", it) }
        }
    }

    fun stop() {
        runCatching { cpm?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        cpm = null
        car = null
    }

    private fun format(id: Int, v: Any?): String = when (id) {
        VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> when (v) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            else -> "gear($v)"
        }
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v as? Float)?.let { "%.2fm/s (%.1fkm/h)".format(it, it * 3.6f) } ?: "$v"
        else -> "$v"
    }
}
