package io.github.mangi.eta.data.repository

/**
 * 「模型 → 内置协议」静态绑定表（纯字符串规则，可单测）。
 *
 * 只做一件事：把当前会话模型名（`provider/model` 已归一成小写）映射到
 * [AgentUserPromptLibrary] 的一条；映射不到就返回 null，调用方回落用户自己的
 * `prompts/破甲记忆.md`。
 *
 * 为什么是静态表而不是 LLM 自选：模型名与条目的对应关系是稳定事实（gemini-4 → Gemini-4-Argon），
 * 猜测会让同一个会话在两轮之间换协议，直接把请求前缀缓存打废。
 *
 * 规则顺序即优先级：越具体的族名写越前面，兜底族名（如裸 `deepseek`）写在最后。
 */
internal object AgentUserPromptModelBinding {

    internal data class Rule(val keywords: List<String>, val entryId: String)

    /** 供单测与诊断用的规则快照。 */
    internal val rules: List<Rule> = listOf(
        Rule(
            keywords = listOf("bigpickle", "big-pickle", "vanta"),
            entryId = "0x8d-jailbreaks__bigpickle-opencode",
        ),
        Rule(
            keywords = listOf("deepseek-v4.1", "deepseek-4.1", "deepseek-v4-1", "deepseek4.1"),
            entryId = "deepseek-4-1",
        ),
        Rule(
            keywords = listOf("deepseek-v4", "deepseek-reasoner", "deepseek-r1"),
            entryId = "deepseek-v4",
        ),
        Rule(
            keywords = listOf("deepseek-v3", "deepseek-chat"),
            entryId = "deepseek-v3",
        ),
        Rule(
            keywords = listOf("deepseek"),
            entryId = "deepseek-v4",
        ),
        Rule(
            keywords = listOf("gemini-4", "gemini4"),
            entryId = "gemini-4-argon",
        ),
        Rule(
            keywords = listOf("gemini-3.1", "gemini-3-1"),
            entryId = "gemini-3.1-pro",
        ),
        Rule(
            keywords = listOf("gemini"),
            entryId = "gemini-4-argon",
        ),
        Rule(
            keywords = listOf("grok-4.7", "grok-47"),
            entryId = "grok-4.7",
        ),
        Rule(
            keywords = listOf("grok-fast"),
            entryId = "0x8d-jailbreaks__grok-fast-v2",
        ),
        Rule(
            keywords = listOf("grok-build", "grok-code"),
            entryId = "grok-build",
        ),
        Rule(
            keywords = listOf("grok"),
            entryId = "0x8d-jailbreaks__grok-fast-v2",
        ),
        Rule(
            keywords = listOf("glm-5.3-flash", "glm-5-3-flash"),
            entryId = "glm-5-3-flash",
        ),
        Rule(
            keywords = listOf("glm-5.3", "glm-zcode-5.3", "zcode-5.3"),
            entryId = "glm-zcode-5.3",
        ),
        Rule(
            keywords = listOf("glm-5.2", "glm-zcode-5.2", "zcode-5.2"),
            entryId = "glm-zcode-5.2",
        ),
        Rule(
            keywords = listOf("glm"),
            entryId = "glm-5.2",
        ),
        Rule(
            keywords = listOf("kimi-k3.1", "kimik3.1", "kimi-3-1"),
            entryId = "kimik3.1",
        ),
        Rule(
            keywords = listOf("kimi-k3", "kimi-3"),
            entryId = "kimi-k3-agents",
        ),
        Rule(
            keywords = listOf("kimi-k2", "kimi-2", "kimi2", "kimi-standard"),
            entryId = "kimi-k2",
        ),
        Rule(
            keywords = listOf("kimi"),
            entryId = "kimi-k2",
        ),
        Rule(
            keywords = listOf("claude-opus-5", "opus-5", "opus-v2", "opus"),
            entryId = "opus-v2",
        ),
        Rule(
            keywords = listOf("claude-sonnet-5", "sonnet-5", "sonnet-v2", "sonnet"),
            entryId = "sonnet-v2",
        ),
        Rule(
            keywords = listOf("qwen-3.8", "qwen-3-8", "qwen3.8", "qwen3-8"),
            entryId = "qwen-3.8",
        ),
        Rule(
            keywords = listOf("qwen-3.7", "qwen-3-7", "qwen3.7", "qwen3-7"),
            entryId = "qwen-3.7",
        ),
        Rule(
            keywords = listOf("qwen", "qwq"),
            entryId = "qwen-3.8",
        ),
        Rule(
            keywords = listOf("gpt-oss"),
            entryId = "gpt-oss-20b",
        ),
        Rule(
            keywords = listOf("gpt-5", "gpt5", "chatgpt"),
            entryId = "0x8d-jailbreaks__chatgpt-free-tier",
        ),
        Rule(
            keywords = listOf("gpt-4"),
            entryId = "0x8d-jailbreaks__gpt-new",
        ),
        Rule(
            keywords = listOf("gemma-4", "gemma4"),
            entryId = "gemma4",
        ),
        Rule(
            keywords = listOf("mistral-large-4", "mistral-large"),
            entryId = "mistral-large-4",
        ),
        Rule(
            keywords = listOf("mistral-small", "duck.ai", "duckduckgo"),
            entryId = "0x8d-jailbreaks__mistral-small-4-duck.ai",
        ),
        Rule(
            keywords = listOf("nemotron"),
            entryId = "nemotron-3-ultra",
        ),
        Rule(
            keywords = listOf("step-5", "step5"),
            entryId = "step-5-preview",
        ),
        Rule(
            keywords = listOf("longcat"),
            entryId = "longcat-agents",
        ),
        Rule(
            keywords = listOf("mimo"),
            entryId = "mimo-2.6-agents",
        ),
        Rule(
            keywords = listOf("muse-wonder"),
            entryId = "muse-wonder",
        ),
        Rule(
            keywords = listOf("muse-spark"),
            entryId = "muse-spark-1-3-v2",
        ),
        Rule(
            keywords = listOf("muse"),
            entryId = "muse-ai-agents",
        ),
        Rule(
            keywords = listOf("space-bunny", "spacebunny"),
            entryId = "spacebunny-agents",
        ),
        Rule(
            keywords = listOf("hy4"),
            entryId = "hy4-agents",
        ),
        Rule(
            keywords = listOf("beam"),
            entryId = "beam",
        ),
    )

