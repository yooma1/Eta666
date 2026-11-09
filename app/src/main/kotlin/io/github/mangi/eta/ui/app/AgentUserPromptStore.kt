package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentUserPromptException
import io.github.mangi.eta.data.repository.AgentUserPromptLibraryEntry
import io.github.mangi.eta.data.repository.AgentUserPromptResolver
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptRepository
import io.github.mangi.eta.data.repository.AgentUserPromptSnapshot
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentUserPromptUiState
import io.github.mangi.eta.ui.model.PendingImport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本文件的类名叫 AgentUserPromptStore，与 data.repository 下同名类冲突，故常量走全限定名、不做 import。 */
private val PROMPT_MAX_FILE_BYTES =
    io.github.mangi.eta.data.repository.AgentUserPromptStore.MAX_FILE_BYTES

/**
 * 作战协议页的装载、编辑、导入与开关状态。
 *
 * 与 [AgentMemoryStore] 的区别：这里的正文是**用户指令**（注入为独立 system 消息），
 * 不是背景资料；因此档位默认关闭，且文件超限时只读——不做"静默截断后回写"这种会毁文件的事。
 */
internal class AgentUserPromptStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var promptState by mutableStateOf(AgentUserPromptUiState())
        private set

    /**
     * 待确认导入的正文。刻意不放 [AgentUserPromptUiState]：
     * 整份提示词可能有 8 万字符，进 Compose 状态会让每次重组都复制一遍。
     */
    private var pendingImportContent: String? = null

    fun refreshPrompt() {
        promptState = promptState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                // Application.onCreate 的播种协程可能还没跑完，这里补一次。
                // seedBuiltinIfNeeded 有标记位 + 已有文件时不覆盖，重复调用是幂等的。
                runCatching { AgentUserPromptRepository.seedBuiltinIfNeeded() }
                val snapshot = AgentUserPromptRepository.snapshot()
                val mode = AgentUserPromptRepository.mode()
                // 作战协议与逆向能力共用一个总开关，此处仅展示其状态。
                val enabled = runCatching {
                    AgentUserPromptRepository.enabled()
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_switch_read_failed") {
                        "User prompt switch read failed: type=${throwable.safeLogType()}"
                    }
                }.getOrDefault(false)
                val contextWindow = runCatching {
                    RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_context_window_failed") {
                        "Context window lookup failed: type=${throwable.safeLogType()}"
                    }
                }.getOrNull()
                // 当前模型 → 内置协议绑定：页面要能直接回答「这一轮到底注入哪份」。
                val modelResolution = runCatching {
                    RuntimeConfigRepository.currentRuntimeConfig()?.let { config ->
                        AgentUserPromptRepository.loadForModel(
                            enabled = enabled,
                            mode = mode,
                            model = config.model,
                            displayName = config.modelDisplayName,
                        )
                    }
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_model_binding_failed") {
                        "Model protocol binding failed: type=${throwable.safeLogType()}"
                    }
                }.getOrNull()
                val library = runCatching { AgentUserPromptRepository.libraryEntries() }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_library_index_failed") {
                            "Prompt library index failed: type=${throwable.safeLogType()}"
                        }
                    }
                    .getOrDefault(emptyList())
                Loaded(
                    snapshot, mode, enabled, contextWindow,
                    AgentUserPromptRepository.builtinSnapshot(), library, modelResolution,
                )
            }.fold(
                onSuccess = { loaded ->
                    pendingImportContent = null
                    withContext(Dispatchers.Main) {
                        val keepLibraryQuery = promptState.libraryQuery
                        promptState = AgentUserPromptUiState(
                            isLoading = false,
                            mode = loaded.mode,
                            draft = loaded.snapshot.content,
                            savedContent = loaded.snapshot.content,
                            sourceFileName = loaded.snapshot.fileName,
                            charCount = loaded.snapshot.charCount,
                            revision = loaded.snapshot.revision,
                            truncated = loaded.snapshot.truncated,
                            enabled = loaded.enabled,
                            contextWindow = loaded.contextWindow,
                            builtinCharCount = loaded.builtin?.charCount,
                            builtinRevision = loaded.builtin?.revision,
                            libraryEntries = loaded.library,
                            libraryQuery = keepLibraryQuery,
                            resolvedSource = loaded.modelResolution?.source?.label,
                            resolvedEntryId = loaded.modelResolution?.libraryEntryId,
                            resolvedReason = loaded.modelResolution?.reason,
                            resolvedChars = loaded.modelResolution
                                ?.takeIf { it.available }?.snapshot?.charCount,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_ui_load_failed") {
                        "User prompt UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isLoading = false,
                            notice = throwable.message ?: "读取作战协议失败，请稍后重试。",
                        )
                    }
                },
            )
        }
    }

    /** Legacy handler kept for compatibility; the editing page no longer displays a second switch. */
    fun setEnabled(enabled: Boolean) {
        if (promptState.enabled == enabled) return
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_switch_failed") {
                            "User prompt switch update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(notice = "注入开关保存失败，请稍后重试。")
                        }
                    },
                )
        }
    }

    fun setMode(mode: AgentUserPromptMode) {
        if (promptState.mode == mode) return
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.setMode(mode) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(mode = mode, notice = null)
                        }
                        refreshResolvedProtocol(mode)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_mode_failed") {
                            "User prompt mode update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(notice = "生效范围保存失败，请稍后重试。")
                        }
                    },
                )
        }
    }

    fun updateDraft(content: String) {
        if (promptState.editingLocked) return
        promptState = promptState.copy(draft = content, notice = null)
    }

    fun savePrompt() {
        if (!promptState.canSave) return
        val target = promptState.draft
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (promptState.draft == target) snapshot.content else promptState.draft,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                revision = snapshot.revision,
                                truncated = snapshot.truncated,
                                editorExpanded = false,
                                notice = "已保存；下个会话生效。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_ui_save_failed") {
                            "User prompt UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "保存作战协议失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    fun clearPrompt() {
        if (promptState.isSaving) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.clearActive() }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                revision = snapshot.revision,
                                truncated = snapshot.truncated,
                                editorExpanded = false,
                                notice = "已清空（原文件已备份到 prompts/.backup/）。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_ui_clear_failed") {
                            "User prompt UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "清空作战协议失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    fun dismissNotice() {
        promptState = promptState.copy(notice = null)
    }

    /** 内容太长被降级成只读预览后，用户显式要求把全文放进编辑区。 */
    fun openEditor() {
        if (promptState.editingLocked) return
        promptState = promptState.copy(editorExpanded = true)
    }

    /**
     * 读入所选文件并停在待确认状态——**不直接落盘**。
     * 落盘写的是 8 万字符，先让用户看清文件名与体积（以及是否超限）再确认，是唯一可回退的时机。
     */
    fun prepareImport(uri: Uri) {
        if (promptState.isSaving || promptState.isLoading) return
        promptState = promptState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { readImport(uri) }
                .fold(
                    onSuccess = { (fileName, text, byteSize) ->
                        pendingImportContent = text
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isLoading = false,
                                pendingImport = PendingImport(
                                    fileName = fileName,
                                    charCount = text.length,
                                    byteSize = byteSize,
                                ),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_import_read_failed") {
                            "User prompt import read failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isLoading = false,
                                notice = throwable.message ?: "导入失败，请换一个文件重试。",
                            )
                        }
                    },
                )
        }
    }

    fun confirmImport() {
        val content = pendingImportContent ?: return
        val pending = promptState.pendingImport ?: return
        if (promptState.isSaving) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                // 先走清空路径：已有文件会被备份到 prompts/.backup/，再写入导入的正文——
                // 导入是"替换生效文件"，不能把用户原来那份悄悄删掉。
                AgentUserPromptRepository.clearActive()
                AgentUserPromptRepository.replaceAll(content)
            }.fold(
                onSuccess = { snapshot ->
                    pendingImportContent = null
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            sourceFileName = snapshot.fileName,
                            charCount = snapshot.charCount,
                            revision = snapshot.revision,
                            truncated = snapshot.truncated,
                            editorExpanded = false,
                            pendingImport = null,
                            notice = if (snapshot.truncated) {
                                "已导入 ${pending.fileName}，但超出上限被截断，请精简后重新导入。"
                            } else {
                                "已导入 ${pending.fileName}（${pending.charCount} 字符）；下个会话生效。"
                            },
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_ui_import_failed") {
                        "User prompt UI import failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            pendingImport = null,
                            notice = throwable.message ?: "导入作战协议失败，请稍后重试。",
                        )
                    }
                },
            )
        }
    }

    fun cancelImport() {
        pendingImportContent = null
        promptState = promptState.copy(pendingImport = null)
    }

    /** 把随包内置的破甲记忆装配成效文件（覆盖当前生效文件，旧文件先备份到 prompts/.backup/）。 */
    fun installBuiltin() {
        if (promptState.isSaving || promptState.isLoading || !promptState.builtinAvailable) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.installBuiltin() }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                revision = snapshot.revision,
                                truncated = snapshot.truncated,
                                editorExpanded = false,
                                notice = "已装配内置破甲记忆（${snapshot.charCount} 字符）；下个会话生效。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_builtin_install_failed") {
                            "Builtin prompt install failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "装配内置版本失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    /**
     * 装配 v6 模板：身份按当前模型真实提供方填充。
     *
     * 渲染失败时仓库层会自动退回内置版本，这里必须把「退回了」说清楚——
     * 静默替换成旧版比直接报错更难排查。
     */
    fun installTemplate() {
        if (promptState.isSaving || promptState.isLoading) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                AgentUserPromptRepository.installTemplateWithFallback(
                    model = config?.model.orEmpty(),
                    displayName = config?.modelDisplayName.orEmpty(),
                )
            }.fold(
                onSuccess = { result ->
                    val snapshot = result.snapshot
                    val message = if (result.fellBack) {
                        val why = result.failure?.label ?: "未知原因"
                        "v6 模板装配失败（$why），已改用内置破甲记忆（${snapshot.charCount} 字符）；" +
                            "两者都已备份旧文件，下个会话生效。"
                    } else {
                        "已装配 v6 模板（${snapshot.charCount} 字符）；身份段按当前模型填充，下个会话生效。"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            sourceFileName = snapshot.fileName,
                            charCount = snapshot.charCount,
                            revision = snapshot.revision,
                            truncated = snapshot.truncated,
                            editorExpanded = false,
                            notice = message,
                        )
                    }
                    refreshResolvedProtocol(promptState.mode)
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_template_install_failed") {
                        "Template install failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            notice = throwable.message ?: "装配 v6 模板失败，内置版本也不可用。",
                        )
                    }
                },
            )
        }
    }

    /**
     * 切换生效范围后立刻重算「当前模型会注入哪一份」。
     *
     * 不重算的话页面会显示上一次的结论，用户切到「跟随模型」却看不到绑定结果，
     * 只能靠下一轮对话去猜——这类静默不一致比功能缺失更难排查。
     */
    private fun refreshResolvedProtocol(mode: AgentUserPromptMode) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val config = RuntimeConfigRepository.currentRuntimeConfig() ?: return@runCatching null
                val enabled = runCatching { AgentUserPromptRepository.enabled() }.getOrDefault(false)
                AgentUserPromptRepository.loadForModel(
                    enabled = enabled,
                    mode = mode,
                    model = config.model,
                    displayName = config.modelDisplayName,
                )
            }.fold(
                onSuccess = { resolution ->
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            resolvedSource = resolution?.source?.label,
                            resolvedEntryId = resolution?.libraryEntryId,
                            resolvedReason = resolution?.reason,
                            resolvedChars = resolution?.takeIf { it.available }?.snapshot?.charCount,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_model_binding_failed") {
                        "Model protocol binding failed: type=${throwable.safeLogType()}"
                    }
                },
            )
        }
    }

    /** 协议库搜索词（纯 UI 过滤，不落盘、不影响生效文件）。 */
    fun setLibraryQuery(query: String) {
        promptState = promptState.copy(libraryQuery = query)
    }

    /**
     * 装配内置协议库里的一条（移植自 yooma1/jailbreaks）。
     *
     * 与 installBuiltin 同一路径：先备份当前生效文件到 prompts/.backup/，再原子写入；
     * 装配**不等于生效**，是否注入只看总开关，页面照实提示。
     */
    fun installLibraryEntry(id: String) {
        if (promptState.isSaving || promptState.isLoading) return
        if (promptState.installingLibraryId != null) return
        val entry = promptState.libraryEntries.firstOrNull { it.id == id } ?: return
        promptState = promptState.copy(installingLibraryId = id, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.installFromLibrary(id) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                installingLibraryId = null,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                revision = snapshot.revision,
                                truncated = snapshot.truncated,
                                editorExpanded = false,
                                notice = "已装配「${entry.title}」（${snapshot.charCount} 字符）；" +
                                    "是否注入仍看总开关，改动作下个会话生效。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_library_install_failed") {
                            "Prompt library install failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                installingLibraryId = null,
                                notice = throwable.message ?: "装配协议库条目失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    /**
     * 读取所选文件。三重拒绝：超字节上限、含 NUL 的二进制、读出来是空白。
     * 换行统一成 LF 并剥掉 UTF-8 BOM——否则 BOM 会作为正文第一个字符被注入。
     */
    private fun readImport(uri: Uri): Triple<String, String, Int> {
        val resolver = appContext.contentResolver
        val fileName = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "导入的协议.md"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (buffer.size() + read > PROMPT_MAX_FILE_BYTES) {
                    throw AgentUserPromptException(
                        code = "PROMPT_TOO_LARGE",
                        message = "文件超过 ${PROMPT_MAX_FILE_BYTES / 1024} KiB，拒绝导入",
                    )
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: throw AgentUserPromptException(
            code = "PROMPT_IMPORT_FAILED",
            message = "无法读取所选文件",
        )

        if (bytes.isEmpty()) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件是空的")
        }
        if (bytes.any { it == 0.toByte() }) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件不是文本文件")
        }
        val text = bytes.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (text.isBlank()) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件没有可注入的正文")
        }
        return Triple(fileName, text, bytes.size)
    }

    private data class Loaded(
        val snapshot: AgentUserPromptSnapshot,
        val mode: AgentUserPromptMode,
        val enabled: Boolean,
        val contextWindow: Int?,
        val builtin: AgentUserPromptSnapshot?,
        val library: List<AgentUserPromptLibraryEntry>,
        val modelResolution: AgentUserPromptResolver.Resolution?,
    )
}
