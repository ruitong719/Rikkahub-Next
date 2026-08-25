# 09 · 内置 Web 平台（Ktor 服务器 + REST/SSE API + web-ui 前端）

> 手机上跑一个 HTTP 服务器，局域网内用浏览器继续聊天。三层：
> `:web` 模块（Ktor 装配+SPA 托管）→ app `web/` 包（业务路由+DTO）→ `web-ui/`（React 前端，构建产物进 :web）。

## 1. :web 模块（web/src/main/java/me/rerere/rikkahub/web/Entry.kt）

```kotlin
fun startWebServer(port=8080, host="0.0.0.0", module: suspend Application.() -> Unit)
```
- Ktor **CIO** 引擎；install 顺序：Compression → CORS(anyHost anyMethod, 允许 ContentType/Authorization, allowNonSimpleContentTypes) → SSE → DefaultHeaders
- routing：`staticResources("/", "static") { default("index.html"); singlePageApplication() }`——resources/static 作 SPA 根，未命中回落 index.html
- 最后调用 module lambda（app 用它挂 /api 业务路由）
- minSdk 覆写为 24

### buildWebUi task（web/build.gradle.kts）
- Exec 类型：workingDir 根目录 `web-ui/`，按平台执行 `pnpm run build`
- inputs: web-ui 的 package.json/pnpm-lock/vite.config.ts/app/public 等；outputs: resources/static
- `preBuild dependsOn(buildWebUi)`——**构建 :web 必然重建前端**；跳过用 `-x :web:buildWebUi`
- static 目录被 gitignore 不入库

## 2. app 侧装配与生命周期

- WebServerManager(Koin single)：start(port=8080, serviceName, localhostOnly)/stop/restart；state: StateFlow<WebServerState(isRunning,port,address,error)>；非 localhost 模式经 NsdServiceRegistrar(JmDNS) 注册 `_http._tcp.local.`（MulticastLock 管理，WiFi Info 取本机 IP）
- RikkaHubApp.startWebServerIfEnabled()：延迟 500ms 读设置；检查 POST_NOTIFICATIONS(33+)/ACCESS_LOCAL_NETWORK(37+, 非 localhost-only)→ startForegroundService(WebServerService(ACTION_START, EXTRA_PORT, EXTRA_LOCALHOST_ONLY))
- WebServerService(specialUse FGS)：启动即 startForeground 并观察 state 更新通知（运行中显示 URL+停止按钮）；intent=null 时按设置决定自启

## 3. JWT 鉴权（WebApiModule.kt）

- `POST /api/auth/token`：访问密码换 JWT（HMAC256，secret=访问密码，issuer rikkahub-web，TTL 30 天）；恒定时间比较防时序攻击
- verifier 每请求动态读当前密码；token 可放 `Authorization: Bearer` 或 `?access_token=` 查询参数（SSE/EventSource 兜底）
- 密码为空时受保护路由直接 Forbidden
- StatusPages：ApiException 族(BadRequest/NotFound/Unauthorized/Forbidden/Conflict)→对应状态码

## 4. REST/SSE API 合同（前缀 /api）

| 方法/路径 | 功能 |
|---|---|
| POST /auth/token | 密码换 JWT |
| GET /ai-icon?name= | AI 供应商图标资产（免鉴权，带 Cache-Control） |
| GET /conversations | 分页会话列表（支持 search） |
| DELETE /conversations/{id} | 删除会话 |
| POST /conversations/{id}/pin \| regenerate-title \| title \| move \| folder \| permission-mode | 置顶/重新生成标题/改标题/移动助手/移动文件夹/权限模式(plan/build/yolo) |
| POST /conversations/{id}/messages | 发送消息(SendMessageRequest) |
| POST .../messages/{mid}/edit · DELETE .../messages/{mid} | 编辑/删除消息 |
| POST .../fork · nodes/{nid}/select · regenerate · stop · tool-approval | fork/切换分支/重新生成/打断/工具审批 |
| **GET /conversations/{id}/stream** | SSE 会话流（生成增量推送） |
| **GET /events** | 全局 SSE 心跳 15s：settings 快照、会话列表失效、folder 列表等 |
| GET/POST /folders · POST /folders/{id}/rename · DELETE /folders/{id} | 文件夹 CRUD |
| POST /files/upload · DELETE /files/{id} · GET /files/id/{id} · GET /files/path/{path...} | 上传/删除/下载 |
| GET /assets/{path...} | 静态资产 |
| POST /settings/assistant \| assistant/model \| assistant/thinking-budget \| assistant/mcp \| assistant/injections | 助手级设置修改 |
| POST /settings/search/enabled \| search/service · model/built-in-tool · favorite-models | 搜索开关/服务、模型内置工具、收藏模型 |

