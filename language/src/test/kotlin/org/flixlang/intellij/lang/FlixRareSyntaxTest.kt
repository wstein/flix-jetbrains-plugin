package org.flixlang.intellij.lang

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/**
 * Happy-path coverage for the three grammar areas flagged as "rare syntax" rather than being
 * expanded further during the initial grammar port: JVM interop, extensible variants, and
 * fixpoint/Datalog (including provenance) forms. Every snippet here is adapted from real,
 * compiler-verified test fixtures under flix/flix's own test suite (Test.Exp.Jvm.*,
 * Test.Exp.ExtTag/ExtMatch/Lambda.ExtMatch, Test.Exp.Fixpoint.*) rather than hand-written from
 * memory, since guessing Flix syntax has produced false failures before (e.g. `run { } with Foo
 * { }` isn't valid -- it's `run { } with handler Foo { }`).
 */
class FlixRareSyntaxTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    private fun parseAndCheck(code: String) {
        val psiFile = createPsiFile("test", code)
        ensureParsed(psiFile)
        println(toParseTreeText(psiFile, true, false))
        val error = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        assertNull("Unexpected parse error: ${error?.errorDescription}", error)
    }

    // ---------------------------------------------------------------------------------------
    // JVM interop
    // ---------------------------------------------------------------------------------------

    fun testJvmConstructorInvocation() {
        parseAndCheck(
            """
            mod Snippet.New {
                import java.math.BigDecimal
                import java.math.BigInteger

                def testInvokeBigDecimalConstructor01(): BigDecimal =
                    new BigDecimal(new BigInteger("12345"), 2)
            }
            """.trimIndent()
        )
    }

    fun testJvmInstanceMethodCall() {
        parseAndCheck(
            """
            def testBigIntegerAdd01(): Unit \ Assert =
                assertEq(expected = 468ii, (-32ii).add(500ii))
            """.trimIndent()
        )
    }

    fun testJvmStaticMethodCall() {
        parseAndCheck(
            """
            import java.lang.{String => JString}

            def invokeStringValueOfInt01(): Unit \ {Assert, IO} =
                assertEq(expected = "42", JString.valueOf(42i32))
            """.trimIndent()
        )
    }

    fun testJvmAnonymousClass() {
        parseAndCheck(
            """
            import java.io.Serializable
            import java.util.Comparator

            def implementSerializable(): Serializable \ IO =
                new Serializable { }

            def testComparator(): Unit \ Assert + IO =
                let anon = new Comparator[String] {
                    def compare(_this: Comparator[String], _t: String, _u: String): Int32 = 0
                };
                assertEq(expected = 0, anon.compare("foo", "bar"))
            """.trimIndent()
        )
    }

    fun testJvmAnonymousClassWithConstructorOverride() {
        parseAndCheck(
            """
            def testGenericAbstractClassCompile01(): TestGenericAbstractClass[String] \ IO =
                new TestGenericAbstractClass[String] {
                    def new(): TestGenericAbstractClass[String] \ IO = super("hello")
                    def transform(_this: TestGenericAbstractClass[String], input: String): String =
                        input
                }
            """.trimIndent()
        )
    }

    fun testStructAllocationInRegion() {
        parseAndCheck(
            """
            struct Point[r] {
                mut x: Int32,
                mut y: Int32
            }

            def makePoint(rc: Region[r]): Point[r] \ r =
                new Point @ rc { x = 1, y = 2 }
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Extensible variants
    // ---------------------------------------------------------------------------------------

    fun testExtTagConstruction() {
        parseAndCheck(
            """
            def testExtTagUnit01(): #| A(Unit) |# = xvar A(())
            def testExtTagArity02(): #| A(Bool, Char) |# = xvar A(false, 'x')
            def testExtTagMultiple01(): #| A(Bool), B(Char) |# = if (true) xvar A(true) else xvar B('a')
            """.trimIndent()
        )
    }

    fun testExtMatch() {
        parseAndCheck(
            """
            def testExtMatchArities02(): Unit \ Assert = {
                ematch xvar B("test", 1) {
                    case A(_)    => fail("unexpected A")
                    case B(x, y) => { assertEq(expected = "test", x); assertEq(expected = 1, y) }
                    case C       => fail("unexpected C")
                }
            }
            """.trimIndent()
        )
    }

    fun testExtMatchLambda() {
        parseAndCheck(
            """
            def testLambdaExtMatch01(): #| A(Int32) |# -> Int32 = ematch A(x) -> x
            def testLambdaExtMatch02(): #| A(Int32, Int32) |# -> Int32 = ematch A(x, y) -> ((x + y) : Int32)
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Fixpoint / Datalog / provenance
    // ---------------------------------------------------------------------------------------

    fun testFixpointSolveAndProject() {
        parseAndCheck(
            """
            def testProjectSingleInt01(): Unit \ Assert = {
                let p = #{
                    A(1). A(2). A(3).
                    B(10). B(20).
                    C(100).
                };
                let q = solve p project A;
                let expected = Vector#{1, 2, 3};
                let actual = query q select x from A(x);
                assertEq(expected = expected, actual)
            }
            """.trimIndent()
        )
    }

    fun testFixpointQueryWithWhere() {
        parseAndCheck(
            """
            def testQuery15(): Unit \ Assert =
                let p = #{
                    P((1, 2)).
                    P((3, 4)).
                };
                let r = query p select x from P(x) where fst(x) > 2;
                assertEq(expected = Vector#{(3, 4)}, r)
            """.trimIndent()
        )
    }

    fun testFixpointInject() {
        parseAndCheck(
            """
            def inject01(): Unit \ Assert = {
                let l = (Vector.empty(): Vector[Bool]);
                let pr = inject l into A/1;
                let r = query pr select x from A(x);
                assertEq(expected = l, r)
            }
            """.trimIndent()
        )
    }

    fun testFixpointProvenanceSolveAndQuery() {
        parseAndCheck(
            """
            def testPSolve01(): Unit \ Assert =
                let db = #{
                    A(1). A(2). A(3).
                    B(1). B(2). B(3).
                    R(x) :- A(x), B(x), B(x), A(x).
                };
                let pm = psolve db;
                let expect = Vector#{"A(1)", "B(1)", "B(1)", "A(1)"};
                let result = pquery pm select R(1) with {A, B};
                let actual = result |> Vector.map(v -> ematch v {
                    case A(x) => "A(${'$'}{x})"
                    case B(x) => "B(${'$'}{x})"
                });
                assertEq(expected = expect, actual)
            """.trimIndent()
        )
    }

    fun testFixpointProvenanceQueryUnit() {
        parseAndCheck(
            """
            def testPQueryUnit(): Unit \ Assert =
                let pr = #{
                    A(()).
                };
                let pp = pquery pr select A(()) with {A};
                let expected = Vector#{()};
                let actual = Vector.map(v -> ematch v { case A(x) => x }, pp);
                assertEq(expected = expected, actual)
            """.trimIndent()
        )
    }
}
