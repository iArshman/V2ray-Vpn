package com.hollow.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hollow.vpn.ui.theme.HollowAmber
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowForest
import com.hollow.vpn.ui.theme.HollowForestCard
import com.hollow.vpn.ui.theme.HollowForestDivider
import com.hollow.vpn.ui.theme.HollowForestMuted
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowLime
import com.hollow.vpn.ui.theme.HollowObsidian
import com.hollow.vpn.ui.theme.HollowObsidianDivider
import com.hollow.vpn.ui.theme.HollowObsidianMuted
import com.hollow.vpn.ui.theme.HollowObsidianSurface
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

enum class HollowRouteState { OFF, BUSY, SECURED }

/**
 * You -> Server -> Internet diagram. OFF = traffic flows straight out (exposed),
 * BUSY = route being built (spinner + moving dashes), SECURED = packets flowing through the server.
 */
@Composable
fun HollowRouteCard(
    state: HollowRouteState,
    serverLabel: String,
    modifier: Modifier = Modifier,
    realIp: String? = null,
    exitIp: String? = null
) {
    val accent = when (state) {
        HollowRouteState.OFF -> HollowOrange
        HollowRouteState.BUSY -> HollowAmber
        HollowRouteState.SECURED -> HollowLime
    }
    val ink = if (state == HollowRouteState.OFF) HollowInk else HollowCream
    val muted = when (state) {
        HollowRouteState.OFF -> HollowInkSecondary
        HollowRouteState.BUSY -> HollowObsidianMuted
        HollowRouteState.SECURED -> HollowForestMuted
    }
    val lineColor = when (state) {
        HollowRouteState.OFF -> HollowCreamDivider
        HollowRouteState.BUSY -> HollowObsidianDivider
        HollowRouteState.SECURED -> HollowForestDivider
    }
    val cardBg = when (state) {
        HollowRouteState.OFF -> HollowCreamSurface
        HollowRouteState.BUSY -> HollowObsidianSurface
        HollowRouteState.SECURED -> HollowForestCard
    }
    val nodeBg = when (state) {
        HollowRouteState.OFF -> HollowCream
        HollowRouteState.BUSY -> HollowObsidian
        HollowRouteState.SECURED -> HollowForest
    }
    val midFilled = state == HollowRouteState.SECURED
    val midLabel = if (state == HollowRouteState.OFF) "No server" else serverLabel
    val transition = rememberInfiniteTransition(label = "route")
    val dashPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "dash"
    )
    val packet by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "packet"
    )
    val ping by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ping"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val nodeSize = 52.dp
    val labelWidth = 76.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Route: You to $midLabel to Internet"
            }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nodeSize)
            ) {
                val r = nodeSize.toPx() / 2f
                val cy = size.height / 2f
                val x0 = labelWidth.toPx() / 2f
                val x1 = size.width / 2f
                val x2 = size.width - x0
                val gap = 4.dp.toPx()
                val sw = 3.dp.toPx()
                val dash = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                    -dashPhase.dp.toPx()
                )
                when (state) {
                    HollowRouteState.OFF -> drawLine(
                        accent,
                        Offset(x0 + r + gap, cy),
                        Offset(x2 - r - gap, cy),
                        sw,
                        pathEffect = dash
                    )
                    HollowRouteState.BUSY -> {
                        drawLine(
                            accent,
                            Offset(x0 + r + gap, cy),
                            Offset(x1 - r - gap, cy),
                            sw,
                            pathEffect = dash
                        )
                        drawLine(
                            lineColor,
                            Offset(x1 + r + gap, cy),
                            Offset(x2 - r - gap, cy),
                            sw
                        )
                    }
                    HollowRouteState.SECURED -> {
                        drawLine(
                            lineColor,
                            Offset(x0 + r + gap, cy),
                            Offset(x2 - r - gap, cy),
                            sw
                        )
                        for (i in 0..2) {
                            val p = (packet + i / 3f) % 1f
                            val px = (x0 + r) + ((x2 - r) - (x0 + r)) * p
                            drawCircle(accent, 4.5.dp.toPx(), Offset(px, cy))
                        }
                        drawCircle(
                            color = accent.copy(alpha = (1f - ping) * 0.7f),
                            radius = r + r * 0.8f * ping,
                            center = Offset(x1, cy),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                if (state == HollowRouteState.BUSY) {
                    val ar = r + 6.dp.toPx()
                    drawArc(
                        color = accent,
                        startAngle = spin,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(x1 - ar, cy - ar),
                        size = Size(ar * 2, ar * 2),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                RouteNode(Icons.Outlined.PhoneAndroid, "You", nodeBg, ink, ink, muted, nodeSize, labelWidth)
                RouteNode(
                    Icons.Outlined.Dns,
                    midLabel,
                    fill = if (midFilled) accent else nodeBg,
                    stroke = when (state) {
                        HollowRouteState.OFF -> lineColor
                        else -> accent
                    },
                    tint = when {
                        midFilled -> HollowForest
                        state == HollowRouteState.OFF -> muted
                        else -> ink
                    },
                    labelColor = muted,
                    nodeSize = nodeSize,
                    width = labelWidth
                )
                RouteNode(Icons.Outlined.Public, "Internet", nodeBg, ink, ink, muted, nodeSize, labelWidth)
            }
        }
        if (realIp != null && exitIp != null) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough)) {
                        append(realIp)
                    }
                    withStyle(SpanStyle(color = muted)) { append("  →  ") }
                    withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Medium)) { append(exitIp) }
                },
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun RouteNode(
    icon: ImageVector,
    label: String,
    fill: Color,
    stroke: Color,
    tint: Color,
    labelColor: Color,
    nodeSize: Dp,
    width: Dp
) {
    Column(modifier = Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(nodeSize)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, stroke, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private const val SPEED_BAR_COUNT = 24

/** Download / upload cells plus live download-speed bars driven by real telemetry (one sample per second). */
@Composable
fun HollowSpeedBarsCard(
    downloadValue: String,
    downloadUnit: String,
    uploadValue: String,
    uploadUnit: String,
    downloadBytesPerSec: Long,
    elapsedSeconds: Long,
    modifier: Modifier = Modifier
) {
    val history = remember { mutableStateListOf<Long>() }
    LaunchedEffect(elapsedSeconds) {
        history.add(downloadBytesPerSec)
        while (history.size > SPEED_BAR_COUNT) history.removeAt(0)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SpeedCell("DOWNLOAD", downloadValue, downloadUnit, Modifier.weight(1f))
            SpeedCell("UPLOAD", uploadValue, uploadUnit, Modifier.weight(1f))
        }
        val maxV = (history.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val pad = SPEED_BAR_COUNT - history.size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(SPEED_BAR_COUNT) { i ->
                val v = if (i < pad) 0L else history[i - pad]
                val f = v.toFloat() / maxV.toFloat()
                val h by animateDpAsState((6f + 46f * f).dp, tween(400), label = "bar")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(h)
                        .clip(RoundedCornerShape(3.dp))
                        .background(HollowLime.copy(alpha = 0.35f + 0.65f * f))
                )
            }
        }
    }
}

