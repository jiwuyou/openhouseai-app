package com.termux.app.openhouse.files.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.termux.app.openhouse.files.importing.OpenHouseInboxGrouping;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseFilesConfigStoreTest {

    private SharedPreferences prefs;
    private OpenHouseFilesConfigStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences(OpenHouseFilesConfigStore.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new OpenHouseFilesConfigStore(prefs);
    }

    @Test
    public void inboxGroupingDefaultsToMonth() {
        Assert.assertEquals(OpenHouseInboxGrouping.MONTH, store.getInboxGrouping());
    }

    @Test
    public void inboxGroupingPersists() {
        store.setInboxGrouping(OpenHouseInboxGrouping.DAY);

        OpenHouseFilesConfigStore reloaded = new OpenHouseFilesConfigStore(prefs);

        Assert.assertEquals(OpenHouseInboxGrouping.DAY, reloaded.getInboxGrouping());
    }

    @Test
    public void invalidInboxGroupingFallsBackToMonth() {
        prefs.edit().putString("inbox_grouping", "quarter").commit();

        Assert.assertEquals(OpenHouseInboxGrouping.MONTH, store.getInboxGrouping());

        store.setInboxGrouping(null);

        Assert.assertEquals(OpenHouseInboxGrouping.MONTH, store.getInboxGrouping());
    }

    @Test
    public void hiddenFilesDefaultToVisible() {
        Assert.assertTrue(store.shouldShowHiddenFiles());
    }

    @Test
    public void hiddenFileVisibilityPersists() {
        store.setShowHiddenFiles(false);

        OpenHouseFilesConfigStore reloaded = new OpenHouseFilesConfigStore(prefs);

        Assert.assertFalse(reloaded.shouldShowHiddenFiles());

        reloaded.setShowHiddenFiles(true);

        Assert.assertTrue(new OpenHouseFilesConfigStore(prefs).shouldShowHiddenFiles());
    }

    @Test
    public void webDavSummaryDoesNotExposePasswordOrQuerySecret() {
        String summary = OpenHouseFilesConfigStore.sanitizedWebDavSummary(
            "https://user:pass@example.com/dav/root/?token=abc123",
            "alice");

        Assert.assertTrue(summary.contains("https://example.com/dav/root/"));
        Assert.assertTrue(summary.contains("user=alice"));
        Assert.assertFalse(summary.contains("pass"));
        Assert.assertFalse(summary.contains("abc123"));
        Assert.assertFalse(summary.contains("token"));
    }

    @Test
    public void s3SummaryDoesNotExposeKeys() {
        OpenHouseFilesConfigStore.S3Record record = store.addS3(
            "Backups",
            "https://s3.example.com",
            "auto",
            "openhouse",
            "AKIA_TEST",
            "SECRET_TEST",
            "SESSION_TEST",
            true);

        String summary = record.sanitizedSummary();

        Assert.assertTrue(summary.contains("https://s3.example.com/"));
        Assert.assertTrue(summary.contains("bucket=openhouse"));
        Assert.assertTrue(summary.contains("region=auto"));
        Assert.assertFalse(summary.contains("AKIA_TEST"));
        Assert.assertFalse(summary.contains("SECRET_TEST"));
        Assert.assertFalse(summary.contains("SESSION_TEST"));
    }

    @Test
    public void recordsPersistAndStableIdsUpsert() {
        OpenHouseFilesConfigStore.WebDavRecord first = store.addWebDav(
            "DAV",
            "https://example.com/dav/",
            "alice",
            "one");
        OpenHouseFilesConfigStore.WebDavRecord second = store.addWebDav(
            "DAV updated",
            "https://example.com/dav/",
            "alice",
            "two");

        Assert.assertEquals(first.id, second.id);
        Assert.assertEquals(1, store.getWebDavRecords().size());
        Assert.assertEquals("DAV updated", store.getWebDavRecords().get(0).displayName);
        Assert.assertEquals("two", store.getWebDavRecords().get(0).password);
    }

    @Test
    public void webDavUserInfoIsMovedOutOfStoredUrl() {
        OpenHouseFilesConfigStore.WebDavRecord record = store.addWebDav(
            "DAV",
            "https://bob:secret@example.com/dav/?token=abc",
            "",
            "");

        Assert.assertEquals("https://example.com/dav/", record.baseUrl);
        Assert.assertEquals("bob", record.username);
        Assert.assertEquals("secret", record.password);
        Assert.assertFalse(record.sanitizedSummary().contains("secret"));
        Assert.assertFalse(record.sanitizedSummary().contains("token"));
    }

    @Test
    public void safContainerUsesTreeUriAsStableIdentity() {
        Uri tree = Uri.parse("content://com.example.documents/tree/primary%3ADocuments");

        OpenHouseFilesConfigStore.SafContainerRecord first = store.addSafContainer("Docs", tree);
        OpenHouseFilesConfigStore.SafContainerRecord second = store.addSafContainer("Docs renamed", tree);

        Assert.assertEquals(first.id, second.id);
        Assert.assertEquals(1, store.getSafContainers().size());
        Assert.assertEquals("Docs renamed", store.getSafContainers().get(0).displayName);
    }
}
