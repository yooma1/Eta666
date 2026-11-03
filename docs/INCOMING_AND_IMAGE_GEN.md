# 分享 / 拖拽导入与生图

本文记录两条在本仓库内自研（非上游）的能力：**外部内容导入聊天草稿** 与 **生图工具**。
两者都不在 `Mangi-11/Eta` 上游，改动集中在本仓库。

最后核对时间：2026-11-03。

---

## 1. 外部内容导入（分享 / 拖拽）

### 1.1 来源与定位

来源于 `bianwuya/Eta` 的 `local` 分支提交 `682fbd3`（「分享与拖放：支持系统分享和中转站导入聊天草稿」），
已整体移植到 `app/src/main/kotlin/io/github/mangi/eta/local/incoming/`。

| 文件 | 职责 |
|---|---|
| `IncomingShareActivity.kt` | 系统分享入口（`ACTION_SEND` / `SEND_MULTIPLE`，`mimeType="*/*"`） |
| `IncomingContentParser.kt`（在 `IncomingContent.kt`） | 把 Intent / ClipData / DragEvent 归一成 `IncomingPayload` |
| `IncomingContentInbox.kt` | 落盘暂存（单次 20 项、总量 64MB、图片 12MB 上限） |
| `IncomingContentHost.kt` | 拖放目标 + 草稿绑定，导入完成后合入当前会话 |

依赖的既有设施：`AgentFileReferenceGateway.importDocumentUri`（复制 content URI 到工作区）、
`AgentImageCodec`（图片预览）、`AgentAppState.attachIncomingContent`（无竞态合稿）。

### 1.2 拖拽只吃图片的问题（已修，`40684cc`）

**现象**：拖图片能进草稿，拖 `.pdf` / `.zip` 完全没反应，连高亮边框都不出现。

**原因**：Compose 的 `Modifier.dragAndDropTarget` 在 startTransfer 阶段按 `ClipDescription`
的 MIME 做**预过滤**，默认只放行 `text/*` 与 `image/*`；非图片的手势根本进不了目标组合项，
`onDrop` 不会被调用。导入链路本身对任意文件都是通的，问题只在"接不接得住"。

**做法**：在内容之上铺一层透明原生 `View`，用 `View.OnDragListener` 接收。

```kotlin
AndroidView(
    factory = { ctx ->
        android.view.View(ctx).apply {
            isClickable = false
            isFocusable = false
            // 触摸一律不消费，穿透到下层 Compose
            setOnTouchListener { _, _ -> false }
            setOnDragListener { _, event -> /* ACTION_DRAG_STARTED 一律 true */ }
        }
    },
    modifier = Modifier.matchParentSize(),
)
```

坑位记录：

- `View.OnDragListener` **不做 MIME 预过滤**，这是绕过 Compose 过滤的关键。
- **不能用 `isEnabled = false`** —— 禁用状态的 View 收不到拖拽事件。
- 铺满的透明 View 必须 `setOnTouchListener { false }`，否则输入框、按钮会被抢触摸而点不动。
- 部分文件管理器只把 URI 放进 `localState` / `ClipDescription`，`clipData` 为空，
  因此补了 `IncomingContentParser.fromDragDescription`。

---

## 2. 生图工具

### 2.1 形态

工具调用，不是模型切换。对话模型判断用户要画图 → 调 `generate_image` →
中转站出图 → 图片落盘并作为视觉输入回灌 → 对话模型继续描述/修改。

代码位置：

| 文件 | 职责 |
|---|---|
| `agent/image/ImageGenConfig.kt` | 配置与 URL 归一化（可填 host / host/v1 / 完整 generations 路径） |
| `agent/image/ImageGenClient.kt` | `/v1/images/generations` 单次请求 + `/v1/models` 列表 |
| `agent/image/ImageGenModelRules.kt` | 按模型名修正参数、异步任务诊断 |
| `agent/image/AgentImageGenToolCatalog.kt` | schema 与执行 |
| `ui/screens/tools/ImageGenSettingsScreen.kt` | 设置页 |

配置与聊天 provider **完全独立**（`SettingsDataStore.IMAGE_GEN_*`），避免互相覆盖。
三个条件同时成立工具才注册：`base_url` / `api_key` / `model` 非空 + 开关打开。

### 2.2 中转站返回形态的兼容

实测中转站（`gpt-image-2.5-sunburst`）**只回 `url`，`b64_json` 为空**。因此解析时：

1. 依次尝试 `b64_json` / `b64` / `image` / `image_base64`；
2. 都失败再回落 `url` 下载（带浏览器 UA，部分 CDN 无 UA 直接 403）；
3. `data` 为空时也尝试解析根层 `{url: ...}`；
4. **必须按魔数验真** —— `Base64.decode` 对任意字符串都可能"成功"，
   不校验会把乱码写成 `.png`（实测出现过 3 字节假图）。要求 PNG/JPEG/WEBP/GIF/BMP
   魔数且 ≥512 字节。

