package com.gamelingo.mobile.core.update

/**
 * Compares dotted numeric versions such as "1.10.0" and "1.9.9".
 *
 * A leading "v" is allowed and non-numeric suffixes on a segment are ignored,
 * so "v1.4.0-beta" is treated as 1.4.0. Missing segments count as zero, which
 * makes "1.4" equal to "1.4.0".
 */
object VersionComparator {

    fun compare(versionA: String, versionB: String): Int {
        val a = parse(versionA)
        val b = parse(versionB)
        val segmentCount = maxOf(a.size, b.size)
        for (i in 0 until segmentCount) {
            val segmentA = a.getOrElse(i) { 0 }
            val segmentB = b.getOrElse(i) { 0 }
            if (segmentA != segmentB) return segmentA.compareTo(segmentB)
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    private fun parse(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
