# 内置协议库（yooma1/jailbreaks 移植）

来源：https://github.com/yooma1/jailbreaks （tarball main 快照）
45 条正文：32 个顶层文件 + 13 个 `0x8D jailbreaks/` 文件；
跳过 `README.md`（索引）与 `qwen-preferences.md`（0 字节占位）。

转码：
- 普通 system prompt 原样保留
- Ollama Modelfile（`FROM` / `SYSTEM """` / `PARAMETER`）剥掉包装，只留正文
- 带 `<system-reminder>` 自包裹的（Unified.md）去标签

正文必须是 `.txt`：`AgentUserPromptStore.resolveFile()` 只认 `prompts/` 下第一个非空 `.md`，
库文件若用 `.md` 会被误当成用户生效协议。装配时才经 `installContent()` 写成 `破甲记忆.md`。

装配语义与内置破甲记忆共用：先备份当前文件到 `prompts/.backup/`，再 AtomicFile 写入；
**装配 ≠ 注入**，是否注入只看 `userPromptEnabled` 总开关。
