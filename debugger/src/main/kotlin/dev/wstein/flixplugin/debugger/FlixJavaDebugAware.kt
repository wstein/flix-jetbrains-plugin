package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.JavaDebugAware
import com.intellij.psi.PsiFile
import org.flixlang.intellij.lang.FlixFileType

/**
 * Declares `.flix` files debuggable by IntelliJ's Java debugger, which is what lets the stock Java
 * line-breakpoint type accept a Flix line.
 *
 * Without this, no breakpoint can be set at all: `JavaLineBreakpointTypeBase.canPutAtElement`
 * refuses any file for which `DebuggerUtils.isBreakpointAware` is false, and that method returns
 * true only when the `LanguageFileType` reports `isJVMDebuggingSupported()` or some
 * [JavaDebugAware] claims the file. The position manager is then never consulted, because nothing
 * ever asks it to resolve a breakpoint.
 *
 * Registering here rather than overriding `FlixFileType.isJVMDebuggingSupported()` keeps the claim
 * in the module that owns Java debugging. `FlixFileType` lives in the language module, which must
 * keep loading in IDEs with no Java plugin; having it assert JVM-debugging support there would
 * state something that module cannot back up. This class only exists where the Java debugger does.
 *
 * Because the stock Java line breakpoint now accepts Flix lines, no custom Flix breakpoint type is
 * needed -- and none should be added without evidence, since a second type claiming the same lines
 * would present the user with two breakpoint variants for one line.
 */
class FlixJavaDebugAware : JavaDebugAware() {
    override fun isBreakpointAware(psiFile: PsiFile): Boolean =
        psiFile.fileType == FlixFileType.INSTANCE
}
