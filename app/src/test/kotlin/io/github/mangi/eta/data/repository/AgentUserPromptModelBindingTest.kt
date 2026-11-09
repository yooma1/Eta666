package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型 → 内置协议」绑定表与解析顺序。
 *
 * 纯 JVM（无 Android 依赖、无 Robolectric），因此能在 aarch64 Linux 上真跑——
 * Robolectric 在这台机器上直接报 native runtime 不支持，协议相关用例无法作为门禁。
 */
class AgentUserPromptModelBindingTest {

    /** 这台机器上用哪个模型跑，就至少要在表里命中一条。 */
    @Test
    fun currentModelAliasResolvesToDeepseekEntry() {
        assertEquals("deepseek-4-1", AgentUserPromptModelBinding.resolve("deepseek-v4.1-flash-1017"))
    }

    /** 降级链本身：逐段砍尾，并在版本段额外补一条剥补丁号的形态。 */
    @Test
    fun degradeChainDropsTrailingSegmentsThenPatchNumber() {
        assertEquals(
            listOf(
                "deepseek-v4.1-flash-1017",
                "deepseek-v4.1-flash",
                "deepseek-v4.1",
                "deepseek-v4",
                "deepseek",
            ),
            AgentUserPromptModelBinding.degradeChain("deepseek-v4.1-flash-1017"),
        )
    }

    @Test
    fun exactBeatsDegradedAndDegradedBeatsFamily() {
        val exact = requireNotNull(
            AgentUserPromptModelBinding.resolveDetailed("deepseek-v4.1"),
        )
        assertEquals(AgentUserPromptModelBinding.MatchKind.EXACT, exact.kind)
        assertEquals(0, exact.levelsDropped)
        assertEquals("deepseek-4-1", exact.entryId)

        // 链：deepseek-v4.1-flash-1017 → deepseek-v4.1-flash → deepseek-v4.1（第 2 级命中）
        // 第 1 级没有对应条目，所以正确结果是降 2 级，而不是 1 级。
        val degraded = requireNotNull(
            AgentUserPromptModelBinding.resolveDetailed("deepseek-v4.1-flash-1017"),
        )
        assertEquals(AgentUserPromptModelBinding.MatchKind.DEGRADED, degraded.kind)
        assertEquals("deepseek-4-1", degraded.entryId)
        assertEquals(2, degraded.levelsDropped)
        assertEquals("deepseek-v4.1", degraded.matchedOn)

        val family = requireNotNull(
            AgentUserPromptModelBinding.resolveDetailed("deepseek-mystery-model"),
        )
        assertEquals(AgentUserPromptModelBinding.MatchKind.FAMILY, family.kind)
        assertEquals("deepseek-v4", family.entryId)
    }

    /** 裸系列名绝不能在 0 级吃掉具体型号——这是 contains 实现最典型的踩坑。 */
    @Test
    fun bareFamilyKeywordNeverMatchesAtLevelZero() {
        assertEquals("deepseek-v4", AgentUserPromptModelBinding.resolve("deepseek-v4-flash-0701"))
        assertEquals("deepseek-4-1", AgentUserPromptModelBinding.resolve("deepseek-v4.1-flash-1017"))
        assertEquals("glm-5-3-flash", AgentUserPromptModelBinding.resolve("glm-5.3-flash-0715"))
        assertEquals("kimi-k2", AgentUserPromptModelBinding.resolve("moonshotai/kimi-k2-0905"))
        assertEquals("grok-4.7", AgentUserPromptModelBinding.resolve("grok-4.7-mini-0301"))
    }

    @Test
    fun describeExplainsWhichLevelWasUsed() {
        val match = requireNotNull(
            AgentUserPromptModelBinding.resolveDetailed("deepseek-v4.1-flash-1017"),
        )
        val text = AgentUserPromptModelBinding.describe(match, "deepseek-v4.1-flash-1017")
        assertTrue(text.contains("降级 2 级"))
        assertTrue(text.contains("deepseek-v4.1"))
        assertTrue(text.contains("deepseek-4-1"))
    }

