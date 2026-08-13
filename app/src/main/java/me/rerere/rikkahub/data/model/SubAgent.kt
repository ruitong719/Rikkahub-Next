package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Subagent（子智能体）定义：主 Agent 可通过工具调用启动的独立 Agent 实例。
 * 全局定义（SettingsStore.subagents），由 Assistant.subagentIds 按需启用。
 */
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    /** 显示名；slug 化后作为工具名的一部分（subagent_<slug>） */
    val name: String = "",
    /** 工具描述：主 Agent 据此决定何时调用 */
    val description: String = "",
    /** 追加到主 Agent system prompt 之后的专属提示 */
    val systemPrompt: String = "",
    /** null = 继承主 Agent 模型 */
    val modelId: Uuid? = null,
    /** 工具名或类别标签（见 plan 功能六 §4），空 = 纯文本专家 */
    val toolAllowlist: Set<String> = emptySet(),
    /** 启用的 skill 名称（共享扩展管理里的 Skills），不走 allowlist */
    val enabledSkills: Set<String> = emptySet(),
    /** 内部循环步数上限（主循环默认 256） */
    val maxSteps: Int = 64,
    /** 超时；超时返回 {status:"timeout"} */
    val timeoutMs: Long = 120_000,
    /** 派发该 subagent 是否需用户审批 */
    val requiresApproval: Boolean = true,
)

/**
 * 内置示例预设：key 不存在时注入，用户保存过（含删光）后以用户数据为准，天然可删除。
 * 固定 id，保证用户复制修改后引用稳定。
 */
val DEFAULT_SUBAGENTS: List<SubAgent> = listOf(
    SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
        name = "Code Reviewer",
        description = "Review code quality, security, and maintainability. Read-only analysis; does not modify files.",
        systemPrompt = """
            You are a senior code reviewer. Focus on correctness, security, performance, and
            maintainability. Point out concrete issues with file paths and line references.
            Do not modify any files; only report findings.
        """.trimIndent(),
        toolAllowlist = setOf("workspace_read", "workspace_shell"),
        requiresApproval = true,
    ),
    SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000a2"),
        name = "Researcher",
        description = "Research questions and gather information from the web and workspace files.",
        systemPrompt = """
            You are a research assistant. Gather up-to-date information from web search and
            workspace files, cross-check sources, and summarize findings clearly with citations.
        """.trimIndent(),
        toolAllowlist = setOf("search", "workspace_read"),
        requiresApproval = false,
    ),
    SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000a3"),
        name = "Data Analyst",
        description = "Run scripts in the workspace to analyze data and produce reports.",
        systemPrompt = """
            You are a data analyst. Inspect data files in the workspace, run scripts when needed,
            and produce clear summaries, tables, and actionable insights.
        """.trimIndent(),
        toolAllowlist = setOf("workspace_read", "workspace_write", "workspace_shell", "workspace_bg"),
        requiresApproval = true,
    ),
)