### DTO（web/dto/WebDto.kt）
- 请求：SendMessageRequest/RegenerateRequest/**ToolApprovalRequest(approved,reason,answer)**/EditMessageRequest/ForkConversationRequest/SelectMessageNodeRequest/MoveConversationRequest/Update* 系列(含 UpdatePermissionModeRequest)/WebAuthTokenRequest
- 响应：ConversationListDto/ConversationDto(含 permissionMode 字段)/MessageNodeDto/MessageDto/PagedResult\<T\>/UploadedFileDto/MessageSearchResultDto/WebAuthTokenResponse/ErrorResponse
- SSE 事件：ConversationUpdateEvent/**SnapshotEvent(seq 增量)**/**NodeUpdateEvent**(单节点变化时替代全量 snapshot，由 routes/ConversationDiff.singleNodeDiffOrNull 计算)/GenerationDoneEvent/ErrorEvent/ListInvalidateEvent/FolderListEvent
- 工具类：routes/RouteUtils.kt(String?.toUuid)、routes/ConversationDiff.kt

## 5. web-ui 前端（根目录 web-ui/，React Router 7 SPA）

### 技术栈
React Router 7.13 framework mode(`ssr:false`) / Vite7 / React 19.2 / TS 5.9 / **Tailwind 4**(+shadcn 风格组件 app/components/ui/* 约40个, radix-ui, cva, tailwind-merge) / TanStack Query 5 / ky(HTTP) / zustand 5(stores 分 slice: chat-input/clock/settings) / immer+zod4 / streamdown(+@streamdown/cjk)+shiki3(流式 Markdown+高亮)+katex/remark-gfm/rehype-raw / i18next(locales en-US·zh-CN×{common,input,markdown,message,page}.json)

### scripts
```
pnpm build      # react-router build && tsx copy.ts → 产物清空复制到 ../web/src/main/resources/static
pnpm dev        # react-router dev
pnpm typecheck  # react-router typegen && tsc ; lint/fmt = oxlint/oxfmt
```

### 结构（web-ui/app/）
- routes.ts 仅两条路由：`index → routes/home.tsx`、`c/:id → routes/c.$id.tsx`(会话页)
- components/: conversation-sidebar/conversation-search-button/conversation-quick-jump/theme-provider/custom-theme-dialog/**web-auth-gate**(密码门)/logo；input/(chat-input/model-list/mcp-picker/reasoning-picker/search-picker/extension-picker)；message/(chat-message/chain-of-thought/avatar-row + parts/{audio,document,image,reasoning,text,tool,video})；markdown/(markdown.tsx/code-block.tsx shiki 封装)；**workbench/**(工作区文件预览面板 workbench-host/context/code-preview-language)；extended/(infinite-scroll-area 等)；ui/(shadcn 库)
- hooks/: use-conversation-list/use-current-assistant/use-current-model/use-folders/use-mobile/use-picker-popover
- stores/: zustand(index/app-store/settings/chat-input + slices/* + use-settings-subscription 订阅 SSE settings 事件)
- types/: 与 Kotlin DTO 对齐的手写类型(core/conversation/message/parts/settings/dto/annotations)

### API 客户端（app/services/api.ts）
- 单一 ky 实例：prefixUrl "/api"、timeout 30s、beforeRequest 自动附 Bearer token
- 认证状态机：token 存 localStorage `rikkahub:web-auth`{token,expiresAt}(10s 过期 skew)；401 清 token 并派发自定义事件 `rikkahub:web-auth-required`(web-auth-gate 监听弹密码框)；`appendWebAuthQuery(url)` 给 SSE 拼 ?access_token=
- api 对象泛型 get/post/postMultipart/put/patch/delete(JSON unwrap + ApiError(message,code))；requestWebAuthToken(password)
- `sse<T>(url, callbacks, options)`：ky GET(Accept text/event-stream, timeout:false) 手动 reader 循环解析 event:/data:/id: 帧(多行 data 拼接)

### 多路复用 SSE 客户端（app/services/events.ts）
对应后端 EventsRoutes.kt：单一共享连接承载 EVENT_SETTINGS/EVENT_CONVERSATION_LIST_INVALIDATE/EVENT_FOLDERS；按事件名订阅(listeners Map)，首个订阅者开连接、无人监听自动关闭、断线 1s 重连。

## 6. 改动指引

- 新端点：web/routes/ 加 Route 扩展 → WebApiModule.configureWebApi 注册(注意鉴权包裹位置) → WebDto.kt 加 DTO → web-ui services/api.ts + types/ 同步
- 前端新页面：web-ui/app/routes.ts 注册路由 + routes/ 下文件；i18n 双语 JSON
- 前端联调：`pnpm dev` 起 Vite 后需代理或直接对手机 IP 调试；产物验证走 `./gradlew :app:assembleDebug`(自动触发 buildWebUi)
