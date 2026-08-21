package org.flixlang.intellij.lang.highlighting

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.flixlang.intellij.lang.FlixFileType
import org.flixlang.intellij.lang.FlixLanguage
import org.flixlang.intellij.lang.FlixParserDefinition

/**
 * That the names a file declares get a colour, and that uses do not get a guessed one.
 *
 * Driven through the real parser: the claim is that the *parser* already knows these roles, so a
 * test against hand-built PSI would assert about its own fixture.
 *
 * The second half matters as much as the first. A fallback that coloured uses would be wrong in
 * ordinary Flix code -- `f(x)` is a call, a constructor, an enum case or a local of function type
 * depending on what `f` resolves to -- and a wrong colour is read as information, which is worse
 * than none. Several tests here exist only to pin that nothing is claimed about a use.
 */
class FlixSemanticFallbackAnnotatorTest : BasePlatformTestCase() {

    /**
     * The parser has to be registered by hand.
     *
     * A module-local fixture never reads the content-module descriptor, so `<lang.parserDefinition>`
     * does not reach [LanguageParserDefinitions] and a `.flix` document comes back as plain text
     * with no PSI. Every assertion below would then find no roles and pass for a reason that has
     * nothing to do with the annotator.
     */
    private lateinit var parserDefinition: FlixParserDefinition

    override fun setUp() {
        super.setUp()
        parserDefinition = FlixParserDefinition()
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(FlixLanguage, parserDefinition)
    }

    override fun tearDown() {
        try {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(FlixLanguage, parserDefinition)
        } finally {
            super.tearDown()
        }
    }

