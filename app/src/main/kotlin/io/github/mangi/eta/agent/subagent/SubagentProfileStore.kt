package io.github.mangi.eta.agent.subagent

import java.io.File

/**
 * `agents/<name>.md` 档案仓库。
 *
 * 文件格式（与模型工具 `subagent_profile` 的 save 产物一致）：
 * ```
 * ---
 * name: bill-analyzer
 * description: 账单流水归类与异常项定位
 * whenToUse: 需要跨月对账时
 * tools: search_bills, summarize_bills
 * disallowedTools: terminal
 * ---
 *
 * 你是账单分析 Subagent……
 * ```
 *
 * 校验规则全部前置：名字 kebab-case 2~48、description 与 prompt 非空、不能覆盖内置预设、
 * 拒写符号链接。这些不是"洁癖"——档案会被原样喂给模型当系统提示词，越界写文件等于
 * 让 `name` 里的 `../` 去改主程序数据。
 */
internal class SubagentProfileStore(private val rootDir: File) {

    val dir: File get() = File(rootDir, "agents")

    fun all(): List<SubagentProfile> {
        val merged = LinkedHashMap<String, SubagentProfile>()
        SubagentCatalog.PRESETS.forEach { merged[it.name] = it }
        listCustom().forEach { custom ->
            val existing = merged[custom.name]
            if (existing == null || !existing.allowOverride || custom.overrideBuiltin) {
                merged[custom.name] = custom
            }
        }
        return merged.values.toList()
    }

    fun get(name: String): SubagentProfile? =
        all().firstOrNull { it.name == name.trim().lowercase() }

    fun listCustom(): List<SubagentProfile> {
        val target = dir
        if (!target.isDirectory || target.canonicalFile.toPath().let { java.nio.file.Files.isSymbolicLink(it) }) {
            return emptyList()
        }
        val files = target.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { parse(it) }
            .sortedBy { it.name }
    }

    fun parse(file: File): SubagentProfile? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val (meta, body) = splitFrontMatter(text)
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val fallbackName = file.name.substringBeforeLast('.').trim().lowercase()
        val parsedName = meta["name"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: fallbackName
        if (!SubagentCatalog.isValidName(parsedName)) return null

        val description = meta["description"]?.trim().orEmpty()
        if (description.isEmpty()) return null

        val whenToUse = (meta["whenToUse"] ?: meta["when_to_use"])?.trim().orEmpty()
        val tools = parseList(meta["tools"])
        val disallowed = parseList(meta["disallowedTools"] ?: meta["disallowed_tools"])
        val overrideFlag = meta["override"]?.trim()?.equals("true", ignoreCase = true) == true

        val allowed = if (tools.isEmpty() || tools.contains("*")) {
            setOf("*")
        } else {
            tools.filterNot { it in SubagentCatalog.FORBIDDEN_TOOLS }.toSet()
        }
        val effectiveTools = if (tools.isEmpty() || tools.contains("*")) {
            SubagentCatalog.DEFAULT_TOOLS
        } else {
            tools.filterNot { it in SubagentCatalog.FORBIDDEN_TOOLS }.take(8)
        }

        return SubagentProfile(
            name = parsedName,
            description = description,
            whenToUse = whenToUse.ifEmpty { description },
            tools = effectiveTools,
            systemPrompt = prompt,
            builtin = false,
            allowOverride = true,
            overrideBuiltin = overrideFlag,
            allowedTools = allowed,
            disallowedTools = disallowed.toSet(),
            sourcePath = file.absolutePath,
        )
    }

