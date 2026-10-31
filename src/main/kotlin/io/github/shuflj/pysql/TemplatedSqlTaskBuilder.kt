package io.github.shuflj.pysql

import com.intellij.dataspell.jupyter.sql.common.SqlCellConfiguration
import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.executor.cell.JupyterCellTaskBuilder
import com.intellij.jupyter.core.executor.task.JupyterExecutionTask
import com.intellij.jupyter.core.jupyter.editor.JupyterCellLinesProvider
import com.intellij.notebooks.visualization.NotebookIntervalPointer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.notebooks.psi.jupyter.nbformat.JupyterCellType

class TemplatedSqlTaskBuilder : JupyterCellTaskBuilder {
    override suspend fun createTaskIfApplicable(
        project: Project,
        notebookVirtualFile: BackedNotebookVirtualFile,
        cellPointer: NotebookIntervalPointer,
    ): JupyterExecutionTask? {
        val notebook = notebookVirtualFile.notebookOrNull ?: return null
        val configuration: SqlCellConfiguration? = SqlCellConfiguration.getConfiguration(notebook)
        if (configuration?.isEnabled() != true) return null

        val sql = readAction {
            val interval = cellPointer.get() ?: return@readAction null
            val cellType = with(JupyterCellLinesProvider.Util) { interval.jupyterCellType }
            if (cellType != JupyterCellType.SQL) return@readAction null
            interval.getContentText(notebookVirtualFile.file)
        } ?: return null

        val template = SqlTemplate.parse(sql)
        if (template.placeholders.isEmpty() || !NativeSqlTasks.isAvailable()) return null
        return TemplatedSqlTask(notebookVirtualFile, project, cellPointer, template)
    }
}
