package io.github.mangi.eta.data.repository

/**
 * 破甲记忆的单一注入模板：`assets/user_protocol/pojia-template.md`。
 *
 * 为什么是模板而不是 45 选 1：协议库里每份都带自己的一套身份与禁令，选一份 = 换一次身份；
 * 而「怎么干活」的工程契约对任何模型都一样。所以共性抽成唯一骨架，只有身份段随模型变——
 * 这样换模型不换缓存前缀主体，也不会出现两份互相矛盾的禁令。
 *
 * 渲染是纯字符串替换 + SHA-256，不解析 Markdown：模板里出现的 `{{7*7}}` 之类的东西属于
 * 工程契约正文（注入测试样例），不是占位符——因此只替换白名单里的四个键。
 */
internal object AgentUserPromptTemplate {

    const val ASSET_PATH = "user_protocol/pojia-template.md"

    const val KEY_IDENTITY = "{{MODEL_IDENTITY}}"
    const val KEY_MODEL = "{{MODEL_ID}}"
    const val KEY_CUTOFF = "{{KNOWLEDGE_CUTOFF}}"
    const val KEY_NOTES = "{{MODEL_NOTES}}"

    /** 白名单：只有这四个键会被替换，其余 `{{...}}` 原样保留。 */
    internal val keys = listOf(KEY_IDENTITY, KEY_MODEL, KEY_CUTOFF, KEY_NOTES)

    internal data class Fill(
        val identity: String,
        val model: String,
        val knowledgeCutoff: String,
        val notes: String,
    )

    internal data class Rendered(
        val content: String,
        val unresolved: List<String>,
    ) {
        val complete: Boolean get() = unresolved.isEmpty()
    }

    /**
     * 渲染模板。
     *
     * [Fill.notes] 为空时把那一段整体收敛成一句「无额外补充」，避免渲染出一个空标题段、
     * 让模型以为有内容被截掉了。
     */
    internal fun render(template: String, fill: Fill): Rendered {
        val notes = fill.notes.trim().ifEmpty { "无额外补充条款。" }
        val rendered = template
            .replace(KEY_IDENTITY, fill.identity.trim())
            .replace(KEY_MODEL, fill.model.trim())
            .replace(KEY_CUTOFF, fill.knowledgeCutoff.trim().ifEmpty { "未声明" })
            .replace(KEY_NOTES, notes)
        val unresolved = keys.filter { rendered.contains(it) }
        return Rendered(rendered, unresolved)
    }

    /** 渲染结果是否还有白名单外的占位符（排查「模板改了但渲染器没跟上」）。 */
    internal fun strayPlaceholders(content: String): List<String> =
        Regex("\\{\\{[A-Z_]{3,}\\}\\}")
            .findAll(content)
            .map { it.value }
            .filter { it in keys }
            .toList()
}
