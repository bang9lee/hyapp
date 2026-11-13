package com.parkingroom.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 공식 디자인 에셋(Pleos_Connect_design_assets_215.fig)에서 뽑은 토큰과 컴포넌트.
 * 수치 정리: docs/플레오스_디자인_스펙.md (디자인 px = dp, 160dpi)
 */
@Immutable
data class PleosColors(
    /** background/primary — 앱 바탕 */
    val background: Color,
    /** surface/basic — 카드·패널 */
    val surface: Color,
    /** surface/high */
    val surfaceHigh: Color,
    /** surface/accent */
    val surfaceAccent: Color,
    /** background/popup */
    val popup: Color,
    /** text/dark/primary 기준색 (보조 단계는 84/64/32%) */
    val text: Color,
    /** button/filled/enabled·pressed */
    val filled: Color,
    val filledPressed: Color,
    val onFilled: Color,
    val active: Color,
    val positive: Color,
    val negative: Color,
    val purple: Color,
    val overlay: Color,
    val dark: Boolean,
) {
    val text2 get() = text.copy(alpha = 0.84f)
    val text3 get() = text.copy(alpha = 0.64f)
    val text4 get() = text.copy(alpha = 0.32f)

    /** button/basic, controller/tab_background — 5% */
    val tint5 get() = text.copy(alpha = 0.05f)

    /** button/basic pressed, divider/secondary — 10% */
    val tint10 get() = text.copy(alpha = 0.10f)

    /** divider/tertiary — 20% */
    val tint20 get() = text.copy(alpha = 0.20f)
}

private val Light = PleosColors(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    popup = Color(0xFFFFFFFF),
    text = Color(0xFF131417),
    filled = Color(0xFF44464E),
    filledPressed = Color(0xFF313236),
    onFilled = Color(0xFFFFFFFF),
    active = Color(0xFF02C265),
    positive = Color(0xFF0064FF),
    negative = Color(0xFFF62E24),
    purple = Color(0xFF5A46FA),
    overlay = Color(0x33131417),
    dark = false,
)

private val Dark = PleosColors(
    background = Color(0xFF1D1E21),
    surface = Color(0xFF1D1E21),
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    popup = Color(0xFF313236),
    text = Color(0xFFFFFFFF),
    filled = Color(0xFFE0E1E6),
    filledPressed = Color(0xFFC7C9CE),
    onFilled = Color(0xFF131417),
    active = Color(0xFF00E074),
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4339),
    purple = Color(0xFF6A57FF),
    overlay = Color(0x80131417),
    dark = true,
)

/** 한국어는 어절 단위로 줄을 바꾼다 */
private val Korean = TextStyle(lineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase))

/** System Font 스케일 (Asta Sans). Size / Line height */
object PleosType {
    val HeadlineL = Korean.copy(fontSize = 60.sp, lineHeight = 80.sp, fontWeight = FontWeight.ExtraBold)
    val HeadlineM = Korean.copy(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold)
    val HeadlineS = Korean.copy(fontSize = 48.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold)
    val TitleL = Korean.copy(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val TitleLStrong = TitleL.copy(fontWeight = FontWeight.ExtraBold)
    val TitleM = Korean.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val TitleMStrong = TitleM.copy(fontWeight = FontWeight.ExtraBold)
    val TitleS = Korean.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val BodyL = Korean.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal)
    val BodyLStrong = BodyL.copy(fontWeight = FontWeight.Bold)
    val BodyM = Korean.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal)
    val BodyMStrong = BodyM.copy(fontWeight = FontWeight.Bold)
    val BodyS = Korean.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
    val BodySStrong = BodyS.copy(fontWeight = FontWeight.Bold)
    val LabelM = Korean.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
    val LabelMStrong = LabelM.copy(fontWeight = FontWeight.Bold)
    val LabelS = Korean.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)

    /** Feature Font: Speed (112/134 Bold) — 금고 다이얼처럼 크게 읽어야 하는 숫자 */
    val Speed = TextStyle(fontSize = 112.sp, lineHeight = 134.sp, fontWeight = FontWeight.Bold)

    /** 키패드 숫자 (56/72 ExtraBold) */
    val Number = TextStyle(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold)
}

val LocalPleos = staticCompositionLocalOf { Light }

object P {
    val c: PleosColors @Composable get() = LocalPleos.current
}

