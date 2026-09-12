package dev.qutivex.core.dependency

import java.math.BigInteger
import java.util.Locale

/**
 * Generic implementation of Maven-compatible version comparison.
 * Compares versions using numeric tokens and standard qualifier precedence.
 */
class ComparableVersion(val value: String) : Comparable<ComparableVersion> {

    internal sealed interface Item : Comparable<Item> {
        val isZero: Boolean get() = false
    }

    private data class IntegerItem(val value: BigInteger) : Item {
        override val isZero: Boolean get() = value == BigInteger.ZERO

        override fun compareTo(other: Item): Int = when (other) {
            is IntegerItem -> value.compareTo(other.value)
            is StringItem -> 1 // integers are always greater than string qualifiers (e.g. 1.0 > 1.0-alpha)
        }

        override fun toString(): String = value.toString()
    }

    private data class StringItem(val value: String) : Item {
        private val qualifierRank: Int = rankQualifier(value)

        override fun compareTo(other: Item): Int = when (other) {
            is IntegerItem -> -1 // qualifiers are lower than integers
            is StringItem -> {
                if (qualifierRank != 0 || other.qualifierRank != 0) {
                    qualifierRank.compareTo(other.qualifierRank)
                } else {
                    value.compareTo(other.value, ignoreCase = true)
                }
            }
        }

        override fun toString(): String = value

        companion object {
            private fun rankQualifier(qualifier: String): Int {
                return when (qualifier.lowercase(Locale.ROOT)) {
                    "alpha", "a" -> -5
                    "beta", "b" -> -4
                    "milestone", "m" -> -3
                    "rc", "cr" -> -2
                    "snapshot" -> -1
                    "", "final", "ga", "release" -> 0
                    "sp" -> 1
                    else -> 0
                }
            }
        }
    }

    private val items: List<Item> = parse(value)

    override fun compareTo(other: ComparableVersion): Int {
        val maxLen = maxOf(items.size, other.items.size)
        var i = 0
        while (i < maxLen) {
            val thisItem = items.getOrNull(i)
            val otherItem = other.items.getOrNull(i)

            if (thisItem == null && otherItem == null) return 0

            if (thisItem == null) {
                if (otherItem is IntegerItem && otherItem.isZero) {
                    i++
                    continue
                }
                return if (otherItem is StringItem) {
                    StringItem("").compareTo(otherItem)
                } else {
                    -1
                }
            }
            if (otherItem == null) {
                if (thisItem is IntegerItem && thisItem.isZero) {
                    i++
                    continue
                }
                return if (thisItem is StringItem) {
                    thisItem.compareTo(StringItem(""))
                } else {
                    1
                }
            }

            val cmp = thisItem.compareTo(otherItem)
            if (cmp != 0) return cmp
            i++
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComparableVersion) return false
        return compareTo(other) == 0
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        internal fun parse(version: String): List<Item> {
            val list = mutableListOf<Item>()
            var isDigit = false
            var start = 0
            var i = 0

            fun flush(end: Int) {
                if (start < end) {
                    val token = version.substring(start, end)
                    if (isDigit) {
                        try {
                            list.add(IntegerItem(BigInteger(token)))
                        } catch (_: Exception) {
                            list.add(StringItem(token))
                        }
                    } else {
                        list.add(StringItem(token))
                    }
                }
            }

            while (i < version.length) {
                val c = version[i]
                if (c == '.' || c == '-' || c == '_') {
                    flush(i)
                    i++
                    start = i
                    isDigit = i < version.length && version[i].isDigit()
                } else if (c.isDigit()) {
                    if (!isDigit && i > start) {
                        flush(i)
                        start = i
                    }
                    isDigit = true
                    i++
                } else {
                    if (isDigit && i > start) {
                        flush(i)
                        start = i
                    }
                    isDigit = false
                    i++
                }
            }
            flush(version.length)
            return list
        }
    }
}
