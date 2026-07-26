package com.desmond.ofd.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Precedence is realme-ota, then danielspringer, then the mirror. The sources usually agree, so
 * most real checks are ties and the tiebreak is what actually decides the winner.
 */
class BackendPriorityTest {

    @Test fun three_way_tie_goes_to_realme_ota() {
        val v = "PLK110_16.0.9.400(CN01)"
        assertEquals(
            BackendId.REALME_OTA,
            pickWinner(
                listOf(
                    BackendId.REALME_OTA to v,
                    BackendId.DANIELSPRINGER to v,
                    BackendId.MIRROR to v,
                ),
            ),
        )
    }

    @Test fun tie_between_danielspringer_and_mirror_goes_to_danielspringer() {
        val v = "PLK110_16.0.9.400(CN01)"
        assertEquals(
            BackendId.DANIELSPRINGER,
            pickWinner(listOf(BackendId.DANIELSPRINGER to v, BackendId.MIRROR to v)),
        )
    }

    @Test fun mirror_wins_only_when_strictly_newest() {
        val old = "PLK110_16.0.9.400(CN01)"
        val new = "PLK110_16.0.9.401(CN01)"
        assertEquals(
            BackendId.MIRROR,
            pickWinner(
                listOf(
                    BackendId.REALME_OTA to old,
                    BackendId.DANIELSPRINGER to old,
                    BackendId.MIRROR to new,
                ),
            ),
        )
    }

    @Test fun when_realme_ota_lags_the_tie_between_the_other_two_goes_to_danielspringer() {
        // The common real case once realme-ota has not yet published: danielspringer and the
        // mirror agree on a newer build. Both beat realme-ota, and between themselves precedence
        // applies — so the download comes from danielspringer, not the mirror.
        val old = "PLK110_16.0.9.400(CN01)"
        val new = "PLK110_16.0.9.401(CN01)"
        assertEquals(
            BackendId.DANIELSPRINGER,
            pickWinner(
                listOf(
                    BackendId.REALME_OTA to old,
                    BackendId.DANIELSPRINGER to new,
                    BackendId.MIRROR to new,
                ),
            ),
        )
    }

    @Test fun newest_beats_precedence() {
        assertEquals(
            BackendId.DANIELSPRINGER,
            pickWinner(
                listOf(
                    BackendId.REALME_OTA to "PLK110_16.0.9.400(CN01)",
                    BackendId.DANIELSPRINGER to "PLK110_16.0.9.401(CN01)",
                ),
            ),
        )
    }

    @Test fun input_order_does_not_change_the_outcome() {
        // Callers build this list from a map, so iteration order must not decide the winner.
        val v = "PLK110_16.0.9.400(CN01)"
        val scrambled = listOf(
            BackendId.MIRROR to v,
            BackendId.DANIELSPRINGER to v,
            BackendId.REALME_OTA to v,
        )
        assertEquals(BackendId.REALME_OTA, pickWinner(scrambled))
    }

    @Test fun both_unparseable_falls_to_precedence() {
        // Two versions we cannot rank must not be decided lexicographically — that would let the
        // mirror outrank realme-ota for reasons unrelated to recency.
        assertEquals(
            BackendId.REALME_OTA,
            pickWinner(
                listOf(BackendId.REALME_OTA to "zzz_unparseable", BackendId.MIRROR to "aaa_unparseable"),
            ),
        )
    }

    @Test fun parseable_beats_unparseable_regardless_of_precedence() {
        assertEquals(
            BackendId.MIRROR,
            pickWinner(
                listOf(
                    BackendId.REALME_OTA to "NE2213_11_C.26",
                    BackendId.MIRROR to "PLK110_16.0.9.400(CN01)",
                ),
            ),
        )
    }

    @Test fun no_candidates_has_no_winner() {
        assertNull(pickWinner(emptyList()))
    }

    @Test fun single_candidate_wins() {
        assertEquals(
            BackendId.MIRROR,
            pickWinner(listOf(BackendId.MIRROR to "PLK110_16.0.9.400(CN01)")),
        )
    }

    @Test fun declared_order_is_the_precedence_order() {
        // pickWinner sorts by ordinal, so the declaration order in the enum *is* the policy.
        assertEquals(
            listOf(BackendId.REALME_OTA, BackendId.DANIELSPRINGER, BackendId.MIRROR),
            BackendId.entries.toList(),
        )
    }
}
