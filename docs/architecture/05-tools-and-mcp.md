# 05 · 工具系统与 MCP

> AI 可调用的全部工具在此汇总。工具统一形态为 `ai/core/Tool.kt` 的 `Tool(name, description, parameters, systemPrompt, needsApproval(args), execute(args): List<UIMessagePart>)`。
> 装配总入口：ChatService.handleMessageComplete；本地开关工具经 `data/ai/tools/local/LocalTools.kt`。

## 1. 工具装配全景

```
ChatService.handleMessageComplete 组装 tools 列表:
├─ SearchTools.createSearchTools(settings)      → search_web (+scrape_web 若所选服务支持)
├─ LocalTools.getTools(assistant.localTools, conversationId)  → 8 类本地工具(§2)
├─ ConversationTools.createConversationTools    → recent_chats / conversation_search
├─ WorkspaceTools.createWorkspaceTools(...)     → 13 个 workspace_* (shell READY 才创建)(§4)
├─ SkillsTools.createSkillTools                 → use_skill
├─ MemoryTools.buildMemoryTools(enableMemory)   → memory_tool
├─ SubAgentTools.createSubAgentTools(...)       → subagent_<slug> × N
└─ MCP: mcp__<server>__<tool> × N               → McpManager.getAllAvailableTools()
GenerationHandler 内部再追加: memory 工具(enableMemory 时)
```

MCP 工具命名约定 **`mcp__<serverName>__<toolName>`**。

## 2. 本地工具（data/ai/tools/local/）

开关模型：`LocalToolOption` sealed（@SerialName 持久化于 Assistant.localTools，默认仅 TimeInfo）：

| 工具名 | 开关 | 参数 | 功能 | 审批 |
|---|---|---|---|---|
| eval_javascript | JavascriptEngine | code | QuickJS ES2020 执行 JS，返回最后表达式+console logs | 否 |
| get_time_info | TimeInfo | 无 | 当前日期时间/星期/ISO/时区/时间戳 | 否 |
| clipboard_tool | Clipboard | action(read/write), text? | 读免审批；写需明确用户请求语境 | 视参数 |
| text_to_speech | Tts | text | 发 AppEvent.Speak 后台朗读；systemPrompt 注入当前 TTS provider 的 promptGuidance（语气标记指引） | 否 |
| ask_user | AskUser | questions[{id,question,options}] | 向用户提问等待回答（ApprovalState.Answered 机制）；**subagent 一律排除** | Pending 等答 |
| get_screen_time | ScreenTime | begin/end 或 range(today/week) | UsageStats per-app 屏幕时长；无权限自动跳设置页 | 否 |
| calendar_query / calendar_create | Calendar | 时间范围 / title+start(+end) | 设备日历查询/创建（创建 needsApproval=true） | create 是 |
| todowrite（2026-08-26 起，替代原 todo_create/update/complete/clear 四工具） | Todo | todos[]（content/status: pending·in_progress·completed·cancelled/priority） | 单工具全量替换，对齐 opencode；返回全量列表；对话隔离待办（TodoStore：filesDir/todo/<convId>.json，StateFlow 供 UI 角标实时同步；旧布尔格式文件加载时自动迁移） | 否（PLAN 模式也不拦截，供模型写执行计划） |

## 3. 搜索 / 会话 / 记忆 / 技能工具（data/ai/tools/）

### SearchTools.kt
- `search_web`：描述内嵌今天日期、引用语法 `[citation,domain](id)`、图片嵌入规则；参数 schema 由 search 模块对应服务的 `parameters(options)` 提供；执行后为每个结果注入随机短 id/index
- `scrape_web`：仅当所选搜索服务支持 scrape 时注册；schema 来自 `scrapingParameters(options)`
- 结果转 Text part JSON（items 含 title/url/text + images）

### ConversationTools.kt
- `recent_chats`（limit≤30，标题+日期）、`conversation_search`（query+limit≤50 走 FTS snippet 高亮）
- 按需注入设计：不把历史塞进静态 system，保护 prompt cache

### MemoryTools.kt
- 单一 `memory_tool` action=create/edit/delete，长期记忆 CRUD（MemoryRepository；`__global__`=全局）
- enableMemory 时 GenerationHandler 注入 `<memories>` JSON 块（buildMemoryPrompt）

### SkillsTools.kt
- `use_skill`(name + 可选 path)：读 skills/<name>/SKILL.md 正文（剥 frontmatter）或技能目录内相对路径文件（SkillPaths 防穿越）；systemPrompt 经 `<available_skills>` 列表告知可用技能
- 技能格式 = Claude Code 式 SKILL.md frontmatter（name/description 必填）

