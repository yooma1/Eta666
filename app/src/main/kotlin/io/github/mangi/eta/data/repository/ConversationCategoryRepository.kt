package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.ConversationCategoryEntity
import io.github.mangi.eta.data.db.ConversationCategoryMemberEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID

/** 侧栏里的一个分类及其会话数。 */
internal data class ConversationCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val conversationCount: Int,
)

/**
 * 会话分类的读写入口。
 *
 * 分类只是收纳方式：删掉分类不影响会话本身，只让它们回到「未分类」。
 * 归属变化会立即落库，不等待会话整体保存。
 */
internal object ConversationCategoryRepository {
    const val UNCATEGORIZED_ID = ""
    private const val MAX_NAME = 16

    private fun dao(context: Context) = EtaDatabase.get(context).conversationCategoryDao()

    fun categories(context: Context): List<ConversationCategory> = runCatching {
        dao(context).categoriesWithCount().map {
            ConversationCategory(
                id = it.id,
                name = it.name,
                sortOrder = it.sortOrder,
                conversationCount = it.conversationCount,
            )
        }
    }.getOrDefault(emptyList())

    /** 会话 → 分类 id 的映射；不在映射里的会话就是「未分类」。 */
    fun assignments(context: Context): Map<String, String> = runCatching {
        dao(context).members().associate { it.conversationId to it.categoryId }
    }.getOrDefault(emptyMap())

    fun categoryIdOf(context: Context, conversationId: String): String? =
        runCatching { dao(context).categoryIdOf(conversationId) }.getOrNull()

    fun createCategory(context: Context, name: String): ConversationCategory? {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return null
        val existing = categories(context)
        if (existing.any { it.name == trimmed }) return existing.first { it.name == trimmed }
        val entity = ConversationCategoryEntity(
            id = "cat-${UUID.randomUUID()}",
            name = trimmed,
            sortOrder = existing.size,
            createdAt = System.currentTimeMillis(),
        )
        runCatching { dao(context).upsertCategory(entity) }
        return ConversationCategory(entity.id, entity.name, entity.sortOrder, 0)
    }

    fun renameCategory(context: Context, categoryId: String, name: String): Boolean {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return false
        val current = categories(context).firstOrNull { it.id == categoryId } ?: return false
        return runCatching {
            dao(context).upsertCategory(
                ConversationCategoryEntity(
                    id = current.id,
                    name = trimmed,
                    sortOrder = current.sortOrder,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }.isSuccess
    }

    fun deleteCategory(context: Context, categoryId: String): Boolean =
        runCatching { dao(context).deleteCategoryCascade(categoryId) }.isSuccess

    /** 把会话移进分类；[categoryId] 传 null 或空串表示移出到「未分类」。 */
    fun assignConversation(context: Context, conversationId: String, categoryId: String?): Boolean {
        val dao = dao(context)
        val target = categoryId?.takeIf { it.isNotBlank() }
        return runCatching {
            if (target == null) dao.deleteMember(conversationId) else {
                dao.upsertMember(
                    ConversationCategoryMemberEntity(
                        conversationId = conversationId,
                        categoryId = target,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }.isSuccess
    }
}
