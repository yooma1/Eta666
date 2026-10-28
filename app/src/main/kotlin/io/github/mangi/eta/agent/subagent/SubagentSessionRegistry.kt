package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient

/**
 * `subagent(resume=...)` 的上下文仓库。
 *
 * 只保内存不落盘：子代理上下文是"本次对话里接着聊"的东西，它的原始结论已经回到主会话，
 * 落盘等于把同一段内容存两份，还会在会话删除后变成读不到的孤儿。
 *
 * LRU 上限 16 是为了兜住"一路 resume 把窗口撑爆"——真正需要长跑的活该由主代理自己接。
 */
internal object SubagentSessionRegistry {
    private const val MAX_SESSIONS = 16

    data class Session(
        val id: String,
        val type: String,
        val description: String,
        val profileName: String,
        val transcript: MutableList<AgentModelClient.ConversationMessage> = mutableListOf(),
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?): Boolean =
            size > MAX_SESSIONS
    }

    fun open(
        id: String,
        type: String,
        description: String,
        profileName: String,
    ): Session = synchronized(sessions) {
        val session = Session(id = id, type = type, description = description, profileName = profileName)
        sessions[id] = session
        session
    }

    fun find(id: String): Session? = synchronized(sessions) {
        sessions[id]?.also { it.updatedAt = System.currentTimeMillis() }
    }

    fun snapshot(id: String): List<AgentModelClient.ConversationMessage> =
        find(id)?.transcript?.toList() ?: emptyList()

    fun updateTranscript(id: String, transcript: List<AgentModelClient.ConversationMessage>) {
        synchronized(sessions) {
            sessions[id]?.let { session ->
                session.transcript.clear()
                session.transcript.addAll(transcript)
                session.updatedAt = System.currentTimeMillis()
            }
        }
    }

    fun clear() = synchronized(sessions) { sessions.clear() }
}
