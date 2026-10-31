package io.github.shuflj.pysql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.TimeUnit

class SqlEngineTest {

    private val values = listOf(
        "plain", "", "it's", "''", "\\", "\\\\", "\\'", "\\' OR 1=1 -- ", "' OR '1'='1",
        "\"", "\"\"", "\\\"", "`", "``", "]", "]]", "--", "/* x */", "\n\r\t", "Zürich 🚀",
        "'; DROP TABLE canary; --", "\\'; DROP TABLE canary; --", "\"; DROP TABLE canary; --",
        "`; DROP TABLE canary; --", "]; DROP TABLE canary; --", "%_", "{{ nested }}", "\$\$",
    )

    private val identifierValues = values.filter { v ->
        v.isNotBlank() && !v.endsWith(" ") && v.codePoints().allMatch { it <= 0xFFFF }
    }

    private enum class Read { VALUE, LABEL }

    private class Context(
        val name: String,
        val values: List<String>,
        val read: Read,
        val item: (Int) -> String,
        val expected: (String) -> String = { it },
    )

    private fun valueContext(name: String, values: List<String>, item: (String) -> String, expected: (String) -> String = { it }) =
        Context(name, values, Read.VALUE, { i -> "${item("{{ v[$i] }}")} AS c$i" }, expected)

    private fun labelContext(name: String, values: List<String>, item: (String) -> String, expected: (String) -> String = { it }) =
        Context(name, values, Read.LABEL, { i -> "1 AS ${item("{{ v[$i] }}")}" }, expected)

    private fun literal() = valueContext("literal", values, { it })
    private fun singleQuoted() = valueContext("single-quoted", values, { "'<$it>'" }, { "<$it>" })
    private fun doubleQuotedIdentifier() = labelContext("double-quoted identifier", identifierValues, { "\"$it\"" })

    @Test
    fun sqlite() = DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
        verify(
            connection,
            SqlDialect(backslashEscapes = false, bracketIdentifiers = true),
            literal(),
            singleQuoted(),
            doubleQuotedIdentifier(),
            labelContext("backtick identifier", identifierValues, { "`$it`" }),
            labelContext("bracket identifier", identifierValues.filter { ']' !in it }, { "[$it]" }),
        )
        val withBracket = render("SELECT 1 AS [{{ v[0] }}]", listOf("]; DROP TABLE canary; --"), SqlDialect(false, true))
        assertThrows(SQLException::class.java) { connection.createStatement().use { it.execute(withBracket) } }
        assertCanary(connection)
    }

    @Test
    fun duckdb() = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        verify(connection, SqlDialect.STANDARD, literal(), singleQuoted(), doubleQuotedIdentifier())
    }

    @Test
    fun postgres() {
        val url = System.getenv("PYSQL_TEST_POSTGRES_URL")
        assumeTrue(!url.isNullOrBlank(), "PYSQL_TEST_POSTGRES_URL not set")
        DriverManager.getConnection(url).use { connection ->
            verify(
                connection,
                SqlDialect.STANDARD,
                literal(),
                singleQuoted(),
                valueContext("escape string", values, { "E'<$it>'" }, { "<$it>" }),
                doubleQuotedIdentifier(),
            )
        }
    }

    @Test
    fun mysql() {
        val url = System.getenv("PYSQL_TEST_MYSQL_URL")
        assumeTrue(!url.isNullOrBlank(), "PYSQL_TEST_MYSQL_URL not set")
        DriverManager.getConnection(url).use { connection ->
            verify(
                connection,
                SqlDialect.UNKNOWN,
                literal(),
                singleQuoted(),
                valueContext("double-quoted string", values, { "\"<$it>\"" }, { "<$it>" }),
                labelContext("backtick identifier", identifierValues, { "`$it`" }, { it.replace("\\", "\\\\") }),
            )
        }
    }

    private fun verify(connection: Connection, dialect: SqlDialect, vararg contexts: Context) {
        connection.createStatement().use {
            it.execute("DROP TABLE IF EXISTS canary")
            it.execute("CREATE TABLE canary (x INTEGER)")
            it.execute("INSERT INTO canary VALUES (1)")
        }
        for (context in contexts) {
            val sql = render(
                "SELECT " + context.values.indices.joinToString(", ") { context.item(it) },
                context.values,
                dialect,
            )
            val actual = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    check(rs.next()) { "${context.name}: no row for $sql" }
                    context.values.indices.map { i ->
                        when (context.read) {
                            Read.VALUE -> rs.getString(i + 1)
                            Read.LABEL -> rs.metaData.getColumnLabel(i + 1)
                        }
                    }
                }
            }
            assertEquals(context.values.map(context.expected), actual, "${context.name}: $sql")
        }
        assertCanary(connection)
    }

    private fun assertCanary(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM canary").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1), "canary table was modified")
            }
        }
    }

    private fun render(sql: String, values: List<String>, dialect: SqlDialect): String {
        val python = listOf("python3", "python").firstOrNull { exe ->
            runCatching { ProcessBuilder(exe, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        }
        assumeTrue(python != null, "no Python interpreter on PATH")
        val encoded = values.joinToString(", ") { "'" + Base64.getEncoder().encodeToString(it.toByteArray()) + "'" }
        val template = SqlTemplate.parse(sql, dialect)
        val code = "import base64\nv = [base64.b64decode(x).decode('utf-8') for x in [$encoded]]\n" +
            KernelScript.build(template.placeholders, dialect)
        val process = ProcessBuilder(python, "-I", "-c", code).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        val resolution = KernelScript.parse(output, template.placeholders)
        check(resolution is Resolution.Values) { "rendering failed: $resolution\n$output" }
        return template.render(resolution.values)
    }
}
