package com.parkingroom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TireRepair
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parkingroom.app.game.AppController
import com.parkingroom.app.game.GameSetup
import com.parkingroom.app.game.Screen

@Composable
fun HomeScreen(c: AppController) {
    val colors = P.c
    val scenario = c.scenario
    var level by rememberSaveable { mutableIntStateOf(0) }
    var coop by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth > 1200.dp
        val info: @Composable (Modifier) -> Unit = { m ->
            PCard(m, padding = 48.dp) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("차량형 방탈출", style = PleosType.BodySStrong, color = colors.negative)
                        Text("해킹된 차", style = PleosType.HeadlineM, color = colors.text)
                        Text(scenario.subtitle, style = PleosType.BodyL, color = colors.text2)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("난이도", style = PleosType.LabelM, color = colors.text3)
                        PSegmented(scenario.levels.map { it.label }, level, { level = it }, Modifier.fillMaxWidth())
                        Text(scenario.levels[level].description, style = PleosType.BodyS, color = colors.text3)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("인원", style = PleosType.LabelM, color = colors.text3)
                        PSegmented(listOf("혼자", "같이 (협동)"), coop, { coop = it }, Modifier.fillMaxWidth())
                    }
                    if (wide) Box(Modifier.weight(1f))
                    PButton(
                        "해킹 대응 시작",
                        { c.openSafety(GameSetup(scenario.levels[level].key, coop == 1)) },
                        Modifier.fillMaxWidth(),
                        size = BtnSize.L,
                        icon = Icons.Rounded.PlayArrow,
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(bottom = 40.dp)) {
            AppTitle("파킹룸") {
                PIconButton(Icons.Rounded.EmojiEvents, null, { c.go(Screen.Ranking) }, description = "랭킹")
                PIconButton(Icons.Rounded.Settings, null, { c.go(Screen.Settings) }, description = "설정")
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    HackedCar(Modifier.weight(1.2f).fillMaxHeight())
                    info(Modifier.weight(0.8f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    HackedCar(Modifier.fillMaxWidth().aspectRatio(1.4f))
                    info(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 해킹된 차: 붉은 빛 + 잠긴 보안 구역 4곳 표시 */
@Composable
private fun HackedCar(m: Modifier) {
    val colors = P.c
    Box(m) {
        CarScene(
            Modifier.fillMaxSize(),
            glow = colors.negative,
            spots = listOf(
                CarSpot(FrontSpots.LIGHT_X, FrontSpots.LIGHT_Y, (-60).dp, 110.dp, colors.negative, pulse = true),
                CarSpot(FrontSpots.CLIMATE_X, FrontSpots.CLIMATE_Y, (-80).dp, (-120).dp, colors.negative, pulse = true),
                CarSpot(FrontSpots.WINDOW_X, FrontSpots.WINDOW_Y, 60.dp, (-110).dp, colors.negative, pulse = true),
                CarSpot(FrontSpots.DRIVE_X, FrontSpots.DRIVE_Y, 70.dp, 110.dp, colors.negative, pulse = true),
            ),
            tags = listOf(
                { ZoneTag(Icons.Rounded.Lightbulb, "조명", locked = true) },
                { ZoneTag(Icons.Rounded.AcUnit, "공조", locked = true) },
                { ZoneTag(Icons.Rounded.Window, "창문", locked = true) },
                { ZoneTag(Icons.Rounded.TireRepair, "구동", locked = true) },
            ),
        )
        PChip("해킹 감지 · 보안 구역 4곳 잠김", colors.negative, icon = Icons.Rounded.Lock, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** 차 위 꼬리표: 흰 알약 + 그림자 (잠김=빨간 자물쇠, 해제=초록 체크) */
@Composable
fun ZoneTag(icon: ImageVector, label: String, locked: Boolean, digit: Int? = null, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = P.c
    val shape = RoundedCornerShape(24.dp)
    val bg = if (selected) colors.filled else if (colors.dark) colors.surfaceAccent else colors.surface
    val fg = if (selected) colors.onFilled else colors.text
    Row(
        Modifier.height(72.dp).raised(shape, bg, colors.dark)
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(if (locked) colors.negative else colors.active, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Text(label, style = PleosType.BodyLStrong, color = fg)
        if (!locked && digit != null) Text("$digit", style = PleosType.BodyLStrong.copy(fontWeight = FontWeight.ExtraBold), color = if (selected) fg else colors.active)
        if (locked) Icon(Icons.Rounded.Lock, null, tint = if (selected) fg.copy(alpha = 0.64f) else colors.text4, modifier = Modifier.size(28.dp))
    }
}

/** ivi_title Type=Main: 앱 아이콘 80 + 제목, 오른쪽 아이콘 버튼들 (높이 80, 왼쪽 40 · 오른쪽 24) */
@Composable
fun AppTitle(title: String, sub: String? = null, actions: @Composable () -> Unit = {}) {
    val colors = P.c
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 24.dp).height(80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        AppIcon(80)
        Text(title, style = PleosType.TitleL, color = colors.text)
        sub?.let {
            Box(Modifier.size(2.dp, 32.dp).background(colors.tint20))
            Text(it, style = PleosType.BodyL, color = colors.text3, maxLines = 1)
        }
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** 앱 아이콘: 어두운 판 위 흰 P, 오른쪽 위 빨간 해킹 점 (그림자 y8 blur32 16%) */
@Composable
fun AppIcon(size: Int) {
    val shape = RoundedCornerShape((size * 0.26f).dp)
    Box(
        Modifier.size(size.dp).shadow((size * 0.12f).dp, shape).background(Brush.linearGradient(listOf(Color(0xFF3A3D45), Color(0xFF131417))), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", fontSize = (size * 0.58f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Box(Modifier.align(Alignment.TopEnd).padding((size * 0.16f).dp).size((size * 0.16f).dp).background(Color(0xFFF62E24), CircleShape))
    }
}
