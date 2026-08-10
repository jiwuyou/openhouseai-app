package com.ai.assistance.operit.rescue.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkResourceOfferContractTest {
    @Test
    fun successfulOfferCompletionRequiresVerificationDetail() {
        assertEquals(
            "installed set sequence=2026080901 verified",
            normalizedApkResourceOfferCompletionDetail(
                ApkResourceOfferStatus.SATISFIED,
                " installed set sequence=2026080901 verified ",
            ),
        )
        assertEquals(
            "downloaded set is newer",
            normalizedApkResourceOfferCompletionDetail(
                ApkResourceOfferStatus.SUPERSEDED,
                "downloaded set is newer",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizedApkResourceOfferCompletionDetail(ApkResourceOfferStatus.SATISFIED, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizedApkResourceOfferCompletionDetail(ApkResourceOfferStatus.PENDING, "not allowed")
        }
    }

    @Test
    fun failureAndDismissalRetainReminderDetailsWithoutClaimingSuccess() {
        assertEquals(
            "network unavailable",
            normalizedApkResourceOfferCompletionDetail(
                ApkResourceOfferStatus.FAILED,
                "network unavailable",
            ),
        )
        assertEquals(
            "",
            normalizedApkResourceOfferCompletionDetail(ApkResourceOfferStatus.DISMISSED, ""),
        )
    }
}
