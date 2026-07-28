package dev.wstein.flixplugin.debugger

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManager
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest
import org.flixlang.intellij.lang.FlixFileType

/**
 * Teaches IntelliJ's Java debugger to resolve Flix source positions, so one native debug session
 * covers Flix and every other JVM language in the same process (ADR 0002).
 *
 * Ownership is declined three ways, deliberately: [getAcceptedFileTypes] and [isAcceptedFileType]
 * describe it declaratively, and every method throws [NoDataException] for anything not backed by
 * a `.flix` source. That keeps the Java, Kotlin, Scala and Groovy position managers in charge of
 * their own frames -- the guarantee this plugin owes them is non-interference.
 *
 * It never calls `VirtualMachine.setDefaultStratum`. That setting is process-global and would
 * corrupt every other language's position manager in the session.
 */
class FlixPositionManager(private val debugProcess: DebugProcess) : MultiRequestPositionManager {

    override fun getAcceptedFileTypes(): Set<FileType> = setOf(FlixFileType.INSTANCE)

    override fun isAcceptedFileType(fileType: FileType): Boolean = fileType == FlixFileType.INSTANCE

    override fun getSourcePosition(location: Location?): SourcePosition? {
        val jdiLocation = location ?: throw NoDataException.INSTANCE

        // Resolved once and threaded through. Each derivation costs an availableStrata() round-trip
        // and often a sourceNames() one too, and this runs for every frame of every stack -- so
        // deriving it per attribute tripled JDWP traffic on the hot path. It also kept three
        // independent derivations of one value alive, which is the shape of the locationsOfLine bug.
        val stratum = FlixSourceLocations.stratumOf(jdiLocation) ?: throw NoDataException.INSTANCE
        val sourceName = FlixSourceLocations.sourceNameOf(jdiLocation, stratum)
            ?: throw NoDataException.INSTANCE
        val line = FlixSourceLocations.lineNumberOf(jdiLocation, stratum)
            ?: throw NoDataException.INSTANCE
        val sourcePath = FlixSourceLocations.sourcePathOf(jdiLocation, stratum)

        val file = ReadAction.compute<PsiFile?, RuntimeException> {
            FlixSourceFiles.find(debugProcess.project, sourceName, sourcePath)
        } ?: throw NoDataException.INSTANCE

        // SourcePosition is zero-based; JDI line numbers are one-based.
        return ReadAction.compute<SourcePosition, RuntimeException> {
            SourcePosition.createFromLine(file, line - 1)
        }
    }