    /** Every role the annotator contributes to [source], as `key -> the text it covers`. */
    private fun rolesIn(source: String): List<Pair<String, String>> {
        val file = myFixture.configureByText(FlixFileType.INSTANCE, source)
        val annotator = FlixSemanticFallbackAnnotator()
        val found = mutableListOf<Pair<String, String>>()
        file.accept(
            object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    annotator.roleOf(element)?.let { found += it.key.externalName to it.range.substring(file.text) }
                    super.visitElement(element)
                }
            },
        )
        return found
    }

    private fun keysFor(source: String, text: String): List<String> =
        rolesIn(source).filter { it.second == text }.map { it.first }

    fun testADefinitionsNameIsAFunction() {
        assertEquals(listOf("FLIX_FUNCTION_NAME"), keysFor("def greet(who: String): String = who", "greet"))
    }

    fun testAParameterIsAParameterAndNotALocal() {
        // The distinction the server draws too: a parameter is `Parameter`, a let binding is
        // `Variable`, and they are different colours in every scheme that separates them.
        assertEquals(listOf("FLIX_PARAMETER"), keysFor("def greet(who: String): String = who", "who"))
    }

    fun testALetBindingIsALocalVariable() {
        val source = "def f(): Int32 = let total = 1; total"
        assertEquals(listOf("FLIX_LOCAL_VARIABLE"), keysFor(source, "total"))
    }

    fun testAMatchArmBindsALocalVariableToo() {
        // Not a separate rule: both reach the annotator as a variable pattern, which is the reason
        // a destructuring `let` colours each of its names without anything walking into it.
        val source = "def f(o: Option[Int32]): Int32 = match o { case Some(inner) => inner\n case None => 0 }"
        assertEquals(listOf("FLIX_LOCAL_VARIABLE"), keysFor(source, "inner"))
    }

    fun testAnEnumAndItsCasesAreToldApart() {
        val roles = rolesIn("enum Colour { case Red, case Green }")

        assertEquals(listOf("FLIX_TYPE_NAME"), keysFor("enum Colour { case Red }", "Colour"))
        assertTrue("an enum case should not read as a type: $roles", "FLIX_ENUM_CASE" to "Red" in roles)
        assertTrue("every case, not just the first: $roles", "FLIX_ENUM_CASE" to "Green" in roles)
    }

    fun testATraitAnEffectAndAStructEachGetTheirOwnKey() {
        assertEquals(listOf("FLIX_TRAIT_NAME"), keysFor("trait Show[a] { pub def show(x: a): String }", "Show"))
        assertEquals(listOf("FLIX_EFFECT_NAME"), keysFor("eff Ask { def ask(): String }", "Ask"))
        assertEquals(listOf("FLIX_TYPE_NAME"), keysFor("struct Counter[r] { mut count: Int32 }", "Counter"))
        assertEquals(listOf("FLIX_FIELD_NAME"), keysFor("struct Counter[r] { mut count: Int32 }", "count"))
    }

    fun testATypeParameterIsNotATypeName() {
        // `a` in `trait Show[a]` names nothing that exists; it stands for whatever the instance
        // supplies. The server calls it TypeParameter and colours it like a parameter.
        assertEquals(listOf("FLIX_TYPE_PARAMETER"), keysFor("trait Show[a] { pub def show(x: a): String }", "a"))
    }

    fun testASignatureAndAnEffectOperationAreFunctionsToo() {
        assertEquals(listOf("FLIX_FUNCTION_NAME"), keysFor("trait Show[a] { pub def show(x: a): String }", "show"))
        assertEquals(listOf("FLIX_FUNCTION_NAME"), keysFor("eff Ask { def ask(): String }", "ask"))
    }

    fun testACallIsColouredLikeTheDefinitionItCalls() {
        // The one *use* this layer settles on its own, because the grammar settles it: `postfixExpr`
        // is a head followed by suffixes, and an argument list is one of them.
        assertEquals(
            listOf("FLIX_FUNCTION_NAME"),
            keysFor("def f(): Int32 = helper(1)", "helper"),
        )
    }

    fun testAQualifiedCallIsColouredOnItsLastSegment() {
        // `List.length` is one name; what a reader looks for is `length`, and colouring the module
        // as well would say the module is a function.
        val roles = rolesIn("def f(xs: List[Int32]): Int32 = List.length(xs)")

        assertTrue("the call is not coloured: $roles", "FLIX_FUNCTION_NAME" to "length" in roles)
        assertTrue("the module was coloured too: $roles", roles.none { it.second == "List" })
    }

    fun testARecordLabelIsColouredWhereItIsRead() {
        // `#` has one meaning in the grammar -- `#{`, `#(`, `#|` and `Array#` are each their own
        // token -- so whatever the receiver turns out to be, the name after it is a label.
        val roles = rolesIn("def f(w: {alive = Bool}): Bool = w#alive")

        assertTrue(
            "the label was not coloured as a field: $roles",
            roles.contains("FLIX_FIELD_NAME" to "alive"),
        )
    }

    fun testEveryLabelInAChainIsColoured() {
        // `inv#pos#x` is two labels, and the suffix rule is private, so both are bare tokens under
        // one postfix expression. A rule matching the expression would colour one of them.
        val roles = rolesIn("def f(inv: {pos = {x = Int32}}): Int32 = inv#pos#x")
        val fields = roles.filter { it.first == "FLIX_FIELD_NAME" }.map { it.second }

        assertEquals("not every label in the chain was coloured: $roles", listOf("pos", "x"), fields)
    }

    fun testALabelWrittenInARecordOperationIsColoured() {
        // The three forms `recordOp` has: update, extend and restrict.
        val roles = rolesIn("def f(r: {b = Int32}): Bool = { a = 1, +b = 2, -c | r }")
        val fields = roles.filter { it.first == "FLIX_FIELD_NAME" }.map { it.second }.sorted()

        assertEquals("a written label was missed: $roles", listOf("a", "b", "c"), fields)
    }

    fun testALowercaseNameThatIsNotALabelIsNotColouredAsOne() {
        // The rule is "a name preceded by `#`", not "a name". Without the token test every
        // lowercase name in the file would come back as a field.
        val roles = rolesIn("def f(): Int32 = let total = 1; total")

        assertTrue(
            "a plain name was coloured as a field: $roles",
            roles.none { it.first == "FLIX_FIELD_NAME" },
        )
    }

    fun testARecordPatternsLabelIsLeftToTheServer() {
        // Deliberate. The server emits `Property` for a record pattern's label too, but over
        // `RecordLabelPattern`'s own location -- which `Weeder2` sets to the whole `x = p`, the
        // sub-pattern included. Colouring the label alone would be right and would still not match;
        // colouring the wider span would spread a field colour across a binding.
        val roles = rolesIn("def f(r: {x = Int32}): Int32 = match r { case {x = v | _} => v }")

        assertTrue(
            "a pattern label was coloured: $roles",
            roles.none { it.first == "FLIX_FIELD_NAME" },
        )
    }

    fun testAHeadThatIsNotCalledIsNotColoured() {
        // A postfix expression can carry several suffixes, and only a call counts. `r#fn(1)` selects
        // a field and *then* calls it: the argument list belongs to the selection, not to `r`.
        // Asking merely whether the expression has an argument list somewhere colours `r`.
        //
        // Note `s.length()` cannot make this point: `qualifiedName` is `ident (DOT ident)*`, so
        // `s.length` parses as one name and the call really is on its last segment. Measured, after
        // the first version of this test passed with the next-sibling test removed.
        val roles = rolesIn("def f(): Int32 = let r = { fn = x -> x + 1 }; r#fn(1)")

        assertTrue(
            "a head that is selected from, not called, was coloured: $roles",
            roles.none { it.second == "r" && it.first == "FLIX_FUNCTION_NAME" },
        )
    }

    fun testAnEnumCaseWithArgumentsIsLeftToTheServer() {
        // Measured: the server calls `Some(4)` an EnumMember, not a Function. Flix names cases with
        // a capital and definitions without, so the first letter separates them with no resolution
        // -- and being wrong here would colour a constructor as a call.
        val roles = rolesIn("def f(): Option[Int32] = Some(4)")

        assertTrue("an enum case was coloured as a call: $roles", roles.none { it.second == "Some" })
    }

    fun testAnAppliedParameterIsLeftToTheServer() {
        // Also measured: the server calls `f(n)` a Parameter when `f` is one, because where a name
        // is *bound* outranks what is done with it. The enclosing parameter lists are in the PSI, so
        // this can agree without resolving anything.
        val roles = rolesIn("def apply(g: Int32 -> Int32, n: Int32): Int32 = g(n)")

        assertTrue(
            "an applied parameter was coloured as a call: $roles",
            roles.none { it.second == "g" && it.first == "FLIX_FUNCTION_NAME" },
        )
    }

    fun testAnAppliedLambdaBinderIsLeftToTheServer() {
        // A lambda's binder is a parameter too -- `lambdaExpr` is a parameter list, and the bare
        // form binds an ident -- and the server calls it Parameter for the same reason.
        val roles = rolesIn("def f(): Int32 = (h -> h(1))(x -> x)")

        assertTrue(
            "an applied lambda binder was coloured as a call: $roles",
            roles.none { it.second == "h" && it.first == "FLIX_FUNCTION_NAME" },
        )
    }

    fun testAnAppliedLocalIsColouredLikeACall() {
        // And the other way: the server calls an applied *local* a Function, so this must too --
        // the exclusion is about parameters, not about everything that is not a definition.
        val roles = rolesIn("def f(): Int32 = let g = x -> x + 1; g(1)")

        assertTrue("an applied local was not coloured: $roles", "FLIX_FUNCTION_NAME" to "g" in roles)
    }

    fun testATypeReferenceIsLeftToTheServer() {
        // A type in a signature is a use as well: `Station` may be an alias, an enum, a struct or a
        // trait's associated type. The declaration of each is coloured; the mention is not.
        val roles = rolesIn("def f(who: Station): Station = who")

        assertTrue("a type mention must not be coloured: $roles", roles.none { it.second == "Station" })
    }

    fun testANamelessDeclarationIsSkippedRatherThanReachedInto() {
        // An annotator's normal input is half-written code. A trait signature is the one production
        // whose name the grammar makes optional -- it has error rules for a missing one -- so it is
        // the case that decides whether a missing name is skipped or dereferenced. Checked here
        // rather than with `def ` at top level, which parses to no declaration at all and so proves
        // nothing about the annotator.
        val roles = rolesIn("trait Show[a] { pub def }")

        assertTrue("a nameless signature must contribute nothing: $roles", roles.none { it.second == "def" })
        assertTrue("the trait itself is still named: $roles", "FLIX_TRAIT_NAME" to "Show" in roles)
    }

    fun testTheKeysCarryTheAttributesTheServerWouldUse() {
        // Measured against SemanticTokensProvider and mapped through LSP4IJ's own table, so the
        // handover from this layer to the server's changes nothing on screen. A key whose fallback
        // drifts from that table makes every file flicker through a second palette on open.
        assertEquals(
            "DEFAULT_FUNCTION_CALL",
            FlixSyntaxHighlighter.FUNCTION_NAME.fallbackAttributeKey?.externalName,
        )
        assertEquals("DEFAULT_PARAMETER", FlixSyntaxHighlighter.PARAMETER.fallbackAttributeKey?.externalName)
        assertEquals(
            "DEFAULT_REASSIGNED_LOCAL_VARIABLE",
            FlixSyntaxHighlighter.LOCAL_VARIABLE.fallbackAttributeKey?.externalName,
        )
        assertEquals("DEFAULT_CLASS_NAME", FlixSyntaxHighlighter.TYPE_NAME.fallbackAttributeKey?.externalName)
        assertEquals("DEFAULT_STATIC_FIELD", FlixSyntaxHighlighter.ENUM_CASE.fallbackAttributeKey?.externalName)
        assertEquals("DEFAULT_INSTANCE_FIELD", FlixSyntaxHighlighter.FIELD_NAME.fallbackAttributeKey?.externalName)
        assertEquals("DEFAULT_INTERFACE_NAME", FlixSyntaxHighlighter.TRAIT_NAME.fallbackAttributeKey?.externalName)
    }
}
