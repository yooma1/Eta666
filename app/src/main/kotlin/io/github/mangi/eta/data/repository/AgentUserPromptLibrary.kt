package io.github.mangi.eta.data.repository

import android.content.res.AssetManager
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条内置作战协议（随包 asset，来自 github.com/yooma1/jailbreaks 的移植版）。
 *
 * [assetPath] 是 `assets/` 下的相对路径；正文在首次读取时按 [MAX_CHARS] 截断并与
 * 磁盘文件共用同一套截断口径（[AgentUserPromptStore] 的 `snapshotOf`）。
 */
internal data class AgentUserPromptLibraryEntry(
    val id: String,
    val title: String,
    val assetPath: String,
    val source: String,
    val charCount: Int,
    val byteSize: Int,
)

/**
 * 随包协议库的索引与正文读取。
 *
 * 目录约定（改这里必须同步 build 脚本与单测）：
 * - `assets/user_protocol/library/index.json`：索引
 * - `assets/user_protocol/library/<id>.txt`：正文
 *
 * 正文用 `.txt` 而不是 `.md` 是硬约束：[AgentUserPromptStore.resolveFile] 会取 prompts/
 * 下第一个非空 `.md`，同目录放 `.md` 会被误当成生效协议（库文件本该只在装配时才生效）。
 *
 * 为什么内置：这些协议是「整篇 system 提示」，用户不可能在手机上逐字敲 20 万字符；
 * 装配语义与破甲记忆一致——**装配 ≠ 生效**，写入 prompts/ 之后是否注入仍只看总开关。
 */
internal object AgentUserPromptLibrary {

    const val INDEX_ASSET_PATH = "user_protocol/library/index.json"

    private val cache = mutableMapOf<String, List<AgentUserPromptLibraryEntry>>()

    /** 读取索引；asset 缺失或解析失败时返回空表（页面据此隐藏入口，不崩溃）。 */
    fun entries(assets: AssetManager): List<AgentUserPromptLibraryEntry> =
        synchronized(cache) {
            cache[INDEX_ASSET_PATH] ?: runCatching { parse(assets) }
                .getOrElse { emptyList() }
                .also { cache[INDEX_ASSET_PATH] = it }
        }

    fun find(assets: AssetManager, id: String): AgentUserPromptLibraryEntry? =
        entries(assets).firstOrNull { it.id == id }

    /** 读正文并从 asset 流里直接算 revision，供 UI 判断「当前文件是否就是这一条」。 */
    fun read(assets: AssetManager, entry: AgentUserPromptLibraryEntry): PromptLibraryBody? {
        val bytes = runCatching {
            assets.open(entry.assetPath).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val raw = bytes.toString(Charsets.UTF_8)
        return PromptLibraryBody(
            content = raw,
            revision = sha256(bytes),
            charCount = raw.length,
            byteSize = bytes.size,
        )
    }

    private fun parse(assets: AssetManager): List<AgentUserPromptLibraryEntry> {
        val text = assets.open(INDEX_ASSET_PATH).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val array: JSONArray = root.optJSONArray("entries") ?: return emptyList()
        val result = ArrayList<AgentUserPromptLibraryEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty() || id.contains('/') || id.startsWith(".")) continue
            result += AgentUserPromptLibraryEntry(
                id = id,
                title = item.optString("title").ifBlank { id },
                assetPath = item.optString("file").takeIf { it.isNotBlank() }
                    ?: "user_protocol/library/$id.txt",
                source = item.optString("source"),
                charCount = item.optInt("chars"),
                byteSize = item.optInt("bytes"),
            )
        }
        return result.sortedBy { it.id }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    internal data class PromptLibraryBody(
        val content: String,
        val revision: String,
        val charCount: Int,
        val byteSize: Int,
    )
}
