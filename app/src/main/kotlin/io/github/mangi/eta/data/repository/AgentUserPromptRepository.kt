package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.res.AssetManager
import android.util.AtomicFile
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户自备作战协议的生效范围。
 *
 * 默认 [Off]：文件存在不等于生效——用户显式开启前，会话行为不发生任何变化，
 * 避免"文件躺在那儿、行为已经变了、用户还不知道为什么"。
 */
internal enum class AgentUserPromptMode(val persistedValue: String) {
    /** 不注入。 */
    Off("off"),

    /** 所有会话都注入。 */
    Global("global"),

    /** 仅逆向模式（所有已安装逆向技能均启用）的会话注入。 */
    ReverseOnly("reverse"),

    /**
     * 按当前会话模型自动选内置协议（[AgentUserPromptModelBinding]）。
     *
     * 映射不到时退回 `prompts/` 用户文件；两者都为空则不注入。
     */
    ModelBinding("model");

    companion object {
        fun fromPersistedValue(value: String?): AgentUserPromptMode =
            values().firstOrNull { it.persistedValue == value } ?: Off
    }
}

/**
 * 一次装载的用户作战协议快照。
 *
 * [content] 已按 [AgentUserPromptStore.MAX_CHARS] 截断，[charCount] 是截断前的原始字符数。
 */
internal data class AgentUserPromptSnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val charCount: Int,
    val fileName: String?,
    val truncated: Boolean,
) {
    /** 存在可注入的正文。文件缺失或只有空白时都是 false。 */
    val available: Boolean get() = !fileName.isNullOrBlank() && content.isNotBlank()

    companion object {
        val EMPTY = AgentUserPromptSnapshot(
            content = "",
            revision = "",
            byteSize = 0,
            charCount = 0,
            fileName = null,
            truncated = false,
        )
    }
}

internal class AgentUserPromptException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * `filesDir/prompts/` 下用户自备提示词文件的装载、原子写入与清理。
 *
 * 装载规则与寒霜的提示词目录一致：
 * - 目录内按文件名排序，取**第一个非空** `.md`；
 * - `README.md` 固定忽略；
 * - 目录不存在、只有空文件时视为未配置——**不产生任何注入**，也不写半成品。
 *
 * 快照在会话开始时读取一次并在会话内保持不变；会话中途改文件不会改变正在进行的请求前缀，
 * 否则系统提示抖动会废掉整段 prompt 缓存。
 */
