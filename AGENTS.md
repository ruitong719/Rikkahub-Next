# Repository Guidelines

## Architecture Documentation

**接手前必读：`docs/architecture/`** —— 完整架构文档，避免重新考古源码：

| 文件 | 内容 |
|---|---|
| README.md | 索引、高频修改手册（加 DB 字段/设置项/工具/页面/Web API）、已知坑 |
| 01-overview.md | 模块地图、依赖版本、构建体系、CI、上游同步流程 |
| 02-core-models.md | `ai` 模块：UIMessage/UIMessagePart/StreamChunk/Provider 抽象与实现 |
| 03-data-layer.md | Room 数据库、DataStore 设置键全集、Repository、DI 清单 |
| 04-generation-pipeline.md | 聊天生成全链路（ChatService → GenerationHandler → 工具循环） |
| 05-tools-and-mcp.md | 全部 AI 工具清单与审批机制、MCP/OAuth |
| 06-ui-layer.md | Navigation3 全路由表、页面清单、组件库、主题 |
| 07-library-modules.md | common/search/speech/document/highlight/material3 |
| 08-workspace-sandbox.md | PRoot 沙箱、rootfs 安装、后台任务 |
| 09-web-platform.md | 内置 Web 服务器 REST/SSE API 合同、web-ui 前端 |

## Project Overview

RikkaHub Next is a native Android LLM chat client that supports switching between different AI providers
for conversations. Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.
It is a fork of [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) (AGPL-3.0) with extra agentic
features (workspace sandbox, subagents, message queue); applicationId is `me.rerere.rikkahubnext`
(current versionCode/versionName live in `app/build.gradle.kts`, bumped independently from upstream).

Fork conventions: functional changes are recorded per-date in `docs/CHANGES.md`; upstream merge
reconciliation lives in `docs/UPSTREAM_SYNC.md`. Update them when doing equivalent work.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew :app:assembleRelease   # Release APK（需根目录 local.properties 提供 storeFile/storePassword/keyAlias/keyPassword）
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

Build notes:

- Gradle daemon requires JetBrains JDK 21 (auto-provisioned via foojay); configuration cache is on.
- Building `:web` runs `buildWebUi` which executes `pnpm run build` inside `web-ui/`. Skip with
  `-x :web:buildWebUi` when Node/pnpm is unavailable (static resources stay stale).
- `com.github.rikkahub:sqlite-android` is a requery fork resolved as `-SNAPSHOT` from jitpack/mavenLocal;
  a cold environment may need it published locally first.
- No Firebase/google-services required. ABI: arm64-v8a + x86_64 only.

## Module Structure

Gradle modules (`settings.gradle.kts`):

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI incl. Responses API, Google/Gemini+Vertex, Anthropic/Claude)
- **common**: Common utilities and extensions (logging ring buffer, cache stores, SSE/JSON helpers, QuickJS fetch polyfill)
- **document**: Document parsing module for handling PDF (vendored MuPDF), DOCX, PPTX, and EPUB files (all parsers return Markdown strings)
- **highlight**: Code syntax highlighting — self-contained Kotlin port of highlight.js (~30 languages, golden-fixture tested)
- **material3**: Bridge mapping material-color-utilities `DynamicScheme` to Compose `ColorScheme`; builds the material-color-utilities git submodule
- **search**: Search functionality SDK with ~19 providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, LinkUp, Metaso, Ollama, Perplexity, Firecrawl, Jina, Bocha, Grok, Tinyfish, Serper, Custom JS, and others)
- **speech**: Speech module for cloud/system TTS providers and ASR controllers (OpenAI Realtime/DashScope/Volcengine/MiMo/Step)
- **web**: Embedded Ktor server module providing `startWebServer()` and hosting static frontend build files (built from web-ui/)
- **workspace**: PRoot-sandboxed per-workspace Linux environment (rootfs installer, shell runner, background tasks) exposed to the AI as tools
- **app/baselineprofile**: Macrobenchmark generator for startup baseline profiles

Non-Gradle auxiliary projects at repo root:

