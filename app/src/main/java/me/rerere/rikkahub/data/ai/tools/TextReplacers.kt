package me.rerere.rikkahub.data.ai.tools

/**
 * edit 使用的文本替换器, 按 [WorkspaceEditReplacers] 顺序逐级尝试,
 * 前一级找不到任何匹配时才会降级到下一级更宽松的匹配策略。
 */
interface TextReplacer {
    val name: String

    fun findMatches(content: String, oldText: String, newText: String): List<Match>

    data class Match(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
    )
}

data class ReplaceTextResult(
    val updated: String,
    val replacements: Int,
    val occurrences: Int,
    val strategy: String,
)

val WorkspaceEditReplacers: List<TextReplacer> = listOf(
    ExactReplacer,
    LineTrimmedReplacer,
    BlockAnchorReplacer,
    WhitespaceNormalizedReplacer,
    EscapeNormalizedReplacer,
)

/** 匹配跨度远大于 old_text 时拒绝替换（借鉴 opencode isDisproportionateMatch）：
 * 宽松策略偶尔会把一大段无关内容当成模糊匹配命中，直接替换会大面积毁码。 */
private fun isDisproportionateMatch(content: String, match: TextReplacer.Match, oldText: String): Boolean {
    val span = content.substring(match.start, match.endExclusive)
    val oldLines = oldText.lines().size.coerceAtLeast(1)
    val spanLines = span.lines().size
    if (spanLines >= maxOf(oldLines + 3, oldLines * 2)) return true
    if (oldLines == 1) return false
    val oldLen = oldText.trim().length
    return span.trim().length > maxOf(oldLen + 500, oldLen * 4)
}

/**
 * 逐级尝试 [replacers], 使用第一个产生匹配的替换器。
 * 匹配多处且 [replaceAll] 为 false 时抛出 [IllegalArgumentException], 而不是猜测替换哪一处。
 */
fun replaceText(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean,
    replacers: List<TextReplacer> = WorkspaceEditReplacers,
): ReplaceTextResult {
    require(oldText.isNotEmpty()) { "old_text must not be empty" }
    for (replacer in replacers) {
        val matches = replacer.findMatches(content, oldText, newText)
        if (matches.isEmpty()) continue
        if (!replaceAll) {
            require(matches.size == 1) {
                "old_text matches ${matches.size} locations (strategy: ${replacer.name}); " +
                    "add more surrounding context to make it unique, or set replace_all=true"
            }
        }
        if (replacer !== ExactReplacer) {
            val bad = matches.firstOrNull { isDisproportionateMatch(content, it, oldText) }
            require(bad == null) {
                "Refusing replacement: the fuzzy-matched span is much larger than old_text " +
                    "(strategy: ${replacer.name}). Re-read the file and provide the full exact old_text."
            }
        }
        val applied = if (replaceAll) matches.sortedBy { it.start } else listOf(matches.minBy { it.start })
        val builder = StringBuilder(content.length)
        var cursor = 0
        for (match in applied) {
            builder.append(content, cursor, match.start)
            builder.append(match.replacement)
            cursor = match.endExclusive
        }
        builder.append(content, cursor, content.length)
        return ReplaceTextResult(
            updated = builder.toString(),
            replacements = applied.size,
            occurrences = matches.size,
            strategy = replacer.name,
        )
    }
    throw IllegalArgumentException(
        "old_text was not found, even with whitespace-tolerant matching; " +
            "read the file again and copy old_text exactly from its current content"
    )
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * 第一级: 精确匹配, 非重叠计数, 与 String.replace 语义一致。
 */
object ExactReplacer : TextReplacer {
    override val name: String = "exact"

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val matches = mutableListOf<TextReplacer.Match>()
        var index = content.indexOf(oldText)
        while (index >= 0) {
            matches += TextReplacer.Match(index, index + oldText.length, newText)
            index = content.indexOf(oldText, index + oldText.length)
        }
        return matches
    }
}

/**
 * 行级窗口匹配的公共骨架: 把 content 与 old_text 拆成行, 滑动比较窗口,
 * 命中后以匹配处首行的真实缩进重排 new_text。
 */
abstract class LineWindowReplacer : TextReplacer {

    protected abstract fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean

    /** old_text 全为空白行时禁用宽松匹配, 避免命中任意空白区域 */
    protected open fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.any { it.isNotEmpty() }

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val rawOldLines = oldText.lines()
        // "foo\n" 语义上是一行, 去掉行尾换行产生的空尾行, new_text 同步处理保持对称
        val dropTrailingEmpty = rawOldLines.size > 1 && rawOldLines.last().isEmpty()
        val oldLines = if (dropTrailingEmpty) rawOldLines.dropLast(1) else rawOldLines
        val oldTrimmed = oldLines.map { it.trim() }
        if (!isApplicable(oldTrimmed)) return emptyList()
        val adjustedNewText = if (dropTrailingEmpty) newText.removeOneTrailingNewline() else newText

