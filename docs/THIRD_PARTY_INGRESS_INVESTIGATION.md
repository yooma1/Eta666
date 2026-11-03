# 第三方应用内容导入 Eta 的可行性调研

记录 2026-11-03 对 ColorOS「智能侧边栏 · 中转站」的逆向结论，用于判断
"微信 / QQ 里长按文件拖到中转站或 Eta" 是否可行。

结论先行：**不可行，且不是 Eta 侧能修的**。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 系统 | ColorOS / Android 16（RMX5200） |
| 侧边栏包 | `com.coloros.smartsidebar` 16.14.6 |
| 关键组件 | `com.oplus.smartsidebar.panelview.transferdock.view.TransferDockReceiveActivity` |

---

## 2. 中转站有两套接收通道（反编译取证）

| 通道 | 入口 | 依赖 |
|---|---|---|
| 拖拽 | `TransferDockUtil.handleTransferByDragEvent(DragEvent)` | 读取 `DragEvent.getClipData()` |
| Intent 分享 | `TransferDockUtil.handleTransferByIntent(Intent)` | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` |

`TransferDockReceiveActivity` 的 manifest 声明：

```
exported=true
enabled=false          ← 不在系统分享菜单里露出
permission: 无
intent-filter:
  - ACTION_SEND          + DEFAULT + mimeType="*/*"
  - ACTION_SEND_MULTIPLE + DEFAULT + mimeType="*/*"
  - 自定义 action "com.smartsidebar.transferdock.TransferDockReceiveActivity"
```

代码里还有一条明确的阻断：

```java
// TransferDockReceiveActivity.checkSidebarPermission()
if (isDragToPanel()) {                   // 携带 android.intent.extra.HIDE_DRAG_SOURCE_TASK_ID
    // block drag to panel share
    return;
}
TransferDockUtil.handleTransferByIntent(intent);
```

以及可用性判定：

```java
// TransferDockUtil.isClipDataAvailable(ClipData)
boolean hasValidUri = clipData.getItemAt(0).getUri() != null;
return hasValidUri || (text != null && text.length() != 0);
```

---

## 3. 微信 / QQ 拖不动的真实原因

它们的**长按菜单是自绘浮层（PopupWindow / Dialog），不调用 `View.startDragAndDrop()`**。

因此：

- 不会产生系统 Drag Session；
- `ACTION_DRAG_STARTED` 不会广播；
- 中转站（以及任何接收方，包括 Eta 的拖放目标）的 `OnDragListener` 收不到事件。

文件管理器能拖，是因为它确实调用了 `startDragAndDrop()` 并附带 `content://` URI。

**这是第三方应用的交互实现，系统侧没有开关。**

---

## 4. 外部注入也不通

```bash
# 尝试显式启动该 Activity
am start -a android.intent.action.SEND -t text/plain \
  com.coloros.smartsidebar/.panelview.transferdock.view.TransferDockReceiveActivity
# Error: Activity class {...} does not exist.
```

原因：`enabled=false` 时 `am start` 会被系统拒绝启动（即便 `exported=true` 且无签名权限）。

也没有公开的 ContentProvider / Broadcast 接收文件，唯一入口就是该 Activity 的 Intent 路由。

---

## 5. 可用的替代路径

| 路径 | 说明 | 状态 |
|---|---|---|
| 分享到 Eta | 微信/QQ 长按 → 分享 → Eta → 落草稿（支持任意文件） | ✅ 已实现 |
| 文件管理器中转 | 先把文件放到文件管理器可读位置（如 Download），再从那里拖进中转站 | ✅ 系统自带 |
| Eta 转发到中转站 | Eta 用显式 ComponentName 调 `TransferDockReceiveActivity` | ⚠️ 未验证，`enabled=false` 可能同样被拒 |
| Hook 微信启用拖拽 | LSPosed 拦截长按回调，强制 `startDragAndDrop()` | ❌ 破坏原交互，不做 |

---

## 6. 对 Eta 的意义

- Eta 的 `IncomingShareActivity` 已注册 `ACTION_SEND` / `SEND_MULTIPLE` + `mimeType="*/*"`，
  因此**第三方应用分享到 Eta 是通的**，这条路径覆盖了「微信/QQ 里拿不到文件」的需求。
- Eta 的拖放目标同样依赖系统 Drag Session，因此**不能指望在微信里长按直接拖进 Eta** 能成。
- 若将来要支持"从侧边栏中转站拖进 Eta"，需要中转站具备拖出能力（`startDragAndDrop`），
  当前版本未见该实现。

---

## 7. 复现命令

```bash
# 确认组件与 intent-filter
aapt2 dump xmltree --file AndroidManifest.xml <smartsidebar.apk> | grep -A 20 TransferDockReceive

# 确认系统可解析
cmd package query-activities -a android.intent.action.SEND -t "*/*" | grep -i smartsidebar

# 确认无权限限制
dumpsys package com.coloros.smartsidebar | grep -A 12 TransferDockReceiveActivity
```
