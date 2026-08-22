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

- **基线**：上游 `master` 2.4.9（`0c52b62b`），已同步上游至 `c167c70e`，持续跟踪上游更新
- 与上游的 commit 合并对账见 [docs/UPSTREAM_SYNC.md](docs/UPSTREAM_SYNC.md)；功能合入、review 与构建验证记录见 [docs/CHANGES.md](docs/CHANGES.md)

> [!WARNING]
> 上游官方提醒：社区 fork 与官方无关，使用前请自行评估风险。

## ✨ 相对上游的改动

### 🗑️ 移除

- Firebase / google-services：构建不再需要 `google-services.json`
- S3 备份（云备份仅保留 WebDAV）
- 模式注入 / 世界书

###  新增

- 🖥️ **Workspace 增强**：持久后台任务（关闭应用后继续执行，**任务完成后自动拉起 LLM 继续，无需手动提醒**）、SAF 目录挂载（`/mnt/<name>`）、导出文件到手机、`backup.zip` 一致快照备份工具
- 💬 **生成中消息队列**：LLM 输出期间可输入补充消息——入队后自动插入对话（工具轮次间隙最快生效），或点打断立即发送
- 🤖 **Subagent 子代理**：数据模型、预置模板、运行器、编辑页与执行轨迹
- ✅ **Todo 工具**与 per-tool 可注入提示词
- 🔵 **悬浮球**：聊天时实时查看待办与工具执行状态（源自 rikkahub-apk fork）
-  **Token-aware** 滚动摘要上下文（阈值支持 `32000` / `32K` / `1M` 数字输入）、模型上下文窗口自动发现
- ⚡ **Shell 实时输出**（实验性，默认关闭）：workspace shell 命令执行中在聊天内实时滚动显示 stdout/stderr，设置 → 偏好 → 常规可开启
-  每日自动构建（nightly prerelease）
- 🏷️ 应用更名为 Rikkahub Next

## 🚀 即将推出

- **Shell 命令实时输出打磨**：当前为实验性开关（见上），后续计划优化流式渲染体验并转正

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
