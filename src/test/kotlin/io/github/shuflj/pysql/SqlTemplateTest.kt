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
