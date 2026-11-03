package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
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

    LaunchedEffect(Unit) {
        val s = SettingsDataStore.imageGenSettings()
        enabled = s.enabled
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        model = s.model
        loaded = true
    }

    fun persist() {
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
        }
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
                    "会自动补 /images/generations；模型名按中转站实际支持的填（如 gpt-image-1、dall-e-3）。",
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
                EtaPreferenceDivider(hasLeading = false)
            }
        }
    }
}
