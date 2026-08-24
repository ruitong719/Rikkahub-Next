# 03 · app 数据层（DB / DataStore / Repository / DI / 服务单例）

> 路径：`app/src/main/java/me/rerere/rikkahub/{data,di,service}`。注意 Provider/UIMessage/Tool 等核心 AI 类型在 `:ai` 模块（见 02 文档）。

## 1. Room 数据库（data/db/）

### 1.1 AppDatabase.kt
- 库名 **`rikka_hub`**，版本 **28**（`app/schemas/me.rerere.rikkahub.data.db.AppDatabase/1..28.json` 入库）
- JournalMode 强制 WAL；SQLite 用 requery fork（`com.github.rikkahub:sqlite-android`-SNAPSHOT）+ 自定义扩展 **libsimple**（jieba 中文分词）
- TypeConverter：TokenUsage ↔ JSON

### 1.2 实体表（8 张）

| Entity | 表名 | 关键字段 |
|---|---|---|
| ConversationEntity | ConversationEntity | id(PK,Uuid串), assistant_id(内置助手id), title, nodes(恒"[]"历史遗留), create_at/update_at, suggestions(JSON), is_pinned, custom_system_prompt, workspace_cwd, folder_id, rolling_context_summary(JSON, v28加) |
| MessageNodeEntity | message_node | id(PK), conversation_id(FK→Conversation CASCADE), node_index, **messages(List\<UIMessage\> JSON)**, select_index |
| MemoryEntity | MemoryEntity | id(auto), assistant_id(`__global__`=全局记忆), content |
| GenMediaEntity | GenMediaEntity | id(auto), path, model_id, prompt, create_at, type(image_generation/image_edit), source_paths(JSON) |
| ManagedFileEntity | managed_files | id(auto), folder, relative_path(unique), display_name, mime_type, size_bytes, created_at/updated_at |
| FavoriteEntity | favorites | id(PK=refKey), type(node/message旧), ref_key(unique `node:<convId>:<nodeId>`), ref_json/snapshot_json/meta_json, created_at/updated_at |
| WorkspaceEntity | workspaces | id, name, root(unique=磁盘目录名), shell_status(DISABLED/INSTALLING/READY/BROKEN), created_at/updated_at/last_access_at, tool_approvals(JSON map), export_target_uri(SAF树URI), tool_prompts(JSON map) |
| FolderEntity | conversation_folder | id, assistant_id(indexed), name, sort_index, create_at；**无外键**，删文件夹由 Repository 先清会话 folder_id |

### 1.3 DAO 要点
- **ConversationDAO**：按助手/文件夹分页（轻量投影 LightConversationEntity）、标题 LIKE、置顶排序 `is_pinned DESC, update_at DESC`、clearFolder、getConversationCountPerDay(strftime)
- **MessageNodeDAO**：分页 64 条/页（Repository 循环翻页捕获 SQLiteBlobTooBigException 跳页）；**@RawQuery + SQLite json_each()** 实现全局 token 统计（`$.usage.*`）与每日消息数统计
- FavoriteDAO：upsert(REPLACE)、substr 从 ref_key 提取 nodeId
- 其余 CRUD 常规

### 1.4 迁移
- 手动 Migration：6_7（平铺消息→MessageNode 树）、11_12（nodes JSON 拆到 message_node 表）、13_14（part 类型名迁移）、14_15（favorites 表）、15_16（TOOL 角色合并进 ASSISTANT + ToolCall/Result→Tool，调 ai 模块 migrateToolNodes）、27_28（rolling_context_summary 列）
- 其余 AutoMigration（spec：8_9 删列、16_17 删 truncate_index、22_23 删 shell_enabled、26_27 删 mode_injection_ids+lorebook_ids）
- ⚠️ 11→12 无 AutoMigration 必须手动
- `DatabaseMigrationTracker`：StateFlow 单例暴露迁移进度（UI 显示遮罩）；`DatabaseBackupManager.createSnapshot()` 先 `PRAGMA wal_checkpoint(TRUNCATE)` 再复制（防撕裂）；restore 只换文件不关 Room 连接——**恢复后需重启生效**

### 1.5 FTS 全文搜索（data/db/fts/）
- **不在 Room schema 内**！DataSourceModule 的 RoomDatabase.Callback.onOpen 幂等执行：
  `CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(text, node_id/message_id/conversation_id/title/update_at UNINDEXED, tokenize='simple')`
