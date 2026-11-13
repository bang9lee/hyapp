package com.parkingroom.app

import android.car.Car
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.parkingroom.app.game.GameSetup
import com.parkingroom.app.game.Screen
import com.parkingroom.app.ui.AppRoot
import com.parkingroom.app.ui.ParkingRoomTheme

class MainActivity : ComponentActivity() {

    private val controller get() = (application as ParkingRoomApp).controller

    // 차속(CAR_SPEED)은 dangerous 권한이라 실행 중에 허락을 받는다
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.onSpeedPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequest.launch(Car.PERMISSION_SPEED)
        setContent { ParkingRoomTheme { AppRoot(controller) } }
        // 화면이 다시 만들어질 때(설정 변경) 예전 시작 명령이 다시 실행되지 않도록 첫 생성 때만 처리
        if (savedInstanceState == null) handleTestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // 게임 중 화면을 벗어나면 연출을 남겨 두지 않고 멈춘 뒤 복구한다
        if (!isChangingConfigurations) controller.onAppHidden()
    }

    /**
     * 디버그 빌드 전용 자동 테스트 훅 (release에서는 무시).
     * adb shell am start -n com.parkingroom.app/.MainActivity --es start easy
     *   --es pick light|climate|window|drive|master  --es type 1234  --es cmd stop|hint|replay|home
     */
    private fun handleTestIntent(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        i.getStringExtra("run")?.let {
            controller.go(Screen.DevCheck)
            controller.devCommand(it)
        }
        i.getStringExtra("start")?.let { level ->
            val setup = GameSetup(level, coop = i.getBooleanExtra("coop", false))
            controller.openSafety(setup)
            if (i.getBooleanExtra("go", true)) controller.start(setup)
        }
        i.getStringExtra("pick")?.let { controller.session.value?.select(it) }
        when (i.getStringExtra("cmd")) {
            "stop" -> controller.abort("그만 버튼을 눌러서 멈췄어요")
            "hint" -> controller.session.value?.requestHint()
            "replay" -> controller.session.value?.replay()
            "home" -> controller.go(Screen.Home)
        }
        i.getStringExtra("type")?.let { controller.puzzles.submitTyped(it) }
    }
}
