package io.github.mangi.eta.agent.subagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 Subagent 档案：内置预设（explore / coder / plan）或 `agents/<name>.md` 里的自定义模板。
 *
 * 为什么档案要落到文件而不是数据库：子代理模板是"给模型看的提示词 + 工具白名单"，
 * 用户要能用文本编辑器改、能跟着项目一起进 git；塞进 DB 就只能靠 App 自己改。
 */
internal data class SubagentProfile(
    val name: String,
    val description: String,
    val whenToUse: String,
    val tools: List<String>,
    val systemPrompt: String,
    val builtin: Boolean,
    /** 内置预设是否允许被同名自定义档案覆盖（默认允许，覆盖需显式 `override: true`）。 */
    val allowOverride: Boolean,
    /** 该档案是否显式声明了 override。 */
    val overrideBuiltin: Boolean,
    /** 允许的工具：`*` 表示除禁用集合外的全部。 */
    val allowedTools: Set<String>,
    val disallowedTools: Set<String>,
    val sourcePath: String?,
) {
    /** 子代理能拿到的工具判定：禁用集合 > 显式拒绝 > 白名单。 */
    fun allowsTool(name: String): Boolean {
        if (name in SubagentCatalog.FORBIDDEN_TOOLS) return false
        if (name in disallowedTools) return false
        if (allowedTools.isEmpty() || "*" in allowedTools) return true
        return name in allowedTools
    }

    /** 实际传给模型时用的工具名清单（去掉禁用项，便于回执展示）。 */
    fun effectiveTools(): List<String> =
        if ("*" in allowedTools || allowedTools.isEmpty()) {
            emptyList()
        } else {
            allowedTools.filterNot { it in SubagentCatalog.FORBIDDEN_TOOLS }
        }
}

/**
 * 预设与工具边界。
 *
 * 两条硬边界来自"子代理不能反过来控制主流程"：不能再派子代理、不能改档案、不能碰屏幕。
 * 屏幕那条尤其重要：observation_id 是挂在执行器实例上的，两个 agent 交替观察会让彼此的
 * 观察互相作废，现象是"点错位置"，比直接报错难查得多。
 */
internal object SubagentCatalog {
    const val SUBAGENT_TOOL = "subagent"
    const val PROFILE_TOOL = "subagent_profile"
    const val DEFAULT_TYPE = "explore"

    private val NAME_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    /** 子代理永远拿不到的工具。 */
    val FORBIDDEN_TOOLS: Set<String> = setOf(
        SUBAGENT_TOOL, PROFILE_TOOL,
        "observe_screen", "release_gui",
        "tap", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element",
        "type_text", "input_text", "replace_text", "clear_text", "paste_text",
        "set_clipboard", "press_key", "open_system_panel",
        "launch_app", "open_uri",
        "wait", "wait_for_text", "wait_for_package",
        "browser_use",
        "memory_write", "character_memory_write",
        "skills_install_from_github",
        "terminal_daemon",
        "spawn_agents",
        "task_write",
    )

    /** 只读工具集合：explore / plan 这类只读档案的安全底线。 */
    val READ_ONLY_TOOLS: Set<String> = setOf(
        "read_file", "stat_file", "list_directory", "glob_files", "grep_files", "read_image",
        "web_search", "fetch_url", "get_current_context", "search_apps", "inspect_app",
        "device_status", "network_info", "top_memory_apps", "top_storage_apps",
        "get_setting", "get_logcat", "get_current_location", "get_device_environment",
        "list_alarms", "list_active_timers", "get_health_summary",
        "recent_notifications", "search_notification_history", "recent_app_activity",
        "app_usage_summary", "search_clipboard_history", "get_clipboard",
        "get_flashlight", "get_device_state", "get_display_state", "get_sound_state", "get_hotspot",
        "read_personal_item", "summarize_bills", "search_bills",
        "search_media", "search_audio", "search_recordings", "search_files",
        "search_calendar_events", "search_contacts", "search_call_history",
        "search_messages", "search_downloads", "search_notes",
        "search_coloros_recordings", "search_recording_summaries",
        "search_system_memories", "search_saved_places", "search_personal_orders",
        "search_qq_chat_images", "search_wechat_chat_images",
        "memory_get", "character_memory_get",
        "skills_list", "skills_read", "skills_read_resource",
    )

    /** 自定义档案没写 tools 时的默认集合。 */
    val DEFAULT_TOOLS: List<String> = listOf(
        "read_file", "glob_files", "grep_files", "web_search", "fetch_url", "terminal",
    )

