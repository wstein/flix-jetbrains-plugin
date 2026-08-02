package org.flixlang.intellij.lang

/**
 * Compares this plugin's projected trees against `flix-spec`'s `fixtures/expected/`.
 *
 * Ported from `flix-spec`'s `tools/project/src/main/scala/flix/spec/Conformance.scala` rather than
 * depending on it directly: the published `flix-spec` artifact is deliberately data-only (JSON,
 * fixtures, schemas -- see its `packaging` module), carrying no compiled code, so there is nothing
 * to call. The comparison itself is pure data-in/data-out logic with no consumer-specific knowledge,
 * which is what makes porting it a smaller, more honest choice than adding a second published
 * artifact (and a Scala runtime dependency) just to make one utility callable cross-language. Keep
 * this in step with the Scala original by inspection when either changes; see
 * `docs/CONFORMANCE.md` in flix-spec for what the comparison covers and why.
 */
object Conformance {

    data class KTree(val kind: String, val children: List<KTree>)

    /** Drops token leaves: only `kind`-bearing nodes become part of the comparable tree. */
    fun kindTree(node: Json): KTree? {
        val kind = node.get("kind")?.asString() ?: return null
        val children = (node.get("children")?.asArray() ?: emptyList()).mapNotNull { kindTree(it) }
        return KTree(kind, children)
    }

    /** `{"units": [{"source": ..., "tree": ...}]}` -> source path to its projected tree. */
    fun loadUnits(json: Json): Map<String, KTree> =
        (json.get("units")?.asArray() ?: emptyList())
            .associate { it.asObject().getValue("source").asString() to kindTree(it.asObject().getValue("tree"))!! }

    class Stats {
        val counts = mutableMapOf<String, Int>()
        val unmappedNames = mutableSetOf<String>()
        fun inc(key: String) {
            counts[key] = (counts[key] ?: 0) + 1
        }
        fun count(key: String): Int = counts[key] ?: 0
    }

    data class Divergence(val path: String, val expected: String, val actual: String, val reason: String)

    /**
     * Splices a grouping node's children into its parent, at any arity -- stronger than elision,
     * which only fires at arity <= 1. Opt-in per node name; see the projection map's own `flatten`
     * entries for what qualifies and why.
     */
    private fun applyFlatten(children: List<KTree>, flatten: Set<String>, stats: Stats): List<KTree> =
        children.flatMap { c ->
            if (flatten.contains(c.kind)) {
                stats.inc("flattened")
                applyFlatten(c.children, flatten, stats)
            } else {
                listOf(c)
            }
        }

    /**
     * Removes transparent nodes from a child list, used on both sides: `elide` names canonical
     * wrappers the consumer does not produce, `ignored` names the consumer's own wrappers with no
     * counterpart in the reference. A transparent node is dropped when empty and replaced by its
     * child when it has exactly one; two or more children are kept, since splicing them into the
     * parent would discard real structure. A name belongs in `ignored` only when its canonical
     * target is also `elide`d -- see `docs/CONFORMANCE.md`'s "`ignored` is not a synonym for
     * unimportant" for what goes wrong otherwise.
     */
    private fun applyElision(children: List<KTree>, elide: Set<String>, stats: Stats, counter: String): List<KTree> {
        val out = mutableListOf<KTree>()
        for (start in children) {
            var current: KTree? = start
            while (true) {
                val node = current
                if (node != null && elide.contains(node.kind) && node.children.size <= 1) {
                    stats.inc(counter)
                    current = node.children.firstOrNull()
                    if (current == null) break
                } else {
                    break
                }
            }
            current?.let { out += it }
        }
        return out
    }

    private const val MAX_DIVERGENCES_PER_FIXTURE = 20