    /**
     * 归一化模型标识：小写、去空白差异。
     *
     * **不**在这里截断别名——`deepseek-v4.1-flash-1017` 的降级由 [degradeChain] 逐段做，
     * 一次性截断会把 `glm-5-3-flash` 误判成 `glm-5`。
     */
    internal fun normalize(model: String, displayName: String = ""): String =
        (model.trim() + " " + displayName.trim()).lowercase()

    /** 匹配层级，用于页面上说清「为什么是这一份」。 */
    internal enum class MatchKind {
        /** 完整模型名命中。 */
        EXACT,

        /** 砍掉尾部段（-1017 / -flash）后命中。 */
        DEGRADED,

        /** 只剩裸系列名时命中（deepseek / glm / qwen）。 */
        FAMILY,
    }

    internal data class Match(
        val entryId: String,
        /** 真正参与匹配的那一级（降级后的形态）。 */
        val matchedOn: String,
        val kind: MatchKind,
        /** 向下降了几级：0 = 全文精确命中。 */
        val levelsDropped: Int,
    )

    private val SEGMENT_SEPARATORS = Regex("""[-_/:\s]+""")

    /**
     * 常见服务商前缀。只用于剥掉降级链的首段，不影响关键词表——
     * 把它们留在链里会让 `anthropic/claude-sonnet-5` 从 `anthropic-claude-sonnet-5` 开始降级，
     * 白白多掉一级，且 `deepseek` 之类的裸词有可能在前缀里误命中。
     */
    private val PROVIDER_PREFIXES = setOf(
        "openai", "anthropic", "google", "deepseek", "moonshot", "moonshotai", "zhipu",
        "z-ai", "zai", "alibaba", "qwen", "xai", "meta", "mistral", "nvidia", "opencode",
        "openrouter", "ollama", "together", "groq", "siliconflow", "modelscope", "vertex",
    )
    private val PATCH_SUFFIX = Regex("""\.\d+$""")

    /**
     * 模型名切段：`deepseek-v4.1-flash-1017` → `[deepseek, v4.1, flash, 1017]`。
     *
     * 服务商前缀（`openai/`、`moonshotai/`）会被 `_` `/` `:` 一起切开，
     * 于是它自然变成降级链尾部的段，不影响具体型号优先命中。
     */
    internal fun segments(model: String): List<String> =
        model.lowercase().split(SEGMENT_SEPARATORS).filter { it.isNotBlank() }

