package com.desmond.ofd.backend

/**
 * The firmware backends, declared in precedence order.
 *
 * Order is load-bearing: when two sources report the same version the earlier one wins, so the
 * mirror is chosen only when it alone has something newer. An enum rather than string constants
 * because these values double as identity ("which one won?") and previously had no compile-time
 * link to the labels displayed for them — a typo silently broke the winner highlight.
 */
enum class BackendId {
    REALME_OTA,
    DANIELSPRINGER,
    MIRROR,
}

/**
 * Choose the winning backend from [candidates] — pairs of backend and the version it reported,
 * given in precedence order. Newest version wins; a tie falls to the earlier entry.
 *
 * The tiebreak does real work rather than being a formality: the sources usually agree, so most
 * checks *are* ties, and precedence is what decides them.
 */
fun pickWinner(candidates: List<Pair<BackendId, String>>): BackendId? {
    if (candidates.isEmpty()) return null
    return candidates
        .sortedBy { it.first.ordinal }
        .reduce { acc, candidate ->
            if (VersionResolver.compare(candidate.second, acc.second) > 0) candidate else acc
        }
        .first
}
