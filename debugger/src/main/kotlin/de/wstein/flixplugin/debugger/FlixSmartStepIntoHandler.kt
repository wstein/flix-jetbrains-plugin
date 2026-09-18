package de.wstein.flixplugin.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.actions.JvmSmartStepIntoHandler
import com.intellij.debugger.actions.SmartStepTarget
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.MethodFilter
import com.intellij.psi.PsiElement
import com.intellij.util.Range
import com.sun.jdi.Location
import de.wstein.flixplugin.FlixDebugCalls
import java.nio.file.Path

/** Smart Step Into targets backed exclusively by the compiler's resolved call-site table. */
class FlixSmartStepIntoHandler : JvmSmartStepIntoHandler() {

    override fun isAvailable(position: SourcePosition): Boolean =
        // Availability is asked on the UI path. Do not read a build sidecar here; an absent or
        // stale table simply makes findSmartStepTargets return no choices.
        position.file.virtualFile?.extension == "flix"

    override fun findSmartStepTargets(position: SourcePosition): List<SmartStepTarget> =
        targetsAt(position, callsAt(position))

    internal fun targetsAt(
        position: SourcePosition,
        calls: List<FlixDebugCalls.Call>,
    ): List<SmartStepTarget> =
        calls.mapNotNull { call ->
            highlight(position, call)?.let { element -> FlixSmartStepTarget(call, element) }
        }

    override fun createMethodFilter(target: SmartStepTarget): MethodFilter? =
        (target as? FlixSmartStepTarget)?.let(::FlixSmartMethodFilter)

    private fun callsAt(position: SourcePosition): List<FlixDebugCalls.Call> {
        val root = position.file.project.basePath?.let(Path::of) ?: return emptyList()
        val source = position.file.virtualFile?.path ?: position.file.name
        return FlixDebugCalls.read(root).callsOn(source, position.line + 1)
    }

    private fun highlight(position: SourcePosition, call: FlixDebugCalls.Call): PsiElement? {
        val file = position.file
        val document = file.viewProvider.document ?: return null
        val line = (call.startLine() - 1).coerceIn(0, document.lineCount - 1)
        val start = (document.getLineStartOffset(line) + call.startCol() - 1)
            .coerceIn(document.getLineStartOffset(line), document.getLineEndOffset(line))
        val endLine = (call.endLine() - 1).coerceIn(line, document.lineCount - 1)
        val end = (document.getLineStartOffset(endLine) + call.endCol() - 1)
            .coerceIn(start, document.textLength)

        // The compiler span describes the whole application. That is authoritative for deciding
        // which call this is, but is a poor visual anchor: an infix application and its left-hand
        // call begin at the same column. Prefer the source spelling inside that span so IntelliJ
        // can draw a distinct inline Smart Step Into marker on `List.foldLeft`, `|>`, and
        // `Option.map` rather than stacking the first two at the start of the expression.
        val spelling = sourceSpelling(call.label())
        val occurrence = document.charsSequence.indexOf(spelling, start).takeIf {
            it >= start && it + spelling.length <= end
        }
        if (occurrence != null) {
            val leaf = file.findElementAt(occurrence)
            if (leaf != null) {
                return generateSequence(leaf) { it.parent }
                    .takeWhile { it.textRange.startOffset >= occurrence && it.textRange.endOffset <= occurrence + spelling.length }
                    .lastOrNull { it.text == spelling }
                    ?: leaf
            }
        }

        val leaf = file.findElementAt(start) ?: return null
        return generateSequence(leaf) { it.parent }
            .takeWhile { it.textRange.startOffset >= start && it.textRange.endOffset <= end }
            .lastOrNull()
            ?: leaf
    }
}

internal class FlixSmartStepTarget(
    val call: FlixDebugCalls.Call,
    highlight: PsiElement,
) : SmartStepTarget(
    "${sourceSpelling(call.label())}()",
    highlight,
    false,
    Range(call.startLine() - 1, call.endLine() - 1),
) {
    override fun getClassName(): String = call.className()
    override fun getPresentation(): String = "${sourceSpelling(call.label())}()"
}

/** Drops the compiler's numeric overload/specialization identity, which is not source text. */
private fun sourceSpelling(label: String): String = label.replace(Regex("\\$[0-9]+$"), "")

/** Stops only in the generated definition selected by the compiler for this source call. */
internal class FlixSmartMethodFilter(private val target: FlixSmartStepTarget) : MethodFilter {
    override fun locationMatches(process: DebugProcessImpl, location: Location): Boolean = try {
        matches(target.call, location.declaringType().name(), location.method().name())
    } catch (_: RuntimeException) {
        false
    }

    override fun getCallingExpressionLines(): Range<Int> = target.call.let {
        Range(it.startLine() - 1, it.endLine() - 1)
    }

    companion object {
        internal fun matches(call: FlixDebugCalls.Call, className: String, methodName: String): Boolean =
            className == call.className() && methodName == call.methodName()
    }
}
