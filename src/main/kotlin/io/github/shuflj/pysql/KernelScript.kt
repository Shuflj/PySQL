package io.github.shuflj.pysql

import java.util.Base64

sealed interface Resolution {
    data class Values(val values: List<String>) : Resolution
    data class Error(val message: String) : Resolution
}

object KernelScript {
    private const val MARKER = "__sqlcellvars__:"

    fun build(placeholders: List<Placeholder>, dialect: SqlDialect): String {
        val items = placeholders.joinToString(", ") { "(${b64Literal(it.expression)}, '${it.mode.name}')" }
        return PYTHON_TEMPLATE
            .replace("__ITEMS__", items)
            .replace("__BACKSLASH__", if (dialect.backslashEscapes) "True" else "False")
            .replace("__MARKER__", MARKER)
    }

    fun raiseError(message: String): String =
        "raise type('SqlCellVariableError', (Exception,), {'_render_traceback_': " +
            "lambda self: ['\\x1b[1;31mSqlCellVariableError\\x1b[0m: ' + str(self)]})" +
            "(__import__('base64').b64decode(${b64Literal(message)}).decode('utf-8')) from None"

    fun parse(stdout: String, placeholders: List<Placeholder>): Resolution {
        val values = arrayOfNulls<String>(placeholders.size)
        for (line in stdout.lineSequence()) {
            if (!line.startsWith(MARKER)) continue
            val parts = line.removePrefix(MARKER).split(':', limit = 3)
            if (parts.size != 3) continue
            val i = parts[0].toIntOrNull()?.takeIf { it in placeholders.indices } ?: continue
            val text = runCatching { String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8) }.getOrNull() ?: continue
            if (parts[1] == "err") return Resolution.Error("$text\n  in {{ ${placeholders[i].expression} }}")
            values[i] = text
        }
        val missing = values.indexOfFirst { it == null }
        if (missing >= 0) {
            return Resolution.Error("The kernel returned no value for {{ ${placeholders[missing].expression} }}")
        }
        return Resolution.Values(values.map { it!! })
    }

    private fun b64Literal(text: String) =
        "'" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "'"

    private val PYTHON_TEMPLATE = """
def _sqlcellvars_run(items, backslash):
    import base64, datetime, decimal

    def escape(text, quote_char, double_backslash=backslash):
        if double_backslash:
            text = text.replace("\\", "\\\\")
        return text.replace(quote_char, quote_char + quote_char)

    def quote(text):
        return "'" + escape(text, "'") + "'"

    def scalar(v):
        if v is None:
            return "NULL"
        if isinstance(v, bool):
            return "TRUE" if v else "FALSE"
        if hasattr(v, "item") and not isinstance(v, (str, bytes)) and getattr(v, "ndim", None) == 0:
            return scalar(v.item())
        if isinstance(v, int):
            return str(v)
        if isinstance(v, float):
            if v != v or v in (float("inf"), float("-inf")):
                raise ValueError("cannot use %r in SQL" % v)
            return repr(v)
        if isinstance(v, decimal.Decimal):
            return str(v)
        if isinstance(v, datetime.datetime):
            return quote(v.isoformat(sep=" "))
        if isinstance(v, (datetime.date, datetime.time)):
            return quote(v.isoformat())
        if isinstance(v, str):
            return quote(v)
        raise TypeError("cannot convert %s to a SQL literal; use {{ ... | raw }}" % type(v).__name__)

    def literal(v):
        if hasattr(v, "tolist") and not isinstance(v, (str, bytes)) and getattr(v, "ndim", 1) > 0:
            v = v.tolist()
        if isinstance(v, (list, tuple, set, frozenset)):
            seq = sorted(v) if isinstance(v, (set, frozenset)) else list(v)
            if not seq:
                raise ValueError("cannot use an empty collection in SQL")
            if all(isinstance(row, (list, tuple)) for row in seq):
                return ", ".join("(" + ", ".join(scalar(x) for x in row) + ")" for row in seq)
            return "(" + ", ".join(scalar(x) for x in seq) + ")"
        return scalar(v)

    render = {
        "LITERAL": literal,
        "IN_STRING": lambda v: escape(str(v), "'"),
        "IN_ESCAPE_STRING": lambda v: escape(str(v), "'", True),
        "IN_IDENTIFIER": lambda v: escape(str(v), '"'),
        "IN_BACKTICK": lambda v: escape(str(v), "`"),
        "IN_BRACKET": lambda v: str(v).replace("]", "]]"),
        "RAW": str,
    }
    for i, (expr, mode) in enumerate(items):
        try:
            value = eval(base64.b64decode(expr).decode("utf-8"), globals())
            status, text = "ok", render[mode](value)
        except Exception as e:
            status, text = "err", "%s: %s" % (type(e).__name__, e)
        print("__MARKER__%d:%s:%s" % (i, status, base64.b64encode(text.encode("utf-8")).decode("ascii")))

try:
    _sqlcellvars_run([__ITEMS__], __BACKSLASH__)
finally:
    del _sqlcellvars_run
""".trimStart()
}