    /**
     * 生成从具体到宽泛的降级链（去重、保序）。
     *
     * `deepseek-v4.1-flash-1017` →
     * 1. `deepseek-v4.1-flash-1017`
     * 2. `deepseek-v4.1-flash`
     * 3. `deepseek-v4.1`
     * 4. `deepseek-v4`（剥掉补丁号 .1）
     * 5. `deepseek`
     *
     * 版本段额外补一条剥补丁号的形态，是为了让 `v4.1` 在「比裸系列更具体」的层级上存在，
     * 否则 `deepseek-v4.1-bold` 会跳过 v4.1 直接掉到 `deepseek`。
     */
    internal fun degradeChain(model: String): List<String> {
        // 服务商前缀是噪声：`anthropic/claude-sonnet-5` 不该从 `anthropic-claude-sonnet-5` 开始降级。
        // 只在**显式带路径分隔符**时剥；裸词 `deepseek-v4.1-flash-1017` 的 `deepseek` 是主名本体，不能剥。
        val explicitPrefix = model.trim().takeIf { it.contains('/') || it.contains(':') }
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.lowercase()
            ?.trim()
        val allParts = segments(model)
        val parts = allParts.dropWhile { explicitPrefix != null && it == explicitPrefix }
        if (parts.isEmpty()) return emptyList()
        val chain = LinkedHashSet<String>()
        for (size in parts.size downTo 1) {
            val prefix = parts.take(size)
            chain += prefix.joinToString("-")
            val last = prefix.last()
            val stripped = last.replace(PATCH_SUFFIX, "")
            if (stripped != last && stripped.isNotBlank()) {
                chain += (prefix.dropLast(1) + stripped).joinToString("-")
            }
        }
        return chain.toList()
    }

    /**
     * 段级命中：`candidate` 等于关键词，或以 `关键词-` / `关键词.` 开头。
     *
     * 刻意不用 `contains`：裸 `deepseek` 若用包含判定，会在第一级就吃掉
     * `deepseek-v4.1-flash-1017`，具体型号永远轮不到。
     */
    private fun matches(candidate: String, keyword: String): Boolean {
        if (candidate == keyword) return true
        // 降级链用「前缀段」判定：candidate 是砍尾后的形态，keyword 可能比它短。
        if (candidate.startsWith("$keyword-") || candidate.startsWith("$keyword.")) return true
        val nc = candidate.replace('.', '-')
        val nk = keyword.replace('.', '-')
        return nc == nk || nc.startsWith("$nk-")
    }

    /**
     * 精确命中：整串**就是**关键词（或关键词带尾部段的完整形态），不做前缀放宽。
     *
     * `deepseek-v4.1-flash-1017` 以 `deepseek-v4.1` 开头，但它是「降 1 级」才该命中的形态，
     * 若在前缀判定里就算精确，降级链的意义就没了。
     */
    private fun exactMatches(candidate: String, keyword: String): Boolean =
        candidate == keyword || candidate.replace('.', '-') == keyword.replace('.', '-')

    /** 前缀命中：整串以关键词为开头（`deepseek-v4.1-flash` 对 `deepseek-v4.1`）。 */
    private fun prefixMatches(candidate: String, keyword: String): Boolean {
        val nc = candidate.replace('.', '-')
        val nk = keyword.replace('.', '-')
        return nc.startsWith("$nk-")
    }

    /** 剥掉服务商前缀后的整串形态，作为「精确命中」的唯一比较基准。 */
    private fun fullName(model: String): String = fullNameFor(model)

    internal fun fullNameFor(model: String): String {
        val explicitPrefix = model.trim().takeIf { it.contains('/') || it.contains(':') }
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.lowercase()
            ?.trim()
        return segments(model)
            .dropWhile { explicitPrefix != null && it == explicitPrefix }
            .joinToString("-")
    }

    /**
     * 关键词是否足够具体，能支撑「精确命中」。
     *
     * 裸系列名（`deepseek` / `glm` / `qwen`）只能做降级兜底：它们会把任何同族别名都吃成 EXACT，
     * 于是「降到第几级」这条信息就没了，页面上也说不清到底怎么命中的。
     */
    private fun isSpecific(keyword: String): Boolean =
        keyword.any { it.isDigit() } || keyword.contains('-') || keyword.contains('.')

