package com.newstar.live

/**
 * Pure ID-only policy for deduplicating pending LIVE invite alerts.
 * Keeping Android's org.json classes out of this policy makes its unit tests
 * runnable on the regular JVM without Android runtime stubs.
 */
internal object NativeLiveInviteAlertPolicy {
    fun unseenIds(inviteIds: List<String>, seenIds: Set<String>): Set<String> =
        inviteIds.asSequence()
            .filter { it.isNotBlank() && it !in seenIds }
            .toCollection(LinkedHashSet())

    fun mergeSeenIds(existing: Set<String>, newIds: List<String>, maxEntries: Int = 300): Set<String> {
        require(maxEntries in 1..1000)
        val merged = LinkedHashSet<String>()
        (existing.toList() + newIds).filter { it.isNotBlank() }.forEach(merged::add)
        while (merged.size > maxEntries) merged.remove(merged.first())
        return merged
    }

    fun encode(ids: Set<String>): String =
        org.json.JSONArray().also { array -> ids.forEach(array::put) }.toString()

    fun decode(encoded: String?): Set<String> {
        if (encoded.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = org.json.JSONArray(encoded)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }
}
