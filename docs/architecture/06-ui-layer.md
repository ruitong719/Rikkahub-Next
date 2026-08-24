# 06 · UI 层（Compose + Navigation3）

> 路径：`app/src/main/java/me/rerere/rikkahub/ui/` + 根包 `RikkaHubApp.kt`、`RouteActivity.kt`。

## 1. 应用入口

### RikkaHubApp (Application) onCreate 顺序
1. Koin：`startKoin { androidLogger(); androidContext(this); workManagerFactory(); modules(appModule, viewModelModule, dataSourceModule, repositoryModule) }`
2. 通知渠道×4：`chat_completed`(HIGH+振动) / `chat_live_update`(LOW) / `web_server`(LOW) / `floating_bubble`(LOW)
3. `DatabaseUtil.setCursorWindowSize(32MB)`
4. `CrashHandler.install(this)`（全局崩溃捕获 → SafeMode）
5. `QuickJSLoader.init()`（CustomJs 搜索服务用）
6. AppScope 协程清理：deleteTempFiles / cleanupToolOutputs / cleanupWorkspaceTempDirs / cleanupOrphanBgTasks(被杀进程遗留任务标 failed) / refreshMountedPhoneDirs(挂载启动自动 PULL) / checkWorkspaceIntegrity / syncManagedFiles
7. `startWebServerIfEnabled()`（延迟 500ms；查 POST_NOTIFICATIONS(API33+) 与 ACCESS_LOCAL_NETWORK(API37+ 且非 localhost-only)）→ startForegroundService(WebServerService)
8. `startFloatingBubbleIfEnabled()`（延迟 300ms；查 canDrawOverlays）→ FloatingBubbleService
9. incrementLaunchCount()

`onTerminate`：取消 AppScope、停 WebServerService。`AppScope`=全局协程作用域(SupervisorJob+Main+ExceptionHandler)，Koin single。

### Activity 结构
- **RouteActivity**（唯一主 Activity，LAUNCHER）：崩溃检查→SafeModeActivity；setContent{ RikkahubTheme; coil3 单例 ImageLoader(OkHttp 网络层+GIF/SVG decoder); AppRoutes() }；音量键监听注册表 volumeKeyListeners("最后注册者胜"，ChatList 注册滚动)；onNewIntent 处理 conversationId→追加 Screen.Chat；onResume 恢复临时隐藏悬浮球
- SafeModeActivity：崩溃自救页（堆栈/切助手/清数据）
- ShortcutHandlerActivity：透明，深链 `rikkahub://shortcut` 拍照快捷方式
- McpOAuthCallbackActivity：透明 singleTask，收 `rikkahub://mcp-oauth-callback?code&state&error` → AppEventBus
- UCropActivity（三方图片裁剪）

### AndroidManifest 要点
- 权限：INTERNET/CAMERA/RECORD_AUDIO/WRITE_EXTERNAL_STORAGE(≤28)/POST_NOTIFICATIONS(+PROMOTED)/ACCESS_WIFI_STATE/CHANGE_WIFI_MULTICAST_STATE/FOREGROUND_SERVICE(+SPECIAL_USE)/SYSTEM_ALERT_WINDOW/ACCESS_LOCAL_NETWORK/PACKAGE_USAGE_STATS/READ+WRITE_CALENDAR
- application：largeHeap、usesCleartextTraffic=true、enableOnBackInvokedCallback、Theme.Rikkahub
- Provider：FileProvider(`${applicationId}.fileprovider`)、WorkspaceDocumentsProvider(`.documents`)、InitializationProvider remove WorkManagerInitializer

## 2. 导航体系（Jetpack Navigation3）

核心在 RouteActivity.AppRoutes()：

- 栈：`rememberNavBackStack(startScreen)`——startScreen 按 SharedPreferences `create_new_conversation_on_start`（默认 true→冷启新 UUID 会话；否则恢复 lastConversationId）构造 `Screen.Chat(id)`
- 展示：`NavDisplay(backStack, entryDecorators=[saveableStateHolder, viewModelStore], onBack=removeLastOrNull, transitionSpec 自定义滑动+缩放)`
- 路由 = `sealed interface Screen : NavKey` 的 @Serializable data class/object（类型安全参数）

### 全路由表

