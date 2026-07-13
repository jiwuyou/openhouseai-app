package com.termux.app.openhouse.release;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.openhouse.resources.OpenHouseBundledResourceDelivery;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
public class OpenHousePostUpdateSyncPolicyTest {

    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("openhouse_post_update_sync", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void firstInstallCompletionClearsInternalPendingAfterVerifiedStaging() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 42L,
            OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));
        AtomicBoolean cleared = new AtomicBoolean();

        assertTrue(OpenHousePostUpdateSync.completeFirstInstall(
            preferences, 42L, () -> {
                cleared.set(true);
                return true;
            }));

        assertTrue(cleared.get());
        assertTrue(OpenHousePostUpdateSync.isVersionSynced(preferences, 42L));
        assertTrue(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 0L));
        assertEquals(0L, preferences.getLong("pending_version_code", 0L));
        assertFalse(preferences.contains("pending_reason"));
    }

    @Test
    public void firstInstallCompletionRemainsPendingWhenMarkerCannotBeCleared() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 42L,
            OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));

        assertFalse(OpenHousePostUpdateSync.completeFirstInstall(
            preferences, 42L, () -> false));

        assertFalse(OpenHousePostUpdateSync.isVersionSynced(preferences, 42L));
        assertFalse(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 0L));
        assertEquals(42L, preferences.getLong("pending_version_code", 0L));
        assertEquals("FIRST_INSTALL", preferences.getString("pending_reason", ""));
    }

    @Test
    public void firstInstallPendingNeverMakesUpdateDeliveryEligible() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 43L,
            OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));

        assertFalse(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 0L));
        assertFalse(OpenHousePostUpdateSync.isVersionSynced(preferences, 43L));
        assertEquals(43L, preferences.getLong("pending_version_code", 0L));
        assertEquals("FIRST_INSTALL", preferences.getString("pending_reason", ""));
    }

    @Test
    public void apkUpdateSuccessKeepsPendingUntilAiMarkerDisappears() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 44L,
            OpenHouseBundledResourceDelivery.Reason.APK_UPDATE));

        assertTrue(OpenHousePostUpdateSync.finalizeSuccessfulDelivery(
            preferences, 44L, OpenHouseBundledResourceDelivery.Reason.APK_UPDATE));

        assertTrue(OpenHousePostUpdateSync.isVersionSynced(preferences, 44L));
        assertEquals(44L, preferences.getLong("pending_version_code", 0L));
        OpenHousePostUpdateSync.clearInternalPendingWhenMarkerGone(
            preferences, 44L, true, true);
        assertEquals(44L, preferences.getLong("pending_version_code", 0L));
        OpenHousePostUpdateSync.clearInternalPendingWhenMarkerGone(
            preferences, 44L, true, false);
        assertEquals(0L, preferences.getLong("pending_version_code", 0L));
    }

    @Test
    public void firstInstallStagingDoesNotMarkInstallationComplete() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 47L,
            OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));

        assertTrue(OpenHousePostUpdateSync.finalizeSuccessfulDelivery(
            preferences, 47L, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));

        assertFalse(OpenHousePostUpdateSync.isVersionSynced(preferences, 47L));
        assertFalse(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 0L));
        assertEquals(47L, preferences.getLong("pending_version_code", 0L));
        assertEquals("FIRST_INSTALL", preferences.getString("pending_reason", ""));
    }

    @Test
    public void pendingReasonIsStableForCurrentVersionAndChangesForNewApk() {
        assertTrue(OpenHousePostUpdateSync.recordPending(preferences, 45L,
            OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));
        assertEquals(OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL,
            OpenHousePostUpdateSync.pendingReason(preferences, 45L, 0L, false));
        assertEquals(OpenHouseBundledResourceDelivery.Reason.APK_UPDATE,
            OpenHousePostUpdateSync.pendingReason(preferences, 46L, 45L, true));
    }

    @Test
    public void priorSyncedVersionMakesOnlyTheSubsequentApkEligible() {
        assertFalse(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 0L));
        assertTrue(OpenHousePostUpdateSync.isFirstInstallCompleted(preferences, 46L));
    }
}
