package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 内置协议库（移植自 github.com/yooma1/jailbreaks）的打包与装配验证。
 *
 * 这条链路的失效方式全是静默的：asset 没进包、index.json 少字段、正文被识别成生效协议——
 * 运行期都只表现为「入口不见了」或「用户那份被莫名其妙换掉」，所以必须有真验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentUserPromptLibraryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val assets get() = RuntimeEnvironment.getApplication().assets

    @Test
    fun indexIsPackagedAndEveryEntryResolvesToRealBody() {
        val entries = AgentUserPromptLibrary.entries(assets)

        assertTrue("index.json 必须随包且不为空", entries.size >= 40)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)

        entries.forEach { entry ->
            val body = AgentUserPromptLibrary.read(assets, entry)
            assertNotNull("正文缺失：${entry.id}", body)
            requireNotNull(body)
            assertTrue("${entry.id} 正文为空", body.content.isNotBlank())
            assertFalse("${entry.id} 不该超上限", body.charCount > AgentUserPromptStore.MAX_CHARS)
            assertEquals("${entry.id} 字节数与索引不符", entry.byteSize, body.byteSize)
            assertEquals(64, body.revision.length)
            // 转码残留检查：Ollama Modelfile 的 PARAMETER 行必须在转换时被剥掉。
            assertFalse("${entry.id} 残留 Modelfile 参数", body.content.contains("PARAMETER temperature"))
            assertFalse("${entry.id} 残留 SYSTEM 包装", body.content.contains("SYSTEM \"\"\""))
        }
    }

    /** 库文件必须是 .txt：prompts/ 的装载规则只认 .md，同目录放 .md 会被误当成生效协议。 */
    @Test
    fun libraryBodiesAreNotMarkdownSoTheyCanNeverBecomeTheActiveFile() {
        val prompts = File(temporaryFolder.newFolder(), AgentUserPromptStore.DIRECTORY_NAME)
            .also { it.mkdirs() }
        File(prompts, "library").mkdirs()
        File(prompts, "library/0x8d-sample.txt").writeText("库正文，不该被选中")
        File(prompts, "README.md").writeText("说明")

        val store = AgentUserPromptStore(prompts.parentFile)
        assertFalse("库正文被误判成生效协议", store.snapshot().available)
    }

    @Test
    fun installFromLibraryBacksUpTheActiveFileAndWritesTheChosenBody() {
        val root = temporaryFolder.newFolder()
        val store = AgentUserPromptStore(root, assets)
        store.replaceAll("用户自己写的那份")

        val entry = AgentUserPromptLibrary.entries(assets).first { it.id.contains("gemini-4-argon") }
        val body = requireNotNull(AgentUserPromptLibrary.read(assets, entry))

        // 直接走 store 的同一装配实现：库装配与内置破甲记忆共用备份语义。
        val installed = store.installContent(body.content)

        assertTrue(installed.available)
        assertFalse(installed.truncated)
        assertEquals(AgentUserPromptStore.DEFAULT_FILE_NAME, installed.fileName)
        assertEquals(body.charCount, installed.charCount)
        assertEquals(body.revision, installed.revision)

        val backups = File(File(root, AgentUserPromptStore.DIRECTORY_NAME), ".backup").listFiles()
        assertNotNull(backups)
        assertEquals(1, backups!!.size)
        assertEquals("用户自己写的那份", backups.single().readText())
    }

    @Test
    fun unknownIdResolvesToNullInsteadOfThrowing() {
        assertNull(AgentUserPromptLibrary.find(assets, "没有这一条"))
        assertNull(AgentUserPromptLibrary.read(assets, AgentUserPromptLibraryEntry(
            id = "missing", title = "missing", assetPath = "user_protocol/library/missing.txt",
            source = "", charCount = 0, byteSize = 0,
        )))
    }
}
