package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.image.ImageGenClient
import io.github.mangi.eta.agent.image.ImageGenConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 生图中转站设置。
 *
 * 只支持 OpenAI 兼容的 `/v1/images/generations`：填 base_url + key + 模型名即可，
 * 需要异步任务轮询的服务（可灵/即梦等）协议各不相同，不在这条路径内。
 */
@Composable
internal fun ImageGenSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("auto") }
    var background by remember { mutableStateOf("auto") }
    var saving by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val s = SettingsDataStore.imageGenSettings()
        enabled = s.enabled
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        model = s.model
        quality = s.quality
        background = s.background
        loaded = true
    }

    fun persist(message: String? = "已保存", onDone: (() -> Unit)? = null) {
        scope.launch {
            saving = true
            SettingsDataStore.setImageGenSettings(
                SettingsDataStore.ImageGenSettings(
                    enabled = enabled,
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                    quality = quality,
                    background = background,
                ),
            )
            saving = false
            savedHint = message
            onDone?.invoke()
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            models = models,
            query = query,
            onQueryChange = { query = it },
            onPick = { id ->
                model = id
                showPicker = false
                persist(message = "已选择 $id")
            },
            onDismiss = { showPicker = false },
        )
    }

    MiuixScaffoldPage(
        title = "生图（中转站）",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!loaded) return@MiuixScaffoldPage

        item(key = "enable_title") { EtaPreferenceGroupTitle("能力") }
        item(key = "enable") {
            Column {
                EtaPreferenceGroup {
                    EtaSwitchPreference(
                        title = "启用生图工具",
                        summary = if (enabled) "模型可调用 generate_image" else "关闭时该工具不进入工具列表",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist(message = if (it) "生图工具已启用" else "生图工具已关闭")
                        },
                    )
                }
                ImageGenNote(
                    "只有 base_url、API Key、模型名三项齐全且开关打开时，工具才会出现在模型手里。",
                )
            }
        }

        item(key = "conn_title") { EtaPreferenceGroupTitle("中转站") }
        item(key = "conn") {
            Column {
                EtaPreferenceGroup {
                    ImageGenFieldColumn {
                        ImageGenField(
                            label = "base_url",
                            value = baseUrl,
                            onChange = { baseUrl = it },
                        )
                        ImageGenField(
                            label = "API Key",
                            value = apiKey,
                            secret = true,
                            onChange = { apiKey = it },
                        )
                    }
                }
                ImageGenNote("base_url 可写 https://host 或 https://host/v1，会自动补 /images/generations。")
            }
        }

        item(key = "model_title") { EtaPreferenceGroupTitle("模型") }
        item(key = "model") {
            Column {
                EtaPreferenceGroup {
                    ImageGenFieldColumn {
                        ImageGenField(
                            label = "模型名",
                            value = model,
                            onChange = { model = it },
                        )
                    }
                }
                ImageGenNote(
                    "可用下面的「从远端拉取模型」直接选，避免手打模型 id。",
                )
            }
        }
        item(key = "fetch") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = if (fetching) "正在拉取模型…" else "从远端拉取模型",
                    summary = fetchError
                        ?: "读取 /models 列表，选中后自动填入上面的模型名",
                    enabled = !fetching,
                    onClick = {
                        fetchError = null
                        fetching = true
                        scope.launch {
                            val cfg = ImageGenConfig.fromSettings(baseUrl, apiKey, model, enabled, quality, background)
                            val result = ImageGenClient.fetchModels(cfg)
                            fetching = false
                            result
                                .onSuccess { list ->
                                    models = sortModelsForPicker(list)
                                    query = ""
                                    showPicker = true
                                }
                                .onFailure { fetchError = it.message ?: "拉取失败" }
                        }
                    },
                )
            }
        }

        item(key = "params_title") { EtaPreferenceGroupTitle("默认生成参数") }
        item(key = "params") {
            val qualityOptions = listOf(
                "auto" to "自动 (auto)",
                "low" to "低画质 (low)",
                "medium" to "中画质 (medium)",
                "high" to "高画质 (high)",
                "standard" to "标准 (standard · DALL-E 3)",
                "hd" to "高清 (hd · DALL-E 3)",
            )
            val backgroundOptions = listOf(
                "auto" to "自动 (auto)",
                "opaque" to "不透明 (opaque)",
                "transparent" to "透明背景 (transparent)",
            )
            Column {
                EtaPreferenceGroup {
                    EtaOverlayDropdownPreference(
                        title = "画质 (quality)",
                        summary = "模型调用未显式指定 quality 时使用的默认档位",
                        items = qualityOptions.map { it.second },
                        selectedIndex = qualityOptions.indexOfFirst { it.first == quality }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            quality = qualityOptions[idx].first
                            persist(message = "画质已设为 ${qualityOptions[idx].first}")
                        },
                    )
                    EtaPreferenceDivider()
                    EtaOverlayDropdownPreference(
                        title = "背景 (background)",
                        summary = "支持 gpt-image 系列输出透明底 PNG（图标/贴纸/抠图素材）",
                        items = backgroundOptions.map { it.second },
                        selectedIndex = backgroundOptions.indexOfFirst { it.first == background }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            background = backgroundOptions[idx].first
                            persist(message = "背景已设为 ${backgroundOptions[idx].first}")
                        },
                    )
                }
                ImageGenNote("当服务端或模型不支持 quality / background 参数时，客户端会自动降级剥离并重试。")
            }
        }

        item(key = "save") {
            ImageGenSaveBlock(
                saving = saving,
                hint = savedHint,
                onSave = {
                    savedHint = null
                    persist()
                },
            )
        }
    }
}

@Composable
private fun ImageGenNote(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp),
    )
}

@Composable
private fun ImageGenFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ImageGenField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = true,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.Visibility
                        else Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ImageGenSaveBlock(
    saving: Boolean,
    hint: String?,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EtaTextButton(
            text = if (saving) "保存中…" else "保存",
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onSave,
        )
        hint?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = remember(models, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models else models.filter { it.lowercase().contains(q) }
    }
    EtaWindowDialog(
        show = true,
        title = "选择模型（共 ${models.size} 个）",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                label = "搜索模型",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有匹配的模型",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(filtered) { id ->
                        Text(
                            text = id,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 图像类模型排前面：模型多到手翻很难找，先把明显能生图的挑出来。 */
private val IMAGE_MODEL_HINTS = listOf(
    "image", "dall-e", "dalle", "flux", "nano-banana", "nano_banana",
    "seedream", "seededit", "kling", "kolors", "qwen-image", "imagen",
    "recraft", "ideogram", "grok-imagine", "mai-image", "hidream", "muse",
)

private fun sortModelsForPicker(models: List<String>): List<String> =
    models.sortedWith(
        compareByDescending<String> { id ->
            IMAGE_MODEL_HINTS.any { id.lowercase().contains(it) }
        }.thenBy { it },
    )
