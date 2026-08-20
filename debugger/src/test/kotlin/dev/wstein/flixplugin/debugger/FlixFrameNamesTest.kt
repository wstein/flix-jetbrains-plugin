package dev.wstein.flixplugin.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Flix definition behind a generated class name.
 *
 * Every case here is the compiler's own naming rule read backwards -- `JvmName`'s
 * `mkNamespacedClassName`, `mangle` and `Symbol.generatedDefnSym` -- rather than shapes observed in
 * one program, so a name this plugin has never seen is covered by the same reasoning as the ones it
 * has.
 */
class FlixFrameNamesTest {

    @Test
    fun `a definition in the root namespace is its own name`() {
        assertEquals("readTuning", FlixFrames.definitionOf("dev.flix.gen.Def\$readTuning"))
    }

    @Test
    fun `a one-segment namespace lives in the prefix, not in the package`() {
        // `mod Tuning` compiles to dev.flix.gen.Tuning$Def$path: a namespace with no parent has
        // nowhere to be a package, so it becomes a class-name prefix instead.
        assertEquals("Tuning.path", FlixFrames.definitionOf("dev.flix.gen.Tuning\$Def\$path"))
    }

    @Test
    fun `a nested namespace is split between the package and the prefix`() {
        // `mod Acme.Api` puts the class in package Acme with the prefix Api$ -- only the *first*
        // segment ever becomes a package.
        assertEquals("Acme.Api.map", FlixFrames.definitionOf("Acme.Api\$Def\$map"))
        assertEquals("Acme.Api.Deep.map", FlixFrames.definitionOf("Acme.Api\$Deep\$Def\$map"))
    }

    @Test
    fun `a closure names the definition it belongs to`() {
        assertEquals("main", FlixFrames.definitionOf("dev.flix.gen.Clo\$main"))
        assertEquals("List.map", FlixFrames.definitionOf("dev.flix.gen.List\$Clo\$map"))
    }

    @Test
    fun `a lifted lambda drops the hash it was given, not the name it kept`() {
        // Symbol.generatedDefnSym names a lifted lambda `<owner>$<11 Base58 chars>`. The lambda has
        // no name in the source, so the definition it came out of is the truthful answer.
        assertEquals("main", FlixFrames.definitionOf("dev.flix.gen.Clo\$main\$626ZYxrpg1N"))
        assertEquals("Tuning.path", FlixFrames.definitionOf("dev.flix.gen.Tuning\$Clo\$path\$M3eRsLzGW4P"))
    }

    @Test
    fun `something that merely looks like a hash is kept`() {
        // Ten characters, not eleven, so it is part of the name.
        assertEquals("main\$626ZYxrpg", FlixFrames.definitionOf("dev.flix.gen.Clo\$main\$626ZYxrpg"))
    }

    @Test
    fun `an operator is unmangled back to the operator`() {
        assertEquals("+", FlixFrames.definitionOf("dev.flix.gen.Def\$\$plus"))
        assertEquals("Add.<=>", FlixFrames.definitionOf("dev.flix.gen.Add\$Def\$\$less\$eq\$greater"))
    }

    @Test
    fun `the longest mangled word is not mistaken for a hash`() {
        // `exclamation` is the only eleven-character replacement, which is the length of a
        // lifted-lambda hash. It survives because Base58 excludes `l` -- asserted rather than
        // assumed, since a new replacement word could take that away.
        assertEquals("!", FlixFrames.definitionOf("dev.flix.gen.Def\$\$exclamation"))
        assertEquals("go!", FlixFrames.definitionOf("dev.flix.gen.Def\$go\$exclamation"))
    }

    @Test
    fun `a namespace named after a marker is still a namespace`() {
        // `mod Def` compiles `x` to Def$Def$x. Reading the first marker would take the namespace
        // for the marker and lose it.
        assertEquals("Def.x", FlixFrames.definitionOf("dev.flix.gen.Def\$Def\$x"))
    }

    @Test
    fun `an effect is named like a definition`() {
        assertEquals("Print", FlixFrames.definitionOf("dev.flix.gen.Eff\$Print"))
        assertEquals("List.Crash", FlixFrames.definitionOf("dev.flix.gen.List\$Eff\$Crash"))
    }

    @Test
    fun `a class that names no definition is declined`() {
        // The runtime's own classes, a Java frame in a mixed stack, and a generated shape that is
        // not a symbol. The caller keeps the platform's label rather than inventing one.
        assertNull(FlixFrames.definitionOf("dev.flix.runtime.Handler\$"))
        assertNull(FlixFrames.definitionOf("dev.flix.gen.RecordExtend\$Obj"))
        assertNull(FlixFrames.definitionOf("com.jetbrains.Coordinates"))
        assertNull(FlixFrames.definitionOf("Def\$"))
    }

    @Test
    fun `a label reads as the call and the position`() {
        assertEquals("readTuning(), Main.flix:88", FlixFrameLabel("readTuning", "Main.flix", 88).toString())
    }
}
