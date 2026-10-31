package io.github.shuflj.pysql

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.executor.cell.JupyterCellTaskBuilder
import com.intellij.jupyter.core.executor.task.JupyterExecutionTask
import com.intellij.notebooks.visualization.NotebookIntervalPointer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.lang.reflect.Method

object NativeSqlTasks {
    private const val BUILDER_CLASS = "com.intellij.dataspell.jupyter.sql.backend.run.JupyterSqlTaskBuilder"

    private val EP_NAME = ExtensionPointName<JupyterCellTaskBuilder>(
        "com.intellij.jupyter.core.jupyter.connections.execution.jupyterCellTaskBuilder"
    )

    private val LOG = logger<NativeSqlTasks>()

    private fun nativeBuilder(): Pair<JupyterCellTaskBuilder, Method>? {
        val builder = EP_NAME.extensionList.firstOrNull { it.javaClass.isOrExtends(BUILDER_CLASS) } ?: return null
        val method = generateSequence<Class<*>>(builder.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "createSqlTask" && it.parameterCount == 4 }
            ?: return null
        return runCatching { method.isAccessible = true; builder to method }
            .onFailure { LOG.warn("Cannot access $BUILDER_CLASS.createSqlTask", it) }
            .getOrNull()
    }

    fun isAvailable(): Boolean = nativeBuilder() != null

    fun create(
        file: BackedNotebookVirtualFile,
        project: Project,
        cellPointer: NotebookIntervalPointer,
        sql: String,
    ): JupyterExecutionTask {
        val (builder, method) = nativeBuilder() ?: error("PyCharm's SQL cell task builder is not available")
        return method.invoke(builder, file, project, cellPointer, sql) as JupyterExecutionTask
    }

    private fun Class<*>.isOrExtends(name: String): Boolean =
        generateSequence(this) { it.superclass }.any { it.name == name }
}