- libsimple 扩展提供 jieba 分词；`SimpleDictManager.extractDict()` 解压 assets/simple_dict 到 filesDir 并 `SELECT jieba_dict(?)` 注册
- `MessageFtsManager`：indexConversation 全量重建某会话索引（Text parts 拼接截断 10000 字）；search 用 `MATCH jieba_query(?)` + `simple_snippet(...,'[',']','...',30)` 高亮，LIMIT 50；排序 RELEVANCE(rank)/NEWEST_FIRST/OLDEST_FIRST

## 2. DataStore 设置（data/datastore/PreferencesStore.kt，类名 SettingsStore）

文件名 **`settings`**，挂 V1/V2/V3 DataMigration。settingsFlowRaw 反序列化后自动补回被删除的内置 Provider/Assistant（删除记录存 DELETED_*_IDS 键），并清洗无效引用、invalidate Pebble 模板缓存。写入口：update(Settings) 全量覆盖 / update(fn) / updateAssistant*(...) 系列。

### 键清单（按用途分组）

| 组 | 键 |
|---|---|
| UI/主题 | dynamic_color, theme_id, custom_themes, display_setting(JSON DisplaySetting), developer_mode, data_version |
| 网络 | network_setting(JSON NetworkSetting: userAgent/proxyUrl/proxy用户名密码/providerIdentities+启用ids) |
| 模型槽位 | chat_model, fast_model, title_model, translate_model, suggestion_model, image_generation_model, ocr_model, compress_model, favorite_models |
| 提示词模板 | title_prompt, translation_prompt(+translate_thinking_budget), suggestion_prompt, ocr_prompt, compress_prompt |
| 助手/供应商 | providers(JSON List\<ProviderSetting\>), assistants, select_assistant, assistant_tags, subagents, deleted_assistant_ids, deleted_provider_ids |
| 搜索 | search_services, search_common, search_selected |
| MCP | mcp_servers(JSON List\<McpServerConfig\>) |
| 备份 | webdav_config(WebDavConfig{url,username,password,path="rikkahub_backups",items=[DATABASE,ATTACHMENTS]}) |
| 工作区 | workspace_mounts |
| 语音 | tts_providers, selected_tts_provider, default_tts_playback_speed, asr_providers, selected_asr_provider |
| Web 服务器 | web_server_enabled/port/jwt_enabled/access_password/localhost_only |
| 悬浮球 | floating_bubble_enabled/color/size/opacity/icon_path/expand_width/expand_height/show_todo_tab/show_live_tab |
| 其它 | update_url, global_agent_md, quick_messages, backup_reminder_config, launch_count, sponsor_alert_dismissed_at |

- JSON 迁移：V1 修 mcp_servers 类型名；V2 修 assistants presetMessages part 类型名；V3 把 quickMessages 从 assistant 提为全局。备份恢复走等价的 `SettingsJsonMigrator`（整对象 JSON 迁移）
- `DefaultProviders.kt`：内置供应商（固定 UUID）与 DEFAULT_AUTO_MODEL_ID；`RecommendedProviders.kt`：推荐供应商表

## 3. 核心数据模型（data/model/）

| 文件 | 内容 |
|---|---|
| **Conversation.kt** | Conversation(id,assistantId,title,messageNodes,chatSuggestions,isPinned,createAt,updateAt,customSystemPrompt,workspaceCwd,folderId,rollingContextSummary)；`currentMessages`=各节点取 selectIndex；**`updateCurrentMessages(messages)` 是生成回写核心**（按 index 对齐节点，同 id 替换否则追加并移 selectIndex 到尾）；files 属性递归收集 Tool.output 中 file:// URI。MessageNode(id,messages,selectIndex,isFavorite)=分支容器 |
| **Assistant.kt** | chatModelId(null=全局)/name/avatar/tags/systemPrompt/temperature/topP/maxTokens/**rollingContextCompressionThresholdTokens**(0=默认32K)/streamOutput/enableMemory/useGlobalMemory/enableRecentChatsReference/messageTemplate(Pebble)/presetMessages/quickMessageIds/regexes(List\<AssistantRegex\>)/reasoningLevel/customHeaders/customBodies/mcpServers/localTools(默认[TimeInfo])/enableWebSearch/workspaceId/background*/enabledSkills/subagentIds/enableTimeReminder/allowConversationSystemPrompt。`replaceRegexes()` 带 SimpleCache 正则编译缓存，visualOnly 区分仅显示替换 |
| SubAgent.kt | name(slug→工具名)/description/systemPrompt/modelId(null继承)/toolAllowlist(名字或类别标签)/enabledSkills/maxSteps(64)/timeoutMs(120s)/requiresApproval(true)。DEFAULT_SUBAGENTS 三个预设(Code Reviewer/Researcher/Data Analyst, UUID 00000000-…-00a1/a2/a3) |
| Folder/Tag/Favorite/Avatar | 会话文件夹/助手标签/收藏元模型(NODE 类型 refKey=`node:<conv>:<node>`，buildFavoritePreview 取 Text 前160字)/头像 sealed(Dummy/Emoji/Image) |
| Leaderboard/Sponsor | 排行榜/赞助者 API 模型 |

