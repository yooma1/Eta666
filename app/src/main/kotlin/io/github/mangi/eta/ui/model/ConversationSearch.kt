package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/**
 * 会话内容搜索：对完整消息流做大小写不敏感匹配。
 * 标题与预览的匹配由调用方负责；notice 文案由调用方按当前语言注入。
 */
internal fun AgentChatHomeUiState.contentMatches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean {
    if (query.isBlank()) return true
    return messages.any { message -> message.matches(query, noticeText) }
}

private fun AgentChatMessageUi.matches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean = searchableTexts(noticeText).any { text -> text.contains(query, ignoreCase = true) }

/**
 * 一条消息里所有可搜索的文本片段。会话内搜索与会话导航共用同一套口径，
 * 避免「搜得到」和「目录里列不出来」对不上。
 */
internal fun AgentChatMessageUi.searchableTexts(
    noticeText: (SystemNoticeCode) -> String,
): List<String> = when (this) {
    is UserMessageUi -> {
        val prompt = AgentFileReferencePromptCodec.parse(content)
        listOf(prompt.request) + prompt.references.flatMap { reference ->
            listOf(reference.displayName, reference.absolutePath)
        }
    }

    is AgentMessageUi -> listOf(content)

    is ThinkingMessageUi -> listOf(content)

    is ToolActivityMessageUi -> listOfNotNull(
        toolName,
        command,
        argumentsSummary,
        resultSummary,
    )

    is ToolSummaryMessageUi -> tools

    is SystemNoticeMessageUi -> listOfNotNull(noticeText(code), detail)

    else -> emptyList()
}

/** 会话搜索命中定位：打开会话后滚动到命中消息并临时高亮；消费后由调用方置空。 */
@Immutable
internal data class SearchJumpUiState(
    val messageId: String,
    /** 递增令牌：同一条消息被反复命中时也要能再次触发跳转。 */
    val token: Long,
)

/**
 * 第一条命中消息的 id。仅标题或预览命中、消息内容未命中时返回 null，
 * 此时调用方只打开会话、不做跳转。
 */
internal fun AgentChatHomeUiState.firstMatchMessageId(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): String? {
    if (query.isBlank()) return null
    return messages.firstOrNull { message -> message.matches(query, noticeText) }?.id
}
