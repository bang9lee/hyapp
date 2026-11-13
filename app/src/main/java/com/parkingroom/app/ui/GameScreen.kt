package com.parkingroom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TireRepair
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parkingroom.app.engine.PuzzleEngine
import com.parkingroom.app.engine.PuzzleUi
import com.parkingroom.app.engine.Signal
import com.parkingroom.app.game.AppController
import com.parkingroom.app.game.GameSession
import com.parkingroom.app.game.GameUi
import com.parkingroom.app.game.Stage
import com.parkingroom.app.game.Zone
import com.parkingroom.app.voice.VoiceManager
import kotlinx.coroutines.delay

private fun zoneIcon(id: String): ImageVector = when (id) {
    "light" -> Icons.Rounded.Lightbulb
    "climate" -> Icons.Rounded.AcUnit
    "window" -> Icons.Rounded.Window
    "drive" -> Icons.Rounded.TireRepair
    else -> Icons.Rounded.Key
}

/** 구역별로 차 위 어디에 표시하고 꼬리표를 어느 쪽으로 뺄지 */
private fun zoneSpot(id: String, color: Color, pulse: Boolean): CarSpot = when (id) {
    "light" -> CarSpot(FrontSpots.LIGHT_X, FrontSpots.LIGHT_Y, (-40).dp, 120.dp, color, pulse)
    "climate" -> CarSpot(FrontSpots.CLIMATE_X, FrontSpots.CLIMATE_Y, (-70).dp, (-130).dp, color, pulse)
    "window" -> CarSpot(FrontSpots.WINDOW_X, FrontSpots.WINDOW_Y, 40.dp, (-130).dp, color, pulse)
    else -> CarSpot(FrontSpots.DRIVE_X, FrontSpots.DRIVE_Y, 60.dp, 120.dp, color, pulse)
}

private val ZONE_SHORT = mapOf("light" to "조명", "climate" to "공조", "window" to "창문", "drive" to "구동")

