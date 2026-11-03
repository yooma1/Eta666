package io.github.mangi.eta.local.incoming

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.DragAndDropPermissions
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.AgentAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.Text

/** Only the main chat provides this bridge. Overlay/voice input bars remain unchanged. */
private class IncomingDraftBridge {
    var field by mutableStateOf<TextFieldState?>(null)
}

private val LocalIncomingDraft = staticCompositionLocalOf<IncomingDraftBridge?> { null }

/** Bind the actual local input state instead of overwriting the upstream, possibly stale draft. */
@Composable
internal fun BindIncomingDraft(field: TextFieldState) {
    val bridge = LocalIncomingDraft.current
    DisposableEffect(bridge, field) {
        bridge?.field = field
        onDispose { bridge?.let { if (it.field === field) it.field = null } }
    }
}

internal fun appendIncomingText(existing: String, incoming: String): String = when {
    incoming.isBlank() -> existing
    existing.isEmpty() -> incoming
    existing.endsWith('\n') -> existing + incoming
    else -> "$existing\n$incoming"
}

/** Stable, already-copied attachments are applied on the UI thread together with draft text. */
@Composable
internal fun IncomingContentHost(
    agentState: AgentAppState,
    dropEnabled: Boolean,
    onOpenHome: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val model = viewModel<IncomingContentViewModel>()
    val pending by model.pending.collectAsState()
    val importing by model.importing.collectAsState()
    val bridge = remember { IncomingDraftBridge() }
    var hovering by remember { mutableStateOf(false) }
    val item = pending.firstOrNull()
    val ready = dropEnabled && agentState.homeState.isConversationLoaded && agentState.homeState.messageEdit == null
    val currentReady by rememberUpdatedState(ready)
    val openHome by rememberUpdatedState(onOpenHome)

    LaunchedEffect(item?.id) {
        if (item != null) {
            openHome()
            if (agentState.homeState.messageEdit != null) {
                Toast.makeText(context, R.string.local_incoming_finish_edit, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(item?.id, ready, bridge.field) {
        val incoming = item ?: return@LaunchedEffect
        val field = bridge.field ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (!agentState.attachIncomingContent(incoming.images, incoming.files)) return@LaunchedEffect
        if (incoming.text.isNotBlank()) {
            field.edit {
                val before = toString()
                val combined = appendIncomingText(before, incoming.text)
                replace(length, length, combined.substring(before.length))
                selection = TextRange(length)
            }
        }
        model.consume(incoming.id)
        Toast.makeText(
            context,
            when {
                incoming.text.isBlank() && incoming.images.isEmpty() && incoming.files.isEmpty() ->
                    R.string.local_incoming_unreadable
                incoming.skipped > 0 -> R.string.local_incoming_partial
                else -> R.string.local_incoming_added
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    CompositionLocalProvider(LocalIncomingDraft provides bridge) {
        Box(Modifier.fillMaxSize()) {
            content()
            // 原生拖拽接收层。
            //
            // Compose 的 dragAndDropTarget 在 startTransfer 阶段按 ClipDescription 的
            // MIME 过滤，ColorOS 文件管理拖非图片文件时声明不规范，手势进不了目标
            // （表现为"只能拖图片"）。用一个铺满的透明原生 View 接管：Android 的
            // View.OnDragListener 不做 MIME 预过滤，只要 ACTION_DRAG_STARTED 返回 true，
            // 后续 DRAG_ENTERED / DROP 都会送达。可用性判断交给 parser。
            AndroidView(
                factory = { ctx ->
                    android.view.View(ctx).apply {
                        isClickable = false
                        isFocusable = false
                        // 只做拖拽接收，绝不能抢触摸：铺满的透明 View 若参与命中测试，
                        // 输入框、按钮会全部点不动。返回 false 让手势穿透到下层 Compose。
                        // 触摸一律不消费，让它穿透到下层 Compose；
                        // 注意不能用 isEnabled=false —— 禁用状态的 View 收不到拖拽事件。
                        setOnTouchListener { _, _ -> false }
                        setOnDragListener { _, event ->
                            when (event.action) {
                                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                                    // 声明可接收一切；真正的取舍在 DROP。
                                    if (currentReady) {
                                        hovering = true
                                    }
                                    currentReady
                                }
                                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                                    hovering = currentReady
                                    currentReady
                                }
                                android.view.DragEvent.ACTION_DRAG_EXITED,
                                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                                    hovering = false
                                    true
                                }
                                android.view.DragEvent.ACTION_DROP -> {
                                    hovering = false
                                    if (!currentReady) return@setOnDragListener false
                                    val payload = event.clipData
                                        ?.let(IncomingContentParser::fromClipData)
                                        ?: IncomingContentParser.fromDragDescription(event)
                                    if (payload == null) {
                                        false
                                    } else {
                                        val permission = runCatching {
                                            context.findActivity()
                                                ?.requestDragAndDropPermissions(event)
                                        }.getOrNull()
                                        model.import(payload, permission)
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
            if (hovering && ready) {
                Box(
                    Modifier.matchParentSize()
                        .background(Color(0x184488FF))
                        .border(2.dp, Color(0xFF4488FF)),
                )
            }
            if ((hovering && ready) || importing > 0) {
                Text(
                    text = stringResource(
                        if (hovering && ready) R.string.local_incoming_drop_hint else R.string.local_incoming_importing,
                    ),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                        .background(Color(0xEE3366CC), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
    }
}

internal class IncomingContentViewModel(application: Application) : AndroidViewModel(application) {
    val pending = MutableStateFlow<List<IncomingContent>>(emptyList())
    val importing = MutableStateFlow(0)
    private val consumed = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            IncomingContentInbox.changes.collect {
                try {
                    val items = withContext(Dispatchers.IO) { IncomingContentInbox.list(getApplication()) }
                    pending.value = items.filterNot { it.id in consumed }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    notice(R.string.local_incoming_inbox_error)
                }
            }
        }
    }

    fun import(payload: IncomingPayload, permissions: DragAndDropPermissions?) {
        viewModelScope.launch {
            importing.value += 1
            try {
                val imported = withTimeout(60_000) { IncomingContentInbox.import(getApplication(), payload) }
                if (imported == null) notice(R.string.local_incoming_unreadable)
            } catch (cancel: CancellationException) {
                if (cancel is kotlinx.coroutines.TimeoutCancellationException) notice(R.string.local_incoming_unreadable)
                else throw cancel
            } catch (_: Exception) {
                notice(R.string.local_incoming_inbox_error)
            } finally {
                runCatching { permissions?.release() }
                importing.value -= 1
            }
        }
    }

    fun consume(id: String) {
        consumed += id
        pending.value = pending.value.filterNot { it.id == id }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { IncomingContentInbox.remove(getApplication(), id) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                notice(R.string.local_incoming_inbox_error)
            }
        }
    }

    private fun notice(message: Int) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