| 分组 | 路由 |
|---|---|
| 核心 | Chat(id, text?, files: List\<String\>, nodeId?)、ShareHandler(text, streamUri?)、History、Favorite、MessageSearch、Stats |
| 助手 | Assistant、AssistantDetail(id)、AssistantBasic(id)、AssistantPrompt(id)、AssistantMemory(id)、AssistantRequest(id)、AssistantMcp(id)、AssistantLocalTool(id)、AssistantInjections(id) |
| 工具页 | Translator、ImageGen、Backup、WebView(url, contentId)、Extensions、QuickMessages |
| 扩展 | Skills、SkillDetail(skillName)、SubAgents、SubAgentEdit(id)、SubAgentTrace(id)、Workspaces、WorkspaceDetail(id)、WorkspaceTerminal(id)、WorkspaceFileEditor(id, area, path) |
| 设置 | Setting、SettingTheme、SettingPreferences(+Theme/Notification/General/UI/Network 五子页)、SettingProvider、SettingProviderDetail(providerId)、SettingModels、SettingAbout、SettingSearch、SettingSearchDetail(serviceId)、SettingSpeech、SettingMcp、SettingFiles、SettingWeb |
| 调试 | Debug、Log |

- 导航封装：ui/context/NavContext.kt `Navigator(backStack)`（navigate/popUpTo/launchSingleTop/clearAndNavigate）经 LocalNavController 注入；utils/ChatUtil.kt `navigateToChatPage(navigator, chatId=Uuid.random(), initText, initFiles, nodeId)` 清栈开新会话
- **无底部导航栏**：单聊天页优先设计，功能入口全在聊天抽屉；大屏(≥1100dp 横屏) PermanentNavigationDrawer
- 整树包 SharedTransitionLayout（hero 动画 Modifier.heroAnimation）；sonner Toaster 全局；TTSController 全局播放条；DB 迁移进度遮罩(DatabaseMigrationTracker)

## 3. 页面清单（ui/pages/）

| 目录 | 页面 | VM | 要点 |
|---|---|---|---|
| chat/ | ChatPage | ChatVM | 见 §5 |
| chat/ | ChatDrawer(948行)+ConversationList | ChatDrawerVM(scope绑 Activity 防销毁丢状态) | 更新卡/备份提醒卡/用户头像昵称/Greeting/DrawerActions(搜索·历史)/文件夹 chips/分页会话列表(滚动位置持久化)/长按菜单(置顶·移助手·移文件夹·删除)/AssistantPicker/底部动作行(翻译·绘图·收藏·统计·设置) |
| chat/ | ChatList(859行) | — | 见 §5 |
| chat/ | Export / Background+MeshGradientBackground / TTSAutoPlay / ConversationSystemPromptCard / ChatSizeChecker | — | 导出 Sheet/助手背景(TTS 自动播放监听 generationDoneFlow，支持"只读引号内"过滤)/会话级 system prompt 卡/对话过大警告 |
| assistant/detail/ | AssistantDetailPage(tab容器)+Basic/Prompt/Memory/Request/Mcp/LocalTool/Extensions 子页+BackgroundPicker+PropertyEditor | AssistantDetailVM | 均以 assistantId 为参；Basic 含滚动摘要阈值 preset chips |
| backup/ | BackupPage | BackupVM | 三 Tab：ImportExport(本地 zip)/WebDav/Reminder(提醒周期) |
| extensions/ | ExtensionsPage 入口 + QuickMessagesPage + skills/{SkillsPage,SkillDetailPage} + subagents/{SubAgentsPage,SubAgentEditPage,SubAgentTracePage} + workspace/{WorkspacePage, WorkspaceDetailPage(1245行), WorkspaceFileEditorPage, WorkspaceTerminalPage}+WorkspaceTerminalSession | 各同名 VM | 工作区详情含 rootfs 安装(DEFAULT_ROOTFS_URL ubuntu-base-24.04.3 arm64)；终端页基于 termux_pty JNI+proot(PTY 会话) |
| favorite/ history/ search/ stats/ | FavoritePage / HistoryPage / SearchPage / StatsPage | 同名 VM | 收藏回跳(nodeId 定位)/滑动操作会话历史/FTS 高亮搜索(RELEVANCE 默认排序持久化)/年度热力图+token 统计(json_each SQL) |
| imggen/ | ImageGenPage | ImgGenVM | prompt+数量(1-4)+尺寸+参考图(图生图)；Paging3 历史画廊(GenMediaEntity)；生成可取消；走 ProviderManager image generation/edit |
| translator/ log/ debug/ share/handler/ webview/ setting/(17页+components) | TranslatorPage 直调 GenerationHandler 流式翻译；LogPage 读 common Logging 环形缓冲(TextLog/RequestLog 详情)；DebugPage 会话计数/toast/重置模型/色板；ShareHandlerPage 选助手后 navigateToChatPage(initText)；WebViewPage(url 或 contentId 从 WebViewContentCache 取 HTML)；设置族含 ProviderConfigure/ConnectionTester/BalanceOption/TTS·ASRProviderConfigure/CustomThemeButton 等 | SettingVM 等 | — |