@Composable
fun GameScreen(c: AppController, s: GameSession) {
    val colors = P.c
    val ui by s.ui.collectAsState()
    val puzzle by c.puzzles.ui.collectAsState()
    BackHandler { c.abort("뒤로 가기를 눌러서 멈췄어요") }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth > 1200.dp
        Column(Modifier.fillMaxSize()) {
            GameTitle(c, s, ui)
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(0.92f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        CarMap(c, s, ui, Modifier.weight(1f).fillMaxWidth())
                        CodeBar(s, ui)
                    }
                    ZonePanel(c, s, ui, puzzle, Modifier.weight(1.08f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CarMap(c, s, ui, Modifier.fillMaxWidth().height(360.dp))
                    CodeBar(s, ui)
                    ZonePanel(c, s, ui, puzzle, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        // 해킹·기습·반격·엔딩: 화면 전체 몰입 장면
        val immersive = ui.stage != Stage.PLAY || ui.attackText != null
        AnimatedVisibility(immersive, enter = fadeIn(tween(200)), exit = fadeOut(tween(400))) {
            ImmersiveScene(c, ui)
        }
    }
}

// ───────────────────────── 위: 제목 줄 ─────────────────────────

@Composable
private fun GameTitle(c: AppController, s: GameSession, ui: GameUi) {
    val colors = P.c
    val remaining by produceState(s.remainingMs(), s) {
        while (true) {
            value = s.remainingMs()
            delay(200)
        }
    }
    val urgent = remaining < 60_000
    AppTitle("해킹된 차", "${s.level.label}${if (s.setup.coop) " · 협동" else ""}") {
        Row(
            Modifier.height(80.dp).background(if (urgent) colors.negative else colors.tint5, RoundedCornerShape(24.dp)).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Schedule, null, tint = if (urgent) Color.White else colors.text3, modifier = Modifier.size(36.dp))
            Text("NULL 차단까지", style = PleosType.BodyS, color = if (urgent) Color.White.copy(alpha = 0.84f) else colors.text3)
            Text(mmss(remaining), style = PleosType.TitleLStrong.copy(fontFeatureSettings = "tnum"), color = if (urgent) Color.White else colors.text)
            if (ui.penaltySec > 0) Text("−${ui.penaltySec}초", style = PleosType.BodySStrong, color = if (urgent) Color.White else colors.negative)
        }
        PIconButton(
            Icons.Rounded.SettingsInputAntenna, null, { s.requestHint() },
            description = "보안팀 무전 (힌트)",
            enabled = ui.hintsLeft?.let { it > 0 } ?: true,
            badge = ui.hintsLeft?.toString(),
        )
        PButton("그만", { c.abort("그만 버튼을 눌러서 멈췄어요") }, kind = BtnKind.NEGATIVE, icon = Icons.Rounded.Stop)
    }
}

// ───────────────────────── 왼쪽: 차 지도 ─────────────────────────

@Composable
private fun CarMap(c: AppController, s: GameSession, ui: GameUi, m: Modifier) {
    val colors = P.c
    val mirror by c.effects.mirror.collectAsState()
    val zones = ui.zones.filter { !it.master }
    val allDone = zones.all { it.solved }
    // 실제 차 무드등이 켜져 있으면 그 색으로, 아니면 남은 위협 정도에 따라 빨강 → 초록
    val base = if (allDone) colors.active else colors.negative
    val glow by animateColorAsState(if (mirror.brightness > 8) Color(mirror.color) else base, tween(150), label = "glow")
    val strength by animateFloatAsState(if (mirror.brightness > 8) 0.4f + 0.6f * mirror.brightness / 100f else 0.35f + 0.65f * zones.count { !it.solved } / 4f, tween(150), label = "strength")
    CarScene(
        m,
        glow = glow,
        glowStrength = strength,
        spots = zones.map { zoneSpot(it.id, if (it.solved) colors.active else colors.negative, pulse = !it.solved && it.id == ui.selected) },
        tags = zones.map { z ->
            { ZoneTag(zoneIcon(z.id), ZONE_SHORT[z.id] ?: z.title, locked = !z.solved, digit = z.digit, selected = z.id == ui.selected, onClick = { s.select(z.id) }) }
        },
    )
}

/** 코드 조각 4칸 + 마스터 코드 버튼 */
@Composable
private fun CodeBar(s: GameSession, ui: GameUi) {
    val colors = P.c
    val master = ui.zones.firstOrNull { it.master }
    Column(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(40.dp)).border(2.dp, colors.tint5, RoundedCornerShape(40.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("코드 조각", style = PleosType.BodyLStrong, color = colors.text)
            Text("${ui.digits.count { it != null }} / 4", style = PleosType.BodyL, color = colors.text3, modifier = Modifier.weight(1f))
            PButton(
                "마스터 코드",
                { master?.let { s.select(it.id) } },
                kind = if (ui.selected == master?.id) BtnKind.ACTIVE else BtnKind.FILLED,
                icon = if (ui.masterOpen) Icons.Rounded.Key else Icons.Rounded.Lock,
                enabled = ui.masterOpen,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.digits.forEach { d ->
                Box(
                    Modifier.weight(1f).height(100.dp)
                        .background(if (d != null) colors.surface else colors.tint5, RoundedCornerShape(24.dp))
                        .border(if (d != null) 4.dp else 0.dp, if (d != null) colors.active else Color.Transparent, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (d != null) Text("$d", style = PleosType.Number, color = colors.text)
                    else Icon(Icons.Rounded.Lock, null, tint = colors.text4, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

// ───────────────────────── 오른쪽: 구역 패널 ─────────────────────────

@Composable
private fun ZonePanel(c: AppController, s: GameSession, ui: GameUi, puzzle: PuzzleUi?, m: Modifier) {
    val colors = P.c
    val zone = ui.zones.firstOrNull { it.id == ui.selected }
    val index = ui.zones.indexOfFirst { it.id == ui.selected }
    PCard(m) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(80.dp).background(if (zone?.solved == true) colors.active else if (zone?.master == true) colors.filled else colors.negative, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Icon(zoneIcon(zone?.id.orEmpty()), null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (zone?.master == true) zone.subtitle else "보안 구역 ${index + 1} · ${zone?.subtitle.orEmpty()}",
                        style = PleosType.BodyS,
                        color = colors.text3,
                        maxLines = 1,
                    )
                    Text(zone?.title.orEmpty(), style = PleosType.TitleLStrong, color = colors.text, maxLines = 1)
                }
                when {
                    zone?.solved == true -> PChip("해제", colors.active, icon = Icons.Rounded.LockOpen)
                    zone?.master == true -> PChip("마지막 문", colors.filled, colors.onFilled, icon = Icons.Rounded.Key)
                    else -> PChip("잠김", colors.negative, icon = Icons.Rounded.Lock)
                }
            }
            CommBar(c, ui.taunt.takeIf { zone?.solved != true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (zone?.solved == true) {
                    SolvedView(zone)
                } else {
                    when (puzzle) {
                        is PuzzleUi.Blink -> BlinkView(c, puzzle)
                        is PuzzleUi.Climate -> ClimateView(puzzle)
                        is PuzzleUi.Windows -> WindowsView(c, puzzle)
                        is PuzzleUi.Pattern -> PatternView(puzzle)
                        is PuzzleUi.Code -> CodeView(c, puzzle)
                        null -> Waiting()
                    }
                }
            }
        }
    }
}

/**
 * 무전: 지금 말하는 사람(NULL / 보안팀)의 대사. 아무도 말하지 않으면 이 구역에서 NULL이 남긴 말을 둔다.
 * 모양은 System notification(아바타 80 + 제목 + 설명)을 카드 안에 맞춘 것.
 */
@Composable
private fun CommBar(c: AppController, taunt: String?) {
    val colors = P.c
    val subtitle by c.voice.subtitle.collectAsState()
    val speaker by c.voice.speaker.collectAsState()
    val speaking = subtitle != null
    val isNull = !speaking || speaker == VoiceManager.VOICE_NULL
    val text = subtitle ?: taunt ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).background(colors.tint5, RoundedCornerShape(32.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Avatar(isNull, speaking)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (isNull) "NULL" else "보안팀", style = PleosType.LabelMStrong, color = if (isNull) colors.negative else colors.active)
            Text(text, style = PleosType.BodyL, color = colors.text, maxLines = 2)
        }
    }
}

@Composable
private fun Avatar(isNull: Boolean, speaking: Boolean, size: Dp = 80.dp) {
    val ring by rememberInfiniteTransition(label = "talk").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    val color = if (isNull) Color(0xFFF62E24) else Color(0xFF02C265)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (speaking) Box(Modifier.size(size).background(color.copy(alpha = 0.18f + 0.22f * ring), CircleShape))
        Box(
            Modifier.size(size - 12.dp).background(if (isNull) Color(0xFF131417) else color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isNull) Text("N", style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold), color = Color(0xFFFF4339))
            else Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
    }
}

/** 자물쇠가 준비되기 전 (무전을 듣는 동안) */
@Composable
private fun Waiting() {
    val colors = P.c
    val t by rememberInfiniteTransition(label = "wait").animateFloat(0f, 1f, infiniteRepeatable(tween(1200)), label = "dots")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(3) { i ->
                val a = ((t * 3 - i).let { if (it < 0) it + 3 else it } % 3).let { if (it < 1) 1f else 0.25f }
                Box(Modifier.size(20.dp).background(colors.text.copy(alpha = a * 0.64f), CircleShape))
            }
        }
        Text("자물쇠에 접속하는 중", style = PleosType.TitleM, color = colors.text2)
        Text("무전을 들으며 잠시만 기다려 주세요", style = PleosType.BodyM, color = colors.text3)
    }
}

@Composable
private fun SolvedView(z: Zone) {
    val colors = P.c
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Icon(Icons.Rounded.CheckCircle, null, tint = colors.active, modifier = Modifier.size(120.dp))
        Text("${z.title} 해제 완료", style = PleosType.TitleLStrong, color = colors.text)
        Text("코드 조각 ${z.digit} 을(를) 얻었어요", style = PleosType.BodyL, color = colors.text3)
    }
}

// ───────────────────────── 자물쇠 화면 ─────────────────────────

/** 카드 안의 회색 판 (r32, 5%) */
@Composable
private fun Tray(m: Modifier = Modifier, padding: Dp = 28.dp, content: @Composable () -> Unit) {
    Box(m.background(P.c.tint5, RoundedCornerShape(32.dp)).padding(padding)) { content() }
}

@Composable
private fun Feedback(text: String?, good: Boolean = false) {
    text?.let { Text(it, style = PleosType.BodyLStrong, color = if (good) P.c.text else P.c.negative) }
}

/** ① 색 신호: 무드등이 깜빡인 횟수 맞히기 */
@Composable
private fun BlinkView(c: AppController, p: PuzzleUi.Blink) {
    val colors = P.c
    val lamp by animateColorAsState(p.lamp?.let { Color(it) } ?: Color(0xFF2A2C31), tween(90), label = "lamp")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(0.85f).fillMaxHeight().background(Color(0xFF131417), RoundedCornerShape(32.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(if (p.playing) "신호 수신 중" else "신호 끝", style = PleosType.BodySStrong, color = Color.White.copy(alpha = 0.64f))
            Box(
                Modifier.size(200.dp).shadow(if (p.lamp != null) 48.dp else 0.dp, CircleShape, ambientColor = lamp, spotColor = lamp)
                    .background(Brush.radialGradient(listOf(lerp(lamp, Color.White, 0.35f), lamp)), CircleShape),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                p.legend.forEach { (name, argb) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(22.dp).background(Color(argb), CircleShape))
                        Text(name, style = PleosType.LabelM, color = Color.White.copy(alpha = 0.84f))
                    }
                }
            }
            DarkButton("다시 보기", Icons.Rounded.Replay, enabled = !p.playing) { c.puzzles.replay() }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(p.clue, style = PleosType.TitleM, color = colors.text)
            Text("숫자를 말하거나 눌러 주세요", style = PleosType.BodyS, color = colors.text3)
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                DigitPad(onDigit = { c.puzzles.submitTyped(it.toString()) })
            }
            Feedback(p.feedback)
        }
    }
}

