package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.SubAgentComplexity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一次子代理运行的入参，由执行器负责真正跑 AgentLoop。 */
internal data class SubagentRunRequest(
    val profile: SubagentProfile,
    val prompt: String,
    val description: String,
    val sessionId: String,
    val resumed: Boolean,
    val history: List<AgentModelClient.ConversationMessage>,
    val type: String,
    /** 主代理显式声明的工作量；null 时由 [io.github.mangi.eta.agent.tool.SubAgentBudget] 按 prompt 估算。 */
    val declaredComplexity: SubAgentComplexity? = null,
)

/**
 * 主代理侧的 Subagent 工具集：`subagent`（派出 / 续跑）与 `subagent_profile`（档案增删改查）。
 *
 * 工具合同按 Eta 3.3.4（d1012a0 构建）的 schema 原样对齐：`prompt`/`description` 必填，
 * `subagent_type` 缺省 explore，`resume` 与 `subagent_type` 互斥，工具白名单可临时覆盖。
 */
internal class AgentSubagentToolset(
    private val store: SubagentProfileStore,
    private val run: (SubagentRunRequest) -> AgentModelClient.ToolResult,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult = when (call.name) {
        SubagentCatalog.SUBAGENT_TOOL -> spawn(call.argumentsJson)
        SubagentCatalog.PROFILE_TOOL -> profile(call.argumentsJson)
        else -> failure("SUBAGENT_UNKNOWN_TOOL", "未知的子代理工具：${call.name}")
    }

    private fun spawn(argumentsJson: String): AgentModelClient.ToolResult {
        val args = parse(argumentsJson) ?: return failure("SUBAGENT_INVALID_ARGUMENTS", "参数不是合法 JSON")
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return failure("SUBAGENT_EMPTY_PROMPT", "prompt 不能为空")
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(80)
            .ifEmpty { prompt.take(40) }
        val resumeId = args.optString("resume").trim()
        val role = args.optString("role").trim()
        val requestedTools = stringList(args.optJSONArray("tools"))
        // 非法值不报错，退回估算——模型偶尔会把档位写成 "medium"，不该因此整个派发失败。
        val declaredComplexity = SubAgentComplexity.fromWire(args.optString("complexity"))

        val resolved = if (resumeId.isNotEmpty()) {
            val session = SubagentSessionRegistry.find(resumeId)
                ?: return failure(
                    "SUBAGENT_UNKNOWN_SESSION",
                    "找不到 subagent_id=$resumeId 的子代理上下文（可能已被淘汰）；改用 subagent_type 重新派一个。",
                )
            val profile = store.get(session.profileName)
                ?: SubagentCatalog.ephemeral(session.type, "", emptyList())
            Resolved(
                profile = profile,
                type = session.type,
                sessionId = session.id,
                resumed = true,
                history = session.transcript.toList(),
            )
        } else {
            val type = args.optString("subagent_type").trim().lowercase().ifEmpty { SubagentCatalog.DEFAULT_TYPE }
            val base = store.get(type)
            val profile = when {
                base == null && !SubagentCatalog.isValidName(type) ->
                    return failure(
                        "SUBAGENT_INVALID_TYPE",
                        "subagent_type 必须是预设（${SubagentCatalog.PRESET_NAMES.joinToString("/")}）、" +
                            "已保存的自定义档案名，或 kebab-case 角色名（例如 bill-analyzer）。",
                    )
                base == null -> SubagentCatalog.ephemeral(type, role, sanitizeTools(requestedTools))
                else -> deriveProfile(base, role, requestedTools)
            }
            val sessionId = "sub-" + UUID.randomUUID().toString().take(8)
            SubagentSessionRegistry.open(sessionId, type, description, profile.name)
            Resolved(
                profile = profile,
                type = type,
                sessionId = sessionId,
                resumed = false,
                history = emptyList(),
            )
        }

        if (!SubagentCatalog.isValidName(resolved.profile.name)) {
            return failure("SUBAGENT_INVALID_TYPE", "子代理名称不合法：${resolved.profile.name}")
        }
        if (resolved.profile.systemPrompt.isBlank()) {
            return failure("SUBAGENT_INVALID_PROFILE", "子代理档案缺少 systemPrompt：${resolved.profile.name}")
        }

        val result = run(
            SubagentRunRequest(
                profile = resolved.profile,
                prompt = prompt,
                description = description,
                sessionId = resolved.sessionId,
                resumed = resolved.resumed,
                history = resolved.history,
                type = resolved.type,
                declaredComplexity = declaredComplexity,
            ),
        )
        val payload = runCatching { JSONObject(result.content) }.getOrNull()
        val ok = payload?.optBoolean("ok", true) ?: true
        val output = payload?.optString("output").orEmpty().ifEmpty { result.content }
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", ok)
                .put("subagent_id", resolved.sessionId)
                .put("subagent_type", resolved.type)
                .put("description", description)
                .put("resumed", resolved.resumed)
                .put("tools", JSONArray(resolved.profile.effectiveTools()))
                .put("output", output.take(8_000))
                .toString(),
            images = result.images,
        )
    }

    private fun profile(argumentsJson: String): AgentModelClient.ToolResult {
        val args = parse(argumentsJson) ?: return failure("SUBAGENT_INVALID_ARGUMENTS", "参数不是合法 JSON")
        val action = args.optString("action").trim().lowercase()
        val name = args.optString("name").trim().lowercase()
        return when (action) {
            "list" -> AgentModelClient.ToolResult(
                JSONObject()
                    .put("ok", true)
                    .put(
                        "profiles",
                        JSONArray().also { array ->
                            store.all().forEach { profile ->
                                array.put(
                                    JSONObject()
                                        .put("name", profile.name)
                                        .put("description", profile.description)
                                        .put("builtin", profile.builtin)
                                        .put("tools", JSONArray(profile.effectiveTools())),
                                )
                            }
                        },
                    )
                    .toString(),
            )
            "get" -> {
                if (name.isEmpty()) return failure("SUBAGENT_PROFILE_INVALID", "get 需要 name")
                val found = store.get(name)
                    ?: return failure("SUBAGENT_PROFILE_NOT_FOUND", "没有名为 \"$name\" 的 Subagent 档案")
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("name", found.name)
                        .put("description", found.description)
                        .put("whenToUse", found.whenToUse)
                        .put("builtin", found.builtin)
                        .put("tools", JSONArray(found.effectiveTools()))
                        .put("prompt", found.systemPrompt.take(4_000))
                        .put("path", found.sourcePath ?: "")
                        .toString(),
                )
            }
            "save" -> {
                val saved = store.save(
                    name = name,
                    description = args.optString("description"),
                    prompt = args.optString("prompt"),
                    whenToUse = args.optString("when_to_use"),
                    tools = stringList(args.optJSONArray("tools")),
                    disallowedTools = stringList(args.optJSONArray("disallowed_tools")),
                )
                saved.fold(
                    onSuccess = { profile ->
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", true)
                                .put("name", profile.name)
                                .put("path", profile.sourcePath ?: "")
                                .put("message", "已保存 Subagent 档案：agents/${profile.name}.md")
                                .toString(),
                        )
                    },
                    onFailure = { failure("SUBAGENT_PROFILE_SAVE_FAILED", it.message ?: "保存失败") },
                )
            }
            "delete" -> {
                if (name.isEmpty()) return failure("SUBAGENT_PROFILE_INVALID", "delete 需要 name")
                store.delete(name).fold(
                    onSuccess = {
                        AgentModelClient.ToolResult(
                            JSONObject().put("ok", true).put("name", name)
                                .put("message", "已删除 agents/$name.md").toString(),
                        )
                    },
                    onFailure = { failure("SUBAGENT_PROFILE_DELETE_FAILED", it.message ?: "删除失败") },
                )
            }
            else -> failure("SUBAGENT_PROFILE_INVALID", "action 必须是 list / get / save / delete")
        }
    }

    /** 预设 + 临时 role/tools：不落盘，只改这一次运行。 */
    private fun deriveProfile(
        base: SubagentProfile,
        role: String,
        tools: List<String>,
    ): SubagentProfile {
        if (role.isEmpty() && tools.isEmpty()) return base
        val mergedPrompt = if (role.isEmpty()) base.systemPrompt else base.systemPrompt + "\n\n追加角色设定：\n" + role
        val allowed = when {
            tools.isNotEmpty() -> sanitizeTools(tools).toSet()
            else -> base.allowedTools
        }
        return base.copy(
            systemPrompt = mergedPrompt,
            tools = if (tools.isNotEmpty()) sanitizeTools(tools) else base.tools,
            allowedTools = allowed,
        )
    }

    private fun sanitizeTools(tools: List<String>): List<String> =
        tools.map { it.trim() }.filter { it.isNotEmpty() && it !in SubagentCatalog.FORBIDDEN_TOOLS }.distinct()

    private fun parse(argumentsJson: String): JSONObject? =
        runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
        )

    private data class Resolved(
        val profile: SubagentProfile,
        val type: String,
        val sessionId: String,
        val resumed: Boolean,
        val history: List<AgentModelClient.ConversationMessage>,
    )

    companion object {
        /** 供执行器在组装工具清单时直接追加两个 schema（不需要先实例化工具集）。 */
        fun appendSchemas(target: JSONArray) {
            target.put(subagentSchema())
            target.put(profileSchema())
        }

        fun subagentSchema(): JSONObject {
            val catalog = SubagentCatalog.describeForSchema(SubagentCatalog.PRESETS)
            val description = buildString {
                append("Launch a subagent in an isolated context to handle an autonomous subtask and return a concise summary. ")
                append("You can freely spawn as many specialized ephemeral subagents as needed for the conversation without saving them to disk, ")
                append("or use one of the preset types below. When multiple independent subtasks can run in parallel, issue multiple `subagent` tool calls ")
                append("in the same turn — the runtime executes them one after another, so keep each prompt self-contained.\n\n")
                append("Writing the prompt:\n")
                append("- A fresh subagent starts with zero conversation history. State the goal clearly and include any known file paths, time ranges, package names, or constraints.\n")
                append("- Prefer resuming an existing subagent (pass its `resume` id) when continuing earlier work so it retains its prior context.\n")
                append("- For ad-hoc specialized roles (e.g. `bill-analyzer`, `log-auditor`, `schedule-checker`), pass the role name in `subagent_type` and optionally specify `role` or `tools` — it runs immediately as an ephemeral subagent.\n")
                append("- The subagent's output is only returned to you; summarize relevant findings for the user in your own reply.\n\n")
                append("Preset agent types (or pass any custom kebab-case `subagent_type` for an ephemeral subagent):\n")
                append(catalog)
            }
            val properties = JSONObject()
                .put(
                    "prompt",
                    JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 16_000)
                        .put("description", "Complete task instructions and necessary context for the subagent."),
                )
                .put(
                    "description",
                    JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 120)
                        .put("description", "Short task label (3-5 words) displayed in the timeline UI."),
                )
                .put(
                    "subagent_type",
                    JSONObject().put("type", "string").put("maxLength", 48)
                        .put(
                            "description",
                            "Preset type (\"explore\", \"coder\", \"plan\", or a saved custom profile) OR any ad-hoc kebab-case " +
                                "role name (e.g. \"bill-analyzer\", \"apk-inspector\") to spawn an ephemeral specialized subagent on the fly. " +
                                "Defaults to \"explore\" when omitted. Do not set when `resume` is provided.",
                        ),
                )
                .put(
                    "complexity",
                    JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("simple").put("normal").put("complex"))
                        .put(
                            "description",
                            "Estimated workload, decides the subagent's round budget (simple=10, normal=20, complex=40 rounds). " +
                                "Set it yourself when you know how much work this is; omit to let the runtime estimate from the prompt length.",
                        ),
                )
                .put(
                    "role",
                    JSONObject().put("type", "string").put("maxLength", 2_000)
                        .put("description", "Optional system role or persona instructions for an ad-hoc ephemeral subagent (only used for this subagent instance, not saved to disk)."),
                )
                .put(
                    "tools",
                    JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                        .put("maxItems", 48)
                        .put("description", "Optional tool allowlist for an ad-hoc subagent (e.g. [\"summarize_bills\", \"search_bills\"]). Omit to use the preset's tools or all non-GUI tools."),
                )
                .put(
                    "resume",
                    JSONObject().put("type", "string").put("maxLength", 64)
                        .put("description", "Optional subagent_id from a previous `subagent` result in this conversation to continue its existing context."),
                )
            return JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", SubagentCatalog.SUBAGENT_TOOL)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject().put("type", "object").put("properties", properties)
                            .put("required", JSONArray().put("prompt").put("description"))
                            .put("additionalProperties", false),
                    ),
            )
        }

        fun profileSchema(): JSONObject {
            val properties = JSONObject()
                .put(
                    "action",
                    JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("list").put("get").put("save").put("delete"))
                        .put("description", "Action to perform: list all subagent profiles, get one profile's details, save (create/update) a custom subagent profile in `agents/<name>.md`, or delete a custom profile."),
                )
                .put(
                    "name",
                    JSONObject().put("type", "string").put("maxLength", 48)
                        .put("description", "Subagent profile name in kebab-case (e.g. \"bill-analyzer\", \"log-auditor\"). Required for get, save, and delete."),
                )
                .put(
                    "description",
                    JSONObject().put("type", "string").put("maxLength", 240)
                        .put("description", "Required for save: concise one-line summary of what this custom subagent does."),
                )
                .put(
                    "when_to_use",
                    JSONObject().put("type", "string").put("maxLength", 500)
                        .put("description", "Optional for save: guidance on when the main agent should delegate to this subagent."),
                )
                .put(
                    "tools",
                    JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                        .put("maxItems", 64)
                        .put("description", "Optional for save: list of allowed tool names for this subagent (e.g. [\"summarize_bills\", \"search_bills\", \"search_personal_orders\"]). Omit or pass [\"*\"] to allow all non-GUI tools."),
                )
                .put(
                    "disallowed_tools",
                    JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string").put("maxLength", 80))
                        .put("maxItems", 64)
                        .put("description", "Optional for save: list of tool names to explicitly disallow."),
                )
                .put(
                    "prompt",
                    JSONObject().put("type", "string").put("maxLength", 16_000)
                        .put("description", "Required for save: system instructions / role prompt for the custom subagent."),
                )
            return JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", SubagentCatalog.PROFILE_TOOL)
                    .put(
                        "description",
                        "Manage persistent reusable Subagent templates (`agents/<name>.md`). ONLY call `save` or `delete` when the user " +
                            "explicitly asks to save a reusable custom Subagent template for future sessions. For spawning specialized " +
                            "subagents to do work during a conversation, call `subagent` directly with an ad-hoc `subagent_type` and `role` without saving a profile.",
                    )
                    .put(
                        "parameters",
                        JSONObject().put("type", "object").put("properties", properties)
                            .put("required", JSONArray().put("action"))
                            .put("additionalProperties", false),
                    ),
            )
        }
    }
}