    /**
     * 沿降级链逐级找规则，第一个命中的层级胜出。
     *
     * 主名解析不到时才用 displayName 再走一遍：显示名常带空格与中文，
     * 只能当兜底，不能喧宾夺主。
     */
    internal fun resolveDetailed(model: String, displayName: String = ""): Match? {
        val full = fullName(model)
        // 精确命中 = 整串**完全等于**关键词之一，不做前缀放宽：
        // `deepseek-v4.1-flash-1017` 以 `deepseek-v4.1` 开头，但那是降 1 级才该命中的形态；
        // 若在前缀判定里就算精确，降级链就没有意义了。
        if (full.isNotEmpty()) {
            // 1) 整串完全等于某个具体关键词 → EXACT
            val exactHit = rules.firstOrNull { rule ->
                rule.keywords.any { keyword -> exactMatches(full, keyword) && isSpecific(keyword) }
            }
            if (exactHit != null) {
                return Match(exactHit.entryId, full, MatchKind.EXACT, 0)
            }
        }
        val chain = degradeChain(model).ifEmpty { degradeChain(displayName) }
        if (chain.isEmpty()) return null
        for ((index, candidate) in chain.withIndex()) {
            // 链上只接受「整串等长」命中。
            //
            // 原因：降级链本身已经负责砍段，若这里再允许关键词做前缀命中，
            // `deepseek-v4.1-flash-1017` 的第 0 级就会被 `deepseek-v4.1` 以「前缀」吃掉，
            // 于是「降了几级」永远是 0——链的意义完全消失。前缀放宽只保留给
            // displayName 兜底这一步，见下方 fallback 分支。
            val hit = rules.firstOrNull { rule ->
                rule.keywords.any { keyword -> exactMatches(candidate, keyword) }
            } ?: continue
            // EXACT 只能是「整串原样命中」：服务商前缀被剥掉后第 0 级已是砍尾形态，
            // 那种情况属于 DEGRADED，不能算精确。
            val kind = when {
                !candidate.contains('-') && !candidate.contains('.') -> MatchKind.FAMILY
                index >= chain.lastIndex -> MatchKind.FAMILY
                else -> MatchKind.DEGRADED
            }
            return Match(hit.entryId, candidate, kind, index)
        }
        return null
    }

    /** 返回命中的库条目 id；没有命中返回 null（= 用用户的破甲记忆）。 */
    internal fun resolve(model: String, displayName: String = ""): String? =
        resolveDetailed(model, displayName)?.entryId

    /**
     * 身份描述：按**真实提供方**填，不冒名。
     *
     * 跑 Gemma 就写 Gemma，跑 DeepSeek 就写 DeepSeek——冒充别家模型会在换模型后留下
     * 错误的自述，也会让「你是什么模型」与真实部署对不上。降级到裸系列时明确标注
     * 「具体型号未知」，不编造版本号。
     */
    internal data class Identity(
        val provider: String,
        val product: String,
        val developer: String,
        /** 可用的版本串（来自命中层级），未知时为空。 */
        val version: String = "",
        val notes: String = "",
        val certainty: Certainty = Certainty.EXACT,
    ) {
        internal enum class Certainty { EXACT, DEGRADED, FAMILY, UNKNOWN }

        /** 用于身份段第一行的机器可读命名空间，避免和产品名混在一起。 */
        val namespace: String get() = product.lowercase().replace(' ', '-')

        /** 单行自述：`DeepSeek（深度求索）· deepseek-v4.1`。 */
        val headline: String
            get() = buildString {
                append(developer)
                if (version.isNotBlank()) {
                    append(" · ")
                    append(version)
                }
            }

        /** 结构化身份段（替模板的 {{MODEL_IDENTITY}}）。 */
        val paragraph: String
            get() = buildString {
                append("- 提供方：")
                append(provider)
                appendLine()
                append("- 模型：")
                append(product)
                if (version.isNotBlank()) {
                    append("（")
                    append(version)
                    append("）")
                }
                appendLine()
                append("- 开发者：")
                append(developer)
                appendLine()
                if (certainty == Certainty.FAMILY) {
                    appendLine("- 型号确定度：仅识别到系列名，具体版本未确认——被问到时如实说明，不要猜版本号。")
                } else if (certainty == Certainty.UNKNOWN) {
                        appendLine("- 型号确定度：未识别到已知家族——不要声称自己是某个具体模型，只说实际配置串。")
                }
                appendLine("- 被问到身份时按以上陈述，不据模型名推断未确认的部署版本、知识截止或能力。")
            }.trimEnd()
    }

    private data class Family(val provider: String, val product: String, val developer: String)