        val contentLines = splitLinesWithOffsets(content)
        val matches = mutableListOf<TextReplacer.Match>()
        var index = 0
        while (index + oldLines.size <= contentLines.size) {
            val window = contentLines.subList(index, index + oldLines.size)
            if (windowMatches(window.map { it.text.trim() }, oldTrimmed)) {
                val replacement = reindent(
                    text = adjustedNewText,
                    oldIndent = indentOf(oldLines.first()),
                    newIndent = indentOf(window.first().text),
                )
                matches += TextReplacer.Match(window.first().start, window.last().endExclusive, replacement)
                index += oldLines.size
            } else {
                index++
            }
        }
        return matches
    }
}

/**
 * 第二级: 逐行 trim 后比较, 容忍缩进/行尾空白/CRLF 差异。
 */
object LineTrimmedReplacer : LineWindowReplacer() {
    override val name: String = "line_trimmed"

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed == oldTrimmed
}

/**
 * 第三级: old_text 至少 3 行时, 仅用首尾行做锚点匹配, 容忍中间行的细微差异。
 */
object BlockAnchorReplacer : LineWindowReplacer() {
    override val name: String = "block_anchor"

    override fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.size >= 3 && oldTrimmed.first().isNotEmpty() && oldTrimmed.last().isNotEmpty()

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed.first() == oldTrimmed.first() && windowTrimmed.last() == oldTrimmed.last()
}

/**
 * 第四级: 行内空白串归一化后比较(连续空白视为单个空格), 容忍对齐用的多空格/tab 差异。
 * 行首尾空白已由 trim 处理, 这里只处理行中部分。
 */
object WhitespaceNormalizedReplacer : LineWindowReplacer() {
    override val name: String = "whitespace_normalized"

    private fun normalize(line: String): String = line.trim().replace(WHITESPACE_RUN, " ")

    override fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.any { it.isNotEmpty() }

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed.map(::normalize) == oldTrimmed.map(::normalize)
}

/**
 * 第五级: 把 old_text 中的字面转义序列(\n \t \r \" \' \\)还原成真实字符后再精确查找。
 * 模型经常把换行写成字面 "\\n" 而不是真实换行, 这是 exact 匹配失败的常见原因。
 * 仅当还原后的文本确实不同且能在原文中找到时才产生候选。
 */
object EscapeNormalizedReplacer : TextReplacer {
    override val name: String = "escape_normalized"

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val unescaped = unescapeLiteralSequences(oldText)
        if (unescaped.isEmpty() || unescaped == oldText) return emptyList()
        val matches = mutableListOf<TextReplacer.Match>()
        var index = content.indexOf(unescaped)
        while (index >= 0) {
            matches += TextReplacer.Match(index, index + unescaped.length, newText)
            index = content.indexOf(unescaped, index + unescaped.length)
        }
        return matches
    }

    private val ESCAPE_REGEX = Regex("\\\\[ntr'\"\\\\]")

    /** 只还原常见转义, 不做完整字符串解码, 避免误伤包含反斜杠的代码 */
    private fun unescapeLiteralSequences(text: String): String =
        text.replace(ESCAPE_REGEX) { match ->
            when (match.value) {
                "\\n" -> "\n"
                "\\t" -> "\t"
                "\\r" -> "\r"
                "\\\"" -> "\""
                "\\'" -> "'"
                else -> "\\"
            }
        }
}

private class LineWithOffset(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

private fun splitLinesWithOffsets(content: String): List<LineWithOffset> {
    val lines = mutableListOf<LineWithOffset>()
    var start = 0
    for (index in content.indices) {
        if (content[index] == '\n') {
            val end = if (index > start && content[index - 1] == '\r') index - 1 else index
            lines += LineWithOffset(start, end, content.substring(start, end))
            start = index + 1
        }
    }
    lines += LineWithOffset(start, content.length, content.substring(start))
    return lines
}

private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

private fun reindent(text: String, oldIndent: String, newIndent: String): String {
    if (oldIndent == newIndent) return text
    return text.lines().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.startsWith(oldIndent) -> newIndent + line.removePrefix(oldIndent)
            else -> line
        }
    }
}

private fun String.removeOneTrailingNewline(): String = when {
    endsWith("\r\n") -> dropLast(2)
    endsWith("\n") -> dropLast(1)
    else -> this
}
