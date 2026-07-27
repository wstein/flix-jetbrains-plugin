package dev.wstein.flixplugin.debugger

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManager
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
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
        val sourceName = FlixSourceLocations.sourceNameOf(jdiLocation) ?: throw NoDataException.INSTANCE
        val line = FlixSourceLocations.lineNumberOf(jdiLocation) ?: throw NoDataException.INSTANCE

        val sourcePath = runCatching { jdiLocation.sourcePath(strataFor(jdiLocation)) }.getOrNull()
        val file = ReadAction.compute<PsiFile?, RuntimeException> {
            FlixSourceFiles.find(debugProcess.project, sourceName, sourcePath)
        } ?: throw NoDataException.INSTANCE

        // SourcePosition is zero-based; JDI line numbers are one-based.
        return ReadAction.compute<SourcePosition, RuntimeException> {
            SourcePosition.createFromLine(file, line - 1)
        }
    }

    override fun getAllClasses(position: SourcePosition): List<ReferenceType> {
        val sourcePath = flixSourcePathOf(position)
        return debugProcess.virtualMachineProxy.allClasses().filter { type ->
            matchesSource(type, sourcePath)
        }
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        val sourcePath = flixSourcePathOf(position)
        val stratum = FlixSourceLocations.stratumFor(type) ?: throw NoDataException.INSTANCE
        if (!matchesSource(type, sourcePath)) throw NoDataException.INSTANCE

        // One Flix line commonly compiles into several generated classes and several locations
        // within them -- closures and lambdas each get their own. Returning all of them is what
        // makes a single breakpoint stop wherever that line actually runs.
        return runCatching {
            type.locationsOfLine(stratum, sourcePath.substringAfterLast('/'), position.line + 1)
        }.getOrElse { emptyList() }
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
        flixSourcePathOf(position)

        // Flix compiles a source file into many classes whose names it chooses, so there is no
        // single class-name pattern to filter on. Watching every prepare and re-checking the source
        // on arrival is the correct trade here: breakpoints set before their class loads still
        // resolve, at the cost of one source-name comparison per class.
        val request = debugProcess.requestsManager.createClassPrepareRequest(requestor, "*")
            ?: return emptyList()
        return listOf(request)
    }

    private fun flixSourcePathOf(position: SourcePosition): String {
        val file = position.file
        if (file.fileType != FlixFileType.INSTANCE) throw NoDataException.INSTANCE
        return file.virtualFile?.path ?: file.name
    }

    private fun matchesSource(type: ReferenceType, sourcePath: String): Boolean {
        val stratum = FlixSourceLocations.stratumFor(type) ?: return false
        if (!FlixSourceLocations.declaresFlixSourceIn(type, stratum)) return false
        return runCatching { type.sourcePaths(stratum) }
            .getOrDefault(emptyList())
            .any { FlixSourceLocations.sameSourcePath(it, sourcePath) }
    }

    private fun strataFor(location: Location): String =
        FlixSourceLocations.stratumFor(location.declaringType()) ?: location.declaringType().defaultStratum()
}

/** Registered under `com.intellij.debugger.positionManagerFactory`. */
class FlixPositionManagerFactory : PositionManagerFactory() {
    override fun createPositionManager(process: DebugProcess): PositionManager =
        FlixPositionManager(process)
}