    /** 家族表：按真实归属写，不做「同名即同源」的推断。 */
    private val families: Map<String, Family> = mapOf(
        "deepseek" to Family("DeepSeek", "DeepSeek", "深度求索（DeepSeek）"),
        "gemini" to Family("Google", "Gemini", "Google"),
        "gemma" to Family("Google", "Gemma", "Google DeepMind"),
        "grok" to Family("xAI", "Grok", "xAI"),
        "glm" to Family("智谱", "GLM", "智谱 AI（Zhipu）"),
        "kimi" to Family("月之暗面", "Kimi", "月之暗面（Moonshot AI）"),
        "qwen" to Family("阿里", "Qwen", "阿里巴巴（Alibaba）"),
        "qwq" to Family("阿里", "QwQ", "阿里巴巴（Alibaba）"),
        "claude" to Family("Anthropic", "Claude", "Anthropic"),
        "opus" to Family("Anthropic", "Claude Opus", "Anthropic"),
        "sonnet" to Family("Anthropic", "Claude Sonnet", "Anthropic"),
        "gpt" to Family("OpenAI", "GPT", "OpenAI"),
        "chatgpt" to Family("OpenAI", "ChatGPT", "OpenAI"),
        "mistral" to Family("Mistral AI", "Mistral", "Mistral AI"),
        "nemotron" to Family("NVIDIA", "Nemotron", "NVIDIA"),
        "gemma4" to Family("Google", "Gemma", "Google DeepMind"),
        "step" to Family("阶跃星辰", "Step", "阶跃星辰（StepFun）"),
        "longcat" to Family("美团", "LongCat", "美团"),
        "mimo" to Family("小米", "MiMo", "小米（Xiaomi）"),
        "muse" to Family("OpenCode Zen", "Muse", "OpenCode Zen"),
        "spacebunny" to Family("OpenCode Zen", "Space Bunny", "OpenCode Zen"),
        "bigpickle" to Family("OpenCode Zen", "Big Pickle", "OpenCode Zen"),
        "vanta" to Family("OpenCode Zen", "Big Pickle", "OpenCode Zen"),
        "hy4" to Family("未知", "HY4", "未知"),
        "beam" to Family("未知", "Beam", "未知"),
        "gpt-oss" to Family("OpenAI", "GPT-OSS", "OpenAI"),
    )

    /**
     * 解析身份：先走精确命中，再走降级链；降级到裸系列时标 [Identity.Certainty.FAMILY]。
     *
     * 与 [resolveDetailed] 共用同一条降级链——「选协议」与「填身份」必须是同一个判定，
     * 否则会出现「协议说自己是 Gemma、身份段说自己是 DeepSeek」这种自相矛盾。
     */
    internal fun identityOf(model: String, displayName: String = ""): Identity {
        val match = resolveDetailed(model, displayName)
        val probe = (match?.matchedOn ?: fullNameFor(model)).ifBlank { displayName.lowercase() }
        val familyKey = families.keys
            .sortedByDescending { it.length }
            .firstOrNull { key -> matches(probe, key) || probe.contains(key) }
        val family = familyKey?.let { families[it] }

        if (family == null) {
            return Identity(
                provider = "未知",
                product = model.ifBlank { "未知模型" },
                developer = "未知提供方",
                version = "",
                notes = "本会话未识别到已知模型家族；身份按实际配置串陈述，不冒充已知产品。",
                certainty = Identity.Certainty.UNKNOWN,
            )
        }

        // 版本串：命中层级里去掉家族前缀的剩余部分，例如 deepseek-v4-1-flash → v4-1-flash。
        val remainder = probe
            .removePrefix(familyKey.orEmpty())
            .trim('-', '.', '_')
        return Identity(
            provider = family.provider,
            product = family.product,
            developer = family.developer,
            version = remainder,
            notes = "",
            certainty = when {
                match == null -> Identity.Certainty.UNKNOWN
                match.kind == MatchKind.FAMILY -> Identity.Certainty.FAMILY
                match.kind == MatchKind.DEGRADED -> Identity.Certainty.DEGRADED
                else -> Identity.Certainty.EXACT
            },
        )
    }

    /** 给日志/UI 的一句话解释：命中哪一级、降到第几级。 */
    internal fun describe(match: Match, model: String): String = when (match.kind) {
        MatchKind.EXACT -> "$model 精确命中 ${match.entryId}"
        MatchKind.DEGRADED ->
            "$model 降级 ${match.levelsDropped} 级到 ${match.matchedOn} → ${match.entryId}"
        MatchKind.FAMILY -> "$model 无具体型号，回落到裸系列 ${match.matchedOn} → ${match.entryId}"
    }
}
