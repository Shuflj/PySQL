package io.github.shuflj.pysql

import com.intellij.database.Dbms
import com.intellij.database.psi.DbPsiFacade
import com.intellij.dataspell.jupyter.sql.common.metadata.isDataFrameSQLCell
import com.intellij.dataspell.jupyter.sql.common.metadata.sqlMetadataOrDefault
import com.intellij.jupyter.core.jupyter.nbformat.JupyterNotebook
import com.intellij.openapi.project.Project

object Dialects {
    private val STANDARD = setOf(
        Dbms.POSTGRES, Dbms.GREENPLUM, Dbms.COCKROACH, Dbms.SQLITE, Dbms.ORACLE,
        Dbms.MSSQL, Dbms.MSSQL_LOCALDB, Dbms.AZURE, Dbms.SYNAPSE, Dbms.SYBASE,
        Dbms.HSQL, Dbms.DERBY, Dbms.EXASOL, Dbms.VERTICA,
    )
    private val STANDARD_NAMES = setOf("H2", "DB2", "DB2_LUW", "DB2_IS", "DB2_ZOS")
    private val BRACKETS = setOf(Dbms.MSSQL, Dbms.MSSQL_LOCALDB, Dbms.AZURE, Dbms.SYNAPSE, Dbms.SYBASE, Dbms.SQLITE)

    fun of(dbms: Dbms?): SqlDialect = when {
        dbms == null -> SqlDialect.UNKNOWN
        dbms in STANDARD || dbms.name in STANDARD_NAMES ->
            SqlDialect(backslashEscapes = false, bracketIdentifiers = dbms in BRACKETS)
        else -> SqlDialect.UNKNOWN
    }

    fun ofCell(project: Project, notebook: JupyterNotebook, cellIndex: Int): SqlDialect {
        val cell = notebook.getCell(cellIndex)
        if (cell.isDataFrameSQLCell(notebook)) return SqlDialect.STANDARD
        val name = cell.sqlMetadataOrDefault.dataSourceName
        return of(DbPsiFacade.getInstance(project).dataSources.firstOrNull { it.name == name }?.dbms)
    }
}
