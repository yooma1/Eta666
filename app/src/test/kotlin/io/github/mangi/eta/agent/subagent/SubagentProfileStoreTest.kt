package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentProfileStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): SubagentProfileStore = SubagentProfileStore(temp.root)

    @Test
    fun presetsAreListedWithTheirToolBoundaries() {
        val profiles = store().all()
        assertEquals(listOf("explore", "coder", "plan"), profiles.map { it.name })
        val coder = profiles.first { it.name == "coder" }
        assertTrue(coder.allowsTool("terminal"))
        assertFalse("子代理不能再派子代理", coder.allowsTool("subagent"))
        assertFalse("子代理不能碰屏幕", coder.allowsTool("tap"))
        assertFalse("子代理不能改档案", coder.allowsTool("subagent_profile"))
    }

    @Test
    fun saveWritesFrontMatterAndReadsItBack() {
        val result = store().save(
            name = "bill-analyzer",
            description = "账单流水归类与异常项定位",
            prompt = "你是账单分析 Subagent。",
            whenToUse = "需要跨月对账时",
            tools = listOf("search_bills", "summarize_bills"),
            disallowedTools = listOf("terminal"),
        )
        val profile = result.getOrThrow()
        assertEquals("bill-analyzer", profile.name)
        assertEquals(listOf("search_bills", "summarize_bills"), profile.effectiveTools())
        assertFalse(profile.allowsTool("terminal"))
        assertTrue(profile.allowsTool("search_bills"))

        val file = temp.root.resolve("agents/bill-analyzer.md")
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.startsWith("---\nname: bill-analyzer"))
        assertTrue(text.contains("disallowedTools: terminal"))
        assertTrue(text.endsWith("你是账单分析 Subagent。\n"))
    }

    @Test
    fun builtinNamesCannotBeOverwrittenOrDeleted() {
        val store = store()
        assertTrue(store.save("explore", "x", "y").isFailure)
        assertTrue(store.delete("explore").isFailure)
    }

    @Test
    fun invalidNamesAndEmptyFieldsAreRejected() {
        val store = store()
        assertTrue(store.save("Bad_Name", "x", "y").isFailure)
        assertTrue(store.save("a", "x", "y").isFailure)
        assertTrue(store.save("ok-name", "", "y").isFailure)
        assertTrue(store.save("ok-name", "desc", "  ").isFailure)
    }

    @Test
    fun deleteRemovesOnlyCustomProfiles() {
        val store = store()
        store.save("log-auditor", "日志审计", "你是日志审计 Subagent。").getOrThrow()
        assertNotNull(store.get("log-auditor"))
        store.delete("log-auditor").getOrThrow()
        assertNull(store.get("log-auditor"))
    }
}

class AgentSubagentToolsetTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val seen = mutableListOf<SubagentRunRequest>()

    private fun toolset(store: SubagentProfileStore = SubagentProfileStore(temp.root)) =
        AgentSubagentToolset(store) { request ->
            seen += request
            AgentModelClient.ToolResult(JSONObject().put("ok", true).put("output", "done:" + request.type).toString())
        }

    private fun call(name: String, json: String) =
        AgentModelClient.ToolCall(id = "call-1", name = name, argumentsJson = json)

    @Test
    fun schemaCarriesTheTwoToolsWithRequiredFields() {
        val array = JSONArray()
        AgentSubagentToolset.appendSchemas(array)
        assertEquals(2, array.length())
        val subagent = array.getJSONObject(0).getJSONObject("function")
        assertEquals("subagent", subagent.getString("name"))
        val required = subagent.getJSONObject("parameters").getJSONArray("required")
        assertEquals("prompt", required.getString(0))
        assertEquals("description", required.getString(1))
        assertTrue(subagent.getString("description").contains("explore"))
        val profileTool = array.getJSONObject(1).getJSONObject("function")
        assertEquals("subagent_profile", profileTool.getString("name"))
    }

    @Test
    fun defaultTypeIsExploreAndSessionIdIsReturned() {
        val result = toolset().execute(call("subagent", """{"prompt":"看看有什么","description":"探查"}"""))
        val payload = JSONObject(result.content)
        assertTrue(payload.getBoolean("ok"))
        assertEquals("explore", payload.getString("subagent_type"))
        assertTrue(payload.getString("subagent_id").startsWith("sub-"))
        assertEquals("done:explore", payload.getString("output"))
        assertEquals("explore", seen.single().profile.name)
    }

    @Test
    fun adHocKebabTypeBecomesEphemeralProfileWithRequestedTools() {
        val result = toolset().execute(
            call(
                "subagent",
                """{"prompt":"列一下","description":"临时","subagent_type":"log-auditor","role":"你是审计员","tools":["read_file","tap"]}""",
            ),
        )
        val payload = JSONObject(result.content)
        assertEquals("log-auditor", payload.getString("subagent_type"))
        val request = seen.single()
        assertTrue(request.profile.systemPrompt.contains("你是审计员"))
        assertTrue(request.profile.allowsTool("read_file"))
        assertFalse("GUI 工具被硬拦", request.profile.allowsTool("tap"))
    }

    @Test
    fun resumeKeepsPriorTranscript() {
        val store = SubagentProfileStore(temp.root)
        val toolset = toolset(store)
        val first = JSONObject(
            toolset.execute(call("subagent", """{"prompt":"第一步","description":"开始"}""")).content,
        )
        val sessionId = first.getString("subagent_id")
        SubagentSessionRegistry.updateTranscript(
            sessionId,
            listOf(AgentModelClient.ConversationMessage(role = "user", content = "第一步")),
        )
        val second = JSONObject(
            toolset.execute(
                call("subagent", """{"prompt":"接着做","description":"续跑","resume":"$sessionId"}"""),
            ).content,
        )
        assertTrue(second.getBoolean("resumed"))
        assertEquals(sessionId, second.getString("subagent_id"))
        assertEquals(1, seen.last().history.size)
    }

    @Test
    fun unknownResumeIdFailsCleanly() {
        val payload = JSONObject(
            toolset().execute(call("subagent", """{"prompt":"x","description":"y","resume":"sub-deadbeef"}""")).content,
        )
        assertFalse(payload.getBoolean("ok"))
        assertEquals("SUBAGENT_UNKNOWN_SESSION", payload.getString("code"))
    }

    @Test
    fun profileToolListsSavesAndDeletes() {
        val toolset = toolset()
        val listed = JSONObject(toolset.execute(call("subagent_profile", """{"action":"list"}""")).content)
        assertEquals(3, listed.getJSONArray("profiles").length())

        val saved = JSONObject(
            toolset.execute(
                call(
                    "subagent_profile",
                    """{"action":"save","name":"schedule-checker","description":"日程冲突检查","prompt":"你是日程检查 Subagent。"}""",
                ),
            ).content,
        )
        assertTrue(saved.getBoolean("ok"))

        val fetched = JSONObject(
            toolset.execute(call("subagent_profile", """{"action":"get","name":"schedule-checker"}""")).content,
        )
        assertEquals("日程冲突检查", fetched.getString("description"))

        val deleted = JSONObject(
            toolset.execute(call("subagent_profile", """{"action":"delete","name":"schedule-checker"}""")).content,
        )
        assertTrue(deleted.getBoolean("ok"))
    }
}
