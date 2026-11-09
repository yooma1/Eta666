package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ChatGaugeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChatGauge",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.35f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3.4f, 13.1f)
            arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.6f, 13.1f)
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.35f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Miter,
        ) {
            moveTo(8.0f, 8.5f)
            lineTo(11.5f, 5.0f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.2f, 8.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 8.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 8.5f)
            close()
        }
    }.build()
}

private val ChatDatabaseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChatDatabase",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.35f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(13.8f, 3.8f)
            arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 3.8f)
            arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.8f, 3.8f)
            moveTo(2.2f, 3.8f)
            verticalLineTo(11.8f)
            arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 11.8f)
            verticalLineTo(3.8f)
            moveTo(2.2f, 7.8f)
            arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 7.8f)
        }
    }.build()
}

/**
 * 回答操作行里的轻量性能胶囊（如 `1.1秒 · 146 tok/s`），点击展开单轮「会话统计」浮窗。
 */
@Composable
internal fun MessageStatsPill(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    if (!usage.hasStats) return
    var expanded by remember { mutableStateOf(false) }

    val steps = (usage.stepCount ?: 1).coerceAtLeast(1)
    val llmMs = usage.llmMs ?: 0L
    val toolMs = usage.toolMs ?: 0L
    val ttftSteps = usage.ttftSteps ?: if (usage.ttftMs != null) 1 else 0
    val avgTtftMs = if (ttftSteps > 0 && usage.ttftMs != null) usage.ttftMs / ttftSteps else null
    val decodeTokens = usage.decodeTokens ?: usage.outputTokens
    val tps = if (usage.decodeMs != null && usage.decodeMs > 0L && decodeTokens != null && decodeTokens > 0) {
        decodeTokens.toDouble() / (usage.decodeMs / 1000.0)
    } else {
        null
    }
    val tpsText = tps?.let { "${formatSpeed(it)} tok/s" }
    val pillLabel = when {
        avgTtftMs != null && tpsText != null -> "${formatStatsDuration(avgTtftMs)} · $tpsText"
        tpsText != null -> tpsText
        llmMs > 0L -> formatStatsDuration(llmMs)
        avgTtftMs != null -> "TTFT ${formatStatsDuration(avgTtftMs)}"
        else -> return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f)
    val bgColor = if (expanded) {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChatGaugeIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = textColor,
            )
            Text(
                text = pillLabel,
                color = textColor,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (expanded) {
            StatsAnchoredPopup(onDismiss = { expanded = false }) {
                StatsSectionHeader(
                    icon = ChatGaugeIcon,
                    title = "会话统计",
                    trailing = "1 轮 $steps 步",
                )
                StatsSectionDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (llmMs > 0L) {
                        StatsMetricRow(label = "模型用时", value = formatStatsDuration(llmMs))
                    }
                    if (toolMs > 0L) {
                        StatsMetricRow(label = "工具调用用时", value = formatStatsDuration(toolMs))
                    }
                    if (avgTtftMs != null && avgTtftMs > 0L) {
                        StatsMetricRow(label = "首 token 平均（TTFT）", value = formatStatsDuration(avgTtftMs))
                    }
                    if (tpsText != null) {
                        StatsMetricRow(label = "输出速度（TPS）", value = tpsText)
                    }
                }
            }
        }
    }
}

/**
 * 输入框圆环点开的完整面板：上半段「Token 用量」（含上下文占比、缓存命中、未缓存输入、缓存读取、输出及推理），
 * 下半段「会话统计」（整场对话累计 N 轮 M 步、模型用时、工具调用用时、TTFT、TPS）与「压缩上下文」按钮。
 */
