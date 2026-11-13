package com.parkingroom.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parkingroom.app.R
import kotlin.math.min
import kotlin.math.roundToInt

/** 차 이미지: Pleos 디스플레이 가이드에 들어 있는 공식 아이오닉 6 렌더 (투명 배경) */
enum class CarView(val res: Int, val w: Int, val h: Int) {
    FRONT(R.drawable.car_front, 1600, 753),
    REAR(R.drawable.car_rear, 1344, 613),
}

/**
 * 차 위의 한 지점(x, y: 이미지 안 비율 0..1)과, 그 지점에서 (dx, dy)만큼 떨어져 붙는 꼬리표.
 * dx<0이면 꼬리표 오른쪽 끝이, dx>0이면 왼쪽 끝이 선 끝에 닿는다.
 */
data class CarSpot(val x: Float, val y: Float, val dx: Dp, val dy: Dp, val color: Color, val pulse: Boolean = false)

/** 앞모습 이미지에서 보안 구역 위치: 헤드램프 · 앞유리 안쪽(공조) · 옆 창문 · 앞바퀴(구동) */
object FrontSpots {
    const val LIGHT_X = 0.262f
    const val LIGHT_Y = 0.600f
    const val CLIMATE_X = 0.395f
    const val CLIMATE_Y = 0.255f
    const val WINDOW_X = 0.715f
    const val WINDOW_Y = 0.225f
    const val DRIVE_X = 0.405f
    const val DRIVE_Y = 0.735f
}

private data class CarGeometry(val ix: Float, val iy: Float, val iw: Float, val ih: Float) {
    fun at(s: CarSpot) = Offset(ix + s.x * iw, iy + s.y * ih)
}

/** 차는 가로 maxW, 세로 화면의 heightRatio 안에 맞추고, 세로로는 살짝 아래쪽에 둔다 */
private fun geometry(view: CarView, w: Float, h: Float, maxW: Float, heightRatio: Float): CarGeometry {
    val scale = min(min(w, maxW) / view.w, h * heightRatio / view.h)
    val iw = view.w * scale
    val ih = view.h * scale
    return CarGeometry((w - iw) / 2f, (h - ih) * 0.56f, iw, ih)
}

/**
 * 차 렌더 + 바닥 그림자 + 차 뒤 빛 번짐. glow는 실제 차 무드등 색을 따라가도록 쓴다.
 * spots와 tags(같은 순서)를 주면 차 위 지점에 점을 찍고 선으로 꼬리표를 붙인다.
 */
@Composable
fun CarScene(
    modifier: Modifier,
    view: CarView = CarView.FRONT,
    glow: Color? = null,
    glowStrength: Float = 1f,
    spots: List<CarSpot> = emptyList(),
    tags: List<@Composable () -> Unit> = emptyList(),
    maxCarWidth: Dp = Dp.Infinity,
    heightRatio: Float = 0.8f,
) {
    val pulse by rememberInfiniteTransition(label = "spot").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "pulse")
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize()) {
                val g = geometry(view, size.width, size.height, if (maxCarWidth == Dp.Infinity) Float.MAX_VALUE else maxCarWidth.toPx(), heightRatio)
                glow?.let {
                    val c = Offset(g.ix + g.iw * 0.5f, g.iy + g.ih * 0.55f)
                    val r = g.iw * 0.62f
                    drawCircle(Brush.radialGradient(listOf(it.copy(alpha = 0.42f * glowStrength), it.copy(alpha = 0.12f * glowStrength), Color.Transparent), c, r), r, c)
                }
                // 바닥 그림자
                val sc = Offset(g.ix + g.iw * 0.5f, g.iy + g.ih * 0.93f)
                drawOval(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent), sc, g.iw * 0.42f),
                    Offset(g.ix + g.iw * 0.08f, sc.y - g.ih * 0.07f),
                    Size(g.iw * 0.84f, g.ih * 0.14f),
                )
            }
            Image(painterResource(view.res), null, contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize()) {
                val g = geometry(view, size.width, size.height, if (maxCarWidth == Dp.Infinity) Float.MAX_VALUE else maxCarWidth.toPx(), heightRatio)
                for (s in spots) {
                    val p = g.at(s)
                    val end = Offset(p.x + s.dx.toPx(), p.y + s.dy.toPx())
                    drawLine(Color.White.copy(alpha = 0.9f), p, end, strokeWidth = 6.dp.toPx())
                    drawLine(s.color, p, end, strokeWidth = 3.dp.toPx())
                    if (s.pulse) drawCircle(s.color.copy(alpha = 0.45f * (1f - pulse)), 14.dp.toPx() + 26.dp.toPx() * pulse, p)
                    drawCircle(Color.White, 14.dp.toPx(), p)
                    drawCircle(s.color, 10.dp.toPx(), p)
                }
            }
            tags.forEach { Box { it() } }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val g = geometry(view, w.toFloat(), h.toFloat(), if (maxCarWidth == Dp.Infinity) Float.MAX_VALUE else maxCarWidth.toPx(), heightRatio)
        val back = measurables[0].measure(Constraints.fixed(w, h))
        val car = measurables[1].measure(Constraints.fixed(g.iw.roundToInt(), g.ih.roundToInt()))
        val front = measurables[2].measure(Constraints.fixed(w, h))
        val tagPlaceables = measurables.drop(3).map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
        layout(w, h) {
            back.place(0, 0)
            car.place(g.ix.roundToInt(), g.iy.roundToInt())
            front.place(0, 0)
            tagPlaceables.forEachIndexed { i, p ->
                val s = spots.getOrNull(i) ?: return@forEachIndexed
                val pt = g.at(s)
                val ex = pt.x + s.dx.toPx()
                val ey = pt.y + s.dy.toPx()
                val x = when {
                    s.dx.value < 0 -> ex - p.width
                    s.dx.value > 0 -> ex
                    else -> ex - p.width / 2f
                }
                val y = when {
                    s.dx.value == 0f && s.dy.value < 0 -> ey - p.height
                    s.dx.value == 0f && s.dy.value > 0 -> ey
                    else -> ey - p.height / 2f
                }
                p.place(x.roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0)), y.roundToInt().coerceIn(0, (h - p.height).coerceAtLeast(0)))
            }
        }
    }
}

fun DrawScope.scanlines(alpha: Float = 0.06f, color: Color = Color.White) {
    var y = 0f
    while (y < size.height) {
        drawRect(color.copy(alpha = alpha), Offset(0f, y), Size(size.width, 2f))
        y += 7f
    }
}