@Composable
private fun DarkButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.press(enabled, onClick = onClick).height(64.dp).background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.32f), modifier = Modifier.size(30.dp))
        Text(text, style = PleosType.BodySStrong, color = Color.White.copy(alpha = if (enabled) 1f else 0.32f))
    }
}

/** ② 온도 금고: 실제 공조 값을 금고 다이얼에 맞추기 */
@Composable
private fun ClimateView(p: PuzzleUi.Climate) {
    val colors = P.c
    val tempOk = p.temp != null && kotlin.math.abs(p.temp - p.targetTemp) < 0.26f
    val fanOk = p.fan == p.targetFan
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Dial(Modifier.weight(1f).fillMaxHeight(), Icons.Rounded.Thermostat, "운전석 온도", "%.1f".format(p.targetTemp), "°", p.temp?.let { "%.1f°".format(it) } ?: "—", tempOk)
            Dial(Modifier.weight(1f).fillMaxHeight(), Icons.Rounded.Air, "바람 세기", "${p.targetFan}", "단", p.fan?.let { "${it}단" } ?: "—", fanOk)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("차의 공조 버튼으로 다이얼 값에 맞추세요", style = PleosType.BodyM, color = colors.text2, modifier = Modifier.weight(1f))
            if (p.rounds > 1) PChip("다이얼 ${p.round} / ${p.rounds}", colors.tint10, colors.text)
        }
        Feedback(p.feedback)
    }
}

