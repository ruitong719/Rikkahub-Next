<div align="center">
  <img src="docs/icon.png" alt="应用图标" width="100" />
  <h1>Rikkahub Next</h1>

原生 Android LLM 聊天客户端，支持在多个 AI 提供商之间自由切换 🤖💬

[简体中文](README.md)（默认）
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择" width="450" />
</div>

## 🔗 与上游的关系

本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub)（[AGPL-3.0](LICENSE) 协议）的**中文定制分支（fork）**：

- **基线**：上游 `master` 2.4.9（`0c52b62b`），并持续跟踪上游更新
- **方向**：在保留上游全部功能的基础上，面向中文用户做定制与增强

### 本分支与上游的差异

- 🗑️ **移除**：
  - Firebase / google-services（构建**不再需要** `google-services.json`）
  - 模式注入 / 世界书（数据库迁移 26 → 27）
- ➕ **新增**：
  - Workspace 增强：后台任务、SAF 目录挂载、导出到手机、`backup.zip` 备份工具
  - Subagent（子代理）：数据模型、预置模板、运行器与完整编辑页
  - Todo 工具、per-tool 可注入提示词、思考深度映射
  - 应用图标与品牌更名为 Rikkahub Next
- 📄 逐功能合入、review 修复与编译验证的完整记录见 [CHANGES.md](CHANGES.md)

> [!WARNING]
> 上游 RikkaHub 官方提醒：社区中存在大量 fork 版本，fork 产生的问题与官方无关，使用时请警惕隐私泄露与过度索取权限。本分支由社区维护，使用前请自行评估风险。

## 📦 下载

暂无公开发布版本，请从源码自行构建（见下方「贡献」），或联系维护者获取已签名的 Release APK。

## ✨ 功能特性

- 🎨 Material You 设计与 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux agent 沙箱环境
- 🔄 多 AI 提供商支持：自定义 API / URL / 模型（兼容所有 OpenAI、Google、Anthropic API）
- 🖼️ 多模态输入（图片、文本文档、PDF、Docx）
- 🖥️ Web 端访问，多平台使用
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量（模型名、时间等）
- 🤳 提供商二维码导出与导入
- 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头与请求体
- 💌 Silly Tavern 角色卡导入
- ⏳ Workspace 后台任务（关闭应用后继续执行）
- 📂 SAF 目录挂载 / 文件导出到手机
- 🧩 Subagent 子代理与 Todo 工具

## 💖 赞助商

|                                         Sponsor                                         | Description                                                                                                                                                                                                                                         |
|:---------------------------------------------------------------------------------------:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | Thanks to <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> for their financial support. We recommend using aihubmix as a one-stop shop for mainstream models worldwide. (OpenAI, Claude, Google Gemini, DeepSeek, Qwen, and hundreds more). |
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI中转" width="50" /><br /><b><a href="https://sui-xiang.com">随想AI中转</a></b> | 感谢<a href="https://sui-xiang.com">随想AI中转</a>对本项目的赞助！随想AI中转 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |
| <img src="docs/sponsors/ztest.png" alt="真测 ztest.ai" width="50" /><br /><b><a href="https://ztest.ai">真测 ztest.ai</a></b> | 感谢<a href="https://ztest.ai">真测 ztest.ai</a>对本项目的赞助！真测 ztest.ai 是一个 AI 中转站模型检测平台，检测结果数据全公开，23 项探针覆盖协议、身份、能力、内容完整性、安全性、性能六大维度，交叉印证识别伪造与降级。作为独立第三方验证平台，实时监测 AI 中转站的模型真实性、响应质量与服务可用性。 |

## 🤝 贡献

本项目使用 [Android Studio](https://developer.android.com/studio) 开发，欢迎提交 PR。

技术栈：

- [Kotlin](https://kotlinlang.org/)（开发语言）
- [Koin](https://insert-koin.io/)（依赖注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好数据存储）
- [Room](https://developer.android.com/training/data-storage/room)（数据库）
- [Coil](https://coil-kt.github.io/coil/)（图片加载）
- [Material You](https://m3.material.io/)（UI 设计）
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)（导航）
- [Okhttp](https://square.github.io/okhttp/)（HTTP 客户端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）

> [!TIP]
> 本分支已移除 Firebase，构建**无需** `google-services.json` 文件，可直接编译。

## 💰 捐赠

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）授权。
