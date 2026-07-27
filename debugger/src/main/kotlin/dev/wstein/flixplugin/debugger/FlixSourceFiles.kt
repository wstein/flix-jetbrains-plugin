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
 * Lookup is by file name and then disambiguated by path, rather than by name alone. Flix projects
 * routinely contain several files with the same base name across modules, and picking the first
 * match would silently open the wrong file at a line that may not even exist there -- a failure
 * that looks like a debugger bug rather than a lookup bug.
 */
internal object FlixSourceFiles {

    /**
     * The Flix file matching [sourceName], preferring one whose path agrees with [sourcePath].
     *
     * Returns `null` rather than guessing when several candidates share a name and none matches the
     * recorded path: navigating to an arbitrary same-named file is worse than not navigating, since
     * it presents wrong code as if it were the frame's source.
     */
    fun find(project: Project, sourceName: String, sourcePath: String?): PsiFile? {
        val candidates = FilenameIndex
            .getVirtualFilesByName(sourceName, GlobalSearchScope.allScope(project))
            .filter { it.fileType == FlixFileType.INSTANCE }

        val chosen = when {
            candidates.isEmpty() -> return null
            candidates.size == 1 -> candidates.first()
            sourcePath == null -> return null
            else -> candidates.firstOrNull { FlixSourceLocations.sameSourcePath(it.path, sourcePath) }
                ?: return null
        }

        return PsiManager.getInstance(project).findFile(chosen)
    }
}