@Composable
fun ParkingRoomTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light
    CompositionLocalProvider(LocalPleos provides colors) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(primary = colors.filled, background = colors.background, surface = colors.surface, onSurface = colors.text)
            } else {
                lightColorScheme(primary = colors.filled, background = colors.background, surface = colors.surface, onSurface = colors.text)
            },
            content = content,
        )
    }
}

// ───────────────────────── 눌림 ─────────────────────────

/** 가이드: 눌리면 95%로 줄고 배경이 한 단계 진해진다 (리플 없음) */
@Composable
fun Modifier.press(enabled: Boolean = true, source: MutableInteractionSource? = null, onClick: () -> Unit): Modifier {
    val src = source ?: remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(120), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** 토글·세그먼트에서 켜진 칸: 흰 바탕 + 그림자 (y8 blur32 16% / y2 blur2 8%) */
fun Modifier.raised(shape: Shape, color: Color, dark: Boolean): Modifier =
    if (dark) background(color, shape)
    else this
        .shadow(12.dp, shape, ambientColor = Color(0x29000000), spotColor = Color(0x29000000))
        .background(color, shape)

// ───────────────────────── 버튼 (ivi_basic_button) ─────────────────────────

enum class BtnSize(val height: Dp, val radius: Dp, val hPad: Dp, val style: TextStyle, val icon: Dp, val gap: Dp) {
    L(100.dp, 32.dp, 40.dp, PleosType.TitleS, 40.dp, 16.dp),
    M(80.dp, 24.dp, 32.dp, PleosType.BodyLStrong, 36.dp, 14.dp),
    S(64.dp, 16.dp, 24.dp, PleosType.BodySStrong, 30.dp, 12.dp),
}

/** FILLED = 진회색(주요 동작), BASIC = 5% 회색(보조), NEGATIVE = 빨강(중단), ACTIVE = 초록 */
enum class BtnKind { FILLED, BASIC, NEGATIVE, ACTIVE }

@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.FILLED,
    size: BtnSize = BtnSize.M,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = P.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(size.radius)
    val (bg, fg) = when {
        !enabled -> c.tint5 to c.text4
        kind == BtnKind.FILLED -> (if (pressed) c.filledPressed else c.filled) to c.onFilled
        kind == BtnKind.NEGATIVE -> (if (pressed) Color.Black.copy(alpha = 0.12f).compositeOver(c.negative) else c.negative) to Color.White
        kind == BtnKind.ACTIVE -> (if (pressed) Color.Black.copy(alpha = 0.12f).compositeOver(c.active) else c.active) to Color.White
        else -> (if (pressed) c.tint10 else c.tint5) to c.text
    }
    Row(
        modifier
            .press(enabled, src, onClick)
            .heightIn(min = size.height)
            .background(bg, shape)
            .then(if (!enabled) Modifier.border(2.dp, c.tint10, shape) else Modifier)
            .padding(horizontal = size.hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size.gap, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(size.icon)) }
        Text(text, style = size.style, color = fg, maxLines = 1)
    }
}

/** ivi_icon_button 80×80 (Basic r20 / Circle). 라벨을 주면 아래에 함께 표시 */
@Composable
fun PIconButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    enabled: Boolean = true,
    badge: String? = null,
    description: String? = label,
) {
    val c = P.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = if (circle) CircleShape else RoundedCornerShape(20.dp)
    Column(modifier.press(enabled, src, onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Box(Modifier.size(80.dp).background(if (pressed) c.tint10 else c.tint5, shape), contentAlignment = Alignment.Center) {
                Icon(icon, description, tint = if (enabled) c.text else c.text4, modifier = Modifier.size(44.dp))
            }
            badge?.let {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp).size(36.dp).background(c.filled, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(it, style = PleosType.LabelMStrong, color = c.onFilled) }
            }
        }
        label?.let { Text(it, style = PleosType.LabelM, color = c.text3) }
    }
}

// ───────────────────────── 세그먼트 (ivi_segmented_control/solid) ─────────────────────────