    val EXPLORE: SubagentProfile = SubagentProfile(
        name = "explore",
        description = "只读探索与多源信息检索",
        whenToUse = "Fast read-only agent specialized for exploring files, personal device data " +
            "(calendar, notes, messages, notifications, bills, media, system memories), logs, and " +
            "web pages. Specify desired thoroughness in the prompt: \"quick\", \"medium\", or \"thorough\".",
        tools = listOf(
            "read_file", "glob_files", "grep_files", "list_directory", "stat_file",
            "web_search", "fetch_url", "get_logcat", "get_current_context",
            "search_notes", "search_calendar_events", "search_messages", "search_call_history",
            "search_contacts", "search_files", "search_media", "search_recordings",
            "search_personal_orders", "search_system_memories", "recent_notifications",
            "recent_app_activity", "app_usage_summary", "device_status", "network_info",
            "get_current_location",
        ),
        systemPrompt = "你是只读探索 Subagent（explore）。你的职责是快速、准确地检索文件、设备个人数据、" +
            "系统状态或网页资料，严禁尝试修改任何文件或设备状态。在最终回复中直接给出结构化证据、" +
            "关键路径/时间点与结论摘要。",
        builtin = true,
        allowOverride = true,
        overrideBuiltin = false,
        allowedTools = setOf("*"),
        disallowedTools = emptySet(),
        sourcePath = null,
    )

    val CODER: SubagentProfile = SubagentProfile(
        name = "coder",
        description = "工程执行、文件编辑与终端任务",
        whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; " +
            "use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
        tools = listOf(
            "terminal", "read_file", "write_file", "edit_file", "list_directory", "stat_file",
            "glob_files", "grep_files", "web_search", "fetch_url",
        ),
        systemPrompt = "你是工程与终端执行 Subagent（coder）。你的最后一条消息就是交给主 Agent 的完整交接报告：" +
            "请清楚说明你执行了哪些命令或修改了哪些文件、关键输出与验证结果，以及任何未完成或需后续处理的事项。",
        builtin = true,
        allowOverride = true,
        overrideBuiltin = false,
        allowedTools = setOf("*"),
        disallowedTools = emptySet(),
        sourcePath = null,
    )

    val PLAN: SubagentProfile = SubagentProfile(
        name = "plan",
        description = "只读架构分析与分步方案设计",
        whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or " +
            "execution plan, key file/data identification, and trade-off analysis before making changes.",
        tools = listOf(
            "read_file", "glob_files", "grep_files", "list_directory", "stat_file",
            "web_search", "fetch_url", "skills_read", "skills_list",
        ),
        systemPrompt = "你是只读规划 Subagent（plan）。你拥有只读检索能力，不得修改文件或执行破坏性操作。" +
            "请在最终回复中清晰列出：1. 已确认的上下文事实；2. 仍待核实的关键问题；3. 分步执行方案与风险权衡。",
        builtin = true,
        allowOverride = true,
        overrideBuiltin = false,
        allowedTools = setOf("*"),
        disallowedTools = emptySet(),
        sourcePath = null,
    )

    val PRESETS: List<SubagentProfile> = listOf(EXPLORE, CODER, PLAN)
    val PRESET_NAMES: Set<String> = PRESETS.map { it.name }.toSet()

    fun preset(name: String): SubagentProfile? = PRESETS.firstOrNull { it.name == name }

    fun isValidName(name: String): Boolean = name.length in 2..48 && NAME_PATTERN.matches(name)

    /** 临时（不落盘）子代理：显式 tools 或全量非 GUI 工具，role 作为角色设定。 */
    fun ephemeral(name: String, role: String, tools: List<String>): SubagentProfile = SubagentProfile(
        name = name,
        description = "临时子代理（不落盘）",
        whenToUse = "",
        tools = tools,
        systemPrompt = role.ifBlank {
            "你是主代理临时派出的 $name 子代理。在给定工具范围内自主完成被指派的子任务，" +
                "最终回复里直接给结论与证据（文件路径、命令输出、来源），不要复述过程。"
        },
        builtin = false,
        allowOverride = false,
        overrideBuiltin = false,
        allowedTools = if (tools.isEmpty()) setOf("*") else tools.toSet(),
        disallowedTools = emptySet(),
        sourcePath = null,
    )

    /** 写给模型看的预设目录（与撤销后的 APK 描述同构）。 */
    fun describeForSchema(profiles: List<SubagentProfile>): String =
        profiles.joinToString("\n") { profile ->
            val tools = profile.effectiveTools().let { list ->
                if (list.isEmpty()) "全部非 GUI 工具" else list.joinToString(", ")
            }
            "- ${profile.name}: ${profile.description}（工具：$tools）"
        }

    /**
     * 按档案过滤工具 schema。
     *
     * 只过滤不拦截是错的：模型会照着 schema 去调被删掉的工具然后自问自答。所以调用侧
     * （[io.github.mangi.eta.agent.subagent.SubagentProfile.allowsTool]）也要拦一道。
     */
    fun filterSchemas(schemas: JSONArray, profile: SubagentProfile): JSONArray {
        val filtered = JSONArray()
        for (index in 0 until schemas.length()) {
            val item = schemas.optJSONObject(index) ?: continue
            val name = item.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isNotEmpty() && profile.allowsTool(name)) filtered.put(item)
        }
        return filtered
    }
}
