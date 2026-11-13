package com.parkingroom.app.car

import android.car.VehicleGear
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 시작·계속 조건: 주차 브레이크 + 기어 P + 속도 0 + 주행 규제 꺼짐.
 * 하나라도 모르면(null) 안전하지 않은 것으로 본다. (주행 규제 값은 플랫폼이 안 주면 꺼짐으로 본다)
 */
data class SafetyStatus(
    val parkingBrake: Boolean?,
    val gearPark: Boolean?,
    val stopped: Boolean?,
    val speedKmh: Float?,
    val restricted: Boolean = false,
) {
    val safe: Boolean get() = parkingBrake == true && gearPark == true && stopped == true && !restricted

    /** 게임 중 멈춘 이유 (안전할 때는 null) */
    val violation: String?
        get() = when {
            gearPark == false -> "기어가 P에서 바뀌었어요"
            stopped == false -> "차가 움직이는 게 감지됐어요"
            parkingBrake == false -> "주차 브레이크가 풀렸어요"
            restricted -> "주행 규제가 켜졌어요"
            !safe -> "차 상태를 확인할 수 없어요"
            else -> null
        }
}

class SafetyMonitor(signals: CarSignals, scope: CoroutineScope) {

    val status: StateFlow<SafetyStatus> = combine(
        signals.parkingBrakeAaos,
        signals.parkingBrakeTelltale,
        signals.gear,
        signals.speedMps,
        signals.uxRestricted,
    ) { aaosBrake, telltale, gear, speed, ux ->
        SafetyStatus(
            // 실차: AAOS PARKING_BRAKE_ON / 에뮬레이터: 주차 브레이크 경고등. 둘 중 하나라도 걸림이면 걸린 것
            parkingBrake = when {
                aaosBrake == true || telltale == true -> true
                aaosBrake == null && telltale == null -> null
                else -> false
            },
            gearPark = gear?.let { it == VehicleGear.GEAR_PARK },
            stopped = speed?.let { abs(it) < STOP_MPS },
            speedKmh = speed?.let { it * 3.6f },
            restricted = ux == true,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SafetyStatus(null, null, null, null))

    companion object {
        private const val STOP_MPS = 0.1f
    }
}
