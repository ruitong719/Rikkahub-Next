# 04 · 聊天生成的完整管线

> 从用户按下发送到消息落库的全链路。核心文件：
> `app/.../service/ChatService.kt`（编排）→ `app/.../data/ai/GenerationHandler.kt`（步进循环）→ `:ai` 模块 Provider（网络）。
> 另有 `docs/references/chat-generation-pipeline.md` 是上游遗留的同主题文档，可交叉参考。

## 1. 总览图

```
UI ChatInput.onSendClick
  └─ ChatVM.handleMessageSend(parts, answer)                    [ui/pages/chat/ChatVM.kt]
       └─ ChatService.sendMessage(conversationId, parts, answer)
            ├─ 空闲: sendMessageInternal → launchSendUserMessage ──┐
            │     1. join 上一个 job                                │
            │     2. finishInterruptedPendingTools(遗留 pending→denied)
            │     3. preprocessUserInputParts(助手正则非visual替换)
            │     4. 追加 USER MessageNode → saveConversation        │
            │     5. handleMessageComplete ◄────────────────────────┤
            │     finally flushQueuedMessages 兜底                   │
            └─ 生成中: session.enqueue(UIMessage(USER,...), answer)  │
                 (快路径: 步进循环顶部 poll; 慢路径: 兜底合并发送)      │
                                                                       ▼
handleMessageComplete():                                    [ChatService.kt]
  解析 assistant/model → 能力检查(无 TOOL 能力却开搜索/MCP→告警)
  → checkInvalidMessages → 读记忆(全局或助手级)
  → RollingContext 计划与压缩(§5)
  → 组装 inputTransformers / tools(§3 §4)
  → GenerationHandler.generateText(...) collect:
        chunk 回写 session.state(updateCurrentMessages) + emit AppEvent.ChatGenerationUpdate
  → onCompletion: reasoning finish + ChatGenerationEnded
  → onSuccess: saveConversation + 异步 generateTitle/generateSuggestion
```

## 2. ConversationSession 与并发模型

- `ChatService.sessions: ConcurrentHashMap<Uuid, ConversationSession>`
- Session 持有：`state: MutableStateFlow<Conversation>`（会话单一事实源）、processingStatus、`generationJob`（slotLock + `beginGenerationIfIdle(start)` 占位防竞态）、排队消息队列、autoResumeStreak/Failures 限流计数
- **引用计数生命周期**：ChatVM init 时 `addConversationReference(id)`，onCleared 移除；引用归零且空闲 5s 回收 session
- UI 经 `chatService.getConversationFlow(id)` 收集状态，**不直接持有 Conversation**

## 3. Input Transformers 管道（发送前）

实际组装顺序（ChatService 内构造，接口定义 `data/ai/transformers/Transformer.kt`）：

| # | Transformer | 作用 |
|---|---|---|
| 1 | TimeReminderTransformer | enableTimeReminder 开启时：首条用户消息前 & 间隔>1h 的用户消息前注入 `<time_reminder>Current time: ...` |
| 2 | PlaceholderTransformer | 替换 `{{cur_date}} {{model_id}} {{model_name}} {{locale}} {{timezone}} {{system_version}} {{device_info}} {{battery_level}} {{nickname}} {{char}} {{user}}` 等（双花括号与单花括号都支持） |
| 3 | DocumentAsPromptTransformer | Document part 解析成 `<UploadFile name path="/upload/...">` 文本前置（PDF/DOCX/PPTX/EPUB 用 :document 模块解析为 Markdown 字符串） |
| 4 | OcrTransformer | 主模型无 IMAGE 能力时把图片交给 ocr_model 识别（LruCache 64 条/3 天，持久 ocr_cache.json；显示"正在识别图片"） |
| 5 | TemplateTransformer | Pebble 引擎渲染 assistant.messageTemplate（变量 message/role/time/date；用消息自身 createdAt 保证 prompt cache 稳定） |
| 6 | WorkspaceReminderTransformer | 助手绑定 READY 工作区时 system 追加 `<workspace>` 块：环境说明+工具清单(WORKSPACE_TOOL_NAMES×用户覆盖提示词?默认 DEFAULT_WORKSPACE_TOOL_PROMPTS)+/skills、/upload、/agent 说明+cwd |
| 7 | AgentMdTransformer | `/agent` 目录 *.md(agent.md 优先)拼进 system；空目录回退 settings.globalAgentMd |
| 8 | VisionImageToTextTransformer | 视觉降级网关：主模型无图片能力且配了 visionModelId 时，图片→视觉模型文字描述（内存缓存；失败写 "[图片（无法解析）]"） |
| 9 | BackgroundTaskReminderTransformer | 扫描本对话已完成未提醒的后台任务注入 `<bg_reminder>` 并标已提醒 |

