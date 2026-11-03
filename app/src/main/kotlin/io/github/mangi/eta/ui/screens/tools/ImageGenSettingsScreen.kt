package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.image.ImageGenClient
import io.github.mangi.eta.agent.image.ImageGenConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
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
    var keyVisible by remember { mutableStateOf(false) }
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
        loaded = true
    }

    fun persist(onDone: (() -> Unit)? = null) {
        scope.launch {
            SettingsDataStore.setImageGenSettings(
                SettingsDataStore.ImageGenSettings(
                    enabled = enabled,
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                ),
            )
            savedHint = "已保存"
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
                persist()
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

        item(key = "note") {
            Text(
                text = "填写 OpenAI 兼容中转站。base_url 可写 https://host 或 https://host/v1，" +
                    "会自动补 /images/generations；模型名可用下面的「从远端拉取」直接选。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item(key = "enable") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                EtaPreferenceGroup {
                    EtaSwitchPreference(
                        title = "启用生图工具",
                        summary = if (enabled) "模型可调用 generate_image" else "关闭时该工具不进入工具列表",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist()
                        },
                    )
                }
            }
        }

        item(key = "fetch") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = if (fetching) "正在拉取模型…" else "从远端拉取模型",
                        summary = fetchError
                            ?: "读取 ${baseUrl.trim().ifBlank { "base_url" }} 的 /models 列表，直接选不用手打",
                        onClick = {
                            if (fetching) return@EtaPreference
                            fetchError = null
                            fetching = true
                            scope.launch {
                                val cfg = ImageGenConfig.fromSettings(baseUrl, apiKey, model, enabled)
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
        }

        item(key = "fields") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "base_url",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Rounded.Visibility
                                else Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                            )
                        }
                    },
                )
                TextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "模型名（如 gpt-image-1）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "save") {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = "保存",
                        summary = savedHint ?: "保存后下一轮对话即生效",
                        onClick = {
                            savedHint = null
                            persist()
                        },
                    )
                }
            }
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

/** 图像类模型排前面：97 个模型里手翻很难找，先把明显能生图的挑出来。 */
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
