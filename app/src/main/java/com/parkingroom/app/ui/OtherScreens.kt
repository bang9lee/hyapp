package com.parkingroom.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.parkingroom.app.BuildConfig
import com.parkingroom.app.check.CheckScreen
import com.parkingroom.app.data.GameRecord
import com.parkingroom.app.game.AppController
import com.parkingroom.app.game.GameEnd
import com.parkingroom.app.game.GameSetup
import com.parkingroom.app.game.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(c: AppController) {
    val screen by c.screen.collectAsState()
    val session by c.session.collectAsState()
    when (val s = screen) {
        Screen.Home -> HomeScreen(c)
        is Screen.Safety -> SafetyScreen(c, s.setup)
        Screen.Game -> session?.let { GameScreen(c, it) } ?: Box(Modifier.fillMaxSize().background(P.c.background))
        is Screen.Result -> ResultScreen(c, s.setup, s.end)
        is Screen.Stopped -> StoppedScreen(c, s)
        Screen.Ranking -> RankingScreen(c)
        Screen.Settings -> SettingsScreen(c)
        Screen.DevCheck -> {
            BackHandler { c.leaveDev() }
            Column(Modifier.fillMaxSize().background(Color(0xFF131417))) {
                Row(Modifier.padding(24.dp)) { PButton("설정으로", { c.leaveDev() }, kind = BtnKind.FILLED, size = BtnSize.S, icon = Icons.AutoMirrored.Rounded.ArrowBack) }
                CheckScreen(onCommand = c::devCommand)
            }
        }
    }
}

/** 깊이 화면 틀: ivi_title(Depth) + 내용 */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = P.c
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        PTitleDepth(title, onBack, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 40.dp)) { content() }
    }
}

// ───────────────────────── 결과 ─────────────────────────

@Composable
private fun ResultScreen(c: AppController, setup: GameSetup, end: GameEnd) {
    val colors = P.c
    val cleared = end as? GameEnd.Cleared
    BackHandler { c.go(Screen.Home) }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth > 1200.dp
        val art: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                CarScene(Modifier.fillMaxSize(), view = if (cleared != null) CarView.FRONT else CarView.REAR, glow = if (cleared != null) colors.active else colors.negative, glowStrength = 0.9f)
                if (cleared != null) PChip("SYSTEM RESTORED · 보안 구역 4곳 해제", colors.active, icon = Icons.Rounded.LockOpen, modifier = Modifier.align(Alignment.TopStart))
                else PChip("ACCESS LOST · 제어권 상실", colors.negative, icon = Icons.Rounded.WarningAmber, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        val info: @Composable (Modifier) -> Unit = { m ->
            PCard(m, padding = 48.dp) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (cleared != null) "탈출 성공 · ${cleared.record.levelLabel}" else "탈출 실패", style = PleosType.BodySStrong, color = if (cleared != null) colors.active else colors.negative)
                        Text(if (cleared != null) "차를 되찾았어요!" else "차를 빼앗겼어요", style = PleosType.HeadlineM, color = colors.text)
                        Text(
                            if (cleared != null) "NULL의 접속이 끊기고 차가 원래대로 돌아왔어요." else "NULL이 차의 제어권을 가져갔어요. 다음엔 보안팀 무전을 써 보세요.",
                            style = PleosType.BodyL,
                            color = colors.text2,
                        )
                    }
                    if (cleared != null) {
                        val r = cleared.record
                        val rank = remember(r) { c.store.rankOf(r) }
                        Column(Modifier.fillMaxWidth().background(colors.tint5, RoundedCornerShape(32.dp)).padding(vertical = 8.dp)) {
                            Stat(Icons.Rounded.Timer, "탈출 시간", minSec(r.elapsedMs), strong = true)
                            Stat(Icons.Rounded.HourglassBottom, "남은 시간", minSec(cleared.remainingMs))
                            Stat(Icons.Rounded.SettingsInputAntenna, "보안팀 무전", "${r.hintsUsed}번")
                            Stat(Icons.Rounded.ErrorOutline, "오답", "${r.penalties}회")
                            if (rank > 0) Stat(Icons.Rounded.EmojiEvents, "${r.season} 시즌 순위", "${rank}위 · ${if (r.coop) "협동" else "혼자"}")
                        }
                    }
                    if (wide) Box(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (cleared != null) {
                                PButton("랭킹", { c.go(Screen.Ranking) }, Modifier.weight(1f), kind = BtnKind.BASIC, size = BtnSize.L, icon = Icons.Rounded.EmojiEvents)
                                ShareButton(Modifier.weight(1f), recordLine(cleared.record) + " #HMG차량용앱개발공모전")
                            } else {
                                PButton("다시 도전", { c.openSafety(setup) }, Modifier.weight(1f), kind = BtnKind.BASIC, size = BtnSize.L, icon = Icons.Rounded.Replay)
                            }
                        }
                        PButton("홈으로", { c.go(Screen.Home) }, Modifier.fillMaxWidth(), size = BtnSize.L, icon = Icons.Rounded.Home)
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(bottom = 40.dp)) {
            AppTitle("파킹룸", "결과")
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    art(Modifier.weight(1.1f).fillMaxHeight())
                    info(Modifier.weight(0.9f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    art(Modifier.fillMaxWidth().aspectRatio(1.6f))
                    info(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String, strong: Boolean = false) {
    val colors = P.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(icon, null, tint = colors.text3, modifier = Modifier.size(36.dp))
        Text(label, style = PleosType.BodyL, color = colors.text2, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) PleosType.TitleLStrong else PleosType.BodyLStrong, color = colors.text)
    }
}

private fun recordLine(r: GameRecord) = "파킹룸 · 해킹된 차 · ${r.levelLabel} · ${minSec(r.elapsedMs)} 만에 탈출"

@Composable
private fun ShareButton(m: Modifier, text: String) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PButton("공유", {
            msg = try {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공유"))
                null
            } catch (e: ActivityNotFoundException) {
                "이 차에는 공유할 앱이 없어요"
            }
        }, Modifier.fillMaxWidth(), kind = BtnKind.BASIC, size = BtnSize.L, icon = Icons.Rounded.Share)
        msg?.let { PToast(it) }
    }
}