@Composable
private fun Dial(m: Modifier, icon: ImageVector, label: String, target: String, unit: String, current: String, ok: Boolean) {
    val colors = P.c
    val border by animateColorAsState(if (ok) colors.active else Color.Transparent, tween(200), label = "dial")
    Column(
        m.background(colors.tint5, RoundedCornerShape(32.dp)).border(4.dp, border, RoundedCornerShape(32.dp)).padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = colors.text3, modifier = Modifier.size(32.dp))
            Text(label, style = PleosType.BodyM, color = colors.text3)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(target, style = PleosType.Speed, color = colors.text)
            Text(unit, style = PleosType.TitleL, color = colors.text3, modifier = Modifier.padding(bottom = 22.dp, start = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(72.dp).background(if (ok) colors.active else colors.surface, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("지금 차", style = PleosType.BodyS, color = if (ok) Color.White.copy(alpha = 0.84f) else colors.text3, modifier = Modifier.weight(1f))
            Text(current, style = PleosType.TitleS, color = if (ok) Color.White else colors.text)
            if (ok) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

private val WINDOW_LABELS = listOf("앞\n왼쪽", "앞\n오른쪽", "뒤\n왼쪽", "뒤\n오른쪽")

/** ③ 창문 비밀번호: 왼쪽은 NULL이 잠깐 보여 주는 막대, 오른쪽은 지금 창문 */
@Composable
private fun WindowsView(c: AppController, p: PuzzleUi.Windows) {
    val colors = P.c
    val ok = p.values.zip(p.targets).map { (v, t) -> kotlin.math.abs(v - t) <= p.tolerance }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF131417), RoundedCornerShape(32.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NULL 신호", style = PleosType.BodySStrong, color = Color.White.copy(alpha = 0.84f), modifier = Modifier.weight(1f), maxLines = 1)
                    DarkButton("다시 보기", Icons.Rounded.Replay) { c.puzzles.replay() }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Bars(p.targets.map { if (p.showBars) it else 0 }, null, Brush.verticalGradient(listOf(Color(0xFFFF4339), Color(0xFF6A57FF))), Color.White.copy(alpha = 0.08f))
                    Canvas(Modifier.fillMaxSize()) { scanlines(0.06f) }
                    if (!p.showBars) Text("신호가 사라졌어요", style = PleosType.BodyM, color = Color.White.copy(alpha = 0.64f), modifier = Modifier.align(Alignment.Center))
                }
                BarLabels(Color.White.copy(alpha = 0.64f))
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(colors.tint5, RoundedCornerShape(32.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("지금 창문", style = PleosType.BodySStrong, color = colors.text2, modifier = Modifier.weight(1f))
                    val matched = ok.count { it }
                    PChip("$matched / 4 맞음", if (matched == 4) colors.active else colors.tint10, if (matched == 4) Color.White else colors.text)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { Bars(p.values, ok, null, colors.surface) }
                BarLabels(colors.text3)
            }
        }
        Text("막대 높이만큼 창문 네 개를 열어 주세요" + if (p.tolerance > 0) " · 한 칸 차이까지 괜찮아요" else "", style = PleosType.BodyM, color = colors.text2)
        Feedback(p.feedback)
    }
}

/** 창문 4개 막대 (0~10칸). brush가 있으면 그 색, 없으면 맞은 창문만 초록 */
@Composable
private fun Bars(values: List<Int>, ok: List<Boolean>?, brush: Brush?, slot: Color) {
    val colors = P.c
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
        values.forEachIndexed { i, v ->
            val h by animateFloatAsState((v.coerceIn(0, 10) / 10f).coerceAtLeast(0.001f), tween(250), label = "bar")
            Box(Modifier.width(56.dp).fillMaxHeight().background(slot, RoundedCornerShape(16.dp)), contentAlignment = Alignment.BottomCenter) {
                val fill = Modifier.fillMaxWidth().fillMaxHeight(h)
                if (brush != null) Box(fill.background(brush, RoundedCornerShape(16.dp)))
                else Box(fill.background(if (ok?.getOrNull(i) == true) colors.active else colors.filled, RoundedCornerShape(16.dp)))
            }
        }
    }
}

@Composable
private fun BarLabels(color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        WINDOW_LABELS.forEach { Text(it, style = PleosType.LabelM, color = color, textAlign = TextAlign.Center, modifier = Modifier.width(80.dp), maxLines = 2) }
    }
}

