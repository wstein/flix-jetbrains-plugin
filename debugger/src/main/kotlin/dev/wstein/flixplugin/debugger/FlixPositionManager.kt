package dev.wstein.flixplugin.debugger

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManager
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.engine.SuspendContext
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

    /**
     * Resolved Flix origins per loaded class, for the lifetime of one suspension.
     *
     * Deciding whether a class is Flix costs two JDWP round trips and a file-index lookup, and the
     * answer was previously derived for every loaded class, twice per candidate, once per
     * breakpoint. See [FlixSourceCache] for why it is cleared on resume rather than on a
     * redefinition event.
     */
    private val sources = FlixSourceCache(debugProcess.project)

    init {
        debugProcess.addDebugProcessListener(object : DebugProcessListener {
            override fun resumed(suspendContext: SuspendContext?) = sources.clear()
            override fun processDetached(process: DebugProcess, closedByUser: Boolean) = sources.clear()
        })
    }

    /**
     * Declares the file types this manager answers for, which is what keeps
     * `CompoundPositionManager` from offering it a `.java`, `.kt` or `.scala` position at all.
     *
     * Only [isAcceptedFileType] is overridden. The interface also carries `getAcceptedFileTypes`,
     * whose default returns `null` meaning "every type" -- but it is deprecated, and overriding both
     * says the same thing twice while failing the Plugin Verifier.
     */
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

        val file = ReadAction.computeBlocking<PsiFile?, RuntimeException> {
            FlixSourceFiles.find(debugProcess.project, sourceName, sourcePath)
        } ?: throw NoDataException.INSTANCE

        // SourcePosition is zero-based; JDI line numbers are one-based.
        return ReadAction.computeBlocking<SourcePosition, RuntimeException> {
            SourcePosition.createFromLine(file, line - 1)
        }
    }

    /**
     * The already-loaded classes that can host a breakpoint at [position].
     *
     * Classes are required to hold the **line**, not merely to come from the same file, for the
     * reason spelled out on [FlixLineOnly]: `Breakpoint.createOrWaitPrepare` calls
     * `createRequestForPreparedClass` on everything returned here, and each class without the line
     * answers *"no executable code"* — which sticks if it lands before the class that does have it.
     * One `.flix` file compiles to many classes, each covering a different part of it, so file-level
     * matching returns mostly classes that cannot host the breakpoint.
     *
     * The cheap file test runs first and the JDI line query only for what survives it, so the extra
     * accuracy costs a query per candidate rather than per loaded class.
     */
    override fun getAllClasses(position: SourcePosition): List<ReferenceType> {
        val target = flixTargetOf(position)
        val loaded = debugProcess.virtualMachineProxy.allClasses()
        val matched = loaded.filter { type ->
            matchesSource(type, target) && locationsOfLine(type, position).isNotEmpty()
        }
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "getAllClasses(${target.baseName}:${position.line + 1}): " +
                    "${matched.size} of ${loaded.size} loaded classes hold that line",
            )
        }
        return matched
    }

    /**
     * The locations in [type] that implement [position].
     *
     * ## Why a non-Flix class returns empty rather than [NoDataException]
     *
     * The two are not interchangeable, and treating them as such planted breakpoints in unrelated
     * library code. `CompoundPositionManager` stops at the **first manager that returns without
     * throwing**, and treats [NoDataException] as "ask the next one":
     *
     * ```java
     * for (pm : myPositionManagers)
     *   if (acceptsFileType(pm, fileType))
     *     try { result = pm.locationsOfLine(type, position); break; }
     *     catch (NoDataException) { /* next */ }
     * ```
     *
     * The next one is the platform's `PositionManagerImpl`, and it answers unconditionally:
     *
     * ```java
     * try { return DebuggerUtilsAsync.locationsOfLineSync(type, "Java", null, position.getLine() + 1); }
     * catch (AbsentInformationException e) { return Collections.emptyList(); }
     * ```
     *
     * It never inspects the file type -- and it never declines, because it does not override
     * `getAcceptedFileTypes()`, which defaults to `null`, which `isAcceptedFileType` reads as
     * "every type". So delegating a `.flix` position asks "which locations in this class are at
     * line N?" of a class that has nothing to do with Flix. Any class with code at line N answers,
     * and `LineBreakpoint.createRequestForPreparedClass` plants a real request there. A breakpoint
     * on `Main.flix:52` then stops in whatever library class happened to load with a line 52.
     *
     * A `.flix` position can only be resolved by this manager -- nothing else understands Flix
     * sources -- so "this class was not compiled from that file" is a final answer, not an
     * abstention. Returning it as one ends the chain.
     *
     * The asymmetry with [getSourcePosition] is deliberate: that one is keyed on a *location*, and
     * a non-Flix location genuinely belongs to another manager, so it must keep delegating.
     */
    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        val target = flixTargetOf(position)
        val declared = sources.sourcesOf(type)
        val stratum = declared.stratum
            ?: return noLocations(type, target, "declares no Flix source at all")
        val sourceNames = declared.namesFor(target.file)
        if (sourceNames.isEmpty()) {
            // Distinguished from the case above because the two are fixed in different places and
            // look identical in the log otherwise. This one means the class *is* Flix and names a
            // source, but none of its sources resolved to the breakpoint's file -- so it is a
            // resolution question (project index, or a VirtualFile that no longer matches), not a
            // question about the class.
            return noLocations(
                type,
                target,
                "declares ${declared.describeSources()} in stratum $stratum, " +
                    "none of which resolved to ${target.file.path}",
            )
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
        val target = flixTargetOf(position)

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
        //
        // What a name pattern would have done, the requestor does instead: [FlixSourcesOnly] drops
        // every prepared class that was not compiled from this breakpoint's file, so the breakpoint
        // itself only ever sees its own classes. This is the platform's own idiom for a position
        // whose classes cannot be named up front -- `PositionManagerImpl.createPrepareRequests`
        // wraps the requestor the same way for anonymous classes -- and `RequestManagerImpl`
        // supports it explicitly: `callbackOnPrepareClasses` registers the request against the
        // original requestor, and `deleteRequest` handles a differing `REQUESTOR` property, so the
        // request is still torn down with the breakpoint.
        val request = debugProcess.requestsManager.createClassPrepareRequest(
            FlixLineOnly(requestor, position),
            "",
        )
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

    /**
     * Passes a class-prepare event on only when the prepared class actually contains [position]'s
     * line.
     *
     * The watch cannot be narrowed by name, so it sees every class the VM loads. Handing those
     * straight to the breakpoint is wrong twice over: it asks the position manager chain to resolve
     * a Flix line against unrelated classes, and `LineBreakpoint.createRequestForPreparedClass`
     * marks the breakpoint invalid -- *"no executable code at line N in <class>"* -- for every one
     * that has no such line.
     *
     * ## Why the test is the line and not the file
     *
     * Filtering on the source file alone is not enough, and the way it fails is a race rather than
     * an outright error. One Flix source compiles to many classes -- `Main.flix` produces 27 -- and
     * each covers only part of the file. Line 102 of that file exists in **2** of the 27. Forwarding
     * all 27 means 25 of them tell the breakpoint it has no executable code.
     *
     * Whether that sticks depends on arrival order, because `RequestManagerImpl.setInvalid` records
     * the complaint only while the breakpoint has not yet bound anywhere:
     *
     * ```java
     * public void setInvalid(Requestor requestor, String message) {
     *    if (!this.isVerified(requestor)) { this.myRequestWarnings.put(requestor, message); }
     * }
     * ```
     *
     * and nothing removes it afterwards -- `registerRequest` does not touch `myRequestWarnings`. So
     * a class holding the line arriving first leaves the breakpoint valid, and one arriving after
     * the others leaves it permanently marked invalid despite binding correctly. Observed exactly
     * that way: adjacent lines of one function, identical in every respect that matters, some
     * verified and some not.
     *
     * Testing the line removes the race rather than narrowing it: a class that cannot host the
     * breakpoint is never mentioned to it. This is what the platform's own equivalent does --
     * `PositionManagerImpl` wraps its requestor with `getAllClasses(position).contains(type)`, a
     * *position* test, not a file test.
     *
     * Asking [locationsOfLine] about the one class that just prepared is much cheaper than the
     * platform's `getAllClasses`, which walks every loaded class on every prepare.
     *
     * Visible rather than private so the forwarding can be driven directly. Going through the
     * platform would need a `RequestManager`, which is a class rather than an interface and cannot
     * be substituted, and the test that needs it lives in the root module -- the only place a
     * `SourcePosition` over a real `.flix` file exists, because only there is the assembled plugin
     * loaded. `internal` does not reach across a Gradle module, so this is the narrowest visibility
     * that works. It is a nested class of an already-public one, not an API anyone else consumes.
     */
    inner class FlixLineOnly(
        private val delegate: ClassPrepareRequestor,
        private val position: SourcePosition,
    ) : ClassPrepareRequestor {
        override fun processClassPrepare(process: DebugProcess, type: ReferenceType) {
            if (locationsOfLine(type, position).isEmpty()) return
            if (LOG.isDebugEnabled) {
                LOG.debug("prepared ${type.name()}: holds ${position.file.name}:${position.line + 1}, resolving")
            }
            delegate.processClassPrepare(process, type)
        }
    }

    /** No locations, and why -- see [locationsOfLine] for why this is not [NoDataException]. */
    private fun noLocations(type: ReferenceType, target: Target, reason: String): List<Location> {
        if (LOG.isDebugEnabled) {
            LOG.debug("locationsOfLine(${type.name()}): not compiled from ${target.baseName} -- $reason")
        }
        return emptyList()
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
    private fun matchesSource(type: ReferenceType, target: Target): Boolean =
        sources.sourcesOf(type).declares(target.file)

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
