package io.github.mangi.eta.ui.screens.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentCatalog
import io.github.mangi.eta.agent.subagent.SubagentProfileStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.SectionHeader
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Subagent 设置页。
 *
 * 结构对齐参考实现：通用与模型（启用开关 / Subagent 模型 / 思考强度）→ 说明文字 → Built-in Subagents。
 * 三个内置档案做成只读入口，点开能看到它的提示词与工具边界——这是排查「为什么子代理没做某件事」
 * 最快的入口，比翻日志直接。
 */
@Composable
internal fun SubagentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_TOOLS)) }
    var modelOverride by remember { mutableStateOf(Prefs.getLocalString(Prefs.Keys.AGENT_SUBAGENT_MODEL)) }
    var reasoningOverride by remember {
        mutableStateOf(Prefs.getLocalString(Prefs.Keys.AGENT_SUBAGENT_REASONING))
    }
    var showModelDialog by remember { mutableStateOf(false) }
    var inspectedProfile by remember { mutableStateOf<String?>(null) }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val unknownModelLabel = stringResource(R.string.subagent_model_unknown)
    val mainModelName = remember(providers, selectedModelId, unknownModelLabel) {
        providers.firstOrNull { provider -> provider.models.any { it.id == selectedModelId } }
            ?.models?.firstOrNull { it.id == selectedModelId }?.displayName
            ?: unknownModelLabel
    }
    val overrideName = remember(providers, modelOverride) {
        if (modelOverride.isBlank()) {
            null
        } else {
            providers.firstNotNullOfOrNull { provider ->
                provider.models.firstOrNull { it.id == modelOverride }?.displayName
            }
        }
    }

    val profiles = remember { SubagentProfileStore(context.filesDir).all() }
    val builtins = profiles.filter { it.builtin }

    MiuixScaffoldPage(
        title = stringResource(R.string.subagent_page_title),
        onBack = onBack,
    ) {
        item { EtaPreferenceGroupTitle(stringResource(R.string.subagent_section_general)) }
        item {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.subagent_enable_title),
                    summary = stringResource(R.string.subagent_enable_summary),
                    startAction = { PrefIcon(Icons.Rounded.AccountTree, EtaPreferenceColors.Blue) },
                    endActions = {
                        EtaSwitch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                enabled = checked
                                Prefs.putLocalBoolean(Prefs.Keys.AGENT_SUBAGENT_TOOLS, checked)
                            },
                        )
                    },
                )

                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.subagent_model_title),
                    summary = overrideName
                        ?: stringResource(R.string.subagent_model_follow, mainModelName),
                    startAction = { PrefIcon(Icons.Rounded.Memory, Color(0xFF2FB344)) },
                    onClick = { showModelDialog = true },
                )

                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.subagent_reasoning_title),
                    startAction = { PrefIcon(Icons.Rounded.Tune, Color(0xFFF08A24)) },
                    endActions = {
                        Text(
                            text = reasoningLabel(reasoningOverride),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        StepperArrow(Icons.Rounded.KeyboardArrowUp) {
                            reasoningOverride = stepReasoning(reasoningOverride, +1)
                            Prefs.putLocalString(Prefs.Keys.AGENT_SUBAGENT_REASONING, reasoningOverride)
                        }
                        StepperArrow(Icons.Rounded.KeyboardArrowDown) {
                            reasoningOverride = stepReasoning(reasoningOverride, -1)
                            Prefs.putLocalString(Prefs.Keys.AGENT_SUBAGENT_REASONING, reasoningOverride)
                        }
                    },
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.subagent_footer),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }

        item { SectionHeader(stringResource(R.string.subagent_section_builtin)) }
        item {
            EtaPreferenceGroup {
                builtins.forEachIndexed { index, profile ->
                    if (index > 0) EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = profile.name,
                        summary = profile.description,
                        startAction = { PrefIcon(iconFor(profile.name), colorFor(profile.name)) },
                        onClick = { inspectedProfile = profile.name },
                    )
                }
            }
        }
    }

    if (showModelDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.subagent_model_title),
            onDismissRequest = { showModelDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EtaTextButton(
                    text = stringResource(R.string.subagent_model_follow, mainModelName),
                    onClick = {
                        modelOverride = ""
                        Prefs.putLocalString(Prefs.Keys.AGENT_SUBAGENT_MODEL, "")
                        showModelDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                providers.forEach { provider ->
                    provider.models.filter { it.isEnabled }.forEach { model ->
                        EtaTextButton(
                            text = "${model.displayName} · ${provider.name}",
                            onClick = {
                                modelOverride = model.id
                                Prefs.putLocalString(Prefs.Keys.AGENT_SUBAGENT_MODEL, model.id)
                                showModelDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    inspectedProfile?.let { name ->
        val profile = profiles.firstOrNull { it.name == name }
        if (profile != null) {
            ItemDescriptionDialog(
                title = profile.name,
                description = buildString {
                    appendLine(profile.description)
                    appendLine()
                    appendLine(profile.systemPrompt.trim())
                    appendLine()
                    append("工具：")
                    append(
                        profile.effectiveTools().takeIf { it.isNotEmpty() }?.joinToString(", ")
                            ?: stringResource(R.string.subagent_tools_all_non_gui),
                    )
                },
                onDismiss = { inspectedProfile = null },
            )
        }
    }
}

@Composable
private fun PrefIcon(image: ImageVector, tint: Color) {
    Icon(
        imageVector = image,
        contentDescription = null,
        modifier = Modifier.size(22.dp).padding(end = 2.dp),
        tint = tint,
    )
}

@Composable
private fun StepperArrow(image: ImageVector, onClick: () -> Unit) {
    Icon(
        imageVector = image,
        contentDescription = null,
        modifier = Modifier
            .size(22.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        tint = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.45f),
    )
}

private fun iconFor(name: String): ImageVector = when (name) {
    "coder" -> Icons.Rounded.Code
    "plan" -> Icons.Rounded.AccountTree
    else -> Icons.Rounded.Explore
}

private fun colorFor(name: String): Color = when (name) {
    "coder" -> Color(0xFF2FB344)
    "plan" -> Color(0xFFF08A24)
    else -> Color(0xFF2F7BF6)
}

/** 思考强度档位：空 = 跟随模型默认，其余按 ReasoningEffort 的 wire 值。 */
private val reasoningSteps = listOf("", "minimal", "low", "medium", "high")

private fun stepReasoning(current: String, delta: Int): String {
    val index = reasoningSteps.indexOf(current).let { if (it < 0) 0 else it }
    return reasoningSteps[(index + delta).coerceIn(0, reasoningSteps.lastIndex)]
}

@Composable
private fun reasoningLabel(value: String): String = when (value) {
    "minimal" -> stringResource(R.string.subagent_reasoning_minimal)
    "low" -> stringResource(R.string.subagent_reasoning_low)
    "medium" -> stringResource(R.string.subagent_reasoning_medium)
    "high" -> stringResource(R.string.subagent_reasoning_high)
    else -> stringResource(R.string.subagent_reasoning_default)
}
