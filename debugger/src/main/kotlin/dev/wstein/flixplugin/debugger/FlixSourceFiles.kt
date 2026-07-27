package dev.wstein.flixplugin.debugger

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.flixlang.intellij.lang.FlixFileType

/**
 * Resolves the `.flix` file a JDI location came from.
 *
 * The selection rule is separated from the index lookup in [choose] so it can be tested without a
 * project: the interesting cases are duplicate base names and path-valued source attributes, and
 * both are decided entirely by the rule.
 */
internal object FlixSourceFiles {

    /**
     * The Flix file matching [sourceName], disambiguated by [sourcePath] when several share a base
     * name. Returns `null` rather than guessing.
     */
    fun find(project: Project, sourceName: String, sourcePath: String?): PsiFile? {
        val candidates = FilenameIndex
            .getVirtualFilesByName(baseNameOf(sourceName), GlobalSearchScope.allScope(project))
            .filter { it.fileType == FlixFileType.INSTANCE }

        val chosen = choose(candidates.map { it.path }, sourceName, sourcePath) ?: return null
        val file = candidates.first { it.path == chosen }
        return PsiManager.getInstance(project).findFile(file)
    }

    /**
     * Picks the candidate path that [sourceName]/[sourcePath] identify, or `null` when that cannot
     * be decided.
     *
     * Refusing an ambiguous match is deliberate. Flix projects routinely contain several
     * `Main.flix` across modules, and binding to an arbitrary one presents unrelated code as the
     * frame's source -- which reads as the debugger having stopped somewhere it did not, and is
     * harder to diagnose than simply failing to navigate.
     */
    fun choose(candidatePaths: List<String>, sourceName: String, sourcePath: String?): String? {
        if (candidatePaths.isEmpty()) return null
        if (candidatePaths.size == 1) return candidatePaths.single()

        // Whichever of the two source attributes actually carries directories can disambiguate.
        // Both are candidates because a SMAP `*F` section records whatever path the compiler wrote,
        // so the "name" may be path-valued while the "path" may not be present at all.
        val qualifier = listOfNotNull(sourcePath, sourceName).firstOrNull { it.hasDirectories() }
            ?: return null

        return candidatePaths.singleOrNull { FlixSourceLocations.sameSourcePath(it, qualifier) }
    }

    /**
     * The base name of a source attribute.
     *
     * `FilenameIndex` keys on base names alone. JDI specifies `sourceName()` as a file name, but a
     * SMAP file section records whatever the compiler put there and `sourcePath()` is a path by
     * definition, so either can arrive as `src/Main.flix` or as an absolute path. Passing that
     * through returns no candidates at all, which presents as "the debugger cannot find my source"
     * rather than as a lookup bug.
     */
    fun baseNameOf(name: String): String = name.replace('\\', '/').substringAfterLast('/')

    private fun String.hasDirectories(): Boolean =
        replace('\\', '/').trimEnd('/').contains('/')
}
