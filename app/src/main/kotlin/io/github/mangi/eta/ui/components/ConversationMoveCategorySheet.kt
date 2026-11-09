package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.ConversationCategory
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「移动到分类」底部弹层：选一个已有分类，或就地新建一个。
 *
 * 与聊天页其它对话框同一套 WindowDialog，避免再引入一层自定义浮层。
 */
@Composable
internal fun ConversationMoveCategorySheet(
    show: Boolean,
    categories: List<ConversationCategory>,
    currentCategoryId: String?,
    onMove: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var newName by remember { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme
    EtaWindowDialog(
        show = show,
        title = "移动到分类",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "未分类",
                style = MiuixTheme.textStyles.body2,
                color = if (currentCategoryId.isNullOrBlank()) colors.primary else colors.onSurfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMove(null) }
                    .padding(vertical = 10.dp),
            )
            categories.forEach { category ->
                Text(
                    text = "${category.name}（${category.conversationCount}）",
                    style = MiuixTheme.textStyles.body2,
                    color = if (category.id == currentCategoryId) colors.primary else colors.onSurfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMove(category.id) }
                        .padding(vertical = 10.dp),
                )
            }
            TextField(
                value = newName,
                onValueChange = { newName = it.take(16) },
                label = "新建分类",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            EtaTextButton(
                text = "创建并移入",
                enabled = newName.isNotBlank(),
                onClick = {
                    onCreate(newName)
                    newName = ""
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!currentCategoryId.isNullOrBlank()) {
                EtaTextButton(
                    text = "删除当前分类（会话回到未分类）",
                    onClick = { onDelete(currentCategoryId) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
