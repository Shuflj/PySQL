package io.github.shuflj.pysql

data class SqlDialect(val backslashEscapes: Boolean, val bracketIdentifiers: Boolean) {
    companion object {
        val STANDARD = SqlDialect(backslashEscapes = false, bracketIdentifiers = false)
        val UNKNOWN = SqlDialect(backslashEscapes = true, bracketIdentifiers = false)
    }
}