internal class AgentUserPromptStore(
    rootDir: File,
    private val assets: AssetManager? = null,
) {
    private val promptDir = File(rootDir, DIRECTORY_NAME)
    private val backupDir = File(promptDir, BACKUP_DIRECTORY_NAME)
    private val lock = Any()

    @Volatile
    private var cachedBuiltin: AgentUserPromptSnapshot? = null

    fun snapshot(): AgentUserPromptSnapshot = synchronized(lock) { snapshotLocked() }

    /** 写入当前生效的那份文件；还没有文件时写默认名。 */
    fun replaceAll(content: String): AgentUserPromptSnapshot = synchronized(lock) {
        writeLocked(content, resolveFile()?.name ?: DEFAULT_FILE_NAME)
        snapshotLocked()
    }

    /** 清空当前生效的那份文件；清空前先在同一目录的备份子目录留一份副本。 */
    fun clearActive(): AgentUserPromptSnapshot = synchronized(lock) { clearActiveLocked() }

    /**
     * 随包的破甲记忆（[BUILTIN_ASSET_PATH]）。
     *
     * 没接 [AssetManager]（如单测）或 asset 缺失时返回 null——内置缺失不该让整个页面崩掉，
     * 只是不显示「装配内置版本」这个入口。asset 在同一次安装内不会变，读一次即缓存。
     */
    fun builtinSnapshot(): AgentUserPromptSnapshot? {
        cachedBuiltin?.let { return it }
        val manager = assets ?: return null
        val bytes = runCatching {
            manager.open(BUILTIN_ASSET_PATH).use { stream -> stream.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return snapshotOf(bytes, DEFAULT_FILE_NAME).also { cachedBuiltin = it }
    }

    /**
     * 把内置版本写成效文件。已有文件**先备份**到 prompts/.backup/——装配是替换，
     * 不能把用户手上那份悄悄抹掉，所以复用「清空」的备份路径。
     */
    fun installBuiltin(): AgentUserPromptSnapshot = synchronized(lock) {
        val builtin = builtinSnapshot()
            ?: throw AgentUserPromptException(
                code = "PROMPT_BUILTIN_MISSING",
                message = "应用内置的破甲记忆缺失，无法装配",
            )
        installContentLocked(builtin.content)
    }

    /**
     * 用一段已有正文装配生效文件：旧文件先备份，再原子写入。
     *
     * 与 [installBuiltin] 共用同一实现——内置破甲记忆与内置协议库的落盘语义必须一致，
     * 否则「备份失败不覆盖」这类保证会在其中一条链路上悄悄失效。
     */
    fun installContent(content: String): AgentUserPromptSnapshot = synchronized(lock) {
        installContentLocked(content)
    }

    private fun installContentLocked(content: String): AgentUserPromptSnapshot {
        clearActiveLocked()
        writeLocked(content, DEFAULT_FILE_NAME)
        return snapshotLocked()
    }

    private fun clearActiveLocked(): AgentUserPromptSnapshot {
        resolveFile()?.let { active ->
            runCatching {
                if (!backupDir.exists() && !backupDir.mkdirs() && !backupDir.isDirectory) {
                    error("无法创建备份目录")
                }
                val stamp = System.currentTimeMillis()
                active.copyTo(File(backupDir, "${active.name}.$stamp$BACKUP_SUFFIX"), overwrite = true)
            }.getOrElse { throwable ->
                throw AgentUserPromptException(
                    code = "PROMPT_BACKUP_FAILED",
                    message = "备份当前作战协议失败，已保留原文件",
                    cause = throwable,
                )
            }
            if (!active.delete() && active.exists()) {
                throw AgentUserPromptException(
                    code = "PROMPT_CLEAR_FAILED",
                    message = "无法删除作战协议文件",
                )
            }
        }
        return snapshotLocked()
    }

    private fun snapshotLocked(): AgentUserPromptSnapshot {
        val file = resolveFile() ?: return AgentUserPromptSnapshot.EMPTY
        val bytes = try {
            file.readBytes()
        } catch (throwable: IOException) {
            throw AgentUserPromptException(
                code = "PROMPT_READ_FAILED",
                message = "无法读取作战协议文件 ${file.name}",
                cause = throwable,
            )
        }
        return snapshotOf(bytes, file.name)
    }

    /** 截断与哈希的唯一实现：磁盘文件与内置 asset 共用，避免两边口径漂移。 */
    private fun snapshotOf(bytes: ByteArray, fileName: String?): AgentUserPromptSnapshot {
        val raw = bytes.toString(Charsets.UTF_8)
        val truncated = raw.length > MAX_CHARS
        val content = if (truncated) {
            raw.take(MAX_CHARS) +
                "\n[作战协议超过 $MAX_CHARS 字符上限，已截断 ${raw.length - MAX_CHARS} 字符；请精简文件]"
        } else {
            raw
        }
        return AgentUserPromptSnapshot(
            content = content,
            revision = sha256(bytes),
            byteSize = bytes.size,
            charCount = raw.length,
            fileName = fileName,
            truncated = truncated,
        )
    }

    private fun writeLocked(content: String, fileName: String) {
        val name = sanitizeFileName(fileName)
        if (!promptDir.exists() && !promptDir.mkdirs() && !promptDir.isDirectory) {
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法创建 $DIRECTORY_NAME 目录",
            )
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentUserPromptException(
                code = "PROMPT_TOO_LARGE",
                message = "作战协议不能超过 ${MAX_FILE_BYTES / 1024} KiB UTF-8 字节",
            )
        }
        val atomicFile = AtomicFile(File(promptDir, name))
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法开始写入作战协议",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法保存作战协议",
                cause = throwable,
            )
        }
    }

    /** 目录内按文件名排序，返回第一个非空 `.md`；跳过 README、空文件与超限文件。 */
    private fun resolveFile(): File? {
        if (!promptDir.isDirectory) return null
        val candidates = promptDir.listFiles { file ->
            file.isFile &&
                file.name.endsWith(EXTENSION, ignoreCase = true) &&
                !file.name.equals(IGNORED_FILE, ignoreCase = true)
        }?.sortedBy { it.name } ?: return null
        for (candidate in candidates) {
            val length = candidate.length()
            if (length <= 0L || length > MAX_FILE_BYTES) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank()) return candidate
        }
        return null
    }

    /** 只取文件名，禁止路径穿越；非法输入回落到默认名。 */
    private fun sanitizeFileName(fileName: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return DEFAULT_FILE_NAME
        val base = File(trimmed).name
        if (base.isEmpty() || base.startsWith(".") || !base.endsWith(EXTENSION, ignoreCase = true)) {
            return DEFAULT_FILE_NAME
        }
        return base
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val DIRECTORY_NAME = "prompts"
        const val DEFAULT_FILE_NAME = "破甲记忆.md"
        const val IGNORED_FILE = "README.md"

        /**
         * 随包内置破甲记忆的 asset 路径（寒霜空投的 `memory/破甲记忆.md`）。
         *
         * 刻意用 ASCII 文件名：非 ASCII 的 asset 名在打包与 Robolectric 资源查找下都不可靠
         * （实测中文名 asset 读不到）。**装配后落盘的文件名仍是 `破甲记忆.md`**，
         * 中文只出现在用户看得见的那一层。
         *
         * 改这里必须同步 `app/src/main/assets/user_protocol/` 下的实际文件名，
         * 否则「装配内置版本」入口会静默消失（有单测盯着）。
         */
        const val BUILTIN_ASSET_PATH = "user_protocol/pojia-memory.md"

        /**
         * 注入上限：整篇注入，超限显式截断并留标记——提示词不拆 core/headings，每段都是指令。
         *
         * 取值依据：寒霜空投的 `memory/破甲记忆.md`（v5.2 全文）实测 81,044 字符 / 1,337 行。
         * 32k 会从尾部砍掉约六成，而尾部恰好是 Part II/III/IV playbook 与抗压缩常驻的 core block；
         * 96k 让整篇进得去，代价是系统提示占更多 token——UI 会实时显示占用与接近上限的警示。
         */
        const val MAX_CHARS = 96_000

        /** 单文件字节上限。96k 全 CJK 字符按 UTF-8 三字节计约 288 KiB，512 KiB 仍有余量。 */
        const val MAX_FILE_BYTES = 512 * 1024

        private const val EXTENSION = ".md"
        private const val BACKUP_SUFFIX = ".bak"
        private const val BACKUP_DIRECTORY_NAME = ".backup"
    }
}

