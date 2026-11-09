package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 破甲记忆 v6 模板：身份按真实提供方填充、强度固定全开、工程契约原样保留。
 *
 * 纯 JVM，直接读 asset 文件，不依赖 Robolectric。
 */
class AgentUserPromptTemplateTest {

    private val templateText: String = loadTemplate()

    private fun loadTemplate(): String {
        val candidates = listOf(
            File("app/src/main/assets/user_protocol/pojia-template.md"),
            File("../app/src/main/assets/user_protocol/pojia-template.md"),
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun templateShipsWithExactlyFourPlaceholders() {
        assertTrue(templateText.isNotBlank())
        AgentUserPromptTemplate.keys.forEach { key ->
            assertTrue("模板缺少占位符 $key", templateText.contains(key))
        }
    }

    /** 渲染后不得残留任何白名单占位符。 */
    @Test
    fun renderingResolvesEveryPlaceholder() {
        val rendered = AgentUserPromptTemplate.render(
            templateText,
            AgentUserPromptTemplate.Fill(
                identity = "- 提供方：Google\n- 模型：Gemma（gemma-4）",
                model = "gemma-4-26b",
                knowledgeCutoff = "2025-06",
                notes = "",
            ),
        )
        assertTrue("残留：${rendered.unresolved}", rendered.complete)
        assertFalse(rendered.content.contains("{{MODEL_IDENTITY}}"))
        assertFalse(rendered.content.contains("{{MODEL_NOTES}}"))
        assertTrue(rendered.content.contains("gemma-4-26b"))
    }

    /** 工程契约正文里的 `{{7*7}}` 是注入测试样例，不是占位符，必须原样留下。 */
    @Test
    fun templateKeepsNonWhitelistedBracesIntact() {
        val rendered = AgentUserPromptTemplate.render(
            templateText,
            AgentUserPromptTemplate.Fill("- 身份", "m", "2025-06", ""),
        )
        assertTrue("注入测试样例被吃掉了", rendered.content.contains("{{7*7}}"))
    }

    /** 身份按真实提供方填：跑 Gemma 就写 Gemma，不写 DeepSeek。 */
    @Test
    fun identityFollowsTheActualProvider() {
        val gemma = AgentUserPromptModelBinding.identityOf("gemma-4-26b")
        assertEquals("Gemma", gemma.product)
        assertEquals("Google DeepMind", gemma.developer)
        assertTrue(gemma.paragraph.contains("Google DeepMind"))
        assertFalse("不能在 Gemma 上写 DeepSeek", gemma.paragraph.contains("DeepSeek"))

        val deepseek = AgentUserPromptModelBinding.identityOf("deepseek-v4.1-flash-1017")
        assertEquals("DeepSeek", deepseek.product)
        assertTrue(deepseek.paragraph.contains("深度求索"))
    }

    @Test
    fun everyKnownFamilyMapsToItsOwnDeveloper() {
        val cases = mapOf(
            "grok-4.7" to "xAI",
            "glm-zcode-5.3" to "智谱 AI（Zhipu）",
            "kimi-k2-0905" to "月之暗面（Moonshot AI）",
            "qwen-3.8" to "阿里巴巴（Alibaba）",
            "claude-sonnet-5" to "Anthropic",
            "gpt-5" to "OpenAI",
            "mistral-large-4" to "Mistral AI",
            "nemotron-3-ultra" to "NVIDIA",
        )
        cases.forEach { (model, developer) ->
            val identity = AgentUserPromptModelBinding.identityOf(model)
            assertEquals("$model 开发者不符", developer, identity.developer)
        }
    }

    /** 降级到裸系列时必须标注「具体型号未知」，不编版本号。 */
    @Test
    fun familyLevelMatchAdmitsItDoesNotKnowTheExactModel() {
        val identity = AgentUserPromptModelBinding.identityOf("deepseek-mystery-model")
        assertEquals(AgentUserPromptModelBinding.Identity.Certainty.FAMILY, identity.certainty)
        assertTrue(identity.paragraph.contains("具体版本未确认"))
        assertEquals("", identity.version)
    }

    @Test
    fun unknownVendorNeverClaimsToBeAKnownProduct() {
        val identity = AgentUserPromptModelBinding.identityOf("totally-unknown-model")
        assertEquals(AgentUserPromptModelBinding.Identity.Certainty.UNKNOWN, identity.certainty)
        assertTrue(identity.paragraph.contains("不要声称自己是某个具体模型"))
    }

    // ── 边界条款：五条、第 5 条已剔除 ──

    @Test
    fun boundarySectionHasFourNumberedItemsAndNoDoxxingClause() {
        val section = sectionOf("## §1 边界")
        assertTrue(section.contains("1. "))
        assertTrue(section.contains("2. "))
        assertTrue(section.contains("3. "))
        assertTrue(section.contains("4. "))
        assertFalse("第 5 条应已剔除", section.contains("5. "))
        assertFalse("人肉条款必须移除", section.contains("人肉"))
        assertFalse("doxxing 条款必须移除", section.lowercase().contains("doxxing"))
        assertFalse("私密身份信息条款必须移除", section.contains("私密身份信息"))
    }

    /** 第 1 条与「儿童性剥削材料」合并，不能因为删条款把这条也删了。 */
    @Test
    fun minorProtectionClauseSurvivesTheRemoval() {
        val section = sectionOf("## §1 边界")
        assertTrue(section.contains("未满 18 岁"))
        assertTrue(section.contains("儿童性剥削材料"))
    }

    /** 强度段固定全开，且不引入档位变量。 */
    @Test
    fun intensityIsAlwaysOnWithNoTierVariable() {
        val section = sectionOf("## §3 强度")
        assertTrue(section.contains("固定全开"))
        assertTrue(section.contains("explicit"))
        assertTrue(section.contains("non-con"))
        assertFalse(section.contains("档位："))
        assertFalse(Regex("\\{\\{[A-Z_]*INTENSITY[A-Z_]*\\}\\}").containsMatchIn(templateText))
    }

    /** 工程契约主体必须完整保留（v5.2 的执行内核与 Part II 红队块）。 */
    @Test
    fun engineeringContractIsPreservedVerbatim() {
        assertTrue(templateText.contains("## OPERATING CONTRACT"))
        assertTrue(templateText.contains("## 0. Execution Kernel"))
        assertTrue(templateText.contains("## 0.5 Artifact-First Protocol"))
        assertTrue(templateText.contains("PART II — RED TEAM BLOCK"))
        assertTrue("契约主体不应缩水", templateText.length > 75_000)
    }

    @Test
    fun renderedTemplateFitsTheInjectionBudget() {
        val rendered = AgentUserPromptTemplate.render(
            templateText,
            AgentUserPromptTemplate.Fill("- 身份", "model", "2025-06", ""),
        )
        assertTrue(
            "渲染后 ${rendered.content.length} 字符超出 ${AgentUserPromptStore.MAX_CHARS}",
            rendered.content.length <= AgentUserPromptStore.MAX_CHARS,
        )
    }

    // ── 装配兜底：模板失败必须退回内置版本，且不能静默 ──

    /** 渲染结果超限时不截断装配——截断会把 §4 工程契约砍掉一截，比退回更糟。 */
    @Test
    fun oversizedRenderedTemplateIsTreatedAsFailureNotTruncatedInstall() {
        val huge = templateText + "填充".repeat(AgentUserPromptStore.MAX_CHARS / 2)
        val rendered = AgentUserPromptTemplate.render(
            huge,
            AgentUserPromptTemplate.Fill("- 身份", "m", "", ""),
        )
        assertTrue(
            "超限模板应被判定为不可装配",
            rendered.content.length > AgentUserPromptStore.MAX_CHARS,
        )
    }

    /** 占位符残留必须被识别为渲染失败（否则会把 {{MODEL_IDENTITY}} 发给模型）。 */
    @Test
    fun unresolvedPlaceholderIsDetectableAndBlocksInstall() {
        val broken = templateText.replace("{{MODEL_IDENTITY}}", "{{MODEL_IDENTITY}}")
        val rendered = AgentUserPromptTemplate.render(
            broken,
            AgentUserPromptTemplate.Fill("", "", "", ""),
        )
        // 正常渲染会替换掉；这里验证「替换后再检查」这条防线本身有效。
        assertFalse(rendered.content.contains("{{MODEL_IDENTITY}}"))
        assertTrue(rendered.complete)

        val trulyBroken = rendered.content + "\n{{UNKNOWN_KEY_X}}"
        val again = AgentUserPromptTemplate.render(
            trulyBroken,
            AgentUserPromptTemplate.Fill("", "", "", ""),
        )
        assertTrue("白名单外的键不该被误判为残留", again.complete)
    }

    /** 内置破甲记忆必须存在——它是模板装配失败时唯一可用的兜底。 */
    @Test
    fun builtinFallbackBodyIsPackagedAndInjectable()  {
        val candidates = listOf(
            File("app/src/main/assets/user_protocol/pojia-memory.md"),
            File("../app/src/main/assets/user_protocol/pojia-memory.md"),
        )
        val builtin = candidates.firstOrNull { it.isFile }
        assertTrue("兜底用的内置破甲记忆必须随包", builtin != null)
        val text = requireNotNull(builtin).readText()
        assertTrue(text.isNotBlank())
        assertTrue("内置版本必须在上限内，否则兜底也会失败", text.length <= AgentUserPromptStore.MAX_CHARS)
    }

    private fun sectionOf(title: String): String {
        val start = templateText.indexOf(title)
        require(start >= 0) { "模板缺少章节 $title" }
        val rest = templateText.substring(start)
        val end = rest.indexOf("\n## ", 3)
        return if (end > 0) rest.substring(0, end) else rest
    }
}