- **web-ui/**: React Router 7 + Tailwind 4 SPA served by `:web` (pnpm; output copied into web/src/main/resources/static)
- **trace-cli/**: Bun CLI recording real provider SSE streams into `events.jsonl` fixtures replayed by ai module tests
- **locale-tui/**: Python/textual TUI for managing strings.xml translations (AI auto-translate, dead-key detection)

## Persistence Quick Facts

- Database: Room `rikka_hub` (version in `data/db/AppDatabase.kt`), schemas JSON committed under `app/schemas/`.
  The FTS5 table `message_fts` is NOT in the Room schema — it is created idempotently in the database
  `onOpen` callback (see `di/DataSourceModule.kt`) using the libsimple/jieba tokenizer extension.
- User settings: DataStore preferences file `settings` via `SettingsStore` (key inventory in docs/architecture/03).
- Adding DB fields/settings keys/tool/page/web-endpoint: follow the playbooks in `docs/architecture/README.md`.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, token-threshold-based rolling context compression,
  custom headers/bodies, tools, memory options, regex transformations, MCP servers, local tools, workspace binding,
  skills, and subagents. Assistants provide isolated chat environments with specific behaviors and capabilities.
  (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, folder membership, chat suggestions, optional conversation-level system prompt, a persisted
  rolling-context summary, and an optional bound workspace cwd.
  (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, server tools, etc.). Each message has a role (USER,
  ASSISTANT, SYSTEM, TOOL), creation timestamp, model ID, token usage, pure-generation duration, and optional
  annotations. UIMessages support streaming updates through chunk merging.
  (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Tool & Approval**: Tools are defined as `Tool(name, description, parameters, systemPrompt, needsApproval, execute)`
  (ai/src/main/java/me/rerere/ai/core/Tool.kt). Execution goes through an approval state machine
  (`ToolApprovalState`: Auto/Pending/Approved/Denied(reason)/Answered(answer)) so generation can pause, wait for the
  user, and resume; ask_user reuses Answered. Full tool inventory: docs/architecture/05-tools-and-mcp.md.

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Input-side
  transformers assembled by ChatService, in order:
  - TimeReminderTransformer: inject `<time_reminder>` before stale user turns (assistant opt-in)
  - PlaceholderTransformer: substitute `{{cur_date}}`, `{{model_id}}`, `{{user}}`, … placeholders
  - DocumentAsPromptTransformer: convert document attachments to text prompts (uses :document parsers)
  - OcrTransformer: OCR images via the OCR model when the main model cannot see images
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - WorkspaceReminderTransformer: append the `<workspace>` system block for assistants bound to a READY workspace
  - AgentMdTransformer: prepend markdown files from the `/agent` directory (falls back to globalAgentMd)
  - VisionImageToTextTransformer: degrade images to text descriptions via the configured vision model
  - BackgroundTaskReminderTransformer: notify about finished workspace background tasks

  Output-side transformers:
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts (also visual transform)
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references (onGenerationFinish)

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

- **ChatService**: Not an Android Service — a Koin singleton orchestrating all chat generation
  (service/ChatService.kt). It owns per-conversation `ConversationSession`s (state flow, generation job slot,
  reference counting, queued-message queue), routes tool approvals, auto-resumes generation when workspace background
  tasks finish, and drives title/suggestion side generations. Entry point: `handleMessageComplete()`;
  full pipeline documented in docs/architecture/04-generation-pipeline.md.

- **Workspace**: A sandboxed Linux environment per assistant/conversation backed by PRoot
  (:workspace module; docs/architecture/08-workspace-sandbox.md). Files live in two storage areas — FILES mounted at
  `/workspace` and the LINUX rootfs — plus fixed bind mounts (/skills, /upload, /tool_outputs, /agent). Supports
  persistent background tasks that survive app death and auto-resume the LLM on completion.

- **SubAgent**: User-defined sub-agents invoked by the main agent through generated `subagent_<slug>` tools
  (data/ai/SubAgentRunner.kt). They run isolated generation loops with their own allowlisted tool set, step limit,
  timeout, and execution trace monitoring; nested subagent calls are disallowed.

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Supported locales: `values` (English source), `values-zh`, `values-zh-rTW`, `values-ja`, `values-ko-rKR`, `values-ru`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