扩展函数：`List<UIMessage>.transforms(ctx)` fold 应用。

## 4. Output Transformers（接收后）

| Transformer | 时机 | 作用 |
|---|---|---|
| ThinkTagTransformer | transforms + visualTransforms | 把文本头部 `<think>...</think>` 抽成 Reasoning part（部分供应商不回原生 reasoning） |
| Base64ImageToLocalFileTransformer | onGenerationFinish | base64 图片 part 落盘为本地文件 |
| RegexOutputTransformer | visualTransforms | 对 ASSISTANT 文本应用 AssistantRegex（非 visualOnly 的实际替换；UI 渲染另有 visual 版 replaceRegexes） |

OutputMessageTransformer 三时机：`transform()`（持久化消息）、`visualTransform()`（仅流式期间 UI 显示）、`onGenerationFinish()`（生成结束处理）。

## 5. Token-aware 滚动摘要上下文（data/ai/context/RollingContext.kt）

- 阈值来源 assistant.rollingContextCompressionThresholdTokens（0=默认 **32000**；最小 4000）；设置页支持 `32K`/`1.5M` 写法与预设档位 chips（上限 512K）
- token 估算：CJK 字符≈1 token，其他≈4字符/token，每条消息 +4 overhead
- `createRollingContextPlan()`：已存摘要仅在 sourceMessageIds 为当前分支前缀时有效；工作集 < 阈值不压缩；保留最近窗口（0.55×阈值，至少 4 条，窗口起点回退到 USER 边界保证 tool 配对）
- 压缩执行：ChatService 在 handleMessageComplete 中对窗口前历史调 `generateRollingContextSummary`（ROLLING_CONTEXT_SUMMARY_PROMPT，maxTokens=min(target,2000)，temp 0.3，reasoning OFF）
- 结果 `RollingContextSummary(content, sourceMessageIds, updatedAtMillis)` 持久化在 ConversationEntity.rolling_context_summary（v28），随请求传给 GenerationHandler 拼 `<rolling_context_summary>` 进 system
- 另有手动压缩入口 compressConversation(additionalPrompt, targetTokens, keepRecentMessages=32)：>256 条递归二分并行分块压缩，摘要作为 USER 消息替换原历史

## 6. GenerationHandler 步进循环（data/ai/GenerationHandler.kt）

```kotlin
fun generateText(settings, model, messages, inputTransformers, outputTransformers,
    assistant, memories, tools, maxSteps=256, processingStatus,
    conversationSystemPrompt, workspaceCwd, rollingContextSummary,
    requestMessageStartIndex, onPollQueuedMessages, onRequeueQueuedMessages): Flow<GenerationChunk>
```

主循环（Dispatchers.IO，最多 maxSteps 步——agentic 循环上限 256，subagent 用自己的 maxSteps）：

1. **每轮顶部 `onPollQueuedMessages()`**：把生成中排队的用户消息插入对话（快路径；失败 requeue 回滚）
2. 组装 toolsInternal = memory 工具(enableMemory 时) + 外部 tools
3. 若无待恢复工具 → generateInternal()：
   - 拼 system = 助手提示词 或 对话级 customSystemPrompt + `<rolling_context_summary>` + `<memories>` JSON + 各工具 systemPrompt()
   - 按 requestMessageStartIndex 截断消息（滚动摘要窗口起点）→ `limitContext(assistant 上下文条数限制)` 阶梯截断
   - 应用 inputTransformers → provider.streamText/streamText collect（chunk 过 outputTransformers 后 emit GenerationChunk.Messages）
4. 本轮无新工具调用 → break
5. 有工具调用 → 审批状态机：
   - `needsApproval(args)==true && approvalState==Auto` → 置 Pending、emit、break 等用户操作
   - 恢复场景直接取 `canResumeExecution` 的工具（Approved/Denied/Answered）
6. 执行工具：
   - Denied(reason) → 错误文本写入 output
   - Answered(answer)（ask_user）→ 答案即输出
   - Approved/Auto → `toolDef.execute(args)`（协程上下文注入 `ShellRunKey(toolCallId)` 供 shell 实时输出关联）；CancellationException 上抛；异常转 JSON error
7. 输出过 `maybeTruncateToolOutput`：**>32KB 且助手具备 workspace_shell 时**全文存 `files/tool_outputs/<callId>.txt`，只留 4KB 预览+读取指引

`translateText(...)`：独立翻译入口（Qwen-MT 走 translation_options 特殊分支）。GenerationChunk 目前只有 Messages 一种。

## 7. 工具审批交互闭环

