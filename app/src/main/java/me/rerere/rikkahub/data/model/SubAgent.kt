package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
    /**
     * 暴露的工具类别（精确白名单，见 SubAgentLogic.CATEGORY_TOOLS）。
     * skill 能力由 [enabledSkills] 单独表达；General 的类别由主模型调用时指定，此字段忽略。
     */
    @Serializable(with = SubAgentToolCategorySetSerializer::class)
    val toolAllowlist: Set<SubAgentToolCategory> = emptySet(),
    /** 启用的 skill 名称（共享扩展管理里的 Skills），非空即暴露对应 use_skill 工具 */
    val enabledSkills: Set<String> = emptySet(),
    /** 内部循环步数上限（主循环默认 256） */
    val maxSteps: Int = 64,
    /** 超时；超时返回 {status:"timeout"} */
    val timeoutMs: Long = 120_000,
    /** 派发该 subagent 是否需用户审批 */
    val requiresApproval: Boolean = true,
)

/**
 * Subagent 工具暴露类别。运行模型统一走 Settings.subagentModelId，
 * 不再按 subagent 单独配置。
 */
@Serializable
enum class SubAgentToolCategory {
    /** 只读：workspace_read_file / get_time_info */
    READ,

    /** 写入：workspace_write_file / workspace_edit_file / todo_* */
    WRITE,

    /** 执行：workspace_shell */
    SHELL,
}

/**
 * 内置自由 subagent（General）：常驻、不可删除、每个助手可单独启用/停用。
 * 主模型可在同一轮并行发派多个实例（每对话同时运行数有上限），调用时通过
 * tools 参数指定该实例可用的工具类别，label 参数用于在监看面板区分实例。
 */
val GENERAL_SUBAGENT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000a0")

fun isGeneralSubagent(id: Uuid): Boolean = id == GENERAL_SUBAGENT_ID

/** General 的出厂定义；设置里缺失时由加载逻辑自动补回 */
fun defaultGeneralSubagent(): SubAgent = SubAgent(
    id = GENERAL_SUBAGENT_ID,
    name = "General",
    description = "Free-form general-purpose subagent for ad-hoc tasks. " +
        "Launch multiple instances in parallel (up to the per-conversation limit); " +
        "specify which tool categories each instance may use via the 'tools' parameter, " +
        "and pass a short 'label' to tell concurrent instances apart.",
    systemPrompt = "",
    // 类别由主模型每次调用时指定；skill 池仍来自 enabledSkills
    toolAllowlist = emptySet(),
    requiresApproval = true,
)

/**
 * 兼容旧版字符串标签的类别集合序列化器：
 * 旧 workspace_read/search/mcp/local 等映射到新枚举（无法对应的丢弃），写出统一为新枚举名。
 */
object SubAgentToolCategorySetSerializer : KSerializer<Set<SubAgentToolCategory>> {
    private val delegate = SetSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Set<SubAgentToolCategory> =
        delegate.deserialize(decoder).mapNotNull(::mapLegacyCategory).toSet()

    override fun serialize(encoder: Encoder, value: Set<SubAgentToolCategory>) {
        delegate.serialize(encoder, value.map { it.name }.toSet())
    }

    private fun mapLegacyCategory(raw: String): SubAgentToolCategory? = when (raw.uppercase()) {
        "READ", "WORKSPACE_READ" -> SubAgentToolCategory.READ
        "WRITE", "WORKSPACE_WRITE", "WORKSPACE_OTHER" -> SubAgentToolCategory.WRITE
        "SHELL", "WORKSPACE_SHELL" -> SubAgentToolCategory.SHELL
        else -> null
    }
}

/**
 * 内置示例预设：key 不存在时注入，用户保存过（含删光）后以用户数据为准，天然可删除（General 除外）。
 * 固定 id，保证用户复制修改后引用稳定。
 */
val DEFAULT_SUBAGENTS: List<SubAgent> = listOf(
    defaultGeneralSubagent(),
    SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
        name = "Code Reviewer",
        description = "Review code quality, security, and maintainability by reading files and running read-only commands.",
        systemPrompt = """
            You are a senior code reviewer. Focus on correctness, security, performance, and
            maintainability. Point out concrete issues with file paths and line references.
            Do not modify any files; only report findings.
        """.trimIndent(),
        toolAllowlist = setOf(SubAgentToolCategory.READ, SubAgentToolCategory.SHELL),
        requiresApproval = true,
    ),
    SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000a2"),
        name = "Researcher",
        description = "Research questions using workspace files and any search skills configured for this subagent.",
        systemPrompt = """
            You are a research assistant. Gather information from workspace files and enabled
            skills, cross-check sources, and summarize findings clearly with citations.
        """.trimIndent(),
        toolAllowlist = setOf(SubAgentToolCategory.READ),
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
        toolAllowlist = setOf(
            SubAgentToolCategory.READ,
            SubAgentToolCategory.WRITE,
            SubAgentToolCategory.SHELL,
        ),
        requiresApproval = true,
    ),
)