### 2.3 按模型名自动降级（`ImageGenModelRules`）

| 模型族 | 修正 |
|---|---|
| `dall-e-3` | 强制 `n=1`；尺寸只留 1024x1024 / 1792x1024 / 1024x1792（按横竖近似） |
| `dall-e-2` | 尺寸只留方形三档 |
| `gpt-image*` | 省略它不接受的 `response_format` |

再加一层运行时降级：400 且错误文本指向 `response_format` / `size` 时摘掉该参数重试一次。
降级原因回传到工具结果的 `adjustments` 字段，避免模型以为拿到了请求的张数。

**刻意不按模型名预拦异步任务**：不少中转站把 seedream/可灵一类包成了同步返回，
硬拦会误伤。只在响应里出现 `task_id`/`status` 而非 `data` 时判 `IMAGE_GEN_ASYNC_TASK`。

### 2.4 图片如何显示在对话里（踩坑最多的部分）

这是本次改动最曲折的一环，记录完整链路以免重蹈覆辙。

**正确通道只有一条**：

```
AgentLoop.appendToolImages()
  → appendMessage()              ← 必须走它，同时写 messages 与 transcript
    → transcript
      → publishTranscript() → onTranscript
        → AgentRuntimeHistoryReducer.apply()
          → state.messages       ← UI 渲染读这里
            → conversation_messages 表
```

**四个曾经的错误假设**（每一个都单独导致"生成了但看不到"）：

1. 以为 `ToolResult.images` 够了 —— 那只是 provider 侧上下文，不落会话。
2. 以为走 `_eta_observation` 瞬时观察路径够 —— 该标记的消息会被 `AgentContextSession`
   落盘时剔除，UI 永远拿不到。
3. 以为 `appendMessage` 写进 transcript 就够 —— `transcript()` 里给的清理豁免只覆盖
   读取路径，`encodeTranscriptForStorage` **对每条都跑 sanitize**，图片在落库那一步被
   剥成占位文字。`_eta_generated` 标记也会在 `fromJsonObject` 时被丢弃。
4. 以为进 `history`/`journal` 就够 —— runtime 的 transcript 只喂这两者，
   `AgentRuntimeHistoryReducer.apply` 从不往 `state.messages` 写任何东西。

**最终实现要点**：

- `ConversationMessage` 增加 `generatedImages: Boolean` 字段（`@Serializable` 自动往返），
  使其跨"序列化 → 落库 → 读回"存活；`encodeTranscriptForStorage` 与 `sanitizeMessage`
  都尊重它。
- `AgentLoop` 给图片消息设 `_eta_message_id = "generated-image-<ts>"` 作为 UI 侧锚点。
- `AgentRuntimeHistoryReducer` 从 additions 里挑出该前缀的消息，转成 `GeneratedImageMessageUi`
  追加到 `state.messages`，按 id 去重。

**展示**：不复用 `UserMessageUi`（那条是"用户发出的附件"：右对齐灰气泡 + 100dp 缩略图）。
新增 `GeneratedImageMessageUi`（持久化 type `generated_image`），左对齐、按宽铺满、
最大高 420dp、圆角 16dp、展示像素上限 2M、点击进 `ImageViewerDialog` 看原图。

### 2.5 触发

- 工具描述写全触发词：画 / 绘制 / 来一张 / 插画 / 壁纸 / 封面 / 头像 / 海报 / logo /
  示意图 / 配图 / 换风格重画，命中即调用，不要文字代答。
- 系统提示额外注入一条硬规则，**仅在请求里真的存在 `generate_image` 时**注入
  （`AgentModelClient` 遍历 `additionalTools` 判断），避免未配置时把模型引向不存在的工具。

---

## 3. 相关命令

```bash
# 构建（环境相关，见 MAINTAINING.md）
sh /workspace/build_v335.sh

# 产物
app/build/outputs/apk/debug/Eta-v3.4.0-debug.apk
```

生图结果落盘位置：`filesDir/generated-images/gen-<ts>-<n>.<ext>`。

---

## 4. 已知边界

- 生图只支持 OpenAI 兼容的**同步返回**；异步任务制服务（seedream / 可灵等）需要轮询，
  当前不支持，会返回 `IMAGE_GEN_ASYNC_TASK` 并说明原因。
- 分享/拖拽只接受 `content://` URI（拒绝 `file://`），避免外部应用读取 Eta 私有文件。
- 拖拽导入要求当前在 Home 页、侧栏未展开、且不在消息编辑态（`ready` 条件）。