## 4. Workspace 工具组（data/ai/tools/WorkspaceTools*.kt）

shell_status=READY 才创建。11 个工具（2026-08-26 起对齐 opencode 命名：read/write/edit/bash 裸名，其余保留前缀）：

| 工具 | 默认审批 | 说明 |
|---|---|---|
| read(path/offset/limit; 行号前缀 `N: content` 分页) | false | 文本行级分页读取；目录路径返回条目列表；图片扩展名转 Image part；二进制嗅探拒绝；不存在时给同目录 did-you-mean 建议 |
| write(path) | false | 覆盖写（overwrite 默认 true）；read-before-overwrite 守则；**路径在写入安全区外强制审批** |
| edit(old_text/new_text/replace_all) | false | 五级宽松匹配 TextReplacers: Exact→LineTrimmed→BlockAnchor→WhitespaceNormalized→EscapeNormalized；模糊匹配跨度远大于 old_text 拒改（失配保护）；多义且非 replace_all 报错；输出带 unified diff 存 DiffMetadata 供 UI 渲染；安全区外强制审批 |
| bash(command/cwd/timeout 默认30s 最大600s) | true | PRoot bash -c；实验开关 enableShellLiveOutput 时经 ShellRunMonitor 直播输出；BashPathScanner 启发式提取命令中绝对路径，触及安全区外强制审批 |

写入安全区（2026-08-26）：WorkspaceEntity.writable_roots JSON 数组（默认 ["/workspace","/tmp"]，
按工作区配置于详情页「写入安全区」卡片），write/edit/bash 目标在区外时无视工具级开关强制弹审批。
会话级授权：审批 UI 三选项（拒绝/本次会话内全部同意/单次同意），ToolGrants（内存态挂
ConversationSession）按目录子树或整工具记录授予，ChatService.applyConversationGrants 包装
needsApproval 实现已授权调用免审批、同批 Pending 连带放行。
| workspace_export_to_phone | true | SAF 导出到手机 |
| workspace_bg_start/status/output/kill/list | start,kill=true 其余 false | 常驻后台任务管理（.l2s.bg/<taskId>/） |
| workspace_create_backup | true | 复用 WebDavSync.prepareBackupFile(DATABASE) → `/workspace/backup.zip` |

挂载点（SAF → /mnt/<name>）不再有 AI 工具（2026-08-26 删除 mount_list/mount_sync）：
`<workspace>` 系统块注入挂载点列表与快照同步语义，后台循环自动 push→pull，
间隔设置项 Settings.workspaceAutoSyncIntervalSeconds（关闭/30s/1min/5min，默认 60s）；
设置页手动同步按钮保留。

旧名兼容（2026-08-26 二次调整）：Migration_30_31 启动时直接改写消息与工作区覆盖中的
旧工具名，运行时别名/渲染双匹配已移除，未知旧名由通用渲染器兜底。

三张联动表（改工具必同步）：
- `WorkspaceToolDefaultApprovals`：默认审批表，可被 WorkspaceEntity.toolApprovals 按工作区覆盖
- `WORKSPACE_TOOL_NAMES` + `DEFAULT_WORKSPACE_TOOL_PROMPTS`：`<workspace>` system 块中 "Available tools" 文案（与 Tool.description 相互独立）

## 5. SubAgent 工具（data/ai/tools/SubAgentTools.kt）

- 每个启用 subagent 一个独立工具 `subagent_<slug>`（slug 冲突加短 id 后缀；slugify 在 SubAgentLogic.kt）
- 参数 task(+context)；needsApproval=subAgent.requiresApproval（默认 true，派发一次性审批，子过程内部不再审批）
- allowlist 匹配支持类别标签：workspace_read / search / mcp / conversation / local 等
- 执行细节见 04 文档 §10；轨迹监控 SubAgentRunMonitor

## 6. TodoStore（data/ai/tools/TodoStore.kt + local/TodoTool.kt）

- 模型（2026-08-26 对齐 opencode）：`TodoItem(content, status, priority)` 无 id、顺序即位置；
  状态机 pending/in_progress/completed/cancelled
- 工具形态：单 `todowrite` 全量替换（每次提交完整列表），返回全量列表供模型自洽；
  旧版四增量工具与布尔格式 JSON 文件已废弃，文件首次加载自动转换并回写
- 对话隔离：`filesDir/todo/<conversationId>.json`
- Koin single，StateFlow 缓存 → ChatVM.todos / TodoSheet / 悬浮球待办 Tab / TodoStatusButton 角标实时同步

