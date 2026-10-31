package io.github.shuflj.pysql

enum class RenderMode {
    LITERAL,
    IN_STRING,
    IN_IDENTIFIER,
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

        fun parse(sql: String): SqlTemplate {
            val placeholders = mutableListOf<Placeholder>()
            var quote: Char? = null
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    quote != null && c == quote -> {
                        if (sql.getOrNull(i + 1) == quote) i++ else quote = null
                    }
                    quote == null && (c == '\'' || c == '"') -> quote = c
                    quote == null && sql.startsWith("--", i) -> {
                        i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                        continue
                    }
                    quote == null && sql.startsWith("/*", i) -> {
                        i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                        continue
                    }
                    sql.startsWith("{{", i) -> {
                        val end = sql.indexOf("}}", i + 2)
                        if (end >= 0) {
                            val body = sql.substring(i + 2, end)
                            val raw = RAW_FILTER.containsMatchIn(body)
                            val expression = (if (raw) body.replace(RAW_FILTER, "") else body).trim()
                            if (expression.isNotEmpty()) {
                                val mode = when {
                                    raw -> RenderMode.RAW
                                    quote == '\'' -> RenderMode.IN_STRING
                                    quote == '"' -> RenderMode.IN_IDENTIFIER
                                    else -> RenderMode.LITERAL
                                }
                                placeholders += Placeholder(i..end + 1, expression, mode)
                                i = end + 2
                                continue
                            }
                        }
                    }
                }
                i++
            }
            return SqlTemplate(sql, placeholders)
        }
    }
}