/** ④ 패턴 자물쇠: 소리·빛 신호를 방향지시등·브레이크로 따라 하기 */
@Composable
private fun PatternView(p: PuzzleUi.Pattern) {
    val colors = P.c
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (p.listening) "잘 들어 보세요" else "똑같이 따라 하세요", style = PleosType.HeadlineS, color = colors.text)
            PChip("자물쇠 ${p.round} / ${p.rounds}", colors.tint10, colors.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            p.sequence.forEachIndexed { i, sig ->
                val shown: Signal? = when {
                    p.listening && p.playing == i -> sig
                    !p.listening && i < p.input.size -> p.input[i]
                    else -> null
                }
                val bg by animateColorAsState(shown?.let { Color(PuzzleEngine.colorOf(it)) } ?: colors.tint5, tween(80), label = "slot")
                Box(Modifier.weight(1f, fill = false).widthIn(max = 120.dp).aspectRatio(1f).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                    when (shown) {
                        Signal.LEFT -> Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White, modifier = Modifier.size(60.dp))
                        Signal.RIGHT -> Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(60.dp))
                        Signal.BRAKE -> Text("쿵", style = PleosType.TitleL, color = Color.White)
                        null -> Text("${i + 1}", style = PleosType.TitleM, color = colors.text4)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Legend(Color(PuzzleEngine.colorOf(Signal.LEFT)), "보로롱", "왼쪽 깜빡이")
            Legend(Color(PuzzleEngine.colorOf(Signal.RIGHT)), "딩동", "오른쪽 깜빡이")
            Legend(Color(PuzzleEngine.colorOf(Signal.BRAKE)), "쿵", "브레이크 밟기")
        }
        Feedback(p.feedback, good = p.feedback?.contains("틀") != true)
    }
}

