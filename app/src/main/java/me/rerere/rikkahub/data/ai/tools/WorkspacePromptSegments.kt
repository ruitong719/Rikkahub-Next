package me.rerere.rikkahub.data.ai.tools

/**
 * `<workspace>` 引导提示词的分段键与内置默认文案。
 *
 * 用户可在 工作区详情 -> 提示词 中逐段覆盖；覆盖缺失或空白时回退默认值。
 * IDENTITY 段的 `{name}` 占位符在渲染时替换为工作区名称（默认与覆盖均支持）。
 */
object WorkspacePromptSegment {
    const val IDENTITY = "identity"
    const val FILES_AREA = "files_area"
    const val USAGE_HINT = "usage_hint"
    const val SKILLS = "skills"
    const val UPLOAD = "upload"
    const val AGENT = "agent"
    const val MOUNT = "mount"

    val ALL = listOf(IDENTITY, FILES_AREA, USAGE_HINT, SKILLS, UPLOAD, AGENT, MOUNT)
}

fun defaultWorkspacePromptSegment(key: String): String = when (key) {
    WorkspacePromptSegment.IDENTITY ->
        "You have access to a persistent Linux workspace named \"{name}\", running in a sandboxed proot rootfs environment."
    WorkspacePromptSegment.FILES_AREA ->
        "- The workspace files area is mounted at `/workspace`. Use it as your working directory; " +
            "files written there persist across turns of this conversation.\n" +
            "- All paths passed to workspace tools must be absolute and inside the Rootfs " +
            "(for example `/workspace/notes.md`)."
    WorkspacePromptSegment.USAGE_HINT ->
        "- Prefer `bash` for tasks that standard Unix tools handle well, and prefer `edit` for " +
            "targeted edits over rewriting whole files."
    WorkspacePromptSegment.SKILLS ->
        "- The skills directory is mounted at `/skills`. Each skill is a subdirectory " +
            "`/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) " +
            "plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions."
    WorkspacePromptSegment.UPLOAD ->
        "- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded " +
            "files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. " +
            "If you need to change an uploaded file, copy it into `/workspace` first and edit the copy."
    WorkspacePromptSegment.AGENT ->
        "- The agent instructions directory is mounted at `/agent`. It contains Markdown files " +
            "(e.g. `agent.md`) that define the assistant's behavior; follow them. You may append to " +
            "existing files there, but prefer editing `/workspace` files for your own work."
    WorkspacePromptSegment.MOUNT ->
        "- Phone directories are mounted under `/mnt`: the device's shared storage root is bound " +
            "at `/mnt/storage` (equivalent to `/sdcard`). This is a live bind mount of the real phone " +
            "filesystem, not a synced snapshot: reads always see the current phone state, and writes, " +
            "renames, and deletions take effect immediately on the phone. Note that `Android/data` and " +
            "`Android/obb` are inaccessible due to system restrictions; paths outside shared storage " +
            "(app-private data) are not visible here."
    else -> ""
}

/** 解析分段生效文本：覆盖优先，空白回退默认；`{name}` 替换为工作区名 */
fun resolveWorkspacePromptSegment(
    key: String,
    overrides: Map<String, String>,
    workspaceName: String,
): String = (overrides[key]?.trim()?.takeIf { it.isNotEmpty() } ?: defaultWorkspacePromptSegment(key))
    .replace("{name}", workspaceName)
