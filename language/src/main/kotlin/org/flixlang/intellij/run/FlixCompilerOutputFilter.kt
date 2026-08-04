package org.flixlang.intellij.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Turns the line numbers in Flix compiler output into links to the source.
 *
 * This is what separates running a task from running it in a terminal. The compiler already says
 * exactly where a problem is; without this the user reads the location and navigates by hand.
 *
 * ## Why it takes two lines to make one link
 *
 * Flix does not print `file:line:col`. It prints a header naming the file, then an excerpt whose
 * lines carry the numbers:
 *
 * ```
 * -- Parse Error [E6629] ------------------------------------------------ Bad.flix
 *
 * >> Expected <expression> before '}'.
 *
 * 3 | }
 *     ^
 *     Here
 * ```
 *
 * So the file comes from one line and the line number from another, and the filter has to remember
 * the header while the excerpt goes by. A [Filter] is created per console, so that state belongs to
 * one run and cannot leak between two.
 *
 * ## Why ANSI is stripped here
 *
 * The compiler colours its output, and whether a console has already decoded those escapes depends
 * on the process handler it was built with. Stripping them makes this answer the same either way,
 * for the price of one regex on lines that mostly do not match.
 *
 * @param workingDirectory what a relative path in a header is relative to -- the compiler prints the
 *   path as it was given, and the task runs in the project directory.
 */
class FlixCompilerOutputFilter(
    private val project: Project,
    private val workingDirectory: Path,
) : Filter {

    /** The file named by the most recent header, if any. */
    private var currentSource: String? = null

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val plain = strip(line)

        sourceIn(plain)?.let {
            currentSource = it
            return null
        }

        val source = currentSource ?: return null
        val (lineNumber, start, end) = lineNumberIn(plain) ?: return null
        val file = LocalFileSystem.getInstance().findFileByNioFile(workingDirectory.resolve(source))
            ?: return null

        // Filter offsets are into the whole console document, not into this line.
        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + start,
            lineStart + end,
            OpenFileHyperlinkInfo(project, file, lineNumber - 1),
        )
    }

    companion object {
        /** `-- Parse Error [E6629] ------ Bad.flix` -- the dashes vary, the shape does not. */
        private val HEADER = Regex("""^--\s.*\[E\d+]\s-+\s(.+?)\s*$""")

        /** `  3 | }` -- the number, and where it sits so it can be the clickable part. */
        private val EXCERPT = Regex("""^(\s*)(\d+)\s\|""")

        private val ANSI = Regex("""\[[0-9;]*m""")

        /** [text] without terminal colour escapes. */
        internal fun strip(text: String): String = ANSI.replace(text, "")

        /** The file a diagnostic header names, or `null` if [line] is not one. */
        internal fun sourceIn(line: String): String? = HEADER.find(line)?.groupValues?.get(1)

        /**
         * The line number an excerpt line carries, with the offsets of the number within the line,
         * or `null` if [line] is not an excerpt.
         */
        internal fun lineNumberIn(line: String): Triple<Int, Int, Int>? {
            val match = EXCERPT.find(line) ?: return null
            val indent = match.groupValues[1].length
            val digits = match.groupValues[2]
            return Triple(digits.toInt(), indent, indent + digits.length)
        }
    }
}
