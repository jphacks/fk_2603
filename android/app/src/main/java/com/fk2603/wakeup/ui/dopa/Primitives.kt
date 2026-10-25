package com.fk2603.wakeup.ui.dopa

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.R
import com.fk2603.wakeup.ui.theme.Dopa

// UI プロトタイプ（primitives.tsx / globals.css）の部品

enum class WatcherMood(val res: Int) {
    SLEEPY(R.drawable.dopa_watcher),
    ANGRY(R.drawable.dopa_watcher_angry),
    CLEAR(R.drawable.dopa_watcher_clear),
}

/** ドパ監視員 */
@Composable
fun Watcher(mood: WatcherMood, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(mood.res),
        contentDescription = "ドパ監視員",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

enum class StickerTone { LIME, WHITE, DARK, ALERT }

/** 傾いたステッカー風ラベル */
@Composable
fun Sticker(
    text: String,
    modifier: Modifier = Modifier,
    tone: StickerTone = StickerTone.LIME,
    rotation: Float = 0f,
    fontSize: TextUnit = 11.sp,
    leading: (@Composable () -> Unit)? = null,
) {
    val (bg, fg) = when (tone) {
        StickerTone.LIME -> Dopa.Lime to Dopa.LimeForeground
        StickerTone.WHITE -> Dopa.Foreground to Dopa.Background
        StickerTone.DARK -> Dopa.Background to Dopa.Foreground
        StickerTone.ALERT -> Dopa.Alert to Dopa.Foreground
    }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .rotate(rotation)
            .drawBehind {
                // 右下に黒い影をずらして描く
                drawRoundRect(
                    Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
            .background(bg, shape)
            .border(2.dp, if (tone == StickerTone.DARK) Dopa.Foreground.copy(alpha = 0.8f) else Dopa.Background, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(text, color = fg, fontFamily = Dopa.Display, fontSize = fontSize, lineHeight = fontSize * 1.3f)
    }
}

/** セクション見出し（右側に補足やボタン） */
@Composable
fun SectionLabel(text: String, right: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, fontFamily = Dopa.Display, fontSize = 14.sp, letterSpacing = 1.sp, color = Dopa.Muted)
        right?.invoke()
    }
}

@Composable
fun SectionHint(text: String) {
    Text(text, fontSize = 11.sp, color = Dopa.Muted)
}

@Composable
fun DopaCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    radius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(Dopa.Surface)
            .border(1.dp, Dopa.Border, shape)
            .padding(padding),
        content = content,
    )
}

/** Dela Gothic One の大きな文字 */
@Composable
fun DisplayText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Dopa.Foreground,
    lineHeight: TextUnit = size * 1.05f,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text, modifier, color = color, fontFamily = Dopa.Display, fontSize = size,
        lineHeight = lineHeight, textAlign = textAlign, maxLines = maxLines, softWrap = maxLines > 1,
        style = TextStyle(fontFeatureSettings = "tnum"),
    )
}

/** 流れる帯 */
@Composable
fun Marquee(text: String, modifier: Modifier = Modifier, background: Color = Dopa.Lime, color: Color = Dopa.LimeForeground) {
    Text(
        List(8) { "$text ✕" }.joinToString("   "),
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = 6.dp)
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0, repeatDelayMillis = 0, velocity = 60.dp),
        color = color, fontFamily = Dopa.Display, fontSize = 12.sp, letterSpacing = 2.sp, maxLines = 1,
    )
}

/** オン・オフのスイッチ */
@Composable
fun DopaSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) Dopa.Lime else Dopa.Surface2)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .offset(x = if (checked) 20.dp else 0.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) Dopa.LimeForeground else Dopa.Muted),
        )
    }
}

/** 黄黒の警告ストライプ */
@Composable
fun HazardStripe(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val w = 10.dp.toPx()
        clipRect {
            drawRect(Dopa.LimeForeground)
            var x = -size.height
            while (x < size.width + size.height) {
                val p = Path().apply {
                    moveTo(x, size.height)
                    lineTo(x + w, size.height)
                    lineTo(x + w + size.height, 0f)
                    lineTo(x + size.height, 0f)
                    close()
                }
                drawPath(p, Dopa.Lime)
                x += w * 2
            }
        }
    }
}

