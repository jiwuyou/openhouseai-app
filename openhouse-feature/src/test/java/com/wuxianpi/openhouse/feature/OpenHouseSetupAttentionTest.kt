package com.wuxianpi.openhouse.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenHouseSetupAttentionTest {
    @Test
    fun mapsPendingFirstInstallAndApkUpdateOffers() {
        assertEquals(
            OpenHouseSetupAttention.FIRST_INSTALL,
            OpenHouseSetupAttention.fromResourceOffer("first-install", requiresReminder = true),
        )
        assertEquals(
            OpenHouseSetupAttention.RESOURCE_UPDATE,
            OpenHouseSetupAttention.fromResourceOffer("apk-update", requiresReminder = true),
        )
    }

    @Test
    fun mapsUnknownPendingOfferToGenericRepairEntry() {
        assertEquals(
            OpenHouseSetupAttention.GENERIC,
            OpenHouseSetupAttention.fromResourceOffer("future-reason", requiresReminder = true),
        )
    }

    @Test
    fun hidesOffersThatNoLongerRequireReminder() {
        assertNull(
            OpenHouseSetupAttention.fromResourceOffer("first-install", requiresReminder = false),
        )
        assertNull(
            OpenHouseSetupAttention.fromResourceOffer("apk-update", requiresReminder = false),
        )
    }
}
