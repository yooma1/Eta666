package io.github.mangi.eta.data.repository

/**
 * 模型自适应协议：把「当前会话用的是哪个模型」翻译成「这一轮注入哪份内置协议」。
 *
 * 三方关系（缺一不可，混起来就不可解释）：
 * - **规则**（本文件）：模型名 → 库条目 id，纯字符串、可单测、不看 IO
 * - **模式**（[AgentUserPromptMode]）：用户选的生效范围（关闭 / 全局 / 仅逆向）
 * - **开关**（`userPromptEnabled`）：总闸，关掉什么都不注入
 *
 * 与用户自己那份 `prompts/破甲记忆.md` 的协同：
 * - 模式 = [AgentUserPromptMode.Global]：用户自己的文件优先；它为空时才退回模型绑定
 * - 模式 = [AgentUserPromptMode.ModelBinding]：模型绑定优先；映射不到（或库里没有）时退回用户文件
 *
 * 两种顺序下都**只注入一份**——两份整篇 system 提示叠在一起，冲突条款各说各话，
 * 模型按后出现的执行，等于随机行为。
 */
internal object AgentUserPromptResolver {

    internal data class Resolution(
        val snapshot: AgentUserPromptSnapshot,
        /** 实际生效的那份在哪里：`prompts/` 磁盘文件，或随包协议库。 */
        val source: Source,
        /** 库里那一条的 id；来源是用户文件时为空。 */
        val libraryEntryId: String? = null,
        /** 写给日志/UI 的一句话理由，便于复现「为什么这轮是这个协议」。 */
        val reason: String = "",
    ) {
        val available: Boolean get() = snapshot.available
    }

    internal enum class Source(val label: String) {
        /** `filesDir/prompts/` 下用户自己的文件（含破甲记忆）。 */
        UserFile("prompts/ 用户文件"),

        /** 随包内置协议库（移植自 yooma1/jailbreaks）。 */
        Library("内置协议库"),

        /** 什么都没注入。 */
        None("未注入"),
    }

    /**
     * @param mode 用户选的生效范围
     * @param model 当前会话模型的 `modelId`（已绑定的会话传会话模型，否则传默认模型）
     * @param displayName 模型显示名，仅用于提高关键词命中率
     * @param userPrompt `prompts/` 快照（未开启或读失败时传 EMPTY）
     * @param libraryEntry 由 [AgentUserPromptModelBinding] 解析出的条目；null 表示映射不到
     */
    fun resolve(
        mode: AgentUserPromptMode,
        model: String,
        displayName: String = "",
        userPrompt: AgentUserPromptSnapshot,
        libraryEntry: AgentUserPromptLibraryEntry? = null,
        librarySnapshot: AgentUserPromptSnapshot? = null,
    ): Resolution {
        val boundId = libraryEntry?.id
        val library = librarySnapshot?.takeIf { it.available }
        val userFile = userPrompt.takeIf { it.available }
        val modelLabel = model.ifBlank { "未知模型" }

        fun fromLibrary(reason: String): Resolution? =
            if (boundId != null && library != null) {
                Resolution(library, Source.Library, boundId, reason)
            } else {
                null
            }

        fun fromUserFile(reason: String): Resolution? =
            if (userFile != null) {
                Resolution(userFile, Source.UserFile, null, reason)
            } else {
                null
            }

        val noBindingReason =
            if (boundId == null) "$modelLabel 没有内置协议绑定" else "内置协议库缺少 $boundId"

        return when (mode) {
            AgentUserPromptMode.Off -> Resolution(
                AgentUserPromptSnapshot.EMPTY, Source.None, null, "协议未启用",
            )

            // 用户自己那份是「我怎么指挥」的原始意志，显式装配过就压过模型绑定。
            AgentUserPromptMode.Global ->
                fromUserFile("全局模式：使用 prompts/ 用户文件")
                    ?: fromLibrary("全局模式：用户文件为空，改用 ${boundId ?: "模型绑定"}")
                    ?: Resolution(AgentUserPromptSnapshot.EMPTY, Source.None, null, noBindingReason)

            AgentUserPromptMode.ModelBinding ->
                fromLibrary("模型绑定：$modelLabel → $boundId")
                    ?: fromUserFile("模型绑定：$noBindingReason，改用 prompts/ 用户文件")
                    ?: Resolution(AgentUserPromptSnapshot.EMPTY, Source.None, null, noBindingReason)

            // 逆向模式与模型绑定同效：逆向下模型协议才真正决定交付形状。
            AgentUserPromptMode.ReverseOnly ->
                fromLibrary("仅逆向：$modelLabel → $boundId")
                    ?: fromUserFile("仅逆向：$noBindingReason，改用 prompts/ 用户文件")
                    ?: Resolution(AgentUserPromptSnapshot.EMPTY, Source.None, null, noBindingReason)
        }
    }
}
