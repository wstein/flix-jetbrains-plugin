package dev.wstein.flixplugin.debugger

import com.sun.jdi.ObjectReference
import com.sun.jdi.Value

/**
 * The Flix collections that are readable only if their shape is undone.
 *
 * A `List` is a chain of `Cons` cells, and a `Map` or `Set` is a red-black tree — so the variables
 * view showed `Cons(-96.0, Obj)` and `Map(Node)`, and expanding either walked the *implementation*:
 * one node per element, named after the field that held the rest. The value is the sequence, and the
 * tree is how the standard library stores it.
 *
 * ## What identifies one
 *
 * The case name the compiler records under `--Xdebug`, never the shape. Every case with the same
 * erased arity shares one class, so `Cons` and any user-defined pair are the same class, and so are
 * a tree node and any five-term case. The recorded name carries the enum -- `List.Cons`,
 * `RedBlackTree.Node` -- which is the only thing that distinguishes them.
 *
 * The consequence is stated rather than hidden: **without `--Xdebug` these render as the tags they
 * are**, which is what they did before this existed. Nothing here guesses.
 *
 * ## What it depends on
 *
 * These are the standard library's own types, not the compiler's calling convention, so the field
 * order is the library's to change: `Node` is `(colour, left, key, value, right)`, measured. A
 * change there degrades this to the previous rendering rather than breaking it -- every walk stops
 * at a node it does not recognise.
 */
internal object FlixCollections {

    /** `RedBlackTree.Node`, the interior of every `Map` and `Set`. */
    private const val TREE_NODE = "RedBlackTree.Node"

    /** `Map.Map` and `Set.Set`, each a single-term tag over a tree. */
    private const val MAP = "Map.Map"
    private const val SET = "Set.Set"

    /** How many nodes to visit before giving up on a tree that cannot be walked. */
    private const val MAX_NODES = 4096

    /** Whether `value` is a map. */
    fun isMap(value: ObjectReference): Boolean = caseOf(value) == MAP

    /** Whether `value` is a set. */
    fun isSet(value: ObjectReference): Boolean = caseOf(value) == SET

    /**
     * The entries of a map or set, in key order, and whether the walk was cut short.
     *
     * A set's entries carry the unit value the library stores beside each element; the caller drops
     * it. Both are the same tree, which is why one walk serves both.
     */
    fun entries(value: ObjectReference, limit: Int): Pair<List<Pair<Value?, Value?>>, Boolean> {
        val tree = value.readField("v0") as? ObjectReference ?: return emptyList<Pair<Value?, Value?>>() to false
        return walk(tree, limit)
    }

    /**
     * An in-order walk of the tree at `root`.
     *
     * Iterative, with a budget. A red-black tree is O(log n) deep and recursion would be safe on
     * every tree the library builds -- but this reads whatever the debuggee happens to hold, on the
     * debugger thread, while the UI waits for it. A corrupt or half-built structure is a stack
     * overflow or a hang, and neither is worth the shorter code.
     */
    private fun walk(root: ObjectReference, limit: Int): Pair<List<Pair<Value?, Value?>>, Boolean> {
        val entries = mutableListOf<Pair<Value?, Value?>>()
        val pending = ArrayDeque<ObjectReference>()
        val seen = mutableSetOf<Long>()
        var node: ObjectReference? = root
        var visited = 0

        while ((node != null || pending.isNotEmpty()) && entries.size < limit) {
            while (node != null && caseOf(node) == TREE_NODE) {
                if (visited++ > MAX_NODES || !seen.add(node.uniqueID())) return entries to true
                pending.addLast(node)
                node = node.readField("v1") as? ObjectReference
            }
            node = null
            val current = pending.removeLastOrNull() ?: break
            entries += current.readField("v2") to current.readField("v3")
            node = current.readField("v4") as? ObjectReference
        }
        // More is left when a subtree is still pending or the walk stopped at the limit.
        return entries to (entries.size >= limit && (node != null || pending.isNotEmpty()))
    }

    /** The case `value` is, qualified by its enum and with the specialisation hash removed. */
    private fun caseOf(value: ObjectReference): String? =
        FlixValues.tagOf(value.referenceType().name(), recordedTagOf(value))
}
