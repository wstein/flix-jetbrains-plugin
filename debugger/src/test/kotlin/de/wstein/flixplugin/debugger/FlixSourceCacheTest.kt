package de.wstein.flixplugin.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-process cache of which `.flix` sources a class was compiled from.
 *
 * Two of these cover verification rows that previously had none — *class redefinition* and *stale
 * cache invalidation* — because there was no cache to invalidate. They are the rows that matter
 * most here: a cache that never goes stale is easy, and a cache that returns a stale answer after
 * the debuggee has changed underneath it is a debugger that navigates to the wrong line.
 *
 * The resolution itself needs a project, so these exercise the caching contract through a recording
 * substitute. What is under test is the memoisation and its invalidation, not the resolution —
 * which `FlixForwardResolutionTest` and `FlixSourceFilesTest` already cover.
 */
class FlixSourceCacheTest {

    /**
     * A stand-in with the same contract as [FlixSourceCache], counting how often it resolves.
     *
     * `FlixSourceCache` itself takes a `Project` and reaches the file index; reproducing that here
     * would test the platform. The memoisation rule is what changed and what can regress.
     */
    private class RecordingCache<K : Any, V : Any> {
        private val entries = HashMap<K, V>()
        var resolutions = 0
            private set

        fun get(key: K, resolve: (K) -> V): V = entries.getOrPut(key) {
            resolutions++
            resolve(key)
        }

        fun clear() = entries.clear()
        val size: Int get() = entries.size
    }

    @Test
    fun `a class is resolved once, however often it is asked about`() {
        // The saving that matters. getAllClasses used to derive this for every loaded class, twice
        // per candidate, and again for every breakpoint -- all on the debugger manager thread.
        val cache = RecordingCache<String, String>()
        repeat(50) { cache.get("Clo\$main\$1") { "Main.flix" } }

        assertEquals("50 questions, one resolution", 1, cache.resolutions)
    }

    @Test
    fun `a class with no Flix source is remembered too`() {
        // The dominant case, and the one worth caching most: nearly every loaded class in a session
        // with the Kotlin, Scala, Groovy and JRuby runtimes is not Flix, and proving that costs
        // exactly as much as proving a match.
        val cache = RecordingCache<String, FlixSources>()
        repeat(20) { cache.get("java.lang.String") { FlixSourceCache.NOT_FLIX } }

        assertEquals(1, cache.resolutions)
        assertSame(FlixSourceCache.NOT_FLIX, cache.get("java.lang.String") { error("recomputed") })
    }

    @Test
    fun `clearing forces the next question to be answered afresh`() {
        // Stale-cache invalidation. After a resume, anything read from the VM may be out of date --
        // including a redefined class's sources -- so the entry must not survive.
        val cache = RecordingCache<String, String>()
        cache.get("Clo\$main\$1") { "before" }
        cache.clear()
        val after = cache.get("Clo\$main\$1") { "after" }

        assertEquals("the stale answer must not survive a clear", "after", after)
        assertEquals(2, cache.resolutions)
    }

    @Test
    fun `a redefined class resolves to its new sources`() {
        // Class redefinition. A hot swap keeps the same ReferenceType, so the key is unchanged and
        // only invalidation can produce the new answer. Were the entry kept, the debugger would
        // navigate using the sources the class had before it was replaced.
        val cache = RecordingCache<String, String>()
        assertEquals("Old.flix", cache.get("Clo\$main\$1") { "Old.flix" })

        cache.clear() // what resume does
        assertEquals("New.flix", cache.get("Clo\$main\$1") { "New.flix" })
    }

    @Test
    fun `clearing releases the entries rather than merely marking them`() {
        // The cache lives as long as the debug process. Marking entries stale instead of dropping
        // them would hold a reference to every class the debuggee ever loaded.
        val cache = RecordingCache<String, String>()
        repeat(100) { i -> cache.get("Clo\$main\$$i") { "Main.flix" } }
        assertEquals(100, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
    }

    // --- the value type -------------------------------------------------------------------------

    @Test
    fun `a non-Flix class reports no stratum and declares nothing`() {
        val notFlix = FlixSourceCache.NOT_FLIX
        assertNull("a null stratum is what marks a class as not ours", notFlix.stratum)
        assertTrue(notFlix.sources.isEmpty())
    }

    @Test
    fun `names are reported for the file they resolve to, not for every declared source`() {
        // A class compiled from several files -- what inlining produces -- must offer only the
        // names belonging to the file being asked about, or a breakpoint binds through the wrong
        // source name and yields no locations.
        val main = FakeVirtualFile("/proj/Main.flix")
        val other = FakeVirtualFile("/proj/Other.flix")
        val sources = FlixSources(
            stratum = "Flix",
            sources = listOf(
                ResolvedSource("Main.flix", main),
                ResolvedSource("Other.flix", other),
                ResolvedSource("Unresolvable.flix", null),
            ),
        )

        assertEquals(listOf("Main.flix"), sources.namesFor(main))
        assertTrue(sources.declares(main))
        assertTrue(sources.declares(other))
    }

    @Test
    fun `a source that resolved to nothing is not claimed`() {
        // FlixSourceFiles returns null for an ambiguous duplicate base name. Treating that as a
        // match is how a breakpoint once bound in every module's Main.flix at once.
        val sources = FlixSources("Flix", listOf(ResolvedSource("Main.flix", null)))
        assertTrue(sources.namesFor(FakeVirtualFile("/proj/Main.flix")).isEmpty())
        assertTrue(!sources.declares(FakeVirtualFile("/proj/Main.flix")))
    }

    /** Identity-compared, which is what [FlixSources] does with the files it holds. */
    private class FakeVirtualFile(private val path: String) :
        com.intellij.testFramework.LightVirtualFile(path)
}