// ───────────────────────── 멈춤 (System popup M) ─────────────────────────

@Composable
private fun StoppedScreen(c: AppController, s: Screen.Stopped) {
    val colors = P.c
    BackHandler { c.go(Screen.Home) }
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        CarScene(Modifier.fillMaxSize().padding(80.dp), glow = colors.text4, glowStrength = 0.4f)
        Box(Modifier.fillMaxSize().background(colors.overlay))
        PPopup(
            Modifier.width(880.dp).padding(horizontal = 36.dp),
            icon = Icons.Rounded.PanTool,
            iconTint = colors.negative,
            title = "게임을 멈췄어요",
            description = s.reason,
            content = if (s.restored || s.windowsOpen) {
                {
                    Column(Modifier.fillMaxWidth().background(colors.tint5, RoundedCornerShape(32.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (s.restored) Notice(Icons.Rounded.CheckCircle, colors.active, "앱이 바꾼 무드등·에어컨·바람·와이퍼·핸들 열선을 원래대로 돌려놨어요.")
                        if (s.windowsOpen) Notice(Icons.Rounded.Window, colors.negative, "창문이 열려 있어요. 앱은 창문을 닫지 않아요 — 직접 닫아 주세요.")
                    }
                }
            } else {
                null
            },
        ) {
            PButton("확인", { c.go(Screen.Home) }, Modifier.weight(1f), size = BtnSize.L)
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, tint: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(36.dp))
        Text(text, style = PleosType.BodyM, color = P.c.text2, modifier = Modifier.weight(1f))
    }
}

// ───────────────────────── 랭킹 ─────────────────────────

@Composable
private fun RankingScreen(c: AppController) {
    val colors = P.c
    val records by c.store.records.collectAsState()
    var level by remember { mutableIntStateOf(0) }
    var coop by remember { mutableIntStateOf(0) }
    val season = remember { SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date()) }
    val levels = c.scenario.levels
    val list = remember(records, level, coop) { c.store.ranking(levels[level].key, coop == 1, season) }
    Page("랭킹 · $season 시즌", { c.go(Screen.Home) }) {
        Row(Modifier.widthIn(max = 1600.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("난이도", style = PleosType.LabelM, color = colors.text3)
                PSegmented(levels.map { it.label }, level, { level = it }, Modifier.fillMaxWidth())
                Gap(4.dp)
                Text("인원", style = PleosType.LabelM, color = colors.text3)
                PSegmented(listOf("혼자", "협동"), coop, { coop = it }, Modifier.fillMaxWidth())
                Gap(4.dp)
                Text("전국 랭킹·친구 대결은 서버가 필요해서, 시연 버전은 이 기기 안의 기록만 보여 줘요.", style = PleosType.BodyS, color = colors.text3)
            }
            PCard(Modifier.weight(1f), padding = 24.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.EmojiEvents, null, tint = colors.text4, modifier = Modifier.size(96.dp))
                            Text("아직 기록이 없어요", style = PleosType.TitleM, color = colors.text)
                            Text("첫 번째로 차를 되찾아 보세요", style = PleosType.BodyM, color = colors.text3)
                        }
                    }
                    list.take(10).forEachIndexed { i, r ->
                        Row(
                            Modifier.fillMaxWidth().background(if (i == 0) colors.tint5 else Color.Transparent, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Box(
                                Modifier.size(56.dp).background(if (i < 3) colors.filled else colors.tint5, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${i + 1}", style = PleosType.TitleS, color = if (i < 3) colors.onFilled else colors.text3) }
                            Text(minSec(r.elapsedMs), style = PleosType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
                            Text("무전 ${r.hintsUsed} · 오답 ${r.penalties}", style = PleosType.BodyS, color = colors.text3)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(c: AppController) {
    val colors = P.c
    val settings by c.store.settings.collectAsState()
    Page("설정", { c.go(Screen.Home) }) {
        Column(Modifier.widthIn(max = 1300.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PCard(Modifier.fillMaxWidth(), padding = 24.dp) {
                Column {
                    PListItem("효과음 크기", leading = Icons.Rounded.VolumeUp, subtitle = "다른 앱이 소리를 내면(내비 안내 등) 효과음이 자동으로 작아지거나 멈춰요", trailing = {
                        Text("${(settings.sfxVolume * 100).toInt()}%", style = PleosType.TitleS, color = colors.text)
                    })
                    Slider(
                        value = settings.sfxVolume,
                        onValueChange = { c.store.update(settings.copy(sfxVolume = it)) },
                        valueRange = 0.2f..1f,
                        modifier = Modifier.padding(horizontal = 36.dp),
                        // 공식 slider_knob: 진회색 원형 손잡이 + 20% 트랙
                        thumb = { Box(Modifier.size(40.dp).shadow(4.dp, CircleShape).background(colors.filled, CircleShape)) },
                        colors = SliderDefaults.colors(activeTrackColor = colors.filled, inactiveTrackColor = colors.tint20),
                    )
                }
            }
            PCard(Modifier.fillMaxWidth(), padding = 24.dp) {
                PListItem(
                    "앱이 차를 움직이는 범위",
                    leading = Icons.Rounded.Tune,
                    subtitle = "무드등 · 에어컨 · 바람 · 와이퍼 · 핸들 열선을 잠깐 움직이고, 끝나면 원래대로 돌려놔요. 창문과 문은 절대 직접 움직이지 않아요.",
                )
            }
            PCard(Modifier.fillMaxWidth(), padding = 24.dp) {
                Column {
                    PListItem("앱 정보", subtitle = "파킹룸 v${BuildConfig.VERSION_NAME} · Vehicle · TextToSpeech · SpeechToText SDK")
                    // 1단계 점검 도구는 시트·미러 권한이 더 필요해서 디버그 빌드에만 둔다
                    if (BuildConfig.DEBUG) {
                        PDivider(Modifier.padding(horizontal = 20.dp))
                        PListItem("개발자 메뉴 · 차량 기능 확인", subtitle = "디버그 빌드 전용", onClick = { c.go(Screen.DevCheck) })
                    }
                }
            }
        }
    }
}
