package com.parkingroom.app.car

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 차량 연결 묶음. 앱 전체에서 하나만 만든다 */
class CarHub(context: Context, scope: CoroutineScope) {
    val vehicle = Vehicle(context).also { it.initialize() }
    val signals = CarSignals(context, vehicle)
    val actuators = CarActuators(vehicle)
    val safety = SafetyMonitor(signals, scope)

    init {
        signals.start()
        scope.launch {
            actuators.loadCapabilities()
            signals.startClimate(actuators.driverTempArea, actuators.fanSpeedArea)
        }
    }

    fun release() {
        signals.stop()
        vehicle.release()
    }
}
