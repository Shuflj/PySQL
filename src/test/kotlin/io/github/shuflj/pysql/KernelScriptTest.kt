package io.github.shuflj.pysql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class KernelScriptTest {

    private fun python(): String? = listOf("python3", "python").firstOrNull { exe ->
        runCatching { ProcessBuilder(exe, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun runInPython(setup: String, sql: String): Pair<Resolution, String> {
        val exe = python()
        assumeTrue(exe != null, "no Python interpreter on PATH")
        val template = SqlTemplate.parse(sql)
        val code = setup.trimIndent() + "\n" + KernelScript.build(template.placeholders) +
            "\nassert '_sqlcellvars_run' not in globals()\n"
        val process = ProcessBuilder(exe, "-I", "-c", code).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), output)
        val resolution = KernelScript.parse(output, template.placeholders)
        val rendered = (resolution as? Resolution.Values)?.let { template.render(it.values) } ?: ""
        return resolution to rendered
    }

    @Test
    fun `scalars become SQL literals`() {
        val (_, sql) = runInPython(
            """
            import datetime, decimal
            n = 42; f = 1.5; s = "O'Brien"; none = None; yes = True
            d = datetime.date(2026, 10, 31); ts = datetime.datetime(2026, 10, 31, 12, 30); dec = decimal.Decimal("9.99")
            """,
            "SELECT {{n}}, {{f}}, {{s}}, {{none}}, {{yes}}, {{d}}, {{ts}}, {{dec}}",
        )
        assertEquals("SELECT 42, 1.5, 'O''Brien', NULL, TRUE, '2026-10-31', '2026-10-31 12:30:00', 9.99", sql)
    }

    @Test
    fun `collections become IN lists and VALUES rows`() {
        val (_, sql) = runInPython(
            """
            ids = [1, 2, 3]
            app_values = [("web", 1), ("ios", 2)]
            """,
            "WITH app_ids(app_name, app) AS (VALUES {{app_values}}) SELECT * FROM t WHERE id IN {{ids}}",
        )
        assertEquals("WITH app_ids(app_name, app) AS (VALUES ('web', 1), ('ios', 2)) SELECT * FROM t WHERE id IN (1, 2, 3)", sql)
    }

    @Test
    fun `string literal and raw modes`() {
        val (_, sql) = runInPython(
            """
            name = "it's"
            table = "sales.orders"
            """,
            "SELECT * FROM {{ table | raw }} WHERE note LIKE '%{{name}}%'",
        )
        assertEquals("SELECT * FROM sales.orders WHERE note LIKE '%it''s%'", sql)
    }

    @Test
    fun `quoted identifier mode`() {
        val (_, sql) = runInPython("col = 'odd\"name'", "SELECT \"{{ col }}\" FROM t")
        assertEquals("SELECT \"odd\"\"name\" FROM t", sql)
    }

    @Test
    fun `malformed output lines are ignored`() {
        val placeholders = SqlTemplate.parse("SELECT {{ a }}").placeholders
        val stdout = "__sqlcellvars__:0:ok\n__sqlcellvars__:x:ok:NDI=\n__sqlcellvars__:7:ok:NDI=\n__sqlcellvars__:0:ok:%%%\n"
        val error = assertInstanceOf(Resolution.Error::class.java, KernelScript.parse(stdout, placeholders))
        assertTrue(error.message.contains("no value for {{ a }}"), error.message)
        assertEquals(Resolution.Values(listOf("42")), KernelScript.parse(stdout + "__sqlcellvars__:0:ok:NDI=\n", placeholders))
    }

    @Test
    fun `expressions and unicode`() {
        val (_, sql) = runInPython("cfg = {'city': 'Zürich'}", "SELECT {{ cfg['city'].upper() }}")
        assertEquals("SELECT 'ZÜRICH'", sql)
    }

    @Test
    fun `undefined name is reported with its placeholder`() {
        val (resolution, _) = runInPython("", "SELECT {{ missing }}")
        val error = assertInstanceOf(Resolution.Error::class.java, resolution)
        assertTrue(error.message.startsWith("NameError"), error.message)
        assertTrue(error.message.contains("{{ missing }}"), error.message)
    }

    @Test
    fun `unsupported values are rejected`() {
        val (resolution, _) = runInPython("x = object(); empty = []", "SELECT {{x}}, {{empty}}")
        val error = assertInstanceOf(Resolution.Error::class.java, resolution)
        assertTrue(error.message.startsWith("TypeError"), error.message)
    }

    @Test
    fun `error helper raises with the message`() {
        val exe = python()
        assumeTrue(exe != null, "no Python interpreter on PATH")
        val process = ProcessBuilder(exe, "-I", "-c", KernelScript.raiseError("bad 'thing'\nline two"))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(output.contains("SqlCellVariableError: bad 'thing'\nline two"), output)
    }

    @Test
    fun `IPython shows the error without a traceback`() {
        val ipython = System.getenv("IPYTHON")
        assumeTrue(!ipython.isNullOrBlank(), "IPYTHON not set")
        val process = ProcessBuilder(ipython, "--colors=nocolor", "-c", KernelScript.raiseError("NameError: x\n  in {{ x }}"))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().replace(Regex("\u001b\\[[0-9;]*m"), "")
        process.waitFor(60, TimeUnit.SECONDS)
        assertTrue(output.contains("SqlCellVariableError: NameError: x\n  in {{ x }}"), output)
        assertTrue(!output.contains("Traceback") && !output.contains("b64decode"), output)
    }
}
