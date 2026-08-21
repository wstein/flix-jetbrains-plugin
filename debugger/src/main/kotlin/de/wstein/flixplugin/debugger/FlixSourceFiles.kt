package de.wstein.flixplugin.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.flixlang.intellij.lang.FlixFileType

/**
 * Resolves the `.flix` file a JDI location came from.
 *
 * The selection rules are separated from the lookups -- [absolutePathOf] and [choose] -- so they
 * can be tested without a project: the interesting cases are absolute source attributes, duplicate
 * base names and path-valued names, and all three are decided entirely by those rules.
 */
internal object FlixSourceFiles {

    /**
     * The Flix file matching [sourceName], disambiguated by [sourcePath] when several share a base
     * name. Returns `null` rather than guessing.
     */
    fun find(project: Project, sourceName: String, sourcePath: String?): PsiFile? {
        val file = byAbsolutePath(sourceName, sourcePath)
            ?: byIndex(project, sourceName, sourcePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }

    /**
     * The file an absolute source attribute names, read straight from the VFS.
     *
     * Tried before the index because the index is not always available, and a miss here is not
     * recoverable. A class-prepare event fires **once per class per VM**: [FlixPositionManager]
     * asks whether the prepared class holds the breakpoint's line, and a class judged "not compiled
     * from this file" is dropped and never offered again. So a lookup that transiently answers
     * "no" does not delay a breakpoint, it disables it for the rest of the session.
     *
     * `FilenameIndex` answers nothing while the project is reindexing, and the Flix compiler
     * guarantees that will happen: it writes its class output *inside* the project, so every run
     * triggers a VFS refresh and a re-index. A debug session that raced it lost its breakpoints
     * permanently, which presented as "breakpoints work on the first run and not on any after it".
     *
     * An absolute path needs no index and admits no ambiguity -- it identifies one file -- so this
     * removes the race rather than narrowing it. Flix records exactly that for project sources:
     * `SourceFile: "/Users/…/Hello.flix"`.
     *
     * [LocalFileSystem.findFileByPath] is deliberately not the refreshing variant. Refreshing
     * touches the disk, and this runs on the debugger manager thread, which the UI waits on. The
     * file is already in the VFS whenever it is one the user could have set a breakpoint in.
     */
    private fun byAbsolutePath(sourceName: String, sourcePath: String?): VirtualFile? {
        val path = absolutePathOf(sourceName, sourcePath) ?: return null
        return LocalFileSystem.getInstance().findFileByPath(path)
            ?.takeIf { it.fileType == FlixFileType.INSTANCE }
    }

    /**
     * The file a bare source name identifies, resolved through the project index.
     *
     * Reached only when neither attribute is absolute, which for a project source it always is.
     * What remains are library sources -- `Prelude.flix` and friends, recorded by name because they
     * live inside the compiler jar. Those resolve to nothing whatever the index says, so the
     * residual dependence on it costs nothing that was ever available.
     */
    private fun byIndex(project: Project, sourceName: String, sourcePath: String?): VirtualFile? {
        val candidates = FilenameIndex
            .getVirtualFilesByName(baseNameOf(sourceName), GlobalSearchScope.allScope(project))
            .filter { it.fileType == FlixFileType.INSTANCE }

        val chosen = choose(candidates.map { it.path }, sourceName, sourcePath) ?: return null
        return candidates.first { it.path == chosen }
    }

    /**
     * The absolute source attribute to resolve by, or `null` when neither is absolute.
     *
     * [sourcePath] is preferred for the same reason [choose] prefers it: it is a path by
     * definition, while the name carries directories only when the compiler happened to write them.
     */
    fun absolutePathOf(sourceName: String, sourcePath: String?): String? =
        listOfNotNull(sourcePath, sourceName).firstOrNull { it.isAbsolutePath() }

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

    /**
     * Whether this source attribute names a location on disk rather than a file within a project.
     *
     * Windows drive letters are recognised as well as POSIX roots: the compiler writes whatever
     * path it resolved, and a plugin that only understood one of them would fall back to the index
     * on the other platform -- reintroducing exactly the race [byAbsolutePath] exists to remove,
     * on the platform nobody tested.
     */
    private fun String.isAbsolutePath(): Boolean {
        val normalised = replace('\\', '/')
        return normalised.startsWith("/") || WINDOWS_ROOT.containsMatchIn(normalised)
    }

    /** A drive-letter root, for example `C:/`. */
    private val WINDOWS_ROOT = Regex("^[A-Za-z]:/")
}
