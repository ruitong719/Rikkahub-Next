# 上游同步对账（Upstream Sync）

记录本 fork 与上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 每个 commit 的合并情况。
功能合入过程、review 细节与构建验证见 [CHANGES.md](CHANGES.md)。

## 当前状态

- **基线**：merge-base `0c52b62b`（上游 2.4.9，v1.00 迭代时整体 merge）
- **已同步至**：`986b9c39`（2026-08-23）—— 上游 `master` 全部提交处理完毕，无待办
- **对账方法**：`git cherry HEAD upstream/master`（patch-id 等价判定）+ fork 历史逐条核对；
  cherry-pick 均保留原作者署名

## 全量对账表（`0c52b62b..c167c70e`）

| # | 上游 commit | 主题 | 处理 | fork 落点 |
|---|---|---|---|---|
| 1 | `d1e8effc` | style(ui): 移除推理等级选择器底部刻度 | **跳过** | fork `a47fab90` 方向相反（显示全部 7 档标签并修复空标签），保留 fork 实现 |
| 2 | `de888df2` | fix(asr): 修复 DashScope 语音识别无文本输出 | 已合入 | `b9aaaf6f` |
| 3 | `97df86ec` | fix(workspace): 修复 SAF 文件存在性判断 | 已合入 | `96f70a83` |
| 4 | `bca21d4d` | fix: 修复 .agc 文件支持 | 已合入 | `c94e97ad` |
| 5 | `c88822d6` | feat: 支持豆包搜索 | 已合入 | `3ac6896d` |
| 6 | `82758c36` | fix(chat): 启用英文句首自动大写 | 已合入 | `98c4ae85` |
| 7 | `dca7f01c` | feat: 正则支持排序 | 已合入 | `ae21cb95` |
| 8 | `693c2ce5` | chore: bump to 2.4.10 | 已合入 | `da3ca8b1`（fork 为 versionCode 178 / 2.4.10） |
| 9 | `3b4b80a4` | fix: 修复混淆破坏 auth/jwt 的问题 | 已合入 | `bf9bf81e` |
| 10 | `85402745` | fix(thinking): 忽略正文中内联 think 标签 | 已合入 | `efdbe8d2`，零冲突；附 `ThinkTagTransformerTest` 8 用例 |
| 11 | `adf333ec` | feat(assistant): 上下文条数改数字输入 | **不直接合入** | 字段已被滚动摘要功能整体移除，上游改的是不存在的 UI；交互模式已适配到 Token 阈值输入框 → `d6629e20` |
| 12 | `6b37912f` | docs: 移除 claude.md | 跳过 | fork 已在 `15525bea` 删除根目录 CLAUDE.md，空操作 |
| 13 | `f167a855` | chore: 适配 deepseek-v4-flash-vision-exp 能力 | 已合入 | `00234b94`，零冲突 |
| 14 | `8b3a1f84` | feat: 适配小米 MiMo 思考参数（#1751） | 已合入 | `3a52630c` |
| 15 | `91b81fef` | chore: 更新模型图标（gemma/kimi/qwen） | 已合入 | `6e3993dd`，零冲突 |
| 16 | `c167c70e` | feat: ModelRegistry 支持注册模型上下文长度 | 已合入 | `a882ce60` |
| 17 | `7a93c92a` | feat(backup): 本地备份支持选择内容并调整为首页 tab | 已合入（适配） | `a8ed4ee7` |
| 18 | `f557cef5` | feat(backup): 本地备份导入前增加覆盖确认 | 已合入 | `ab127800` |
| 19 | `54b3ba79` | fix: 修复更新检查频繁调用的问题 | 已合入（适配） | `9a57d7b9` |
| 20 | `02a0c81c` | chore: 更新依赖（huge-icons 1.4 / haze beta01） | 已合入 | `9e9afd94` |
| 21 | `986b9c39` | feat: 新增网络配置页 支持配置 user agent 和 代理 | 已合入 | `f55e67ea` |
| 22 | `3509406b` | fix: 代理测试消息改用 LocalResources | 已合入 | `0d621367` |
| 23 | `b270766f` | chore: bump to 2.4.11 | 已合入（语义） | fork 自行 bump：179 / 2.4.11 |

