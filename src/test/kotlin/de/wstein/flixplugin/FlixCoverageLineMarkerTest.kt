package de.wstein.flixplugin

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.flixlang.intellij.lang.FlixFileType

/** The assembled editor path from a compiler coverage report to exactly one gutter mark per line. */
class FlixCoverageLineMarkerTest : BasePlatformTestCase() {

    fun testCoveredUncoveredAndPartialLinesHaveDistinctGutterMeaning() {
        val file = myFixture.configureByText(
            FlixFileType.INSTANCE,
            """
            def main(): Int32 =
                let covered = 1;
                let uncovered = 2;
                covered + uncovered
            """.trimIndent(),
        )
        val path = file.virtualFile.path.replace("\\", "\\\\").replace("\"", "\\\"")
        val provider = FlixCoverageLineMarkerProvider()
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }

        coverage(path, partial = false)
        val complete = leaves.mapNotNull { provider.getLineMarkerInfo(it)?.lineMarkerTooltip }
        assertEquals(listOf("Coverage: covered (3 hits)", "Coverage: not covered"), complete)

        coverage(path, partial = true)
        val partial = leaves.mapNotNull { provider.getLineMarkerInfo(it)?.lineMarkerTooltip }
        assertEquals(
            listOf("Coverage: partial run (3 hits recorded)", "Coverage: partial run (0 hits recorded)"),
            partial,
        )
    }

    private fun coverage(path: String, partial: Boolean) {
        project.getService(FlixCoverageService::class.java).accept(
            """{"formatVersion":1,"partial":$partial,"files":[{"path":"$path","lines":[{"line":2,"covered":true,"hitCount":3},{"line":3,"covered":false,"hitCount":0}]}]}""",
            partial,
        )
    }
}