## 4. Repository 层（data/repository/，均在 di/RepositoryModule 注册 single）

| Repository | 关键 API |
|---|---|
| **ConversationRepository** | 分页 Flow<PagingData>(PAGE_SIZE 20)+手动偏移分页(供 WebAPI)；insert/update/delete（withTransaction 同步 nodes+FTS 重索引）；searchMessages(FTS)；rebuildAllIndexes(onProgress)；loadMessageNodes 循环翻页标注 isFavorite；实体↔模型转换 public（入库禁 base64 part） |
| FolderRepository | getFoldersOfAssistant/create/rename/deleteFolder(先 clearFolder 再删) |
| MemoryRepository | GLOBAL_MEMORY_ID=`__global__`；按助手/全局 CRUD |
| GenMediaRepository | 图片生成历史 PagingSource |
| FilesRepository | managed_files 薄封装 |
| FavoriteRepository | addNodeFavorite/remove/isFavorited/listByType（经 NodeFavoriteAdapter） |
| **WorkspaceRepository** | 工作区生命周期中枢：checkIntegrity(目录缺失→BROKEN/rootfs 缺失→DISABLED)、create/rename/delete(killSession+清理助手引用)、installRootfs(runInterruptible 状态机)、文件 CRUD(FILES/LINUX 双区)、executeCommandStreaming(PRoot shell+动态 bindMounts)、工具审批/提示词覆盖 set/clear |

## 5. AI 管线 app 侧组件（data/ai/）

详细生成流程见 04 文档；此处列文件：

| 文件 | 职责 |
|---|---|
| `GenerationHandler.kt` | 生成核心：maxSteps 步进循环、工具审批状态机、32KB 工具输出截断落盘 tool_outputs/、translateText(Qwen-MT 特判) |
| `transformers/Transformer.kt` | TransformerContext/InputMessageTransformer/OutputMessageTransformer(visualTransform/onGenerationFinish) 接口体系 |
| `transformers/*.kt` | TimeReminder/Placeholder({{cur_date}} 等)/Template(Pebble)/DocumentAsPrompt/Ocr(LruCache+持久 ocr_cache.json)/VisionImageToText(视觉降级)/ThinkTag/Base64ImageToLocalFile/RegexOutput |
| `context/RollingContext.kt` | 纯函数滚动摘要计划：token 估算(CJK≈1token/其他4字符)、阈值 min4000 默认32000、最近窗口 0.55×阈值、摘要目标 阈值/4 clamp(512~8000)、sourceMessageIds 前缀匹配校验 |
| `mcp/` | McpManager/McpSessionRegistry/McpOAuthCoordinator 等（详见 05 文档 §7） |
| `prompts/` | TitleSummary/Translation/Suggestion/OcrPrompt/CompressPrompt 默认提示词常量 |
| `tools/` | LocalTools/SearchTools/MemoryTools/ConversationTools/SkillsTools/SubAgentTools/WorkspaceTools（见 05 文档） |
| `SubAgentRunner.kt`+`SubAgentLogic.kt` | 子代理运行器：合成虚拟 Assistant、allowlist 过滤工具(needsApproval 全 false)、stripReasoning 后拼历史、withTimeoutOrNull 包裹、结果 JSON {status,result,steps,usage} |
| `AIRequestInterceptor/RequestLoggingInterceptor` | OkHttp 层请求日志(dev 开关) |
| `GenerationPrompts.kt` | buildMemoryPrompt(`<memories>` JSON 块) |

## 6. 文件与技能管理（data/files/）

