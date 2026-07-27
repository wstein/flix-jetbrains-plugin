package org.flixlang.intellij.lang

import com.intellij.testFramework.ParsingTestCase
import kotlin.random.Random

/**
 * Deterministic, seed-recorded fuzzing of the rare-syntax corpus from [FlixRareSyntaxTest]: each
 * base snippet is put through a fixed sequence of small random mutations (character
 * delete/insert/truncate/duplicate) and re-parsed at every step. The only assertion is "the
 * parser never throws" -- a resilient recursive-descent/Grammar-Kit parser should degrade to
 * PsiErrorElements on garbage input, never an exception, regardless of how mangled the rare
 * constructs (JVM interop, extensible variants, fixpoint/provenance) get mid-edit.
 *
 * Determinism matters more than raw coverage here: every mutation is derived from
 * [BASE_SEED] plus the snippet's name, with no wall-clock time or [kotlin.random.Random] default
 * source involved, so a failure reported by this test reproduces exactly by rerunning it (the
 * failure message also includes the fully-mutated text at the point of failure, so you don't even
 * need to rerun to see what broke it).
 */
class FlixRareSyntaxFuzzTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    companion object {
        /** Bump this only if you deliberately want a different mutation history; keep it fixed
         *  otherwise so past failures stay reproducible. */
        private const val BASE_SEED = 20260718L
        private const val MUTATIONS_PER_SNIPPET = 50
    }

    private val corpus = listOf(
        "jvmConstructor" to """
            import java.math.BigDecimal
            import java.math.BigInteger

            def foo(): BigDecimal =
                new BigDecimal(new BigInteger("12345"), 2)
        """.trimIndent(),
        "jvmAnonymousClass" to """
            import java.util.Comparator

            def foo(): Unit =
                let anon = new Comparator[String] {
                    def compare(_this: Comparator[String], _t: String, _u: String): Int32 = 0
                };
                ()
        """.trimIndent(),
        "extTag" to """
            def foo(): #| A(Bool, Char) |# = xvar A(false, 'x')
        """.trimIndent(),
        "extMatch" to """
            def foo(): Unit =
                ematch xvar B("test", 1) {
                    case A(_)    => ()
                    case B(x, y) => ()
                    case C       => ()
                }
        """.trimIndent(),
        "fixpointSolveProject" to """
            def foo(): Unit =
                let p = #{
                    A(1). A(2). A(3).
                };
                let q = solve p project A;
                ()
        """.trimIndent(),
        "fixpointProvenance" to """
            def foo(): Unit =
                let db = #{
                    A(1).
                    R(x) :- A(x).
                };
                let pm = psolve db;
                let result = pquery pm select R(1) with {A};
                ()
        """.trimIndent(),
    )

    /** Small, Flix-syntax-relevant character set so mutations land on meaningful boundaries
     *  (delimiters, operators) more often than plain-random Unicode would. */
    private val insertionChars = "{}()[]\"'`/*.,:;=<>-+ \n\t$#|@\\"

    private fun mutate(text: String, random: Random): String {
        if (text.isEmpty()) return text
        return when (random.nextInt(4)) {
            0 -> { // delete one character at a random position
                val i = random.nextInt(text.length)
                text.removeRange(i, i + 1)
            }
            1 -> { // insert one syntax-relevant character at a random position
                val i = random.nextInt(text.length + 1)
                val c = insertionChars[random.nextInt(insertionChars.length)]
                text.substring(0, i) + c + text.substring(i)
            }
            2 -> { // truncate at a random position, simulating "user hasn't finished typing yet"
                val i = 1 + random.nextInt(text.length)
                text.substring(0, i)
            }
            else -> { // duplicate a random substring in place, simulating a paste/repeat glitch
                val i = random.nextInt(text.length)
                val len = random.nextInt(minOf(10, text.length - i) + 1)
                text.substring(0, i) + text.substring(i, i + len) + text.substring(i)
            }
        }
    }

    fun testFuzzRareSyntaxNeverCrashes() {
        for ((name, base) in corpus) {
            val seed = BASE_SEED + name.hashCode()
            val random = Random(seed)
            var current = base
            for (round in 0 until MUTATIONS_PER_SNIPPET) {
                current = mutate(current, random)
                try {
                    val psiFile = createPsiFile("test", current)
                    ensureParsed(psiFile)
                } catch (t: Throwable) {
                    fail(
                        "Parser threw on a fuzzed mutation of '$name' (seed=$seed, round=$round). " +
                            "Reproducible with BASE_SEED=$BASE_SEED unchanged; exact text at failure:\n" +
                            "-----\n$current\n-----\nCause: $t"
                    )
                }
            }
        }
    }
}
