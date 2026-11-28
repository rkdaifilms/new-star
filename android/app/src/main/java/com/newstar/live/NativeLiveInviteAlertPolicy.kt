package com.newstar.live

import org.json.JSONObject

/**
 * Deduplicates pending LIVE invite alerts per signed-in account.
 * The persisted set is deliberately bounded so it cannot grow indefinitely.
 */
internal object NativeLiveInviteAlertPolicy {
    fun unseen(invitations: List<JSONObject>, seenIds: Set<String>): List<JSONObject> =
        invitations.filter { invite ->
            val id = invite.optString("id")
            id.isNotBlank() && id !in seenIds
        }

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