@Composable
private fun Legend(color: Color, sound: String, action: String) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(P.c.tint5, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(28.dp).background(color, CircleShape))
        Text(sound, style = PleosType.BodyLStrong, color = P.c.text, modifier = Modifier.width(120.dp))
        Text(action, style = PleosType.BodyM, color = P.c.text3)
    }
}

/** 마스터 코드 */
@Composable
private fun CodeView(c: AppController, p: PuzzleUi.Code) {
    val colors = P.c
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(p.length) { i ->
                Box(
                    Modifier.size(width = 104.dp, height = 120.dp).background(colors.tint5, RoundedCornerShape(28.dp))
                        .border(4.dp, if (i == typed.length) colors.filled else Color.Transparent, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(typed.getOrNull(i)?.toString() ?: "", style = PleosType.Number, color = colors.text) }
            }
        }
        Text(if (p.reverse) "NULL: \"내 금고는 모든 걸 거꾸로 센다\"" else "모은 코드 조각을 순서대로 · 말해도 돼요", style = PleosType.BodyM, color = colors.text3, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).widthIn(max = 640.dp), contentAlignment = Alignment.Center) {
            DigitPad(
                onDigit = { if (typed.length < p.length) typed += it },
                onDelete = { typed = typed.dropLast(1) },
                onConfirm = if (typed.length == p.length) ({ c.puzzles.submitTyped(typed); typed = "" }) else null,
            )
        }
        Feedback(p.feedback)
    }
}

