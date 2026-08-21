<div align="center">
  <img src="docs/icon.png" alt="应用图标" width="100" />
  <h1>Rikkahub Next</h1>
  <p>原生 Android LLM 聊天客户端，支持在多个 AI 提供商之间自由切换 🤖💬</p>
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择" width="450" />
</div>

## 🔗 与上游的关系

本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub)（[AGPL-3.0](LICENSE)）的 **Fork**：

- **基线**：上游 `master` 2.4.9（`0c52b62b`），已同步上游至 `6b37912f`（2.4.10 后），持续跟踪上游更新
- 逐功能合入、review 与编译验证记录见 [CHANGES.md](CHANGES.md)

> [!WARNING]
> 上游官方提醒：社区 fork 与官方无关，使用前请自行评估风险。

## ✨ 相对上游的改动

### 🗑️ 移除

- Firebase / google-services：构建不再需要 `google-services.json`
- S3 备份（云备份仅保留 WebDAV）
- 模式注入 / 世界书

###  新增

- 🖥️ **Workspace 增强**：持久后台任务（关闭应用后继续执行）、SAF 目录挂载（`/mnt/<name>`）、导出文件到手机、`backup.zip` 一致快照备份工具
- 🤖 **Subagent 子代理**：数据模型、预置模板、运行器、编辑页与执行轨迹
- ✅ **Todo 工具**与 per-tool 可注入提示词
- 🔵 **悬浮球**：聊天时实时查看待办与工具执行状态（源自 rikkahub-apk fork）
-  **Token-aware** 滚动摘要上下文（阈值支持 `32000` / `32K` / `1M` 数字输入）、模型上下文窗口自动发现
-  每日自动构建（nightly prerelease）
- 🏷️ 应用更名为 Rikkahub Next

## 🚀 即将推出

- **Shell 命令实时输出**：将阻塞式命令执行改造为流式输出，聊天内实时滚动显示 stdout/stderr（当前为：执行中可点开查看运行状态，结束后一次性展示完整输出）

## 🛠️ 构建

本分支已移除 Firebase，可直接编译：

```bash
./gradlew :app:assembleRelease
```

## 💗 捐赠

- [Patreon](https://patreon.com/rikkahub)
- [爱发电](https://afdian.com/a/reovo)

##  许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）授权。
