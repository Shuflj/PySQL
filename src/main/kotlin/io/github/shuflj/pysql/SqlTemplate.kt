package io.github.shuflj.pysql

enum class RenderMode {
    LITERAL,
    IN_STRING,
    IN_ESCAPE_STRING,
    IN_IDENTIFIER,
    IN_BACKTICK,
    IN_BRACKET,
    UNSUPPORTED,
    RAW,
}

data class Placeholder(val range: IntRange, val expression: String, val mode: RenderMode)

class SqlTemplate private constructor(private val sql: String, val placeholders: List<Placeholder>) {

    fun render(values: List<String>): String {
        require(values.size == placeholders.size) { "Expected ${placeholders.size} values, got ${values.size}" }
        val out = StringBuilder(sql.length)
        var last = 0
        placeholders.forEachIndexed { i, p ->
            out.append(sql, last, p.range.first).append(values[i])
            last = p.range.last + 1
        }
        return out.append(sql, last, sql.length).toString()
    }

    companion object {
        private val RAW_FILTER = Regex("""\|\s*raw\s*$""")
        private val DOLLAR_TAG = Regex("""\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$""")
        private val Q_CLOSERS = mapOf('[' to ']', '{' to '}', '(' to ')', '<' to '>')

        fun parse(sql: String, dialect: SqlDialect = SqlDialect.STANDARD): SqlTemplate {
            val placeholders = mutableListOf<Placeholder>()
            var close: String? = null
            var mode = RenderMode.LITERAL
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                if (close == null) {
                    when {
                        sql.startsWith("--", i) -> {
                            i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                            continue
                        }
                        sql.startsWith("/*", i) -> {
                            i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                            continue
                        }
                        c == '\'' && (hasPrefix(sql, i, "q") || hasPrefix(sql, i, "nq")) && i + 1 < sql.length -> {
                            val delimiter = sql[i + 1]
                            close = "${Q_CLOSERS[delimiter] ?: delimiter}'"
                            mode = RenderMode.UNSUPPORTED
                            i += 2
                            continue
                        }
                        c == '\'' -> {
                            close = "'"
                            mode = if (hasPrefix(sql, i, "e") || hasPrefix(sql, i, "u&")) {
                                RenderMode.IN_ESCAPE_STRING
                            } else {
                                RenderMode.IN_STRING
                            }
                            i++
                            continue
                        }
                        c == '"' -> {
                            close = "\""
                            mode = RenderMode.IN_IDENTIFIER
                            i++
                            continue
                        }
                        c == '`' -> {
                            close = "`"
                            mode = RenderMode.IN_BACKTICK
                            i++
                            continue
                        }
                        c == '[' && dialect.bracketIdentifiers -> {
                            close = "]"
                            mode = RenderMode.IN_BRACKET
                            i++
                            continue
                        }
                        c == '$' && !isWordChar(sql.getOrNull(i - 1)) -> {
                            val tag = DOLLAR_TAG.matchAt(sql, i)?.value
                            if (tag != null) {
                                close = tag
                                mode = RenderMode.UNSUPPORTED
                                i += tag.length
                                continue
                            }
                        }
                    }
                } else {
                    if (c == '\\' && backslashEscapes(mode, dialect)) {
                        i += 2
                        continue
                    }
                    if (sql.startsWith(close, i)) {
                        if (close.length == 1 && sql.startsWith(close, i + 1)) {
                            i += 2
                            continue
                        }
                        i += close.length
                        close = null
                        continue
                    }
                }
                if (sql.startsWith("{{", i)) {
                    val end = sql.indexOf("}}", i + 2)
                    if (end >= 0) {
                        val body = sql.substring(i + 2, end)
                        val raw = RAW_FILTER.containsMatchIn(body)
                        val expression = (if (raw) body.replace(RAW_FILTER, "") else body).trim()
                        if (expression.isNotEmpty()) {
                            val placeholderMode = when {
                                raw -> RenderMode.RAW
                                close == null -> RenderMode.LITERAL
                                else -> mode
                            }
                            placeholders += Placeholder(i..end + 1, expression, placeholderMode)
                            i = end + 2
                            continue
                        }
                    }
                }
                i++
            }
            return SqlTemplate(sql, placeholders)
        }

        private fun backslashEscapes(mode: RenderMode, dialect: SqlDialect) =
            mode == RenderMode.IN_ESCAPE_STRING ||
                (dialect.backslashEscapes && (mode == RenderMode.IN_STRING || mode == RenderMode.IN_IDENTIFIER))

        private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

        private fun hasPrefix(sql: String, quoteAt: Int, prefix: String): Boolean {
            val start = quoteAt - prefix.length
            return start >= 0 &&
                sql.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true) &&
                !isWordChar(sql.getOrNull(start - 1))
        }
    }
}