## 7. MCP 客户端（data/ai/mcp/）

### 配置模型
- `McpServerConfig` sealed：`@SerialName("sse") SseTransportServer(url)` / `@SerialName("streamable_http") StreamableHTTPServer(url)`；均支持自定义 headers + OAuth
- `McpCommonOptions(enable,name,headers,tools,m oauth:McpOAuthState)`；`McpTool(enable,name,description,inputSchema,needsApproval)`
- transport/ 下两个文件是 SDK 自带 transport 的注释副本（参考用），实际用 `io.modelcontextprotocol:kotlin-sdk`（catalog 0.15.0），requestBuilder 注入 Bearer header

### McpManager（Koin single，公共入口）
- 自建 OkHttp + Ktor HttpClient(SSE)
- init 订阅 settingsFlow.mcpServers distinctUntilChanged → sessionRegistry.reconcile(configs)（增删/参数变更自动重连）
- API：syncingStatus: StateFlow<Map<Uuid,McpStatus>>、getClient/getStatus/addClient/removeClient/syncAll、getAllAvailableTools()（过滤 enabled 且被当前助手选中）、callTool(serverId,tool,args): List<UIMessagePart>（TextContent→Text；ImageContent→base64 解码存 upload/ 变 Image part）
- OAuth 入口：startAuthorization/cancelAuthorization/clearAuthorization

### McpSessionRegistry
每 server 一个 McpSession(config/client/connectedConfig/lifecycleMutex/reconnectJob)。连接成功且首次工具同步后才对外可见；指数退避重连最多 5 次（1s→30s）；callTool 前 ensureFreshToken，401 标 NeedsAuthorization。

### McpStatus 状态机
Idle / Connecting / Connected / Reconnecting(attempt,max) / Error(message,detail) / NeedsAuthorization / Authorizing

### OAuth 2.1 实现（McpOAuthClient/McpOAuthCoordinator/McpOAuthCallback）
- RFC9728 受保护资源元数据发现、RFC8414/OIDC server 元发现、RFC7591 动态客户端注册(DCR)、PKCE(S256)、RFC8707 resource indicator、令牌刷新(60s leeway)
- redirect_uri=`rikkahub://mcp-oauth-callback`：Chrome Custom Tabs 打开授权页 → McpOAuthCallbackActivity 深链接收 → AppEventBus.McpOAuthCallback → Coordinator
- 状态持久化 McpOAuthState（toString 脱敏）

## 8. 工具 UI 渲染（ui/components/message/tools/）

- `ToolUIContext(tool, arguments, content, loading)` 预解析上下文
- `interface ToolUIRenderer { toolName; icon(); title(); hasSummary(); Summary(); Preview() }`
- `object ToolUIRegistry.resolve(toolName)` 静态注册表；未注册走 DefaultToolUIRenderer（JSON pretty 打印）
- 已注册渲染器：
  - BuiltinToolUIs.kt：Memory/SearchWeb(结果卡片)/ScrapeWeb/GetTimeInfo/Clipboard/TextToSpeech/UseSkill/RecentChats/ConversationSearch/GetScreenTime/CalendarQuery/CalendarCreate
  - WorkspaceToolUIs.kt：ReadFile/WriteFile/EditFile(diff 视图接 richtext/DiffView)/Shell(终端样式+实时输出)
- 审批交互在 ChatMessageTools.kt（批准/拒绝按钮、拒绝理由对话框、ask_user 表单）——见 06 文档 §4

## 9. 新增工具检查清单

1. 实现 Tool（execute 异常会被捕获转 error JSON，无需自行兜底）
2. 需要审批 → needsApproval 返回 true（或按参数判断）；恢复语义自动获得
3. 本地工具 → LocalTools.getTools() + LocalToolOption 加分支
4. workspace 工具 → 同步 DefaultApprovals / WORKSPACE_TOOL_NAMES / DEFAULT_WORKSPACE_TOOL_PROMPTS 三张表
5. UI 卡片 → ToolUIRegistry 注册 Renderer（否则 JSON 兜底）
6. 输出 >32KB 自动截断落盘 tool_outputs/（有 bash 的助手才生效）
7. 大输出想给 AI 读全文 → 引导它用 read 或提供专用读取工具

会话级权限模式 PermissionMode（plan/build/yolo，Conversation 字段）：ChatService
装配完工具后经 PermissionModePolicy.apply 统一改写——PLAN 拒绝变更类工具并下线
subagent + 注入 <plan_mode> 提示；YOLO 全部 needsApproval=false。详见 CHANGES.md N 节。
