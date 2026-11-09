package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    /** 侧栏分类；空表示用户还没建过分类，此时按时间分组。 */
    val categories: List<ConversationCategoryUi> = emptyList(),
    /** 会话 → 分类 id；不在映射里的会话归「未分类」。 */
    val categoryAssignments: Map<String, String> = emptyMap(),
)

/** 侧栏分类（与 data 层 ConversationCategory 同形，界面层不直接依赖数据库类型）。 */
@Immutable
data class ConversationCategoryUi(
    val id: String,
    val name: String,
    val conversationCount: Int,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val runStatus: String? = null,
    val characterName: String? = null,
    val characterAvatarPath: String? = null,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
