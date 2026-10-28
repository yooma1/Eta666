package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 全屏看图。
 *
 * 缩略图只有 60/100dp，字看不清、细节全糊，所以点开必须给「原图铺满 + 可放大」：
 * 单击任意处或返回键关闭，双指可捏合放大到 5 倍，放大后单指拖动平移。
 *
 * 为什么用 `usePlatformDefaultWidth = false`：默认 Dialog 宽度受系统主题限制（约 320dp），
 * 那样只是把缩略图放大到一张卡片大小，等于没放大。
 */
@Composable
internal fun ImageViewerDialog(
    dataUrl: String?,
    onDismiss: () -> Unit,
) {
    if (dataUrl == null) return
    val bitmap = rememberDataUrlBitmap(dataUrl) ?: return
    var scale by remember(dataUrl) { mutableFloatStateOf(1f) }
    var offset by remember(dataUrl) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 5f)
        scale = next
        // 缩回 1 倍时把位移归零，否则图片会带着偏移停在屏幕外。
        offset = if (next <= 1f) Offset.Zero else offset + panChange
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                // 点背景关掉；图片本身也吃点击，所以放大后点图片不会误关。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(transformState),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ui_remove_image_089db3),
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
            }
        }
    }
}