/** ivi_numeric_keypad: 흰 키 + 4px 테두리 10% r32, 숫자 56 ExtraBold */
@Composable
private fun DigitPad(onDigit: (Int) -> Unit, onDelete: (() -> Unit)? = null, onConfirm: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { n -> KeyBox(Modifier.weight(1f), onClick = { onDigit(n) }) { Text("$n", style = PleosType.Number, color = P.c.text) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onDelete != null) KeyBox(Modifier.weight(1f), secondary = true, onClick = onDelete) { Icon(Icons.Rounded.Backspace, "지우기", tint = P.c.text, modifier = Modifier.size(40.dp)) } else Box(Modifier.weight(1f))
            KeyBox(Modifier.weight(1f), onClick = { onDigit(0) }) { Text("0", style = PleosType.Number, color = P.c.text) }
            if (onDelete != null) KeyBox(Modifier.weight(1f), primary = true, enabled = onConfirm != null, onClick = { onConfirm?.invoke() }) {
                Text("확인", style = PleosType.TitleS, color = if (onConfirm != null) P.c.onFilled else P.c.text4)
            } else Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyBox(m: Modifier, secondary: Boolean = false, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = P.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(28.dp)
    val bg = when {
        primary && enabled -> if (pressed) colors.filledPressed else colors.filled
        primary -> colors.tint5
        secondary -> if (pressed) colors.tint20 else colors.surfaceAccent
        else -> if (pressed) colors.surfaceHigh else if (colors.dark) colors.surfaceHigh else colors.surface
    }
    Box(
        m.height(92.dp).press(enabled, src, onClick).background(bg, shape).border(4.dp, colors.tint10, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ───────────────────────── 몰입 장면 ─────────────────────────

@Composable
private fun ImmersiveScene(c: AppController, ui: GameUi) {
    val mirror by c.effects.mirror.collectAsState()
    val caption by c.effects.caption.collectAsState()
    val subtitle by c.voice.subtitle.collectAsState()
    val speaker by c.voice.speaker.collectAsState()
    val ending = ui.stage == Stage.ENDING
    val base = if (ending) Color(0xFF00E074) else Color(0xFFFF4339)
    val glow by animateColorAsState(if (mirror.brightness > 8) Color(mirror.color) else base, tween(120), label = "scene")
    val strength = if (mirror.brightness > 8) 0.5f + 0.5f * mirror.brightness / 100f else 0.7f
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        CarScene(
            Modifier.fillMaxSize().padding(top = 300.dp, start = 120.dp, end = 120.dp, bottom = 40.dp),
            view = if (ending) CarView.FRONT else CarView.REAR,
            glow = glow,
            glowStrength = strength,
        )
        Canvas(Modifier.fillMaxSize()) { scanlines(if (ending) 0.02f else 0.05f) }
        Row(Modifier.align(Alignment.TopStart).padding(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(16.dp).background(base, CircleShape))
            Text(
                if (ending) "SECURITY · 제어권 복구" else if (ui.stage == Stage.FAILED) "ACCESS LOST · 제어권 상실" else "WARNING · 외부 침입 감지",
                style = PleosType.BodySStrong.copy(letterSpacing = 2.sp),
                color = base,
            )
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val headline = when {
                ui.attackText != null -> ui.attackText
                ending -> "SYSTEM RESTORED"
                ui.stage == Stage.FAILED -> "ACCESS LOST"
                else -> "NULL"
            }
            val glitch = TextStyle(fontSize = 104.sp, lineHeight = 112.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp, textAlign = TextAlign.Center)
            Box {
                if (!ending) {
                    Text(headline, style = glitch, color = Color(0x9900E5FF), modifier = Modifier.offset(x = (-5).dp))
                    Text(headline, style = glitch, color = Color(0x99FF1744), modifier = Modifier.offset(x = 5.dp))
                }
                Text(headline, style = glitch, color = Color.White)
            }
            caption?.let { Text(it, style = PleosType.TitleM.copy(fontWeight = FontWeight.Normal), color = Color.White.copy(alpha = 0.84f), textAlign = TextAlign.Center) }
        }
        subtitle?.let {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 48.dp, end = 48.dp).widthIn(max = 1400.dp)
                    .background(Color(0xFF1D1E21).copy(alpha = 0.92f), RoundedCornerShape(48.dp)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(48.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val isNull = speaker == VoiceManager.VOICE_NULL
                Avatar(isNull, true)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(if (isNull) "NULL" else "보안팀", style = PleosType.LabelMStrong, color = if (isNull) Color(0xFFFF4339) else Color(0xFF00E074))
                    Text(it, style = PleosType.BodyL, color = Color.White, maxLines = 2)
                }
            }
        }
    }
}