/** うっすら方眼の背景 */
fun Modifier.gridBackground(): Modifier = drawBehind {
    val step = 24.dp.toPx()
    val c = Color.White.copy(alpha = 0.035f)
    var x = 0f
    while (x < size.width) {
        drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(c, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

/** 白い吹き出し（しっぽは左下） */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.matchParentSize()) {
            val sx = size.width / 200f
            val sy = size.height / 60f
            val p = Path().apply {
                moveTo(15 * sx, 2 * sy)
                lineTo(185 * sx, 2 * sy)
                quadraticTo(198 * sx, 2 * sy, 198 * sx, 15 * sy)
                lineTo(198 * sx, 35 * sy)
                quadraticTo(198 * sx, 48 * sy, 185 * sx, 48 * sy)
                lineTo(55 * sx, 48 * sy)
                lineTo(55 * sx, 58 * sy)
                lineTo(30 * sx, 48 * sy)
                lineTo(15 * sx, 48 * sy)
                quadraticTo(2 * sx, 48 * sy, 2 * sx, 35 * sy)
                lineTo(2 * sx, 15 * sy)
                quadraticTo(2 * sx, 2 * sy, 15 * sx, 2 * sy)
                close()
            }
            drawPath(p, Color.White)
            drawPath(p, Color(0xFF1B1B1B), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
        }
        Text(
            text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

/** 角が丸い吹き出し（影つき） */
@Composable
fun TalkLabel(text: String, modifier: Modifier = Modifier, rotation: Float = 0f, shadow: Color = Dopa.Lime, fontSize: TextUnit = 14.sp) {
    Text(
        text,
        modifier
            .rotate(rotation)
            .drawBehind {
                drawRoundRect(shadow, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
            }
            .background(Dopa.Foreground, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Dopa.Background, fontFamily = Dopa.Display, fontSize = fontSize, lineHeight = fontSize * 1.3f,
    )
}

/** 進捗バー */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Dopa.Lime, height: Dp = 12.dp, marker: Float? = null) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Dopa.Surface2)
            .drawBehind {
                drawRoundRect(
                    color, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
                marker?.let {
                    val x = size.width * it.coerceIn(0f, 1f)
                    drawLine(Dopa.Foreground, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                }
            },
    )
}

/** 丸いアイコン台 */
@Composable
fun IconBadge(modifier: Modifier = Modifier, size: Dp = 40.dp, background: Color = Dopa.Lime, content: @Composable () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) { content() }
}

/** 押すと沈むライムの大ボタン */
@Composable
fun BigLimeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fontSize: TextUnit = 20.sp) {
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(Dopa.LimeShadow, topLeft = Offset(0f, 6.dp.toPx()), size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Dopa.Lime)
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Dopa.LimeForeground, fontFamily = Dopa.Display, fontSize = fontSize)
    }
}

/** 小さい枠つきチップ */
@Composable
fun OutlineChip(text: String, color: Color) {
    Text(
        text,
        Modifier.border(2.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun VSpace(h: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.width(w))

// ---- アニメーション ----

/** ふわふわ上下（float） */
@Composable
fun Modifier.floating(): Modifier {
    val t = rememberInfiniteTransition(label = "float")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    return graphicsLayer {
        translationY = -8.dp.toPx() * p
        rotationZ = -2f + 3f * p
    }
}

/** ぶるぶる（shake） */
@Composable
fun Modifier.shaking(): Modifier {
    val t = rememberInfiniteTransition(label = "shake")
    val p by t.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 500
            -4f at 100
            4f at 200
            -3f at 300
            3f at 400
        }),
        label = "p",
    )
    return graphicsLayer {
        translationX = p.dp.toPx()
        rotationZ = p / 2
    }
}

/** 広がって消える輪（ring-pulse） */
@Composable
fun PulseRing(modifier: Modifier = Modifier, color: Color = Dopa.Lime, stroke: Dp = 2.dp, filled: Boolean = false) {
    val t = rememberInfiniteTransition(label = "ring")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))), label = "p")
    Canvas(modifier.graphicsLayer {
        val s = 0.85f + 0.75f * p
        scaleX = s
        scaleY = s
        alpha = 0.7f * (1 - p)
    }) {
        if (filled) drawCircle(color) else drawCircle(color, style = Stroke(stroke.toPx()))
    }
}

/** 点滅（blink） */
@Composable
fun Modifier.blinking(): Modifier {
    val t = rememberInfiniteTransition(label = "blink")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "p")
    return alpha(if (p < 0.5f) 1f else 0f)
}

/** 拡大して押されるスタンプ（stamp）。delayMs 後に1回 */
@Composable
fun Modifier.stamp(delayMs: Int = 0): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(550, delayMs, CubicBezierEasing(0.2f, 1.6f, 0.4f, 1f))) }
    return graphicsLayer {
        val p = a.value
        val s = 2.4f - 1.4f * p
        scaleX = s
        scaleY = s
        rotationZ = -14f + 8f * p
        alpha = p.coerceIn(0f, 1f)
    }
}

/** 下からせり上がる（rise）。delayMs 後に1回 */
@Composable
fun Modifier.rise(delayMs: Int = 0): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(500, delayMs, CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))) }
    return graphicsLayer {
        translationY = 16.dp.toPx() * (1 - a.value)
        alpha = a.value
    }
}
