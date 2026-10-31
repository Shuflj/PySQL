package io.github.shuflj.pysql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceholderPatternProviderTest {

    private val pattern = Regex(PlaceholderPatternProvider.PATTERN)

    private fun assertSameRanges(sql: String) {
        assertEquals(
            SqlTemplate.parse(sql).placeholders.map { it.range },
            pattern.findAll(sql).map { it.range }.toList(),
            sql,
        )
    }

    @Test
    fun `matches the placeholders the template substitutes`() {
        assertSameRanges("SELECT * FROM t WHERE id = {{user_id}} AND price > {{ min_price }}")
        assertSameRanges("SELECT * FROM {{ table | raw }} WHERE note LIKE '%{{ name }}%'")
        assertSameRanges("SELECT {{ {'a': 1}['a'] }}, {{ x }}")
        assertSameRanges("WITH apps(app_name, app) AS (VALUES {{ app_values }}) SELECT * FROM apps")
    }

    @Test
    fun `group 1 is the expression`() {
        assertEquals(" user_id ", pattern.find("WHERE id = {{ user_id }}")!!.groupValues[1])
    }
}