@Composable
internal fun SessionContextUsagePopup(
    usage: AgentContextUsageUi,
    summary: String,
    detail: String,
    canCompact: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stats = usage.sessionStats
    val cacheHitPercent = if (stats.promptTokens > 0 && stats.cachedTokens > 0) {
        if (stats.cachedTokens >= stats.promptTokens) {
            "100"
        } else {
            val pct = (stats.cachedTokens.toDouble() / stats.promptTokens.toDouble()) * 100.0
            val rounded = pct.roundToInt()
            if (rounded < 100) {
                rounded.toString()
            } else {
                val oneDec = ((pct * 10.0).toInt()) / 10.0
                if (oneDec < 100.0) String.format(Locale.US, "%.1f", oneDec) else "99.9"
            }
        }
    } else {
        null
    }
    val avgTtftMs = if (stats.ttftSteps > 0) stats.ttftMs / stats.ttftSteps else null
    val tpsText = if (stats.decodeMs > 0L && stats.decodeTokens > 0) {
        "${formatSpeed(stats.decodeTokens.toDouble() / (stats.decodeMs / 1000.0))} tok/s"
    } else {
        null
    }

    StatsAnchoredPopup(onDismiss = onDismiss) {
        val headerTitle = if (stats.hasTokenUsage) {
            "Token 用量"
        } else {
            stringResource(R.string.ui_contextual_usage_d12810)
        }
        val headerTrailing = if (stats.hasTokenUsage) {
            "${formatFullInt(stats.promptTokens + stats.outputTokens)} tok"
        } else {
            null
        }
        StatsSectionHeader(
            icon = ChatDatabaseIcon,
            title = headerTitle,
            trailing = headerTrailing,
        )
        StatsSectionDivider()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (usage.contextTokens == null || usage.contextWindow == null || usage.contextWindow <= 0) {
                Text(
                    text = detail,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                )
            } else {
                StatsMetricRow(
                    label = stringResource(R.string.ui_contextual_usage_d12810),
                    value = summary.replace(" tokens · ", " · "),
                )
            }

            if (stats.hasTokenUsage) {
                if (cacheHitPercent != null) {
                    StatsMetricRow(label = "缓存命中", value = "$cacheHitPercent%")
                }
                StatsMetricRow(
                    label = "未缓存输入",
                    value = "${formatFullInt(stats.uncachedInputTokens)} tok",
                )
                if (stats.cachedTokens > 0) {
                    StatsMetricRow(
                        label = "缓存读取",
                        value = "${formatFullInt(stats.cachedTokens)} tok",
                    )
                }
                StatsOutputTokensRow(
                    outputTokens = stats.outputTokens,
                    reasoningTokens = stats.reasoningTokens,
                )
            }
        }

        val hasSessionTiming = stats.llmMs > 0L ||
            stats.toolMs > 0L ||
            stats.ttftSteps > 0 ||
            (stats.decodeMs > 0L && stats.decodeTokens > 0)
        if (hasSessionTiming) {
            Spacer(Modifier.height(14.dp))
            StatsSectionHeader(
                icon = ChatGaugeIcon,
                title = "会话统计",
                trailing = "${stats.turns} 轮 ${stats.steps} 步",
            )
            StatsSectionDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stats.llmMs > 0L) {
                    StatsMetricRow(label = "模型用时", value = formatStatsDuration(stats.llmMs))
                }
                if (stats.toolMs > 0L) {
                    StatsMetricRow(label = "工具调用用时", value = formatStatsDuration(stats.toolMs))
                }
                if (avgTtftMs != null && avgTtftMs > 0L) {
                    StatsMetricRow(label = "首 token 平均（TTFT）", value = formatStatsDuration(avgTtftMs))
                }
                if (tpsText != null) {
                    StatsMetricRow(label = "输出速度（TPS）", value = tpsText)
                }
            }
        }

        if (canCompact) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.context_compact_action),
                onClick = {
                    onCompact()
                    onDismiss()
                },
                minWidth = 0.dp,
                minHeight = 34.dp,
                cornerRadius = 17.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun StatsAnchoredPopup(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        val marginPx = with(density) { 12.dp.roundToPx() }
        val gapPx = with(density) { 8.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val rawX = if (layoutDirection == LayoutDirection.Rtl) {
                    anchorBounds.right - popupContentSize.width
                } else {
                    anchorBounds.left
                }
                val x = rawX.coerceIn(marginPx, maxX)

                val aboveY = anchorBounds.top - popupContentSize.height - gapPx
                val belowY = anchorBounds.bottom + gapPx
                val y = when {
                    aboveY >= marginPx -> aboveY
                    belowY + popupContentSize.height <= windowSize.height - marginPx -> belowY
                    else -> (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                }
                return IntOffset(x, y)
            }
        }
    }

    val cornerRadius = 18.dp
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 256.dp, max = 328.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(cornerRadius),
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.16f),
                )
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = cornerRadius,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cornerRadius = cornerRadius,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun StatsSectionHeader(
    icon: ImageVector,
    title: String,
    trailing: String?,
) {
    val color = MiuixTheme.colorScheme.onSurface
    val textStyle = MiuixTheme.textStyles.footnote1.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = color,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = trailing,
                color = color,
                style = textStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatsSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun StatsMetricRow(
    label: String,
    value: String,
) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    val style = MiuixTheme.textStyles.footnote1.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            color = labelColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            color = valueColor,
            style = style,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsOutputTokensRow(
    outputTokens: Int,
    reasoningTokens: Int,
) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    val style = MiuixTheme.textStyles.footnote1.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "输出",
            color = labelColor,
            style = style,
        )
        Spacer(Modifier.width(16.dp))
        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(color = valueColor)) {
                append("${formatFullInt(outputTokens)} tok")
            }
            if (reasoningTokens > 0) {
                withStyle(SpanStyle(color = labelColor)) {
                    append("（其中推理 ${formatFullInt(reasoningTokens)} tok）")
                }
            }
        }
        Text(
            text = annotated,
            style = style,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

internal fun formatFullInt(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.coerceAtLeast(0))

internal fun formatStatsDuration(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val seconds = clamped / 1000.0
    if (seconds >= 60.0) {
        val totalSeconds = seconds.roundToLong()
        return "${totalSeconds / 60}分${totalSeconds % 60}秒"
    }
    val tenths = (seconds * 10.0).roundToLong()
    val whole = tenths / 10
    val rem = tenths % 10
    return if (rem == 0L) "${whole}秒" else "${whole}.${rem}秒"
}

internal fun formatSpeed(tps: Double): String {
    val clamped = tps.coerceAtLeast(0.0)
    if (clamped >= 10.0) {
        return clamped.roundToInt().toString()
    }
    val tenths = (clamped * 10.0).roundToInt()
    val whole = tenths / 10
    val rem = tenths % 10
    return if (rem == 0) whole.toString() else "$whole.$rem"
}