    /** Walks both trees in lockstep, appending divergences to `out`. */
    private fun compare(
        expected: KTree,
        actual: KTree,
        mapping: Map<String, String>?,
        ignored: Set<String>,
        elide: Set<String>,
        flatten: Set<String>,
        path: String,
        out: MutableList<Divergence>,
        stats: Stats,
    ) {
        if (out.size >= MAX_DIVERGENCES_PER_FIXTURE) return

        val actKind: String =
            if (mapping == null) {
                actual.kind
            } else {
                val mapped = mapping[actual.kind]
                if (mapped != null) {
                    stats.inc("mapped")
                    mapped
                } else {
                    stats.inc("unmapped")
                    stats.unmappedNames += actual.kind
                    return // not a disagreement: we simply have no opinion yet
                }
            }

        val expChildren = applyElision(expected.children, elide, stats, "elided")
        val actChildren = applyElision(applyFlatten(actual.children, flatten, stats), ignored, stats, "ignored")

        stats.inc("compared")
        if (expected.kind != actKind) {
            out += Divergence(path, expected.kind, actKind, "kind")
            return // subtree shape is meaningless once the kinds disagree
        }

        if (expChildren.size != actChildren.size) {
            out += Divergence(path, "${expChildren.size} children", "${actChildren.size} children", "arity")
        }

        expChildren.zip(actChildren).forEachIndexed { i, (e, a) ->
            compare(e, a, mapping, ignored, elide, flatten, "$path.${expected.kind}[$i]", out, stats)
        }
    }

    data class ProjectionMap(
        val consumer: String,
        val mappings: Map<String, String>,
        val ignored: Set<String>,
        val elide: Set<String>,
        val flatten: Set<String>,
    )

    fun loadProjectionMap(json: Json): ProjectionMap =
        ProjectionMap(
            consumer = json.get("consumer")!!.asString(),
            mappings = json.get("mappings")!!.asObject().mapValues { it.value.asString() },
            ignored = (json.get("ignored")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
            elide = (json.get("elide")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
            flatten = (json.get("flatten")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
        )

    data class Result(
        val fixturesExpected: Int,
        val fixturesCompared: Int,
        val fixturesMissing: List<String>,
        val fixturesAgreeing: Int,
        val nodesCompared: Int,
        val nodesUnmapped: Int,
        val unmappedNames: List<String>,
        val divergences: List<Pair<String, Divergence>>,
    ) {
        val depth: Double
            get() {
                val encountered = nodesCompared + nodesUnmapped
                return if (encountered == 0) 0.0 else nodesCompared.toDouble() / encountered
            }

        fun summary(consumer: String): String {
            val unmappedSuffix = if (nodesUnmapped > 0) ", $nodesUnmapped unmapped" else ""
            val depthPct = Math.round(depth * 100)
            return "$consumer: $fixturesAgreeing/$fixturesCompared fixtures agree, " +
                "${divergences.size} divergences, $nodesCompared nodes compared " +
                "(depth $depthPct%)$unmappedSuffix"
        }
    }

    /**
     * `expectedBySource`/`actualBySource`: fixture source path (e.g.
     * `fixtures/positive/foo.flix`) -> the tree projected for it. The Scala CLI tool matches files
     * by name and then units within each file by their own `source` field, to support a file
     * holding more than one unit; every fixture here is exactly one unit in exactly one file, so
     * that two-level structure collapses to this flat map without changing what gets compared.
     */
    fun run(
        expectedBySource: Map<String, KTree>,
        actualBySource: Map<String, KTree>,
        map: ProjectionMap,
    ): Result {
        val stats = Stats()
        val divergences = mutableListOf<Pair<String, Divergence>>()
        var agreeing = 0
        val missing = mutableListOf<String>()

        for ((source, expTree) in expectedBySource.toSortedMap()) {
            val actTree = actualBySource[source]
            if (actTree == null) {
                missing += source
                continue
            }
            val found = mutableListOf<Divergence>()
            compare(expTree, actTree, map.mappings, map.ignored, map.elide, map.flatten, source, found, stats)
            if (found.isNotEmpty()) divergences += found.map { source to it } else agreeing++
        }

        return Result(
            fixturesExpected = expectedBySource.size,
            fixturesCompared = expectedBySource.size - missing.size,
            fixturesMissing = missing.sorted(),
            fixturesAgreeing = agreeing,
            nodesCompared = stats.count("compared"),
            nodesUnmapped = stats.count("unmapped"),
            unmappedNames = stats.unmappedNames.sorted(),
            divergences = divergences,
        )
    }
}