/**
 * 作战协议的进程级入口。
 *
 * 与 [AgentMemoryRepository] 的分工：记忆是"用户可编辑的背景资料，不是指令"；
 * 作战协议是**用户指令本身**，因此走独立的 system 消息，绝不塞进 memory_core。
 */
internal object AgentUserPromptRepository {

    @Volatile
    private lateinit var store: AgentUserPromptStore

    /** 与 store 同源的 AssetManager：内置协议库走同一个 app.assets。 */
    @Volatile
    private lateinit var assets: AssetManager

    fun init(context: Context) {
        if (!::store.isInitialized) {
            val app = context.applicationContext
            store = AgentUserPromptStore(app.filesDir, app.assets)
            assets = app.assets
        }
    }

    fun snapshot(): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun replaceAll(content: String): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun clearActive(): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.clearActive()
    }

    /** 随包内置的破甲记忆；asset 缺失时返回 null。 */
    fun builtinSnapshot(): AgentUserPromptSnapshot? {
        ensureInitialized()
        return store.builtinSnapshot()
    }

    /** 手动装配内置版本（覆盖当前生效文件，旧文件先备份）。 */
    fun installBuiltin(): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.installBuiltin()
    }

    /**
     * 模板渲染（v6 唯一注入路径）：身份段按当前模型真实提供方填充，工程契约原样保留。
     *
     * 与 [loadForModel] 的分工：
     * - [loadForModel]：45 选 1，切换 = 换整套身份与禁令，会打废请求前缀缓存
     * - 本方法：一份骨架 + 变量，换模型只换身份段
     *
     * 渲染不完整（有占位符残留）时**不注入**并返回源标记——宁可回落到用户文件，
     * 也不能把带 `{{MODEL_IDENTITY}}` 的正文发给模型。
     */
    fun loadTemplateFor(
        enabled: Boolean,
        model: String,
        displayName: String = "",
        knowledgeCutoff: String = "",
    ): AgentUserPromptResolver.Resolution {
        if (!enabled) {
            return AgentUserPromptResolver.Resolution(
                AgentUserPromptSnapshot.EMPTY,
                AgentUserPromptResolver.Source.None,
                reason = "注入总开关关闭",
            )
        }
        ensureInitialized()
        val template = runCatching {
            assets.open(AgentUserPromptTemplate.ASSET_PATH).use { it.readBytes() }
        }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("prompt_template_unavailable") {
                "Prompt template asset unavailable: type=${throwable.safeLogType()}"
            }
            return AgentUserPromptResolver.Resolution(
                AgentUserPromptSnapshot.EMPTY,
                AgentUserPromptResolver.Source.None,
                reason = "模板 asset 缺失",
            )
        }
        if (template.isEmpty()) {
            return AgentUserPromptResolver.Resolution(
                AgentUserPromptSnapshot.EMPTY,
                AgentUserPromptResolver.Source.None,
                reason = "模板 asset 为空",
            )
        }

        val identity = AgentUserPromptModelBinding.identityOf(model, displayName)
        val rendered = AgentUserPromptTemplate.render(
            template = template.toString(Charsets.UTF_8),
            fill = AgentUserPromptTemplate.Fill(
                identity = identity.paragraph,
                model = model.ifBlank { "未知" },
                knowledgeCutoff = knowledgeCutoff,
                notes = identity.notes,
            ),
        )
        if (!rendered.complete) {
            AndroidAgentLogger.warnThrottled("prompt_template_unresolved") {
                "Prompt template has unresolved keys: ${rendered.unresolved.joinToString(",")}"
            }
            return AgentUserPromptResolver.Resolution(
                AgentUserPromptSnapshot.EMPTY,
                AgentUserPromptResolver.Source.None,
                reason = "模板渲染不完整：${rendered.unresolved.joinToString(",")}",
            )
        }

        val bytes = rendered.content.toByteArray(Charsets.UTF_8)
        val raw = rendered.content
        val truncated = raw.length > AgentUserPromptStore.MAX_CHARS
        val content = if (truncated) {
            raw.take(AgentUserPromptStore.MAX_CHARS) +
                "\n[作战协议超过 ${AgentUserPromptStore.MAX_CHARS} 字符上限，已截断 ${raw.length - AgentUserPromptStore.MAX_CHARS} 字符]"
        } else {
            raw
        }
        return AgentUserPromptResolver.Resolution(
            snapshot = AgentUserPromptSnapshot(
                content = content,
                revision = sha256Of(bytes),
                byteSize = bytes.size,
                charCount = raw.length,
                fileName = "破甲记忆.md（模板渲染）",
                truncated = truncated,
            ),
            source = AgentUserPromptResolver.Source.UserFile,
            libraryEntryId = null,
            reason = "模板渲染：${identity.headline}｜${identity.certainty.name}",
        )
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    /**
     * 装配 v6 模板失败的原因，用于页面如实告知「为什么退回了内置版本」。
     */
    internal enum class TemplateInstallFailure(val label: String) {
        ASSET_MISSING("模板 asset 缺失"),
        ASSET_EMPTY("模板 asset 为空"),
        UNRESOLVED("模板占位符未渲染完整"),
        WRITE_FAILED("模板写入失败"),
    }

    /**
     * 装配 v6 模板的结果。
     *
     * [fellBack] 为真时 [snapshot] 是**内置破甲记忆**而不是模板正文——调用方必须在 UI 上说清楚，
     * 否则用户以为装的是 v6，实际跑的是旧版，这种静默替换比直接失败更难排查。
     */
    internal data class TemplateInstallResult(
        val snapshot: AgentUserPromptSnapshot,
        val fellBack: Boolean,
        val failure: TemplateInstallFailure? = null,
        val detail: String = "",
    )

    /**
     * 装配 v6 模板；失败时自动退回内置破甲记忆（[installBuiltin]）。
     *
     * 兜底顺序刻意是「模板 → 内置」而不是反过来：模板是新的默认形态，
     * 内置版本是保底可用，两者的落盘语义完全一致（先备份再原子写入）。
     * 两种情况都会把旧文件备份到 prompts/.backup/，所以退回永远可逆。
     */
    fun installTemplateWithFallback(model: String, displayName: String = ""): TemplateInstallResult {
        ensureInitialized()
        val plan = runCatching { renderTemplateSnapshot(model, displayName) }
        val snapshot = plan.getOrNull()
        if (snapshot != null) {
            return runCatching { store.installContent(snapshot.content) }
                .fold(
                    onSuccess = { installed ->
                        TemplateInstallResult(installed, fellBack = false)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("prompt_template_install_failed") {
                            "Template install failed, falling back: type=${throwable.safeLogType()}"
                        }
                        fallbackToBuiltin(TemplateInstallFailure.WRITE_FAILED, throwable.message.orEmpty())
                    },
                )
        }
        val failure = (plan.exceptionOrNull() as? TemplateRenderException)
            ?: TemplateRenderException(TemplateInstallFailure.UNRESOLVED, plan.exceptionOrNull()?.message.orEmpty())
        return fallbackToBuiltin(failure.failure, failure.message.orEmpty())
    }

    private fun fallbackToBuiltin(
        failure: TemplateInstallFailure,
        detail: String,
    ): TemplateInstallResult {
        AndroidAgentLogger.warn("Prompt template unavailable (${failure.label}), using builtin instead")
        val builtin = runCatching { store.installBuiltin() }.getOrElse { throwable ->
            throw AgentUserPromptException(
                code = "PROMPT_TEMPLATE_AND_BUILTIN_FAILED",
                message = "v6 模板装配失败（${failure.label}），内置版本也不可用：${throwable.message.orEmpty()}",
                cause = throwable,
            )
        }
        return TemplateInstallResult(builtin, fellBack = true, failure = failure, detail = detail)
    }

    private class TemplateRenderException(
        val failure: TemplateInstallFailure,
        message: String,
    ) : IllegalStateException(message)

    /** 渲染模板成可直接落盘的快照；任何一步不满足就抛 [TemplateRenderException]。 */
    /**
     * 运行期模板渲染缓存，按 `model|displayName` 分键。
     *
     * 为什么必须缓存：模板约 80k 字符，每轮 run 都重新读 asset + 做四次全串替换，
     * 在低端机上会直接拖慢首 token。同一模型的渲染结果是纯函数输出，缓存安全；
     * 换模型 = 换键 = 重新渲染一次，这正是「切模型自动换身份」要的行为。
     *
     * 上限保护：正常会话只会在少数几个模型间切换，超过 8 条说明调用方在拿随机串当模型名，
     * 此时清空重建，避免把 80k × N 常驻在内存里。
     */
    private val templateCache =
        java.util.concurrent.ConcurrentHashMap<String, AgentUserPromptSnapshot>()

    internal fun renderForModel(model: String, displayName: String = ""): AgentUserPromptSnapshot? {
        ensureInitialized()
        val key = "${model.trim()}|${displayName.trim()}"
        templateCache[key]?.let { return it }
        val rendered = runCatching { renderTemplateSnapshot(model, displayName) }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("prompt_template_render_failed") {
                "Prompt template render failed for '$key': type=${throwable.safeLogType()}"
            }
            null
        } ?: return null
        if (templateCache.size >= 8) templateCache.clear()
        templateCache[key] = rendered
        return rendered
    }

    private fun renderTemplateSnapshot(model: String, displayName: String): AgentUserPromptSnapshot {
        val bytes = runCatching {
            assets.open(AgentUserPromptTemplate.ASSET_PATH).use { it.readBytes() }
        }.getOrElse {
            throw TemplateRenderException(TemplateInstallFailure.ASSET_MISSING, AgentUserPromptTemplate.ASSET_PATH)
        }
        if (bytes.isEmpty()) {
            throw TemplateRenderException(TemplateInstallFailure.ASSET_EMPTY, "模板 asset 为 0 字节")
        }
        val identity = AgentUserPromptModelBinding.identityOf(model, displayName)
        val rendered = AgentUserPromptTemplate.render(
            template = bytes.toString(Charsets.UTF_8),
            fill = AgentUserPromptTemplate.Fill(
                identity = identity.paragraph,
                model = model.ifBlank { "未知" },
                knowledgeCutoff = "",
                notes = identity.notes,
            ),
        )
        if (!rendered.complete) {
            throw TemplateRenderException(
                TemplateInstallFailure.UNRESOLVED,
                rendered.unresolved.joinToString(","),
            )
        }
        val raw = rendered.content
        if (raw.length > AgentUserPromptStore.MAX_CHARS) {
            // 模板超限就不再截断装配：截断版会把 §4 工程契约砍掉一截，
            // 装上去比用内置版本更糟，所以直接走兜底。
            throw TemplateRenderException(
                TemplateInstallFailure.WRITE_FAILED,
                "模板 ${raw.length} 字符超出 ${AgentUserPromptStore.MAX_CHARS} 上限",
            )
        }
        val out = raw.toByteArray(Charsets.UTF_8)
        return AgentUserPromptSnapshot(
            content = raw,
            revision = sha256Of(out),
            byteSize = out.size,
            charCount = raw.length,
            fileName = AgentUserPromptStore.DEFAULT_FILE_NAME,
            truncated = false,
        )
    }

    /**
     * 内置协议库（随包 asset，移植自 github.com/yooma1/jailbreaks）。
     *
     * 语义与「装配内置版本」完全一致：先备份当前文件、再原子写入、失败不产生半成品；
     * 区别只是正文来源不同。asset 缺失或索引损坏时返回空表，页面隐藏入口而不是崩。
     */
    fun libraryEntries(): List<AgentUserPromptLibraryEntry> {
        ensureInitialized()
        return AgentUserPromptLibrary.entries(assets)
    }

    /** 库里某一条的正文快照（供 UI 预览与 revision 比对）。 */
    fun librarySnapshot(id: String): AgentUserPromptSnapshot? {
        ensureInitialized()
        val entry = AgentUserPromptLibrary.find(assets, id) ?: return null
        val body = AgentUserPromptLibrary.read(assets, entry) ?: return null
        return AgentUserPromptSnapshot(
            content = body.content,
            revision = body.revision,
            byteSize = body.byteSize,
            charCount = body.charCount,
            fileName = null,
            truncated = false,
        )
    }

    /** 装配库里的一条：旧文件先进 .backup/，再写入新正文。 */
    fun installFromLibrary(id: String): AgentUserPromptSnapshot {
        ensureInitialized()
        val entry = AgentUserPromptLibrary.find(assets, id)
            ?: throw AgentUserPromptException(
                code = "PROMPT_LIBRARY_MISSING",
                message = "内置协议库里找不到 $id",
            )
        val body = AgentUserPromptLibrary.read(assets, entry)
            ?: throw AgentUserPromptException(
                code = "PROMPT_LIBRARY_READ_FAILED",
                message = "无法读取内置协议 ${entry.title}",
            )
        return store.installContent(body.content)
    }

    /**
     * 首次启动装配一次随包内置的破甲记忆。
     *
     * **只装一次**：用户之后删掉不会再被写回来（想恢复用页面上的「装配内置版本」）。
     * 已经有文件（导入过 / 手写过）时不覆盖用户的内容，但同样落标记——免得用户清空后
     * 下次启动又被自动装回来，把「清空」变成清不掉。
     *
     * 装配**不等于生效**：是否注入仍由 [AgentUserPromptMode] 决定，默认关闭。
     *
     * @return 本次是否真的写了文件。
     */
    suspend fun seedBuiltinIfNeeded(): Boolean {
        ensureInitialized()
        if (SettingsDataStore.userPromptBuiltinSeeded()) return false
        val alreadyConfigured = runCatching { store.snapshot().available }.getOrDefault(false)
        if (!alreadyConfigured) store.installBuiltin()
        SettingsDataStore.setUserPromptBuiltinSeeded(true)
        return !alreadyConfigured
    }
    fun modeFlow(): Flow<AgentUserPromptMode> =
        SettingsDataStore.userPromptModeFlow().map { AgentUserPromptMode.fromPersistedValue(it) }

    suspend fun mode(): AgentUserPromptMode =
        AgentUserPromptMode.fromPersistedValue(SettingsDataStore.userPromptMode())

    suspend fun setMode(mode: AgentUserPromptMode) =
        SettingsDataStore.setUserPromptMode(mode.persistedValue)

    /**
     * 注入开关。
     *
     * The reverse capability switch is the sole source of truth for skill and prompt status.
     */
    suspend fun enabled(): Boolean = SettingsDataStore.userPromptEnabled()

    suspend fun setEnabled(value: Boolean) = SettingsDataStore.setUserPromptEnabled(value)

    fun isActive(enabled: Boolean): Boolean = enabled

    /** 运行期装载：未开启或文件为空时返回 [AgentUserPromptSnapshot.EMPTY]，调用方据此不注入。 */
    fun loadFor(enabled: Boolean): AgentUserPromptSnapshot =
        if (isActive(enabled)) {
            runCatching { snapshot() }.getOrDefault(AgentUserPromptSnapshot.EMPTY)
        } else {
            AgentUserPromptSnapshot.EMPTY
        }

    /**
     * 运行期装载（模型自适应）：按生效范围与当前模型在「用户文件 / 内置协议库」之间二选一。
     *
     * 顺序一旦定下就不再逐轮变化——同一个会话里换模型会让系统提示换一份，
     * 请求前缀缓存整段失效，所以调用方必须在会话开始时冻结一次结果。
     *
     * 映射不到模型、库条目读失败、asset 缺失都只降级不抛错：协议是增强项，
     * 不能因为它让整轮对话起不来。
     */
    fun loadForModel(
        enabled: Boolean,
        mode: AgentUserPromptMode,
        model: String,
        displayName: String = "",
    ): AgentUserPromptResolver.Resolution {
        if (!enabled) {
            return AgentUserPromptResolver.Resolution(
                AgentUserPromptSnapshot.EMPTY,
                AgentUserPromptResolver.Source.None,
                reason = "注入总开关关闭",
            )
        }
        ensureInitialized()
        val userPrompt = runCatching { snapshot() }.getOrDefault(AgentUserPromptSnapshot.EMPTY)
        val match = AgentUserPromptModelBinding.resolveDetailed(model, displayName)
        val boundId = match?.entryId
        val bindingNote = match?.let { AgentUserPromptModelBinding.describe(it, model) }.orEmpty()
        val entry = boundId?.let { id -> AgentUserPromptLibrary.find(assets, id) }

        // 模板优先：v6 骨架 + 现场身份，取代「库里那整份带自己身份与禁令的协议」。
        //
        // 为什么不再注入库原文：45 份库条目各自写死一套身份（`You are Kimi K3.1…`），
        // 切换模型等于换一整套人格与禁令，而且仓库那 45 份之间的条款互相矛盾。
        // 模板只换 §0 身份段，工程契约固定不变，切换模型不再改变行为边界。
        //
        // 只对 ModelBinding / ReverseOnly 生效：这两个模式的语义就是「按模型选协议」。
        // Global 模式下用户文件是原始意志，必须原样优先，不能被模板顶掉；
        // Off 已在上面提前返回。
        //
        // 渲染失败（asset 缺失 / 占位符残留 / 超限）时回落到库原文，再回落到用户文件——
        // 三层降级保证协议不会因为模板坏掉而整体消失。
        val templateEligible =
            mode == AgentUserPromptMode.ModelBinding || mode == AgentUserPromptMode.ReverseOnly
        val templateRendered =
            if (templateEligible && boundId != null) renderForModel(model, displayName) else null
        if (templateRendered != null) {
            return AgentUserPromptResolver.Resolution(
                snapshot = templateRendered,
                source = AgentUserPromptResolver.Source.UserFile,
                libraryEntryId = boundId,
                reason = "v6 模板现场渲染：$model → $boundId" +
                    (if (bindingNote.isNotEmpty()) "（$bindingNote）" else ""),
            )
        }
        val librarySnapshot = entry?.let { candidate ->
            runCatching { librarySnapshot(candidate.id) }.getOrNull()
        }
        val resolution = AgentUserPromptResolver.resolve(
            mode = mode,
            model = model,
            displayName = displayName,
            userPrompt = userPrompt,
            libraryEntry = entry,
            librarySnapshot = librarySnapshot,
        )
        // 把「降了几级、命中哪一级」并进理由：页面与日志共用同一句，避免两处口径不一致。
        return if (bindingNote.isNotEmpty() && resolution.source == AgentUserPromptResolver.Source.Library) {
            resolution.copy(reason = resolution.reason + "（" + bindingNote + "）")
        } else {
            resolution
        }
    }

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentUserPromptRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
