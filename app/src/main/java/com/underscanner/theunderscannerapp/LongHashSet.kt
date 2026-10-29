package com.underscanner.theunderscannerapp

/**
 * Open-addressing set of primitive longs (linear probing, power-of-two table), used for the
 * live preview's voxel dedup. A `HashSet<Long>` costs ~60 B per entry (boxed key + node);
 * this costs 8 B per slot, which is what makes a multi-million-voxel preview affordable.
 *
 * [EMPTY] marks a free slot and therefore can't be stored — voxel keys use 63 bits and
 * never produce it. Not thread-safe (single producer, like [PreviewCloud]).
 */
class LongHashSet(expected: Int = 1 shl 16) {
    private var table = newTable(tableSizeFor(expected))
    private var mask = table.size - 1

    var size = 0
        private set

    /** Adds [key]; returns true if it was not already present. */
    fun add(key: Long): Boolean {
        if (size >= (table.size * MAX_LOAD).toInt()) grow()
        var i = mix(key) and mask
        while (true) {
            val k = table[i]
            if (k == EMPTY) {
                table[i] = key
                size++
                return true
            }
            if (k == key) return false
            i = (i + 1) and mask
        }
    }

    /** Empties the set, keeping the current table (no reallocation). */
    fun clear() {
        table.fill(EMPTY)
        size = 0
    }

    private fun grow() {
        val old = table
        table = newTable(old.size * 2)
        mask = table.size - 1
        size = 0
        for (k in old) if (k != EMPTY) add(k)
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE
        private const val MAX_LOAD = 0.75f

        private fun newTable(n: Int) = LongArray(n).also { it.fill(EMPTY) }

        private fun tableSizeFor(expected: Int): Int {
            val needed = (expected / MAX_LOAD).toInt().coerceAtLeast(16)
            return Integer.highestOneBit(needed - 1) shl 1
        }

        /** Fibonacci hashing: spreads the packed voxel bits over the low (masked) bits. */
        private fun mix(k: Long): Int {
            val h = k * -0x61c8864680b583ebL
            return (h xor (h ushr 32)).toInt()
        }
    }
}