    override fun getAllClasses(position: SourcePosition): List<ReferenceType> {
        val target = flixTargetOf(position)
        val loaded = debugProcess.virtualMachineProxy.allClasses()
        val matched = loaded.filter { type -> matchesSource(type, target) }
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "getAllClasses(${target.baseName}:${position.line + 1}): " +
                    "${matched.size} of ${loaded.size} loaded classes matched",
            )
        }
        return matched
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        val target = flixTargetOf(position)
        val stratum = FlixSourceLocations.stratumFor(type) ?: throw NoDataException.INSTANCE
        val sourceNames = matchingSourceNames(type, stratum, target)
        if (sourceNames.isEmpty()) {
            if (LOG.isDebugEnabled) {
                LOG.debug("locationsOfLine(${type.name()}): no source name matches ${target.baseName}")
            }
            throw NoDataException.INSTANCE
        }

        // One Flix line commonly compiles into several generated classes and several locations
        // within them -- closures and lambdas each get their own. Returning all of them is what
        // makes a single breakpoint stop wherever that line actually runs. Query with the source
        // name JDI actually reported, rather than [Target.baseName]: a no-SMAP class can expose an
        // absolute SourceFile name (for example `/project/src/Main.flix`). Passing `Main.flix`
        // then silently yields no locations even though the line table contains the requested
        // line.
        val locations = sourceNames.flatMap { sourceName ->
            runCatching {
                type.locationsOfLine(stratum, sourceName, position.line + 1)
            }.getOrElse { emptyList() }
        }.distinct()
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "locationsOfLine(${type.name()}, stratum=$stratum, names=$sourceNames, " +
                    "line=${position.line + 1}): ${locations.size} location(s)",
            )
        }
        return locations
    }

    override fun createPrepareRequest(
        requestor: ClassPrepareRequestor,
        position: SourcePosition,
    ): ClassPrepareRequest? = createPrepareRequests(requestor, position).firstOrNull()

    override fun createPrepareRequests(
        requestor: ClassPrepareRequestor,
        position: SourcePosition,
    ): List<ClassPrepareRequest> {
        // Guarantees the position is Flix before requesting anything, so a breakpoint in another
        // language never produces a class-prepare request owned by this manager.
        flixTargetOf(position)

        // Flix compiles a source file into many classes whose names it chooses -- Def$main,
        // Clo$main$400074 and so on -- so there is no class-name pattern to filter on. Watching
        // every prepare and re-checking the source on arrival is the correct trade: breakpoints set
        // before their class loads still resolve, at the cost of one source-name comparison per
        // class.
        //
        // Both obvious spellings of "every class" are wrong, in opposite directions:
        //
        //   "*"  RequestManagerImpl applies a non-empty pattern with addClassFilter, and JDI
        //        restricts class patterns to an exact name or one that begins or ends with `*`
        //        (`java.*`, `*.Foo`). A bare `*` is not well-formed, so the filter matches nothing,
        //        no prepare event ever arrives, and every breakpoint stays unresolved while looking
        //        correctly placed in the gutter.
        //
        //   ""   installs no filter -- but the request carries SUSPEND_EVENT_THREAD, so the VM now
        //        suspends on *every* class it loads, thousands of them, and the session stops deep
        //        inside JDK code the user never marked.
        //
        // So the request stays unfiltered and the noise is removed with exclusions instead. Those
        // are safe to state positively: user Flix code is never in these namespaces, and the Flix
        // runtime and compiler are not code anyone sets a Flix breakpoint in.
        val request = debugProcess.requestsManager.createClassPrepareRequest(requestor, "")
        if (request == null) {
            LOG.debug("createPrepareRequests(${position.file.name}:${position.line + 1}): refused")
            return emptyList()
        }
        NON_FLIX_NAMESPACES.forEach { request.addClassExclusionFilter(it) }
        LOG.debug(
            "createPrepareRequests(${position.file.name}:${position.line + 1}): " +
                "watching prepares outside ${NON_FLIX_NAMESPACES.joinToString()}",
        )
        return listOf(request)
    }

    /** The breakpoint's file, or [NoDataException] if it is not Flix. */
    private fun flixTargetOf(position: SourcePosition): Target {
        val file = position.file
        if (file.fileType != FlixFileType.INSTANCE) throw NoDataException.INSTANCE
        val virtualFile = file.virtualFile ?: throw NoDataException.INSTANCE
        return Target(virtualFile, virtualFile.name)
    }

    /**
     * Whether [type] was compiled from the breakpoint's file.
     *
     * Resolution is **project-aware**, and has to be. Comparing recorded source paths textually is
     * not enough: a class with no SMAP reports only `Main.flix`, and a suffix comparison against
     * `/project/moduleA/Main.flix` succeeds for that and for every other module's `Main.flix` too --
     * so one breakpoint would bind in all of them. Resolving each recorded source through
     * [FlixSourceFiles], which refuses ambiguous duplicate-base-name matches, makes forward binding
     * exactly as strict as the reverse navigation in [getSourcePosition]. Anything else lets a
     * breakpoint bind to a class whose frames the debugger would then decline to navigate to.
     *
     * The cheap base-name filter comes first because [getAllClasses] runs this over every loaded
     * class, and the authoritative check touches the file index.
     */
    private fun matchesSource(type: ReferenceType, target: Target): Boolean {
        val stratum = FlixSourceLocations.stratumFor(type) ?: return false
        return matchingSourceNames(type, stratum, target).isNotEmpty()
    }

    /**
     * The JDI source names that identify [target] in [type].
     *
     * This is intentionally shared by forward binding and the later `locationsOfLine` query:
     * using one selection rule for the former and a base-name approximation for the latter was
     * enough to verify a breakpoint on some generated classes while leaving it unbound on others.
     */
    private fun matchingSourceNames(type: ReferenceType, stratum: String, target: Target): List<String> {
        val sources = FlixSourceLocations.flixSourcesOf(type, stratum)
            .filter { (name, _) -> FlixSourceLocations.couldReferToBaseName(name, target.baseName) }
        if (sources.isEmpty()) return emptyList()

        return ReadAction.compute<List<String>, RuntimeException> {
            sources.mapNotNull { (name, path) ->
                name.takeIf {
                    FlixSourceFiles.find(debugProcess.project, name, path)?.virtualFile == target.file
                }
            }.distinct()
        }
    }

    private data class Target(val file: VirtualFile, val baseName: String)

    private companion object {
        /**
         * Namespaces excluded from the class-prepare watch.
         *
         * The watch has to be unfiltered to catch Flix's generated class names, which encode the
         * definition rather than the source file (`Def$main`, `Clo$main$400074`), so there is no
         * positive pattern to match on. Excluding the runtime instead keeps the event volume sane:
         * without this the request fires for every JDK class, and because it carries
         * SUSPEND_EVENT_THREAD the session stops inside whatever JDK method happened to be running.
         *
         * Stated as things a Flix breakpoint can never live in -- the JDK, other JVM languages'
         * runtimes, and Flix's own runtime and compiler -- rather than as a guess at what user code
         * looks like. Deliberately *not* the DAP adapter's old `com.*`/`org.*`/`net.*` list, which
         * would also exclude user Java, Kotlin and Scala.
         */
        private val NON_FLIX_NAMESPACES = listOf(
            "java.*",
            "javax.*",
            "jdk.*",
            "sun.*",
            "com.sun.*",
            "scala.*",
            "kotlin.*",
            "dev.flix.runtime.*",
            "ca.uwaterloo.*",
        )

        /**
         * Off by default; enable with `#dev.wstein.flixplugin.debugger` in
         * Help > Diagnostic Tools > Debug Log Settings.
         *
         * A breakpoint that does not bind looks identical whichever step failed -- no class
         * matched, no source name matched, no location at that line -- and the gutter shows the
         * same thing for all three. These messages name which one it was.
         */
        private val LOG = Logger.getInstance(FlixPositionManager::class.java)
    }

}

/** Registered under `com.intellij.debugger.positionManagerFactory`. */
class FlixPositionManagerFactory : PositionManagerFactory() {
    override fun createPositionManager(process: DebugProcess): PositionManager =
        FlixPositionManager(process)
}
