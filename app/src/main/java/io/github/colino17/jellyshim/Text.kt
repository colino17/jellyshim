package io.github.colino17.jellyshim

/** "21 MINUTES REMAINING", "1 HOUR 5 MINUTES REMAINING", "LESS THAN A MINUTE REMAINING". */
internal fun remainingText(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    fun unit(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"
    return when {
        totalMin < 1 -> "LESS THAN A MINUTE REMAINING"
        h == 0L -> "${unit(m, "MINUTE", "MINUTES")} REMAINING"
        m == 0L -> "${unit(h, "HOUR", "HOURS")} REMAINING"
        else -> "${unit(h, "HOUR", "HOURS")} ${unit(m, "MINUTE", "MINUTES")} REMAINING"
    }
}

/** 64-bit FNV-1a hash; used to detect changed programs (a 32-bit hash could collide). */
internal fun fnv1a64(s: String): Long {
    var h = -3750763034362895579L // FNV offset basis 14695981039346656037 as a signed Long
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 1099511628211L
    }
    return h
}