## 4. 公共组件库（ui/components/）

### message 渲染管线（components/message/）
- **ChatMessage.kt**(635行)：字号缩放(displaySetting.fontSizeRatio)+聊天字体；头像行(助手=模型 icon 或头像)；**MessagePartsBlock** 用 `parts.groupMessageParts()` 分组：
  - ThinkingBlock → **ChainOfThought** 卡片逐 step 渲染 ReasoningStep / ToolStep(审批 UI) / ServerToolStep
  - ContentBlock.Text：USER→气泡(primaryContainer,点击编辑)；ASSISTANT→可选气泡或平铺；文本先 replaceRegexes(visual=true) 再进 MarkdownBlock；流式期间不用 SelectionContainer(避免 ConcurrentModificationException)
  - Image→ZoomableAsyncImage；Video/Audio/Document→FileProvider 外部打开
  - 引用点击：解析 search_web 输出 items JSON 按 citation id 开 URL
  - 生成中触觉反馈(debounce 50ms)；CollapsibleTranslationText；EditedFilesList(diff)；ChatMessageNerdLine(token/耗时极客行)
  - 操作区：重新生成/分支切换 ChatMessageBranch/翻译/收藏 + 长按 ActionsSheet(编辑/删除/fork/复制面板/WebView Markdown 预览)
- **ChatMessageTools.kt**(520行)：pending 审批按钮(拒绝弹 ToolDenyReasonDialog)；AskUserToolStep 问答表单；denied 显示理由
- tools/ 子包注册表见 05 文档 §8

