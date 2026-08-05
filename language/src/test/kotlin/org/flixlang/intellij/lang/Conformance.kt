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
     * Removes transparent nodes from a whole tree, bottom-up, as a single fixed point.
     *
     * Two rules, applied together rather than in sequence:
     *  - `splice` -- the node's children replace it in its parent, at any arity. Stronger than
     *    elision, so it is opt-in per node name and belongs only on pure grouping constructs.
     *  - `elide` -- dropped when it has no children, replaced by its child when it has one, and
     *    kept at two or more, since splicing a branching node would discard real structure.
     *
     * **Bottom-up, and that is not a detail.** This used to apply the two rules once per level, in
     * sequence, and a node promoted into a level from below never met the other rule. The canonical
     * trees contain exactly that shape -- `Type.Type` wrapping an empty `ErrorTree` -- where
     * splicing the marker leaves the wrapper childless and therefore elidable, which a single pass
     * cannot see. flix-spec carried the same defect and fixed it by feeding `fixtures/raw` back
     * through its own comparison; this is the same fix, so the two agree again.
     */
    private fun transparent(
        node: KTree,
        splice: Set<String>,
        elide: Set<String>,
        stats: Stats,
        spliceCounter: String,
        elideCounter: String,
    ): List<KTree> {
        val kids = node.children.flatMap { transparent(it, splice, elide, stats, spliceCounter, elideCounter) }
        return when {
            splice.contains(node.kind) -> {
                stats.inc(spliceCounter)
                kids
            }
            elide.contains(node.kind) && kids.size <= 1 -> {
                stats.inc(elideCounter)
                kids
            }
            else -> listOf(KTree(node.kind, kids))
        }
    }

    /** Applies transparency while leaving the root alone: it has no parent to be spliced into. */
    private fun transparentTree(
        root: KTree,
        splice: Set<String>,
        elide: Set<String>,
        stats: Stats,
        spliceCounter: String,
        elideCounter: String,
    ): KTree =
        KTree(root.kind, root.children.flatMap { transparent(it, splice, elide, stats, spliceCounter, elideCounter) })

    private const val MAX_DIVERGENCES_PER_FIXTURE = 20

    /**
     * Walks both trees in lockstep, appending divergences to `out`.
     *
     * Both trees arrive with transparency already applied, so this does one job: match kinds and
     * arity, position by position. Doing the two in one pass is what let the rules interact with
     * the walk's own recursion and hid the fixed-point defect described on [transparent].
     */
    private fun compare(
        expected: KTree,
        actual: KTree,
        mapping: Map<String, String>?,
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

        stats.inc("compared")
        if (expected.kind != actKind) {
            out += Divergence(path, expected.kind, actKind, "kind")
            return // subtree shape is meaningless once the kinds disagree
        }

        if (expected.children.size != actual.children.size) {
            out += Divergence(path, "${expected.children.size} children", "${actual.children.size} children", "arity")
        }

        expected.children.zip(actual.children).forEachIndexed { i, (e, a) ->
            compare(e, a, mapping, "$path.${expected.kind}[$i]", out, stats)
        }
    }

    data class ProjectionMap(
        val consumer: String,
        val mappings: Map<String, String>,
        val ignored: Set<String>,
        val elide: Set<String>,
        val flatten: Set<String>,
        /**
         * Our own nodes that mark error recovery rather than syntax -- `PsiErrorElement` and the
         * named `*_ERROR` elements. flix-spec normalises the reference's error vocabulary out of
         * `fixtures/expected`, so ours has to come out of our side too or every negative fixture
         * reports a disagreement that is really a modelling difference. Transparency has to be
         * symmetric; that argument was always true of wrappers and is no different here.
         */
        val recoveryMarkers: Set<String>,
    ) {
        /** The vocabulary the structural comparison uses: our recovery markers spliced out. */
        val spliceStructural: Set<String> get() = flatten + recoveryMarkers
    }

    fun loadProjectionMap(json: Json): ProjectionMap =
        ProjectionMap(
            consumer = json.get("consumer")!!.asString(),
            mappings = json.get("mappings")!!.asObject().mapValues { it.value.asString() },
            ignored = (json.get("ignored")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
            elide = (json.get("elide")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
            flatten = (json.get("flatten")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
            recoveryMarkers =
                (json.get("recoveryMarkers")?.asArray() ?: emptyList()).map { it.asString() }.toSet(),
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
            // Transparency is applied to whole trees first, as one bottom-up fixed point per side,
            // and only then compared. flix-spec does exactly this, and the two must agree.
            val expT = transparentTree(expTree, emptySet(), map.elide, stats, "flattenedCanonical", "elided")
            val actT = transparentTree(actTree, map.spliceStructural, map.ignored, stats, "flattened", "ignored")
            compare(expT, actT, map.mappings, source, found, stats)
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
