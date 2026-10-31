package io.github.shuflj.pysql

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement

class PlaceholderErrorFilter : HighlightErrorFilter() {

    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean =
        !(isSql(element) && isAtPlaceholder(element.containingFile.text, element.textRange.startOffset))

    companion object {
        private val PLACEHOLDER = Regex(PlaceholderPatternProvider.PATTERN)

        private fun isSql(element: PsiErrorElement) =
            generateSequence(element.language) { it.baseLanguage }.any { it.id == "SQL" }

        private fun isAtPlaceholder(text: String, offset: Int): Boolean {
            var i = offset
            while (i < text.length && text[i].isWhitespace()) i++
            if (text.startsWith("{{", i)) return true
            return PLACEHOLDER.findAll(text).any { offset in it.range }
        }
    }
}
