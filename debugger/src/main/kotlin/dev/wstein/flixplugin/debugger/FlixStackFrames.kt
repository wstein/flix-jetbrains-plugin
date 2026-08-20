package dev.wstein.flixplugin.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.memory.utils.StackFrameItem
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.EmptyIcon
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XStackFrameUiPresentationContainer
import com.sun.jdi.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A live Flix frame, labelled in Flix.
 *
 * Everything a [JavaStackFrame] does is kept -- the evaluator, the variables, stepping, the
 * recursion marker -- and only the one line the frames view draws is replaced: `applyFrame:88,
 * Def$readTuning (dev.flix.gen)` becomes `readTuning(), Main.flix:88`. See [FlixFrames] for how the
 * definition is recovered from the class name.
 *
 * ## Why this is reachable at all
 *
 * The label itself is not. It comes from `StackFrameDescriptorImpl.calcRepresentation`, which
 * `JavaFramesListRenderer` composes with the icon and the recursion count, and neither is
 * extensible. The frame *object* is: `JavaExecutionStack.createFrames` asks
 * `positionManager.createStackFramesAsync(descriptor)` first and only falls back to a plain
 * `JavaStackFrame` when every manager declines -- so the way to change what a Flix frame says is to
 * supply the frame, not to intercept the label. [FlixPositionManager] is where that happens, and it
 * declines for everything that is not Flix.
 *
 * The label is computed once, here, rather than per repaint: this constructor runs on the debugger
 * manager thread, where the JDI reads it needs are legal, while `customizePresentation` is called
 * from the UI.
 */
internal class FlixStackFrame(
    descriptor: StackFrameDescriptorImpl,
    private val label: FlixFrameLabel,
) : JavaStackFrame(descriptor, true) {

    override fun customizePresentation(component: ColoredTextContainer) {
        component.setIcon(descriptor.icon)
        // Greyed for a frame the platform would fold away, as JavaFramesListRenderer does: a frame
        // that reads like the others but is about to be hidden is worse than no colour at all.
        val attributes = if (descriptor.shouldHide()) {
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        } else {
            SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES
        }
        component.append(label.call, attributes)
        component.append(label.position, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        if (descriptor.isRecursiveCall) {
            // The one part of the Java presentation that is not decoration. Flix definitions recurse
            // freely, and without this every level of a recursion reads identically.
            component.append(" [${descriptor.occurrenceIndex}]", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }

    /**
     * The same label for the new frames UI.
     *
     * The platform's own implementation emits "Computing frame…" first and fills it in from the
     * manager thread, because a Java label may need evaluation. This one is already computed, so a
     * single emission is the whole story.
     */
    override fun customizePresentation(): Flow<XStackFrameUiPresentationContainer> {
        val container = XStackFrameUiPresentationContainer()
        customizePresentation(container)
        return flowOf(container)
    }
}

/**
 * One entry of the reconstructed Flix call chain, labelled the same way as a live frame.
 *
 * The default rendering of a `StackFrameItem` is the compiled one -- `applyFrame:177,
 * Tuning$Def$path` -- and an async entry has less excuse for it than a live frame does: it exists
 * only because the reconstruction put it there, so it should read as the call it stands for.
 *
 * `createFrame` is the hook. What it returns still has to be a `CapturedStackFrame`: the separator
 * above the chain is drawn by `StackFrameItem.setWithSeparator`, which asks the frame for
 * `XStackFrameWithSeparatorAbove`, and a plain `XStackFrame` would silently lose the *Async stack
 * trace* line that says what the entries below it are.
 */
internal class FlixStackFrameItem(location: Location, private val label: FlixFrameLabel) :
    StackFrameItem(location, null) {

    override fun createFrame(debugProcess: DebugProcessImpl, sourcePosition: SourcePosition?): XStackFrame =
        FlixCapturedFrame(debugProcess, this, sourcePosition, label)
}

private class FlixCapturedFrame(
    debugProcess: DebugProcessImpl,
    item: StackFrameItem,
    sourcePosition: SourcePosition?,
    private val label: FlixFrameLabel,
) : StackFrameItem.CapturedStackFrame(debugProcess, item, sourcePosition) {

    override fun customizePresentation(component: ColoredTextContainer) {
        // The empty icon the platform gives a captured frame, so the chain stays aligned with the
        // live frames above it rather than hanging an icon's width to the left.
        component.setIcon(EmptyIcon.ICON_16)
        component.append(label.call, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append(label.position, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
    }

    override fun customizeTextPresentation(component: ColoredTextContainer) {
        component.append(label.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun customizePresentation(): Flow<XStackFrameUiPresentationContainer> {
        val container = XStackFrameUiPresentationContainer()
        customizePresentation(container)
        return flowOf(container)
    }
}
