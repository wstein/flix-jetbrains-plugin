package dev.wstein.flixplugin.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Flix value reads in the variables view.
 *
 * The shapes here are taken from `flix-lab`'s compiled output rather than invented: a record is a
 * `label`/`value`/`rest` chain ending at `RecordEmpty$`, a tag is a class whose name ends in it, and
 * values shared across tags compile to `Tag$Bool`, `Tag$Char$Obj` and similar, where the last
 * segment names the representation instead.
 */
class FlixValuesTest {

    // --- records ------------------------------------------------------------------------------

    @Test
    fun `a record reads as its fields`() {
        assertEquals(
            "{ name = \"Ada\", age = 36 }",
            FlixValues.formatRecord(listOf("name" to "\"Ada\"", "age" to "36"), truncated = false),
        )
    }

    @Test
    fun `an empty record is not an empty pair of braces with a stray comma`() {
        assertEquals("{}", FlixValues.formatRecord(emptyList(), truncated = false))
    }

    @Test
    fun `only a truncated record says so`() {
        // A full record that displayed an ellipsis would suggest hidden fields that do not exist,
        // which is worse than showing nothing: the reader stops trusting the label.
        assertEquals("{ a = 1 }", FlixValues.formatRecord(listOf("a" to "1"), truncated = false))
        assertEquals("{ a = 1, … }", FlixValues.formatRecord(listOf("a" to "1"), truncated = true))
    }

    // --- tagged unions ------------------------------------------------------------------------

    @Test
    fun `a nullary tag reads as its name`() {
        assertEquals(
            "NoneLeft",
            FlixValues.formatTagged("Chain\$dotViewLeft\$405229\$NoneLeft", emptyList(), ordinal = 0),
        )
    }

    @Test
    fun `a tag with a payload reads as a call`() {
        assertEquals("Some(42)", FlixValues.formatTagged("Option\$1234\$Some", listOf("42"), ordinal = 1))
        assertEquals(
            "Pair(1, \"x\")",
            FlixValues.formatTagged("Tuple\$99\$Pair", listOf("1", "\"x\""), ordinal = 0),
        )
    }

    @Test
    fun `a shared representation falls back to its ordinal`() {
        // Tag$Bool and friends are one class serving several tags; the final segment names the
        // representation, not a tag. Reporting "Bool" as the tag name would be confidently wrong,
        // which is worse than the ordinal being terse.
        assertEquals("#3", FlixValues.formatTagged("Tag\$Bool", emptyList(), ordinal = 3))
        assertEquals("#2(true)", FlixValues.formatTagged("Tag\$Char\$Obj", listOf("true"), ordinal = 2))
        assertNull(FlixValues.tagNameOf("Tag\$Bool"))
        assertNull(FlixValues.tagNameOf("Tagged\$"))
    }

    @Test
    fun `a synthetic trailing segment is not a tag name`() {
        // Flix appends a counter to generated names. `Clo$main$400074` ends in digits, which is not
        // a tag -- treating it as one would put "400074" in the variables view.
        assertNull(FlixValues.tagNameOf("Clo\$main\$400074"))
        assertNull(FlixValues.tagNameOf(""))
    }

    @Test
    fun `an unnameable tag with no ordinal degrades to the class name`() {
        // Better a raw name than a fabricated one: the reader can still tell what they are looking
        // at, and nothing claims more than is known.
        assertEquals("Tag\$Bool", FlixValues.formatTagged("Tag\$Bool", emptyList(), ordinal = null))
    }

    // --- qualified names, which is all JDI ever hands over ------------------------------------

    @Test
    fun `a tag is read from the simple name, not the qualified one`() {
        // The defect these assertions exist for. Every name in this suite used to be unqualified,
        // so every rule passed here and none of them fired in a debugger: JDI reports
        // `dev.flix.gen.Tag$Obj$Obj`, whose last `$` segment is `Obj` -- a representation, not a
        // tag. The variables view would have reported `Obj` for every shared value.
        assertNull(FlixValues.tagNameOf("dev.flix.gen.Tag\$Obj\$Obj"))
        assertNull(FlixValues.tagNameOf("dev.flix.gen.Tagged\$"))
        assertEquals("InvaderBlast", FlixValues.tagNameOf("dev.flix.gen.BlastKind\$InvaderBlast"))
    }

    @Test
    fun `an unnameable tag degrades to the simple name`() {
        assertEquals(
            "Tag\$Obj\$Obj",
            FlixValues.formatTagged("dev.flix.gen.Tag\$Obj\$Obj", emptyList(), ordinal = null),
        )
    }

    @Test
    fun `a qualified shared representation still falls back to its ordinal`() {
        assertEquals("#1", FlixValues.formatTagged("dev.flix.gen.Tag\$Obj\$Obj", emptyList(), ordinal = 1))
    }

    @Test
    fun `the simple name is taken at the package boundary, not at the first dollar`() {
        // A package is separated by `.` and a nested class by `$`, so a tag name survives.
        assertEquals("BlastKind\$InvaderBlast", FlixValues.simpleNameOf("dev.flix.gen.BlastKind\$InvaderBlast"))
        assertEquals("Record\$", FlixValues.simpleNameOf("dev.flix.gen.Record\$"))
        assertEquals("Record\$", FlixValues.simpleNameOf("Record\$"))
    }

    // --- applicability --------------------------------------------------------------------------

    @Test
    fun `a record is recognised through its interface, whatever the package`() {
        // The hierarchies are the real ones, read from a compiled project:
        //   dev.flix.gen.RecordExtend$Obj implements dev.flix.gen.Record$
        //   dev.flix.gen.Tag$Obj$Obj     extends    dev.flix.gen.Tagged$
        val record = sequenceOf("dev.flix.gen.RecordExtend\$Obj", "dev.flix.gen.Record\$", "java.lang.Object")
        assertTrue(FlixValues.isA(record, FlixValues.RECORD_TYPE))
        assertFalse(FlixValues.isA(record, FlixValues.TAGGED_TYPE))
    }

    @Test
    fun `a tagged value is recognised through its superclass`() {
        val tagged = sequenceOf("dev.flix.gen.Tag\$Obj\$Obj", "dev.flix.gen.Tagged\$", "java.lang.Object")
        assertTrue(FlixValues.isA(tagged, FlixValues.TAGGED_TYPE))
        assertFalse(FlixValues.isA(tagged, FlixValues.RECORD_TYPE))
    }

    @Test
    fun `matching survives the compiler moving its package`() {
        // Why the match is on the simple name at all. These renderers were written when generated
        // classes sat in the unnamed package; codegen moved them to `dev.flix.gen` and both
        // renderers silently stopped applying, because the platform compares the qualified name
        // verbatim. A package change must not be able to do that again.
        for (pkg in listOf("", "dev.flix.gen.", "some.future.package.")) {
            assertTrue(
                "a record in package '$pkg' must still be recognised",
                FlixValues.isA(sequenceOf("${pkg}RecordExtend\$Obj", "${pkg}Record\$"), FlixValues.RECORD_TYPE),
            )
        }
    }

    @Test
    fun `an unrelated class is not a Flix value`() {
        val other = sequenceOf("java.lang.String", "java.lang.Object")
        assertFalse(FlixValues.isA(other, FlixValues.RECORD_TYPE))
        assertFalse(FlixValues.isA(other, FlixValues.TAGGED_TYPE))
    }
}
