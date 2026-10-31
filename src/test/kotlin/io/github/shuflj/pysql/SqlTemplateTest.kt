package io.github.shuflj.pysql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlTemplateTest {

    private fun parsed(sql: String) = SqlTemplate.parse(sql).placeholders.map { it.expression to it.mode }

    @Test
    fun `finds placeholders and trims expressions`() {
        assertEquals(
            listOf("user_id" to RenderMode.LITERAL, "limits['price']" to RenderMode.LITERAL),
            parsed("SELECT * FROM t WHERE id = {{user_id}} AND price > {{ limits['price'] }}"),
        )
    }

    @Test
    fun `raw filter`() {
        assertEquals(listOf("schema + '.orders'" to RenderMode.RAW), parsed("SELECT * FROM {{ schema + '.orders' | raw }}"))
    }

    @Test
    fun `bitwise or is not mistaken for a filter`() {
        assertEquals(listOf("a | b" to RenderMode.LITERAL), parsed("SELECT {{ a | b }}"))
    }

    @Test
    fun `placeholder inside a string literal`() {
        assertEquals(
            listOf("name" to RenderMode.IN_STRING, "x" to RenderMode.LITERAL),
            parsed("SELECT 'it''s {{name}}' AS a, {{x}} AS b"),
        )
    }

    @Test
    fun `placeholder inside a quoted identifier`() {
        assertEquals(
            listOf("col" to RenderMode.IN_IDENTIFIER, "x" to RenderMode.LITERAL),
            parsed("SELECT \"{{ col }}\", \"it's\" AS a, {{ x }}"),
        )
    }

    @Test
    fun `backtick identifiers`() {
        assertEquals(listOf("col" to RenderMode.IN_BACKTICK, "x" to RenderMode.LITERAL), parsed("SELECT `{{ col }}`, `it's`, {{ x }}"))
    }

    @Test
    fun `escape strings`() {
        assertEquals(
            listOf("a" to RenderMode.IN_ESCAPE_STRING, "b" to RenderMode.IN_ESCAPE_STRING, "c" to RenderMode.IN_STRING),
            parsed("SELECT E'{{ a }}', U&'{{ b }}', name'{{ c }}'"),
        )
    }

    @Test
    fun `dollar and q quoted strings are unsupported`() {
        assertEquals(
            listOf("a" to RenderMode.UNSUPPORTED, "b" to RenderMode.UNSUPPORTED, "c" to RenderMode.UNSUPPORTED, "d" to RenderMode.LITERAL),
            parsed("SELECT \$\${{ a }}\$\$, \$fn\$ {{ b }} \$fn\$, q'[{{ c }}]', {{ d }}"),
        )
    }

    @Test
    fun `dollar signs that are not quotes`() {
        assertEquals(
            listOf("a" to RenderMode.LITERAL, "b" to RenderMode.LITERAL),
            parsed("SELECT \$1, my\$col\$x, {{ a }} WHERE x = \$2 AND y = {{ b }}"),
        )
    }

    @Test
    fun `brackets depend on the dialect`() {
        val brackets = SqlDialect(backslashEscapes = false, bracketIdentifiers = true)
        assertEquals(listOf(RenderMode.IN_BRACKET), SqlTemplate.parse("SELECT [{{ c }}]", brackets).placeholders.map { it.mode })
        assertEquals(listOf(RenderMode.LITERAL), SqlTemplate.parse("SELECT ARRAY[{{ c }}]").placeholders.map { it.mode })
    }

    @Test
    fun `backslash escapes keep a string open only in backslash dialects`() {
        val sql = "SELECT 'it\\'s {{ x }}'"
        assertEquals(listOf(RenderMode.IN_STRING), SqlTemplate.parse(sql, SqlDialect.UNKNOWN).placeholders.map { it.mode })
        assertEquals(listOf(RenderMode.LITERAL), SqlTemplate.parse(sql, SqlDialect.STANDARD).placeholders.map { it.mode })
    }

    @Test
    fun `nested braces end at the first closing pair`() {
        assertEquals(listOf("f({'a': {'b': 1" to RenderMode.LITERAL), parsed("SELECT {{ f({'a': {'b': 1}}) }}"))
    }

    @Test
    fun `malformed placeholders`() {
        assertEquals(emptyList<Pair<String, RenderMode>>(), parsed("SELECT {{ | raw }}, {{  }}, { x }, {x}}"))
        assertEquals(listOf("{x" to RenderMode.LITERAL), parsed("SELECT {{{x}}}"))
    }

    @Test
    fun `comments are ignored`() {
        assertEquals(
            listOf("kept" to RenderMode.LITERAL),
            parsed("-- {{ skipped }}\nSELECT /* {{ also_skipped }} */ {{ kept }}"),
        )
    }

    @Test
    fun `quote inside a comment does not open a string`() {
        assertEquals(listOf("x" to RenderMode.LITERAL), parsed("-- don't\nSELECT {{x}}"))
    }

    @Test
    fun `empty and unclosed braces are left alone`() {
        assertEquals(emptyList<Pair<String, RenderMode>>(), parsed("SELECT '{{}}', {{ unclosed"))
    }

    @Test
    fun `render replaces placeholders in order`() {
        val template = SqlTemplate.parse("SELECT {{a}}, '{{b}}', {{c|raw}};")
        assertEquals("SELECT 1, 'x''y', t;", template.render(listOf("1", "x''y", "t")))
    }
}
