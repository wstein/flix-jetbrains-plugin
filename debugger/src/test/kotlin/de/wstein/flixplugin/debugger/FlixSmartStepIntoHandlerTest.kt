package de.wstein.flixplugin.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ParsingTestCase
import de.wstein.flixplugin.FlixDebugCalls
import org.flixlang.intellij.lang.FlixParserDefinition

class FlixSmartStepIntoHandlerTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""
    override fun skipSpaces(): Boolean = false

    fun testNestedCallsRemainSeparateTargetsInSourceOrder() {
        val file = parse("def main(): Unit \\ IO = println(one())")
        val position = position(file)
        val calls = listOf(
            call("println", 1, 26, 1, 40, "Def\$println"),
            call("one", 1, 34, 1, 39, "Def\$one"),
        )

        val targets = FlixSmartStepIntoHandler().targetsAt(position, calls)

        assertEquals(listOf("println()", "one()"), targets.map { it.presentation })
        assertEquals(listOf("println", "one"), targets.map { requireNotNull(it.highlightElement).text })
    }

    private fun parse(code: String): PsiFile = createPsiFile("Main", code).also { ensureParsed(it) }

    private fun position(file: PsiFile): SourcePosition = object : SourcePosition() {
        override fun getFile(): PsiFile = file
        override fun getElementAt(): PsiElement? = file.findElementAt(0)
        override fun getLine(): Int = 0
        override fun getOffset(): Int = 0
        override fun openEditor(requestFocus: Boolean): Editor? = null
        override fun navigate(requestFocus: Boolean) = Unit
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }

    private fun call(
        label: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        className: String,
    ) = FlixDebugCalls.Call(
        "Main.flix", startLine, startCol, endLine, endCol, label, className, "staticApply",
    )
}
