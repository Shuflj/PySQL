package io.github.shuflj.pysql

import com.intellij.database.dialects.sqlite.sql.SqliteDialect
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PlaceholderErrorFilterTest : BasePlatformTestCase() {

    private fun parseErrors(sql: String): List<PsiErrorElement> {
        val file = PsiFileFactory.getInstance(project).createFileFromText("cell.sql", SqliteDialect.INSTANCE, sql)
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).toList()
    }

    private fun shownErrors(sql: String): List<String> {
        val filter = PlaceholderErrorFilter()
        return parseErrors(sql).filter { filter.shouldHighlightErrorElement(it) }.map { it.errorDescription }
    }

    fun testPlaceholdersInValuePositionsParseAsParameters() {
        listOf(
            "SELECT * FROM orders WHERE user_id = {{user_id}} AND price > {{ min_price }}",
            "SELECT * FROM orders WHERE product IN {{ products }}",
            "SELECT * FROM {{ table | raw }} WHERE id = 1",
            "SELECT * FROM orders WHERE note LIKE '%{{ name }}%'",
            "SELECT * FROM orders LIMIT {{ n }}",
        ).forEach { assertEquals(it, emptyList<PsiErrorElement>(), parseErrors(it)) }
    }

    fun testValuesPlaceholderErrorIsHidden() {
        listOf(
            "WITH apps(app_name, app) AS (VALUES {{ app_values }}) SELECT * FROM apps",
            "INSERT INTO t (a, b) VALUES {{ rows }}",
        ).forEach { sql ->
            assertFalse("the parser should still flag $sql", parseErrors(sql).isEmpty())
            assertEquals(sql, emptyList<String>(), shownErrors(sql))
        }
    }

    fun testOtherErrorsAreStillShown() {
        assertFalse(shownErrors("SELECT * FROM orders WHERE AND {{ x }}").isEmpty())
        assertFalse(shownErrors("INSERT INTO t (a, b) VALUES (1, 2").isEmpty())
        assertFalse(shownErrors("SELECT * FROM WHERE id = {{ x }}").isEmpty())
    }
}