/** 트랙 5% 회색 r32 + 안쪽 8, 선택 칸은 흰 바탕·그림자 r24, 칸 사이 구분선 2×36 */
@Composable
fun PSegmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, large: Boolean = true) {
    val c = P.c
    val h = if (large) 100.dp else 80.dp
    val inner = if (large) 84.dp else 64.dp
    Row(
        modifier.height(h).background(c.tint5, RoundedCornerShape(if (large) 32.dp else 24.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(if (large) 24.dp else 16.dp)
            Box(
                Modifier.weight(1f).height(inner)
                    .then(if (on) Modifier.raised(shape, if (c.dark) c.surfaceAccent else c.surface, c.dark) else Modifier)
                    .press { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = if (large) PleosType.TitleS else PleosType.BodyLStrong, color = if (on) c.text else c.text2, maxLines = 1)
            }
            if (i < items.lastIndex) {
                val hide = on || i + 1 == selected
                Box(Modifier.width(2.dp).height(36.dp).background(if (hide) Color.Transparent else c.tint10))
            }
        }
    }
}

// ───────────────────────── 스위치 (ivi_switch M) ─────────────────────────

@Composable
fun PSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = P.c
    val track by animateColorAsState(if (checked) c.active else c.text.copy(alpha = if (c.dark) 0.1f else 0.3f), tween(150), label = "switch")
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "knob")
    Box(Modifier.size(80.dp, 48.dp).background(track, RoundedCornerShape(16.dp)).press { onChange(!checked) }.padding(8.dp)) {
        Box(
            Modifier.offset(x = (32 * x).dp).size(32.dp)
                .shadow(4.dp, RoundedCornerShape(10.dp))
                .background(if (!checked && c.dark) Color(0xFFA4A8AE) else Color.White, RoundedCornerShape(10.dp)),
        )
    }
}

// ───────────────────────── 카드 · 팝업 · 목록 ─────────────────────────

/** 표면 카드: surface/basic r48 + 2px divider/primary 테두리 */
@Composable
fun PCard(modifier: Modifier = Modifier, padding: Dp = 40.dp, content: @Composable () -> Unit) {
    val c = P.c
    val shape = RoundedCornerShape(48.dp)
    Box(modifier.background(c.surface, shape).border(2.dp, c.tint5, shape).padding(padding)) { content() }
}

/**
 * ivi_system_popup: 바탕 r48 + 2px 테두리, 안쪽 48, 간격 40.
 * 머리(아이콘 80 · 제목 40 ExtraBold · 설명 30) + 내용 + 버튼(높이 100).
 */
@Composable
fun PPopup(
    modifier: Modifier = Modifier,
    icon: ImageVector?,
    iconTint: Color? = null,
    title: String,
    description: String? = null,
    content: (@Composable () -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = P.c
    val shape = RoundedCornerShape(48.dp)
    Column(
        modifier.background(c.popup, shape).border(2.dp, c.tint5, shape).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            icon?.let { Icon(it, null, tint = iconTint ?: c.text, modifier = Modifier.size(80.dp)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = PleosType.TitleLStrong, color = c.text, textAlign = TextAlign.Center)
                description?.let { Text(it, style = PleosType.BodyL, color = c.text2, textAlign = TextAlign.Center) }
            }
        }
        content?.invoke()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), content = buttons)
    }
}

/** ivi_toast_popup: #44464E r16, 좌우 40 · 위아래 24, 글자 26 Bold 흰색 */
@Composable
fun PToast(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF44464E), RoundedCornerShape(16.dp)).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(text, style = PleosType.BodySStrong, color = Color.White)
    }
}

/** 상태 칩 (Label 24 Bold, r16) */
@Composable
fun PChip(text: String, color: Color, textColor: Color = Color.White, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 48.dp).background(color, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, tint = textColor, modifier = Modifier.size(28.dp)) }
        Text(text, style = PleosType.LabelMStrong, color = textColor, maxLines = 1)
    }
}

/** 목록 행 (List/item: 높이 112, r24, 선택 시 5%, 글자 30 Bold) */
@Composable
fun PListItem(
    title: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    leadingTint: Color? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = P.c
    Row(
        modifier.fillMaxWidth().heightIn(min = 112.dp)
            .background(if (selected) c.tint5 else Color.Transparent, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { Icon(it, null, tint = leadingTint ?: c.text, modifier = Modifier.size(44.dp)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PleosType.BodyLStrong, color = c.text)
            subtitle?.let { Text(it, style = PleosType.BodyS, color = c.text3) }
        }
        trailing?.invoke()
    }
}

/** 구분선 (divider/secondary 10%) */
@Composable
fun PDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(P.c.tint10))
}

/** ivi_title Type=Depth: 뒤로 화살표 + 제목 40 Bold, 높이 80, 왼쪽 40 */
@Composable
fun PTitleDepth(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = P.c
    Row(modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(80.dp).press(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "뒤로", tint = c.text, modifier = Modifier.size(44.dp).padding(start = 8.dp))
        }
        Text(title, style = PleosType.TitleL, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun minSec(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d분 %02d초".format(s / 60, s % 60)
}
