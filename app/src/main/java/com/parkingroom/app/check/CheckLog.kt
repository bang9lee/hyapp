package com.parkingroom.app.check

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * 1단계 기능 확인 결과 기록.
 * logcat 형식: PRCHECK  item|event|epochMillis|detail  (PC 스크립트가 이 줄을 모아 표로 만든다)
 */
object CheckLog {
    const val TAG = "PRCHECK"

    private val main = Handler(Looper.getMainLooper())

    /** 화면 표시용: 항목별 마지막 요약 */
    val summary = mutableStateMapOf<String, String>()

    /** 화면 표시용: 최근 로그 (최신이 위) */
    val recent = mutableStateListOf<String>()

    fun log(item: String, event: String, detail: String = "") {
        val now = System.currentTimeMillis()
        Log.i(TAG, "$item|$event|$now|$detail")
        main.post {
            summary[item] = "$event: $detail"
            recent.add(0, "$item · $event · $detail")
            if (recent.size > 300) recent.removeRange(300, recent.size)
        }
    }

    fun fail(item: String, event: String, e: Throwable) =
        log(item, "$event-FAIL", "${e.javaClass.simpleName}:${e.message}")
}