## #17–#21 批次说明（2026-08-23）

- **#17 备份项选择**：fork 无 S3（已删），tab 排列为 本地/WebDAV/提醒；上游新增的
  `localBackupItems` VM 状态移植到 fork 的 `BackupScope` 类型体系
  （`DATABASE`/`ATTACHMENTS`），替换原先写死的 `BackupScope.selectableEntries`
- **#19 更新检查去频**：上游把 `checkUpdate()` 冷流改为 AppScope 内 `Lazily`
  共享 StateFlow；fork 额外有自定义更新地址设置项，故共享流按 `updateUrl`
  `distinctUntilChanged + flatMapLatest` 组装——地址变化自动重新检查，
  其余场景全进程只发一次请求
- **#21 网络配置页**：`NetworkSetting`（userAgent/proxyUrl/proxyUsername/proxyPassword）
  持久化为独立 DataStore key；代理支持 HTTP/SOCKS5 与鉴权，代理设置变化时
  `connectionPool.evictAll()`；UA 注入在共享 OkHttpClient 拦截器，留空回退
  `RikkaHub-Android/<version>`

## 特殊处理说明

### #1 `d1e8effc` — 跳过

上游删除了推理等级选择器底部刻度；fork `a47fab90` 反而利用该区域显示全部 7 个档位标签
（并修复了 MEDIUM/HIGH/XHIGH 显示为空的问题）。两者方向相反，保留 fork 实现。

⚠️ 后续上游再改动 `ReasoningPicker.kt` 时 cherry-pick 会冲突，需手动解冲突。

### #11 `adf333ec` — 不直接合入

上游把"上下文最大条数"滑条改为数字输入；fork 的滚动摘要功能已将 `contextMessageLimit`
字段整体移除（改为 token 阈值），上游改的是 fork 中不存在的 UI。其交互模式
（数字输入 + 失焦校验 + 过小自动重置弹窗）已适配到 fork 的 Token 阈值输入框（`d6629e20`，
支持 `32000` / `32K` / `1.5M` 写法）。若上游后续在该功能上继续迭代，需重新评估而非直接 pick。

### #13–#16 批次（2026-08-21）

按时间顺序逐个 cherry-pick：

- `8b3a1f84` 是唯一自动合并的文件：fork 的 moonshot K2.6 `thinking.keep` 逻辑导致上下文偏移；
  合并后 MiMo 块落在 bigmodel 与 moonshot 分支之间，K2.6 逻辑经人工核对完好
- `c167c70e` 依赖 `f167a855` 先行（要给 DEEPSEEK_V4_FLASH_VISION_EXP 补 `contextLength(1.m)`），
  单独应用会 patch 失败，必须保持顺序
- fork 的"模型上下文窗口自动发现"是运行时 API 发现（`contextWindowTokensOrNull`），
  与注册表新增的静态 `MODEL_CONTEXT_LENGTH` 互补，无重复实现

## 下次同步流程

```bash
# 1. 更新上游引用（upstream = GitHub；local-upstream = 本地 clone，沙箱环境用后者）
git fetch upstream          # 或 git fetch local-upstream

# 2. 对账：'-' 表示已有等价提交，'+' 为待处理
git cherry HEAD upstream/master

# 3. 预检 + 按时间顺序逐个 cherry-pick（注意提交间依赖，如 c167c70e ← f167a855）
git show <sha> | git apply --check -
git cherry-pick <sha>

# 4. 更新本文档对账表与 docs/CHANGES.md 记录
```

## 注意事项

- fork 的 versionCode 自 178 起自行维护，上游 bump 提交只对齐版本语义，不照抄
- 沙箱环境无 Android SDK，cherry-pick 后的验证以 `git apply --check`、
  逐文件人工核对与 JVM 可跑的单测为准，Gradle 构建回归在真机构建时补做