| 类 | 职责 |
|---|---|
| **FilesManager**(single) | 统一附件管理：save*FromUri/Bytes/Text → managed_files 登记；createChatFilesByContents(UUID 重命名落 upload/)；convertBase64ImagePartToLocalFile；deleteChatFiles；syncFolder(DB↔磁盘对账)；`object FileFolders{UPLOAD,SKILLS,FONTS,TOOL_OUTPUTS,AGENT}` |
| SkillManager(single) | Claude-Code 式技能目录 `filesDir/skills/<name>/SKILL.md`(frontmatter name/description 必填)；listSkills/readSkillBody/saveSkill/deleteSkill(联动清 enabledSkills)/pruneOrphanedEnabledSkills/saveSkillFilesAtomically(staging+rename 原子替换) |
| SkillPaths/SkillFrontmatterParser | canonical path 防穿越 / 极简 YAML frontmatter |
| **WorkspaceBgManager**(single) | 持久后台任务：每工作区常驻 headless proot bash，任务以 `(cmd > log 2>&1; echo $? > exit_code) &` 运行于 `.l2s.bg/<taskId>/`；MAX_CONCURRENT_TASKS=3；listUnNotifiedFinishedTasks/markNotified/truncateOutputIfLarge/killSession/cleanupOrphanTasks |
| WorkspaceMountManager(single) | SAF 手机目录 ↔ 工作区 `/mnt/<name>`；物化到 filesDir/mnt/<mountId>/；快照式手动 PULL/PUSH(size+mtime 增量)；activeBindMounts() 供 shell 动态挂载 |
| WorkspacePhoneExporter(single) | rootfs→SAF 导出（拒绝绝对路径/../..，跳过 .l2s.* 与 symlink） |

## 7. service/ 包

> **没有名为 ChatService 的 Android Service**——ChatService 是 Koin single 普通类；前台服务只有两个。

### ChatService（1621 行，聊天业务中枢）
- `sessions: ConcurrentHashMap<Uuid, ConversationSession>`
- 公开流：errors/generationDoneFlow(SharedFlow\<Uuid\>)/getConversationFlow/getGenerationJobStateFlow/getQueuedMessagesFlow/getProcessingStatusFlow/getConversationJobs
- sendMessage()：空闲→sendMessageInternal；生成中→session.enqueue（返回 true 表示入队）
- launchSendUserMessage：join 旧 job → finishInterruptedPendingTools → preprocessUserInputParts(用户正则) → 追加 USER node → saveConversation → handleMessageComplete → finally flushQueuedMessages 兜底
- handleMessageComplete()：解析 assistant/model → 能力检查 → 读记忆 → 滚动摘要压缩 → 组装 transformers/tools(搜索/localTools/conversationTools/workspaceTools/skills/MCP `mcp__<server>__<tool>`/subagents) → GenerationHandler.generateText collect 回写 → onCompletion 收尾 → onSuccess save + 异步 generateTitle/generateSuggestion
- handleToolApproval(toolCallId, approved, reason, answer)：更新 ApprovalState 并续跑
- startBgTaskAutoResumeWatcher()：每 2s 轮询活跃会话未通知的后台任务完成 → beginGenerationIfIdle 预留式拉起（限 streak≤3/failures≤3）
- 辅助生成：generateTitle(titleModel fallback fastModel, 最后4条)/generateSuggestion(最后8条,≤10条)/compressConversation(>256条递归二分并行分块)
- 消息操作：regenerateAt/editMessage/fork/selectMessageNode/deleteMessage/translateMessage/stopGeneration/moveToFolder…

### ConversationSession
每会话运行时容器：state MutableStateFlow\<Conversation\>、processingStatus、generationJob(slotLock 占位防竞态 beginGenerationIfIdle)、引用计数 acquire/release + 5s 空闲回收、排队消息队列 enqueue/drainQueue/requeueFront、autoResumeStreak/Failures 限流。

### 前台服务
| 服务 | 说明 |
|---|---|
| WebServerService | specialUse FGS 壳，ACTION_START/STOP + EXTRA_PORT/EXTRA_LOCALHOST_ONLY；观察 WebServerManager.state 更新通知 |
| FloatingBubbleService | FloatingX 悬浮球 FGS；静态 serviceRunning/tempHidden；展开创建 FloatingExpandWindow |

### 辅助单例
- ChatNotificationManager(createdAtStart)：消费 ChatGenerationUpdate/Ended 发 Live Update 进度通知(1s 节流)+完成通知；前台时静默
- FloatingActivityHub(createdAtStart)：聚合 liveText/reasoning/TodoStore 待办/shell 命令 → FloatingActivityState 供悬浮窗