    @Test
    fun concreteFamiliesWinOverBareFamilyFallback() {
        assertEquals("gemini-4-argon", AgentUserPromptModelBinding.resolve("gemini-4-argon"))
        assertEquals("gemini-3.1-pro", AgentUserPromptModelBinding.resolve("gemini-3.1-pro-001"))
        assertEquals("deepseek-v4", AgentUserPromptModelBinding.resolve("deepseek-v4"))
        assertEquals("deepseek-4-1", AgentUserPromptModelBinding.resolve("deepseek-4.1"))
        assertEquals("kimi-k2", AgentUserPromptModelBinding.resolve("kimi-k2-0905"))
        assertEquals("glm-zcode-5.3", AgentUserPromptModelBinding.resolve("glm-zcode-5.3"))
        assertEquals("grok-4.7", AgentUserPromptModelBinding.resolve("grok-4.7-fast"))
        assertEquals("mistral-large-4", AgentUserPromptModelBinding.resolve("mistral-large-4"))
        assertEquals(
            "0x8d-jailbreaks__mistral-small-4-duck.ai",
            AgentUserPromptModelBinding.resolve("mistral-small-4"),
        )
    }

    /** 服务商前缀（`openai/`、`moonshotai/`）不该影响判定。 */
    @Test
    fun providerPrefixesAndCaseDoNotMatter() {
        assertEquals("qwen-3.8", AgentUserPromptModelBinding.resolve("Qwen/Qwen3.8-72B"))
        assertEquals("sonnet-v2", AgentUserPromptModelBinding.resolve("anthropic/claude-sonnet-5"))
        assertEquals("opus-v2", AgentUserPromptModelBinding.resolve("Claude-Opus-5"))
    }

    /** 环境事实：模型可能是服务商别名，绑定表只按名字匹配，不推断版本或能力。 */
    @Test
    fun unknownModelReturnsNullSoCallerFallsBackToUserFile() {
        assertNull(AgentUserPromptModelBinding.resolve("some-internal-model-v9"))
        assertNull(AgentUserPromptModelBinding.resolve(""))
    }

    /** 每条规则指向的 id 都必须在随包索引里存在（防止生成表时手改出错）。 */
    @Test
    fun everyRuleTargetsAnEntryThatExistsInThePackagedIndex() {
        val entries = bundledLibraryIds()
        assertTrue("随包索引至少要有几十条", entries.size >= 40)
        AgentUserPromptModelBinding.rules.forEach { rule ->
            assertTrue("规则指向不存在的条目 ${rule.entryId}", rule.entryId in entries)
            assertTrue("规则关键词为空：${rule.entryId}", rule.keywords.isNotEmpty())
        }
    }

    /** 每个库条目都至少有一条规则能命中（否则库里有条目永远装不上）。 */
    @Test
    fun everyLibraryEntryIsReachableByAtLeastOneRule() {
        val targets = AgentUserPromptModelBinding.rules.map { it.entryId }.toSet()
        val unreachable = bundledLibraryIds() - targets -
            // 这两个是「通用兜底/风格类」协议，按名字匹配模型本身没有意义，靠手动装配。
            setOf(
                "unified",
                "0x8d-jailbreaks__bigpickle-opencode",
                "0x8d-jailbreaks__grok-fast",
                "0x8d-jailbreaks__kimi-standard",
                "0x8d-jailbreaks__gemini-chat",
                "glm-zcode-5.3-agents",
                "glm5-3opencode",
                "muse-spark-1.3",
                // 上一代同族（v1）：新模型按名字不该命中旧版协议，只能手动装配。
                "opus-v1",
                "sonnet-v1",
            )
        assertTrue("这些条目没有任何模型能命中：$unreachable", unreachable.isEmpty())
    }