```
ChainOfThought[ToolStep pending 显示批准/拒绝]  [ChatMessageTools.kt]
  → ChatVM.handleToolApproval(toolCallId, approved, reason) / handleToolAnswer(...)
  → ChatService.handleToolApproval: cancel 当前 job
      → 更新对应 Tool part 的 approvalState(Approved/Denied(reason)/Answered(answer)) → saveConversation
      → 无剩余 pending → 继续 handleMessageComplete 续写
      → finally flushQueuedMessages
```
ask_user 工具复用同一状态机：Pending 时 UI 渲染问答表单，Answered 的答案即工具结果。拒绝弹 ToolDenyReasonDialog 可填理由。

## 8. 生成中消息队列（fork 特色）

- 入队：生成期间 sendMessage → `session.enqueue(UIMessage(USER), answer)`
- 快路径：GenerationHandler 每轮顶部 poll——工具轮次间隙插入最快生效
- 慢路径：launchSendUserMessage 的 finally `flushQueuedMessages`——残留队列合并成一条发送（任一 answer 即再触发生成）
- UI：发送钮 BadgedBox 显示 queuedCount(≤99)；单击=智能发送（生成中有输入→入队/无输入→打断），长按=仅添加不触发；图标随行为切换（interrupt 红 Cancel01/发送 ArrowUp02/禁用灰）

## 9. 后台任务自动拉起 LLM（fork 特色）

- WorkspaceBgManager 维护常驻 headless proot bash；任务完成留下 exit_code 文件
- `startBgTaskAutoResumeWatcher()` 每 2s 轮询活跃会话（refCount>0）的未通知完成任务
- `beginGenerationIfIdle` 预留式拉起生成（注入 BackgroundTaskReminderTransformer 提醒 AI 读取输出并继续工作）
- 防失控限流：BG_AUTO_RESUME_MAX_STREAK=3 连续触发 / MAX_FAILURES=3 失败熔断
- 任务通知后 markNotified；大输出 truncateOutputIfLarge

## 10. SubAgent 执行流（data/ai/SubAgentRunner.kt）

1. 主循环里 AI 调用 `subagent_<slug>` 工具（task + context 参数；needsApproval=subAgent.requiresApproval，派发时一次性审批）
2. SubAgentRunner.run：
   - 模型 = subAgent.modelId ?? assistant.chatModelId ?? settings.chatModelId
   - 合成虚拟 Assistant（主 effective systemPrompt + subagent.systemPrompt 拼接）
   - 工具 = allowlist 过滤的主工具池（类别标签支持 workspace_read/search/mcp/conversation/local；ask_user 一律排除；needsApproval 全覆写 false；catalog 已剔除 subagent 工具禁止嵌套）+ 自己的 skill 工具
   - 消息 = 主对话历史 stripReasoning(删 Reasoning part+剥残留 think 标签) + task(+context 包裹)
   - `withTimeoutOrNull(timeoutMs=120s)` 包裹 generateText(maxSteps=subAgent.maxSteps=64)
3. 过程上报 SubAgentRunMonitor（内存轨迹 StateFlow，UI 页面 SubAgentsPage/SubAgentTracePage 观察）
4. 返回 JSON `{status: success/error/timeout, result, steps, usage{...}}`

## 11. 流式更新如何到达 UI

```
provider streamText → StreamChunkDecoder.accept(SseEvent) → StreamChunk
  → StreamChunkHandler.handle(messages, chunk) → List<UIMessage>
  → GenerationHandler emit GenerationChunk.Messages
  → outputTransformers.visualTransforms (ThinkTag/Regex 仅视觉版)
  → ChatService 回写 session.state.updateCurrentMessages + AppEvent.ChatGenerationUpdate
  → ChatVM.conversation (StateFlow) → ChatList LazyColumn重组
并行旁路: ChatNotificationManager(Live Update 通知, 1s节流)
         FloatingActivityHub(悬浮球实时输出/todos)
         ShellRunMonitor(workspace_shell 实时输出, ShellRunKey 关联 toolCallId)
```

tok/s 统计只按纯吐字时长：UIMessage.generationDurationMs 累加各轮（首 chunk 到 Finish），不含连接建立/首包延迟/工具间隙；旧数据回退 createdAt..finishedAt 总时长。

## 12. 辅助小生成任务

| 任务 | 模型 fallback | 输入 |
|---|---|---|
| generateTitle | title_model → fast_model | 最后 4 条摘要（DEFAULT_TITLE_PROMPT ≤10 字符跟随 locale） |
| generateSuggestion | suggestion_model | 最后 8 条（扮演用户出 3~5 建议） |
| OCR | ocr_model | 图片（DEFAULT_OCR_PROMPT） |
| 滚动摘要 | compress_model | 窗口外历史（DEFAULT_COMPRESS_PROMPT） |
| 消息翻译 | translate_model | 单条消息（DEFAULT_TRANSLATION_PROMPT；Qwen-MT 特判） |

均不走会话存储，结果写回消息字段或会话字段。