### richtext 子包（渲染方案）
| 文件 | 方案 |
|---|---|
| Markdown.kt(~1100行) | **主力**：org.intellij.markdown ASTNode Compose 自绘递归渲染(非 WebView)；标题/列表/引用/表格/图片/链接(LinkAnnotation)/行内码/代码块/HTML 片段降级 SimpleHtmlBlock；入口 `MarkdownBlock(content, onClickCitation)` |
| LatexText.kt+MathBlock.kt | JLatexMath Canvas 绘制；displaySetting.enableLatexRendering 开关；行内 `$...$` InlineContent 占位 |
| HighlightCodeBlock.kt | :highlight 模块集成入口(LocalCodeHighlighter tokenize→AnnotatedString)；语言标签头/复制/横滚/换行开关 |
| Mermaid.kt | WebView 加载本地 mermaid.min.js(虚拟域名 https://rikkahub.local/assets 拦截映射 assets)；JS bridge 回传 base64 PNG 大图查看 |
| MarkdownNew.kt / MarkdownWeb.kt | 实验性重写 / WebView 完整预览(marked.js+KaTeX+highlight.js 模板 mark.html) |
| DiffView.kt | unified diff 双列/单列 |

### ai 子包（输入区）
- **ChatInput.kt**(946行)三层：
  1. MediaFileInputRow 附件 chips
  2. TextInputRow：BasicTextFieldState + QuickMessageButton(斜杠触发快捷消息) + CompletionPopup(ChatCompletionProvider 异步补全：label/insertText/sortScore，键盘选择；WorkspaceCompletionProvider 实现 @ 文件路径补全带 TTL 缓存+WorkspaceIgnoreMatcher gitignore 式过滤) + FullScreenEditor
  3. 底部动作行(横滚)：ModelSelector(图标)→SearchPickerButton(三态 OFF/LOCAL/BUILT-IN)→ReasoningButton(7档)→TodoStatusButton(角标)→SubAgentMonitorButton(运行中数)→BackgroundTaskButton(4s轮询 bg 任务)→"+"附件→AsrButton(录音波形+音效 SoundEffectPlayer)→发送圆钮(combinedClickable 智能发送语义，见 04 文档 §8)
- ModelList.kt(842行 模型选择器)/AssistantPicker/McpPicker/SearchPicker/ReasoningPicker/FilesPicker(538行)/AttachmentChips/CropLauncher(UCrop)/CompressContextDialog(压缩参数)/WorkspaceSelectSheet/SubAgentMonitor/TodoSheet/BackgroundTaskMonitor/ProviderBalanceText

### ui 基础组件
AIIcon(厂商图标)/ChainOfThought/DotLoading/ErrorCard/Form/Input/TextArea/Select/Switch/Tag/Tooltip/CardGroup/StickyHeader/ListSelectableItem/ToggleSurface/JsonTree/QRCode/Favicon/ShareSheet/Export(BitmapComposer 合成导出图)/UpdateCard/BackupReminderCard/Greeting/FloatingWindow/KeepScreenOn/ColorPickerRow/ImagePreviewDialog/Emoji/icons(自绘 Discord·QQ·Heart·Reasoning)/permission(自封装运行时权限 PermissionManager/RationaleDialog)

### nav/webview/table/easteregg
BackButton；自封装 WebView(WebViewState)+WebViewContentCache(HTML 存 cacheDir sha256 id 引用免 Intent 大字符串)+WebViewLocalAssets(assets 本地拦截映射)；DataTable(subcompose 测量自适应列宽)；EmojiBurst 彩蛋粒子。

## 5. ChatPage / ChatList 专项

### ChatPage(id,text,files,nodeId)
- 大屏 PermanentNavigationDrawer / 小屏 ModalNavigationDrawer → ChatPageContent
- text base64 解码、files 经 FilesManager 落地按 MIME 转 part 灌入 inputState；nodeId 初始定位或贴底
- Scaffold：TopBar(标题可编辑 useEditState；副标题"助手 / 模型 (提供商)"；previewMode 大纲切换；新会话钮) + bottomBar=ChatInput + 内容=ChatList；haze 毛玻璃背景(AssistantBackground hazeSource)
- ChatFilesPickerSheet("+")：相机(权限+TakePicture+UCrop 可 skipCrop；HEIF→JPEG)/多选媒体/文档白名单 isAllowedFileType→全经 FilesManager.createChatFilesByContents 私有目录拷贝；Sheet 内还有 MCP 选择、CompressContextDialog(手动压缩)、助手注入设置
- 助手绑定 workspace 时注入 WorkspaceCompletionProvider(@路径补全)

### ChatList(859行)
- AnimatedContent(previewMode)：正常列表 vs ChatListPreview(大纲跳转)
- LazyColumn(itemsIndexed(messageNodes, key=node.id)) 每项 ChatMessage 外包 ListSelectableItem 多选导出
- **自动贴底闩锁**：snapshotFlow(visibleItemsInfo)+stickToBottom——生成期间贴底就持续 requestScrollToItem(lastIndex+10)；拖拽解锁，回底重新闩上
- 音量键滚动(RouteActivity listener 注册表)
- 底部浮动：ErrorCardsDisplay(zIndex5)/多选 HorizontalFloatingToolbar/MessageJumper(1.5s 出现)/ChatSuggestionsRow
- loading 尾部 ContainedLoadingIndicator+processingStatus(正在执行的工具名)

## 6. ChatVM 状态流一览

| StateFlow | 来源 |
|---|---|
| conversation | chatService.getConversationFlow(id)(session 单一事实源) |
| todos | todoStore.todos(id) |
| inputState | ChatInputState()(放 VM 防 TransactionTooLargeException) |
| conversationJob | getGenerationJobStateFlow(id)(null=空闲) |
| processingStatus / queuedCount / conversationJobs / errors / generationDoneFlow | ChatService |
| settings / enableWebSearch / currentChatModel | settingsStore 派生 |
| updateState | UpdateChecker(可禁用/暂停) |

生命周期：init addConversationReference→initializeConversation(无则新建并填 presetMessages)→写 lastConversationId；onCleared 移除引用。
其它职责：generateTitle/generateSuggestion/translateMessage/handleCompressContext/forkMessage/toggleMessageFavorite(Repo+node.isFavorite 双写)/会话移助手清 folderId。

## 7. 主题系统（ui/theme/）

- RikkahubTheme(colorMode=rememberCurrentColorMode())：ColorMode{SYSTEM,LIGHT,DARK} 存 SharedPreferences "colorMode"(与 Settings 主题配置分离)
- 配色优先级：settings.dynamicColor && SDK≥S → Material You dynamicDark/LightColorScheme；否则 themeId 先查 customThemes 再查内置 PresetThemes[Sakura,Ocean,Spring,Autumn,Black,Minimal,Claude]（兜底 Sakura）
- AMOLED 纯黑(dark+amoledDarkMode)；LocalExtendColors 扩展色板；MaterialExpressiveTheme+MotionScheme.expressive()
- CustomTheme JSON 格式：`{"id":"<uuid>","name":"","primaryColorArgb":0xFF6750A4,"secondaryColorArgb":null,"tertiaryColorArgb":null}`；material-color-utilities Hct→TONAL_SPOT DynamicScheme→:material3 模块 toColorScheme()；导入导出走 JSON 粘贴/文件(SettingThemePage ImportThemeDialog)
- 其余：Type.kt 排版/CodeColor.kt(高亮配色→HighlightTextColorPalette)/ChatFont.kt(聊天字体 LocalChatFontFamily)/GoogleSans.kt(883 行内嵌字体定义)

## 8. 悬浮球（fork 特色）

```
FloatingBubbleService(FGS, FloatingX 球体, 拖动贴边, 点击回 App)
  └─ 展开 → ui/floating/FloatingExpandWindow.kt(537行)
       无 Activity 环境的 Compose 悬浮窗:
       手动组装 WindowLifecycleOwner(LifecycleRegistry+ViewModelStore+SavedStateRegistry)
       → ComposeView → WindowManager addView
       PrimaryTabRow 两 Tab: 待办(TodoStoreItem 勾选) / 实时输出(shell 命令流)
       拖动/关闭/暂停显示(tempHidden=true, RouteActivity.onResume 自动恢复)
数据源: service/FloatingActivityHub(Koin single)
  订阅 ChatService/AppEventBus 生成事件
  → FloatingActivityState(status, liveText, reasoning, realTodos, terminalCommands)
```
窗口尺寸/标签开关/颜色/大小/透明度/自定义图标均由 floating_bubble_* 设置驱动（服务订阅 settingsStore 实时刷新）。

## 9. hooks / modifier / context / utils

- hooks/: ASR.kt(rememberCustomAsrState 按 provider 构建重建)/TTS.kt/Settings.kt(rememberUserSettingsState)/SharedPreferences.kt/**ChatInputState.kt**(文本 TextFieldState+messageContent 附件+editingParts 编辑态保留原附件 URL 集合)/UseAssistant/UseEditState/ColorMode/HeroAnimation/ImeAutoScroller/Debounce/Lifecycle/PlayStore(隐藏更新检查)/AvatarShape(loading 旋转渐变边框)
- modifier/: Clickable(Modifier.onClick ripple)/Shimmer
- context/: LocalSettings/LocalNavController(Navigator)/LocalSharedTransitionScope/LocalToaster/LocalTTSState/LocalASRState——均在 RouteActivity 顶层 provide
- utils/(22 文件)：ChatUtil(navigateToChatPage+isAllowedFileType 白名单)/CrashHandler(hasCrashed/getStackTrace/clearCrashed)/UpdateChecker(GitHub release)/SoundEffectPlayer/ImageUtils(HEIF→JPEG)/NotificationUtil/DatabaseUtil/UiState(Loading/Success/Error)/TimeUtil/StringUtils/DiffUtils/EmojiUtils/ClipboardUtil/MarkdownUtils/AIIconMatcher/Json(JsonInstant/JsonInstantPretty)

## 10. i18n

6 语言 values/values-zh/values-zh-rTW/values-ja/values-ko-rKR/values-ru；页面级 key 前缀约定；locale-tui 工具链见 01 文档 §7。
