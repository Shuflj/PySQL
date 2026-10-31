package io.github.shuflj.pysql

import com.intellij.database.Dbms
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DialectsTest : BasePlatformTestCase() {

    fun testStandardDatabasesKeepBackslashes() {
        listOf(Dbms.POSTGRES, Dbms.ORACLE, Dbms.MSSQL, Dbms.SQLITE, Dbms.COCKROACH, Dbms.VERTICA).forEach {
            assertFalse(it.name, Dialects.of(it).backslashEscapes)
        }
    }

    fun testBackslashDatabasesAndUnknownEscapeBackslashes() {
        listOf(Dbms.MYSQL, Dbms.MARIA, Dbms.MEMSQL, Dbms.CLICKHOUSE, Dbms.BIGQUERY, Dbms.SPARK, Dbms.DATABRICKS,
            Dbms.HIVE, Dbms.SNOWFLAKE, Dbms.REDSHIFT, Dbms.UNKNOWN).forEach {
            assertTrue(it.name, Dialects.of(it).backslashEscapes)
        }
        assertEquals(SqlDialect.UNKNOWN, Dialects.of(null))
    }

    fun testBracketIdentifiers() {
        listOf(Dbms.MSSQL, Dbms.AZURE, Dbms.SYBASE, Dbms.SQLITE).forEach { assertTrue(it.name, Dialects.of(it).bracketIdentifiers) }
        listOf(Dbms.POSTGRES, Dbms.MYSQL, Dbms.ORACLE).forEach { assertFalse(it.name, Dialects.of(it).bracketIdentifiers) }
    }
}
