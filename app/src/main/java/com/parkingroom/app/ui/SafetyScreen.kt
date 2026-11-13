package com.parkingroom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.parkingroom.app.car.SafetyStatus
import com.parkingroom.app.game.AppController
import com.parkingroom.app.game.GameSetup
import com.parkingroom.app.game.Screen

/** 시나리오.md "시작 전 안전 확인" 문구 그대로 (시작할 때마다 표시) */
private val SAFETY_LINES = listOf(
    Icons.Rounded.LocalParking to "차가 안전한 주차 공간에 서 있나요? 도로, 갓길, 주정차 금지 구역에서는 할 수 없어요.",
    Icons.Rounded.WarningAmber to "주차 브레이크를 걸어주세요.",
    Icons.Rounded.PersonOutline to "게임 중 창문, 와이퍼, 에어컨, 열선이 움직이거나 켜질 수 있어요. 차 주변에 사람이 없는지 확인해 주세요.",
    Icons.Rounded.Air to "환기가 안 되는 밀폐된 곳에서는 엔진 시동을 켜둔 채 하지 마세요.",
    Icons.Rounded.MicNone to "언제든 \"그만\"이라고 말하면 바로 끝나요.",
)

/** 시작 전 안전 확인: 공식 System popup(L) 모양, 뒤에는 해킹된 차를 흐리게 깐다 */
@Composable
fun SafetyScreen(c: AppController, setup: GameSetup) {
    val colors = P.c
    val status by c.hub.safety.status.collectAsState()
    val level = c.scenario.level(setup.levelKey)
    BackHandler { c.go(Screen.Home) }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        CarScene(Modifier.fillMaxSize().padding(80.dp), glow = colors.negative, glowStrength = 0.6f)
        Box(Modifier.fillMaxSize().background(colors.overlay))
        val popupWidth = if (maxWidth > 1300.dp) 1200.dp else maxWidth - 80.dp
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp), contentAlignment = Alignment.Center) {
            PPopup(
                Modifier.width(popupWidth),
                icon = Icons.Rounded.ReportProblem,
                iconTint = colors.negative,
                title = "잠깐! 시작하기 전에 확인해 주세요.",
                content = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(
                            Modifier.fillMaxWidth().background(colors.tint5, RoundedCornerShape(32.dp)).padding(horizontal = 32.dp, vertical = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            SAFETY_LINES.forEach { (icon, line) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                                    Icon(icon, null, tint = colors.text3, modifier = Modifier.size(36.dp))
                                    Text(line, style = PleosType.BodyM, color = colors.text2, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        level.warning?.let {
                            Row(
                                Modifier.fillMaxWidth().background(colors.negative.copy(alpha = 0.08f), RoundedCornerShape(24.dp)).padding(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.WarningAmber, null, tint = colors.negative, modifier = Modifier.size(36.dp))
                                Text(it, style = PleosType.BodyM, color = colors.text)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusPill(Modifier.weight(1f), "주차 브레이크", status.parkingBrake, if (status.parkingBrake == true) "걸림" else "풀림")
                            StatusPill(Modifier.weight(1f), "기어", status.gearPark, if (status.gearPark == true) "P" else "P 아님")
                            StatusPill(Modifier.weight(1f), "속도", status.stopped, status.speedKmh?.let { "%.0f km/h".format(it) } ?: "확인 중")
                        }
                        if (!status.safe) Text(unmetHint(status), style = PleosType.BodyS, color = colors.text3)
                    }
                },
            ) {
                PButton("돌아가기", { c.go(Screen.Home) }, Modifier.weight(1f), kind = BtnKind.BASIC, size = BtnSize.L, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                PButton(
                    if (status.safe) "확인했어요, 시작할게요" else "조건을 맞추면 시작돼요",
                    { c.start(setup) },
                    Modifier.weight(1f),
                    size = BtnSize.L,
                    icon = if (status.safe) Icons.Rounded.PlayArrow else null,
                    enabled = status.safe,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(m: Modifier, label: String, ok: Boolean?, value: String) {
    val colors = P.c
    val (tint, icon: ImageVector) = when (ok) {
        true -> colors.active to Icons.Rounded.Check
        false -> colors.negative to Icons.Rounded.Close
        null -> colors.text4 to Icons.Rounded.MoreHoriz
    }
    Row(
        m.heightIn(min = 96.dp).background(colors.tint5, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = PleosType.LabelM, color = colors.text3, maxLines = 1)
            Text(value, style = PleosType.BodyLStrong, color = if (ok == false) colors.negative else colors.text, maxLines = 1)
        }
    }
}

private fun unmetHint(s: SafetyStatus): String = buildList {
    if (s.parkingBrake != true) add("주차 브레이크를 걸어 주세요 (에뮬레이터: Virtual Console › Safety › Telltales › Parking Brake On)")
    if (s.gearPark != true) add("기어를 P에 두세요 (Virtual Console › Motion › Gear Selection › P)")
    if (s.stopped != true) add("차를 완전히 세워 주세요")
    if (s.restricted) add("주행 규제 중이라 시작할 수 없어요")
}.joinToString("\n")
