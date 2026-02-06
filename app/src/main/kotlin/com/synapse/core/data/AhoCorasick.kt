package com.synapse.core.data

/**
 * Represents a single state (node) in the Aho-Corasick Trie.
 *
 * This node holds the transition table for the automaton, failure links for backtracking,
 * and any pattern matches that conclude at this specific state.
 *
 * @param T The type of the elements in the sequences.
 */
class Node<T> {
    /**
     * A map of outgoing edges from this node, keyed by the input element.
     * Represents valid forward transitions in the Trie.
     */
    val children = HashMap<T, Node<T>>()

    /**
     * The failure link points to the node representing the longest proper suffix of the string
     * represented by the current node that is also a prefix of some pattern in the dictionary.
     *
     * Used to transition state without resetting to the root when a mismatch occurs.
     */
    var failure: Node<T>? = null

    /**
     * A list of complete sequences (patterns) that end exactly at this node.
     * This includes patterns that end here directly, as well as patterns inherited via failure links.
     */
    val matches = ArrayList<List<T>>()
}

/**
 * A finite automaton (state machine) designed for efficient multi-pattern matching.
 *
 * This class encapsulates the logic for traversing the Trie constructed by [buildAutomaton].
 * It handles state transitions, automatically following failure links when a direct transition
 * is not available for a given input.
 *
 * @param T The type of the elements in the sequences.
 * @property root The starting state of the automaton.
 */
class Automaton<T> internal constructor(val root: Node<T>) {

    /**
     * Computes the next state of the automaton based on the current state and the input value.
     *
     * If the [current] node has a direct child for [value], the automaton transitions to that child.
     * Otherwise, it traverses [Node.failure] links until a match is found or the root is reached.
     *
     * @param current The current state (node) of the automaton.
     * @param value The next input element from the stream.
     * @return The new state (node) after processing the value.
     */
    fun next(current: Node<T>, value: T): Node<T> {
        var node = current
        while (node !== root && !node.children.containsKey(value)) {
            node = node.failure ?: root
        }
        return node.children[value] ?: root
    }
}

/**
 * Constructs an Aho-Corasick automaton from a collection of sequences.
 *
 * This function builds a Trie from the provided [sequences] and then computes failure links
 * using a Breadth-First Search (BFS). The resulting [Automaton] allows for finding all occurrences
 * of all input sequences in a data stream in linear time relative to the stream length.
 *
 * **Algorithm Steps:**
 * 1. **Trie Construction:** Inserts all sequences into a standard prefix tree.
 * 2. **Failure Link Construction:** Iterates through the Trie level-by-level to establish failure links,
 *    ensuring that if a match fails at a specific character, the machine falls back to the longest
 *    possible matching suffix rather than restarting from scratch.
 *
 * @param T The type of the elements in the sequences.
 * @param sequences An iterable of sequences (patterns) to be detected by the automaton.
 * @return An initialized [Automaton] ready for processing inputs.
 */
fun <T> buildAutomaton(sequences: Iterable<Iterable<T>>): Automaton<T> {
    val root = Node<T>()

    for (seq in sequences) {
        var node = root
        for (item in seq) {
            node = node.children.getOrPut(item) { Node() }
        }
        node.matches.add(seq.toList())
    }

    val queue = ArrayDeque<Node<T>>()
    root.children.values.forEach {
        it.failure = root
        queue.add(it)
    }

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        current.children.forEach { (key, child) ->
            var fail = current.failure
            while (fail != null && !fail.children.containsKey(key)) {
                fail = fail.failure
            }
            child.failure = fail?.children?.get(key) ?: root
            child.failure?.let { child.matches.addAll(it.matches) }
            queue.add(child)
        }
    }
    return Automaton(root)
}