package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** live result 与 outbox recovery 共用的 history 幂等提交点。 */
internal object AgentRuntimeHistoryReducer {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ): Outcome {
        if (runId in state.appliedRuntimeRunIds) {
            return Outcome(state, alreadyApplied = true)
        }
        val validSnapshot = snapshot?.takeIf { it.operationId == runId }
        val consumedSupplements = maxOf(additions.count { it.role == "user" }, validSnapshot?.consumedSupplementCount ?: 0)
        val pendingSupplements = if (!retainPendingSupplements) emptyList() else state.messages.filterIsInstance<UserMessageUi>()
            .filter { it.id.startsWith("user-$runId-supplement-") }
            .sortedBy { it.id.substringAfterLast('-').toIntOrNull() ?: 0 }
            .drop(consumedSupplements)
            .map { AgentModelClient.buildUserHistoryMessage(it.content, emptyList()).copy(messageId = it.id) }
        val history = if (validSnapshot != null) {
            val currentTurns = state.history.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
            val extra = (currentTurns - validSnapshot.consumedUserTurns).coerceAtLeast(0)
            val users = state.history.indices.filter { state.history[it].role == "user" }
            val laterHistory = if (extra > 0) state.history.drop(users.takeLast(extra).first()) else emptyList()
            // 快照与 transcript 独立落盘；崩溃时仍须接上快照之后已完成的批次。
            val covered = validSnapshot.consumedTranscriptMessages ?: additions.size
            require(covered in 0..additions.size || additions.isEmpty()) { "Invalid context transcript boundary" }
            validSnapshot.messages + additions.drop(covered) + laterHistory + pendingSupplements
        } else state.history + additions + pendingSupplements
        // 生图结果必须变成可见的 UI 消息：runtime 的 transcript 只喂 history/journal，
        // 从不写 state.messages，所以图片会"生成了但看不到"。按 messageId 前缀挑出来追加。
        val generatedMessages = additions
            .filter { it.messageId.startsWith(GENERATED_IMAGE_ID_PREFIX) }
            .map { message ->
                UserMessageUi(
                    id = message.messageId,
                    content = message.content,
                    images = imageReferencesOf(message),
                )
            }
        val knownIds = state.messages.mapTo(mutableSetOf()) { it.id }
        val newMessages = generatedMessages.filter { it.id !in knownIds }

        return Outcome(
            state = state.copy(
                messages = if (newMessages.isEmpty()) state.messages else state.messages + newMessages,
                journal = boundedJournal(
                    state.journal.ifEmpty { state.history } + additions + pendingSupplements,
                ),
                history = history,
                appliedRuntimeRunIds = state.appliedRuntimeRunIds + runId,
            ),
            alreadyApplied = false,
        )
    }

    fun wasApplied(state: AgentChatHomeUiState, runId: String): Boolean =
        runId in state.appliedRuntimeRunIds

    private const val GENERATED_IMAGE_ID_PREFIX = "generated-image-"

    /** contentJson 里的 data URL 就是 UI 显示要用的引用，直接抽出来给 MessageImageRow。 */
    private fun imageReferencesOf(message: AgentModelClient.ConversationMessage): List<String> {
        val raw = message.contentJson
        if (raw.isBlank()) return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.optJSONObject("image_url")?.optString("url")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