@Composable
private fun SpeedCell(label: String, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(HollowForestCard)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 1.1.sp,
            color = HollowForestMuted,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                color = HollowCream,
                maxLines = 1
            )
            Text(
                text = " $unit",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                color = HollowForestMuted,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** Small status dot that blinks (used next to NOT PROTECTED / CONNECTING / PROTECTED labels). */
@Composable
fun HollowBlinkingDot(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink_alpha"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .alpha(a)
            .clip(CircleShape)
            .background(color)
    )
}

/** Looping fill bar shown while the tunnel is being built. */
@Composable
fun HollowIndeterminateBar(color: Color, track: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bar_fill")
    val f by t.animateFloat(
        initialValue = 0.06f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "bar_fraction"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** The Hollow launcher mark (hollow shield + dot). */
@Composable
fun HollowShieldMark(size: Dp, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(background)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.width / 108f
            val shield = Path().apply {
                moveTo(54f * s, 28f * s)
                lineTo(75f * s, 37f * s)
                lineTo(75f * s, 54f * s)
                cubicTo(75f * s, 68f * s, 66f * s, 77f * s, 54f * s, 81f * s)
                cubicTo(42f * s, 77f * s, 33f * s, 68f * s, 33f * s, 54f * s)
                lineTo(33f * s, 37f * s)
                close()
            }
            drawPath(shield, foreground, style = Stroke(width = 7f * s, join = StrokeJoin.Round))
            drawCircle(foreground, 7f * s, Offset(54f * s, 56f * s))
        }
    }
}
