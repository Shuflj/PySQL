package io.github.shuflj.pysql

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.executor.cell.JupyterCellTask
import com.intellij.jupyter.core.executor.kernel.JupyterKernelCellTask
import com.intellij.jupyter.core.executor.kernel.JupyterKernelSilentTask
import com.intellij.jupyter.core.executor.task.JupyterExecutionTask
import com.intellij.jupyter.core.jupyter.connections.execution.JupyterTaskExecutorResult
import com.intellij.jupyter.core.jupyter.nbformat.outputs.JupyterStreamOutput
import com.intellij.notebooks.visualization.NotebookIntervalPointer
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

class TemplatedSqlTask(
    file: BackedNotebookVirtualFile,
    project: Project,
    override val cellPointer: NotebookIntervalPointer,
    private val template: SqlTemplate,
    private val dialect: SqlDialect,
) : JupyterExecutionTask(file, project), JupyterCellTask {

    override suspend fun doExecuteTask(scope: CoroutineScope): JupyterTaskExecutorResult {
        template.placeholders.firstOrNull { it.mode == RenderMode.UNSUPPORTED }?.let {
            return showError(
                scope,
                "{{ ${it.expression} }} is inside a dollar-quoted or q-quoted string, where values can't be " +
                    "escaped safely. Move it outside that string, or use {{ ${it.expression} | raw }} with a trusted value.",
            )
        }
        val lookup = JupyterKernelSilentTask(KernelScript.build(template.placeholders, dialect), notebookVirtualFile, project)
        val lookupResult = lookup.doExecuteTask(scope)
        if (lookupResult is JupyterTaskExecutorResult.Aborted) return lookupResult
        val stdout = lookup.output.outputs
            .filterIsInstance<JupyterStreamOutput>()
            .filter { it.name == "stdout" }
            .joinToString("") { it.text.joinToString("") }

        return when (val resolution = KernelScript.parse(stdout, template.placeholders)) {
            is Resolution.Values -> {
                val sql = template.render(resolution.values)
                val task = try {
                    NativeSqlTasks.create(notebookVirtualFile, project, cellPointer, sql)
                } catch (e: Exception) {
                    return showError(scope, "Could not start PyCharm's SQL cell task: ${e.cause ?: e}")
                }
                task.doExecuteTask(scope)
            }
            is Resolution.Error -> {
                val message = if (lookupResult is JupyterTaskExecutorResult.Failed && stdout.isEmpty()) {
                    "Could not read variables from the kernel: ${lookupResult.reason}"
                } else {
                    resolution.message
                }
                showError(scope, message)
            }
        }
    }

    private suspend fun showError(scope: CoroutineScope, message: String): JupyterTaskExecutorResult =
        JupyterKernelCellTask(cellPointer, notebookVirtualFile, project, KernelScript.raiseError(message))
            .doExecuteTask(scope)
}