    fun save(
        name: String,
        description: String,
        prompt: String,
        whenToUse: String = "",
        tools: List<String> = emptyList(),
        disallowedTools: List<String> = emptyList(),
    ): Result<SubagentProfile> {
        val normalized = name.trim().lowercase()
        if (!SubagentCatalog.isValidName(normalized)) {
            return Result.failure(
                IllegalArgumentException("Subagent 名称必须为 2~48 位小写字母、数字与连字符（kebab-case，例如 bill-analyzer）"),
            )
        }
        if (normalized in SubagentCatalog.PRESET_NAMES) {
            return Result.failure(
                IllegalArgumentException(
                    "不能覆盖内置 Subagent \"$normalized\"；请换用其他名称（例如 custom-$normalized）或显式开启 override",
                ),
            )
        }
        val cleanDescription = description.trim().replace(Regex("\\s+"), " ")
        if (cleanDescription.isEmpty()) return Result.failure(IllegalArgumentException("description 不能为空"))
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return Result.failure(IllegalArgumentException("prompt 不能为空"))
        val cleanWhen = whenToUse.trim().replace(Regex("\\s+"), " ")

        val allow = tools.map { it.trim() }.filter { it.isNotEmpty() && it !in SubagentCatalog.FORBIDDEN_TOOLS }.distinct()
        val deny = disallowedTools.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val target = dir
        if (!target.exists() && !target.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建 agents 目录：${target.absolutePath}"))
        }
        val rootPath = target.canonicalFile.toPath()
        val file = File(target, "$normalized.md")
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath) || filePath == rootPath) {
            return Result.failure(IllegalArgumentException("非法的 Subagent 文件路径"))
        }
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return Result.failure(IllegalArgumentException("拒绝写入符号链接文件"))
        }

        val text = buildString {
            append("---\n")
            append("name: ").append(normalized).append('\n')
            append("description: ").append(yamlScalar(cleanDescription)).append('\n')
            if (cleanWhen.isNotEmpty() && cleanWhen != cleanDescription) {
                append("whenToUse: ").append(yamlScalar(cleanWhen)).append('\n')
            }
            if (allow.isNotEmpty()) append("tools: ").append(allow.joinToString(", ")).append('\n')
            if (deny.isNotEmpty()) append("disallowedTools: ").append(deny.joinToString(", ")).append('\n')
            append("---\n\n")
            append(cleanPrompt).append('\n')
        }
        runCatching { file.writeText(text) }.onFailure { error ->
            return Result.failure(IllegalStateException("写入 Subagent 文件失败：${error.message}"))
        }
        val parsed = parse(file)
            ?: return Result.failure(IllegalStateException("保存 Subagent 文件后解析失败：$normalized"))
        return Result.success(parsed)
    }

    fun delete(name: String): Result<Unit> {
        val normalized = name.trim().lowercase()
        if (!SubagentCatalog.isValidName(normalized)) {
            return Result.failure(IllegalArgumentException("Subagent 名称不合法：$name"))
        }
        if (normalized in SubagentCatalog.PRESET_NAMES) {
            return Result.failure(IllegalArgumentException("内置 Subagent \"$normalized\" 不能删除"))
        }
        val target = dir
        val rootPath = target.canonicalFile.toPath()
        val file = File(target, "$normalized.md")
        if (!file.exists()) return Result.failure(IllegalArgumentException("没有名为 \"$normalized\" 的自定义 Subagent"))
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath) || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return Result.failure(IllegalArgumentException("非法的 Subagent 文件路径"))
        }
        val removed = runCatching { file.delete() }.getOrDefault(false)
        return if (removed) Result.success(Unit)
        else Result.failure(IllegalStateException("删除失败：${file.absolutePath}"))
    }

    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return emptyMap<String, String>() to normalized
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to normalized
        val header = normalized.substring(3, end).trim('\n')
        val body = normalized.substring(end + 4)
        val meta = LinkedHashMap<String, String>()
        header.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@forEach
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            meta[key] = value
        }
        return meta to body
    }

    /** `[a, b]` / `a, b` / 带引号条目都能读，与模型的两种写法兼容。 */
    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val stripped = raw.trim().removePrefix("[").removeSuffix("]")
        if (stripped.isBlank()) return emptyList()
        return stripped.split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun yamlScalar(value: String): String {
        val oneLine = value.replace("\n", " ").trim()
        val needsQuote = oneLine.any { it in ":[]{},#&*!|>'\"%@`" }
        if (!needsQuote) return oneLine
        val escaped = oneLine.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