    @Test
    fun resolverPrefersUserFileInGlobalModeAndLibraryInBindingMode() {
        val user = snapshotOf("用户自己的破甲记忆")
        val lib = snapshotOf("Gemini 协议正文")
        val entry = entryOf("gemini-4-argon")

        val global = AgentUserPromptResolver.resolve(
            mode = AgentUserPromptMode.Global,
            model = "gemini-4-argon",
            userPrompt = user,
            libraryEntry = entry,
            librarySnapshot = lib,
        )
        assertEquals(AgentUserPromptResolver.Source.UserFile, global.source)
        assertEquals("用户自己的破甲记忆", global.snapshot.content)

        val bound = AgentUserPromptResolver.resolve(
            mode = AgentUserPromptMode.ModelBinding,
            model = "gemini-4-argon",
            userPrompt = user,
            libraryEntry = entry,
            librarySnapshot = lib,
        )
        assertEquals(AgentUserPromptResolver.Source.Library, bound.source)
        assertEquals("gemini-4-argon", bound.libraryEntryId)
        assertEquals("Gemini 协议正文", bound.snapshot.content)
    }

    @Test
    fun bindingModeFallsBackToUserFileWhenModelHasNoEntry() {
        val user = snapshotOf("用户自己的破甲记忆")
        val resolved = AgentUserPromptResolver.resolve(
            mode = AgentUserPromptMode.ModelBinding,
            model = "unknown-model",
            userPrompt = user,
            libraryEntry = null,
            librarySnapshot = null,
        )
        assertEquals(AgentUserPromptResolver.Source.UserFile, resolved.source)
        assertTrue(resolved.reason.contains("没有内置协议绑定"))
    }

    @Test
    fun offModeInjectsNothingEvenWhenBothSourcesExist() {
        val resolved = AgentUserPromptResolver.resolve(
            mode = AgentUserPromptMode.Off,
            model = "gemini-4-argon",
            userPrompt = snapshotOf("用户文件"),
            libraryEntry = entryOf("gemini-4-argon"),
            librarySnapshot = snapshotOf("库正文"),
        )
        assertEquals(AgentUserPromptResolver.Source.None, resolved.source)
        assertTrue(!resolved.available)
    }

    @Test
    fun bindingModeWithoutAnyContentResolvesToNoneInsteadOfThrowing() {
        val resolved = AgentUserPromptResolver.resolve(
            mode = AgentUserPromptMode.ModelBinding,
            model = "gemini-4-argon",
            userPrompt = AgentUserPromptSnapshot.EMPTY,
            libraryEntry = entryOf("gemini-4-argon"),
            librarySnapshot = null,
        )
        assertEquals(AgentUserPromptResolver.Source.None, resolved.source)
        assertTrue(!resolved.available)
    }

    @Test
    fun persistedModeValueRoundTripsForModelBinding() {
        assertEquals(
            AgentUserPromptMode.ModelBinding,
            AgentUserPromptMode.fromPersistedValue("model"),
        )
    }

    // ---- helpers ----

    private fun snapshotOf(text: String) = AgentUserPromptSnapshot(
        content = text,
        revision = "rev-${text.hashCode()}",
        byteSize = text.toByteArray(Charsets.UTF_8).size,
        charCount = text.length,
        fileName = "破甲记忆.md",
        truncated = false,
    )

    private fun entryOf(id: String) = AgentUserPromptLibraryEntry(
        id = id,
        title = id,
        assetPath = "user_protocol/library/$id.txt",
        source = "",
        charCount = 0,
        byteSize = 0,
    )

    /** 直接读随包 index.json（workspace 相对路径），避免依赖 AssetManager。 */
    private fun bundledLibraryIds(): Set<String> {
        val candidates = listOf(
            File("app/src/main/assets/user_protocol/library/index.json"),
            File("../app/src/main/assets/user_protocol/library/index.json"),
        )
        val index = candidates.firstOrNull { it.isFile } ?: return emptySet()
        val text = index.readText()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
    }
}
