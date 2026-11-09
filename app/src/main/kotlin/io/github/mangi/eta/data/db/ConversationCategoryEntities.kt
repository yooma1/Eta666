package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * 会话分类。
 *
 * 分类只是用户自己的收纳方式，不参与 Agent 运行：删掉分类不会动会话，
 * 只会让它们回到「未分类」。
 */
@Entity(tableName = "conversation_categories")
internal data class ConversationCategoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 会话 → 分类的归属；一个会话最多属于一个分类。 */
@Entity(
    tableName = "conversation_category_members",
    indices = [Index(name = "idx_conv_category_members_category", value = ["category_id"])],
)
internal data class ConversationCategoryMemberEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal data class ConversationCategoryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "conversation_count") val conversationCount: Int,
)

@Dao
internal interface ConversationCategoryDao {
    @Query("SELECT * FROM conversation_categories ORDER BY sort_order ASC, created_at ASC")
    fun categories(): List<ConversationCategoryEntity>

    @Query(
        "SELECT c.id AS id, c.name AS name, c.sort_order AS sort_order, " +
            "(SELECT COUNT(*) FROM conversation_category_members m WHERE m.category_id = c.id) AS conversation_count " +
            "FROM conversation_categories c ORDER BY c.sort_order ASC, c.created_at ASC",
    )
    fun categoriesWithCount(): List<ConversationCategoryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCategory(entity: ConversationCategoryEntity)

    @Query("DELETE FROM conversation_categories WHERE id = :categoryId")
    fun deleteCategory(categoryId: String)

    @Query("DELETE FROM conversation_category_members WHERE category_id = :categoryId")
    fun deleteMembersOfCategory(categoryId: String)

    @Query("SELECT * FROM conversation_category_members")
    fun members(): List<ConversationCategoryMemberEntity>

    @Query("SELECT category_id FROM conversation_category_members WHERE conversation_id = :conversationId LIMIT 1")
    fun categoryIdOf(conversationId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMember(entity: ConversationCategoryMemberEntity)

    @Query("DELETE FROM conversation_category_members WHERE conversation_id = :conversationId")
    fun deleteMember(conversationId: String)

    /** 删分类与清归属必须同事务，否则中间态会出现指向不存在分类的成员行。 */
    @Transaction
    fun deleteCategoryCascade(categoryId: String) {
        deleteMembersOfCategory(categoryId)
        deleteCategory(categoryId)
    }
}
