package io.github.shuflj.pysql

import com.intellij.database.settings.DatabaseParameterPatternProvider
import com.intellij.database.settings.UserPatterns.ParameterPattern

class PlaceholderPatternProvider : DatabaseParameterPatternProvider {
    override fun getPatterns(): Array<ParameterPattern> =
        arrayOf(ParameterPattern(PATTERN, "", EXAMPLE))

    companion object {
        const val PATTERN = """\{\{(.+?)\}\}"""
        private const val EXAMPLE = "{{name}}"
    }
}