## 8. 其他 data 子包

| 包 | 内容 |
|---|---|
| sync/webdav/ | WebDavClient(Ktor PUT/GET/PROPFIND/MKCOL/DELETE basicAuth) + WebDavSync(testConnection/backup/list/restore/prepareBackupFile)。备份 zip 结构：settings.json + rikka_hub.db(wal 兜底) + ATTACHMENTS scope 下 skills/,agent/,fonts/,upload/,workspaces/*/files/** 及每工作区 .bashrc。恢复：settings 过 SettingsJsonMigrator；DB 暂存 cacheDir 最后 restore(重启生效)；zip-slip 防护 |
| sync/importer/ | ChatboxImporter / CherryStudioProviderImporter 第三方导入 |
| export/ | ExportData{version=1,type,data} 信封 + ExportSerializer<T> + Compose 侧 rememberExporter/rememberImporter(SAF) |
| favorite/ | FavoriteAdapter 泛型接口 + NodeFavoriteAdapter 实现 |
| network/ | ProxyConfig(toProxyOrNull http/socks5、SettingsProxySelector 热切、全局 Authenticator)；**ClientPresets** 客户端身份伪装预设（Claude Code/Codex CLI/OpenCode/Gemini CLI/CherryStudio/Chatbox/curl 按 host 自动匹配 UA/header，keyless 移除 Authorization，供应商级开关） |
| api/ | RikkaHubAPI(空 Retrofit 占位 api.rikka-ai.com)、SponsorAPI(GET /sponsors) |
| event/ | AppEventBus(MutableSharedFlow buffer16, tryEmit 可丢)；AppEvent: Speak/OpenUsageAccessSettings/McpOAuthCallback/ChatGenerationUpdate/ChatGenerationEnded |
| provider/ | WorkspaceDocumentsProvider(DocumentsProvider 把工作区文件区经 SAF 暴露, authority `${applicationId}.documents`) |
| ai/tools/TodoStore | 对话隔离待办 `filesDir/todo/<conversationId>.json` StateFlow 缓存 |

## 9. DI 清单（di/）

| 模块 | 注册内容（全部 single 除注明） |
|---|---|
| **AppModule** | Json(JsonInstant)、AppEventBus、LocalTools、TodoStore、UpdateChecker、AppScope(SupervisorJob+Main)、EmojiData、TTSManager、SoundEffectPlayer、ChatNotificationManager(createdAtStart)、FloatingActivityHub(createdAtStart)、ChatService(17 依赖)、WebServerManager |
| **DataSourceModule** | SettingsStore、AppDatabase(Room builder WAL+requery+libsimple+migrations+onOpen 建 FTS/载词典)、AssistantTemplateLoader、PebbleEngine、TemplateTransformer、全部 8 个 DAO、MessageFtsManager、DatabaseBackupManager、McpManager、GenerationHandler、共享 OkHttpClient(代理 selector/authenticator/Accept-Language/UA/身份拦截/RequestLogging/HttpLogging HEADERS + SearchService.init)、SponsorAPI、ProviderManager(ai)、WebDavSync、Ktor HttpClient、Retrofit |
| **RepositoryModule** | ConversationRepo、FolderRepo、MemoryRepo、GenMediaRepo、FilesRepo、FavoriteRepo、WorkspaceManager(filesDir/workspaces + ProotShellRunner(nativeLibraryDir) + 固定 bindMounts /skills,/tool_outputs,/upload,/agent)、RootfsInstaller、WorkspaceRepository、FilesManager、SubAgentRunMonitor、ShellRunMonitor、WorkspacePhoneExporter、WorkspaceMountManager、WorkspaceBgManager、SkillManager |
| **ViewModelModule** | viewModel：ChatVM(带参 conversationId)、ChatDrawerVM、SettingVM、DebugVM、HistoryVM、AssistantVM、AssistantDetailVM(带参)、TranslatorVM、ShareHandlerVM(带参 text)、BackupVM、ImgGenVM、SubAgentsVM、SubAgentEditVM、QuickMessagesVM、SkillsVM、SkillDetailVM、WorkspaceVM、WorkspaceDetailVM(带参)、FavoriteVM、SearchVM、StatsVM |

带参 VM 注入方式：`viewModel<ChatVM> { params -> ChatVM(id = params.get(), ...) }`，UI 侧 `koinViewModel(parameters = { parametersOf(id.toString()) })`。
