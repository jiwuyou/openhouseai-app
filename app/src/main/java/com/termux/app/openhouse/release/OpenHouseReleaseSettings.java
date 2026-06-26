package com.termux.app.openhouse.release;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class OpenHouseReleaseSettings {

    private static final String PREFS_NAME = "openhouse_release_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CHANNEL = "channel";
    private static final String DEFAULT_CHANNEL = "stable";
    private static final String DEFAULT_MANIFEST_FILE = "manifest.json";
    private static final String SETTINGS_KIND = "openhouseai.settings";
    private static final String[] RELEASE_ROOT_LEAF_NAMES = new String[] {"apk-release", "releases"};

    private OpenHouseReleaseSettings() {
    }

    public static String getServerUrl(Context context) {
        String fromFile = readServerUrlFromSettingsFiles();
        if (!fromFile.isEmpty()) {
            return fromFile;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context context, String serverUrl) {
        String normalizedServerUrl = normalizeServerUrl(serverUrl);
        String channel = getChannel(context);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normalizedServerUrl)
            .putString(KEY_CHANNEL, channel)
            .apply();
        writeReleaseSettingsMirrors(normalizedServerUrl, channel);
    }

    public static void clearServerUrl(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SERVER_URL)
            .apply();
        writeReleaseSettingsMirrors("", DEFAULT_CHANNEL);
    }

    public static String getChannel(Context context) {
        String fromFile = readChannelFromSettingsFiles();
        if (!fromFile.isEmpty()) {
            return fromFile;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeChannel(preferences.getString(KEY_CHANNEL, DEFAULT_CHANNEL));
    }

    public static boolean isValidServerUrl(String serverUrl) {
        try {
            URL url = new URL(normalizeServerUrl(serverUrl));
            return "http".equalsIgnoreCase(url.getProtocol())
                || "https".equalsIgnoreCase(url.getProtocol());
        } catch (MalformedURLException e) {
            return false;
        }
    }

    public static String resolveManifestUrl(String serverUrl) throws OpenHouseReleaseException {
        List<String> candidates = resolveManifestUrls(serverUrl, DEFAULT_CHANNEL);
        if (candidates.isEmpty()) {
            throw new OpenHouseReleaseException("发布服务器 URL 无效");
        }
        return candidates.get(0);
    }

    public static List<String> resolveManifestUrls(String serverUrl, String channel) throws OpenHouseReleaseException {
        String normalizedServerUrl = normalizeServerUrl(serverUrl);
        if (!isValidServerUrl(normalizedServerUrl)) {
            throw new OpenHouseReleaseException("请输入 http 或 https 发布服务器 URL");
        }

        try {
            URL url = new URL(normalizedServerUrl);
            String path = url.getPath();
            if (path != null && path.toLowerCase(java.util.Locale.US).endsWith(".json")) {
                List<String> manifestUrls = new ArrayList<>();
                manifestUrls.add(url.toString());
                return manifestUrls;
            }
            String base = url.toString();
            if (!base.endsWith("/")) {
                base = base + "/";
            }

            String normalizedChannel = normalizeChannel(channel);
            String encodedChannel = URLEncoder.encode(normalizedChannel, "UTF-8");
            String baseLeaf = basename(path);
            boolean releaseRoot = isReleaseRoot(baseLeaf);
            String[] relativePaths = releaseRoot
                ? new String[] {
                    encodedChannel + "/" + DEFAULT_MANIFEST_FILE,
                    "manifest-" + encodedChannel + ".json",
                    DEFAULT_MANIFEST_FILE,
                    "release-manifest.json"
                }
                : new String[] {
                    "apk-release/" + encodedChannel + "/" + DEFAULT_MANIFEST_FILE,
                    "releases/" + encodedChannel + "/" + DEFAULT_MANIFEST_FILE,
                    encodedChannel + "/" + DEFAULT_MANIFEST_FILE,
                    "manifest-" + encodedChannel + ".json",
                    DEFAULT_MANIFEST_FILE,
                    "release-manifest.json"
                };

            Set<String> manifestUrls = new LinkedHashSet<>();
            for (String relativePath : relativePaths) {
                URL candidate = new URL(base + relativePath);
                String candidateText = candidate.toString();
                if (relativePath.endsWith(DEFAULT_MANIFEST_FILE) && !relativePath.contains(encodedChannel + "/")) {
                    candidateText = appendChannelQuery(candidateText, normalizedChannel);
                }
                manifestUrls.add(candidateText);
            }
            return new ArrayList<>(manifestUrls);
        } catch (MalformedURLException e) {
            throw new OpenHouseReleaseException("发布服务器 URL 无效", e);
        } catch (IOException e) {
            throw new OpenHouseReleaseException("发布 channel 无法编码", e);
        }
    }

    public static String normalizeServerUrl(String serverUrl) {
        return serverUrl == null ? "" : serverUrl.trim();
    }

    private static String normalizeChannel(String channel) {
        String normalized = channel == null ? "" : channel.trim();
        if (normalized.isEmpty()) {
            return DEFAULT_CHANNEL;
        }
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return DEFAULT_CHANNEL;
        }
        return normalized;
    }

    private static String readServerUrlFromSettingsFiles() {
        for (File settingsFile : settingsMirrorFiles()) {
            JSONObject document = readSettingsDocument(settingsFile);
            if (document == null) {
                continue;
            }
            String value = firstNonBlank(
                document.optString("releaseServerBaseUrl", ""),
                document.optString("release_server_base_url", ""),
                document.optString("baseUrl", ""),
                document.optString("base_url", ""),
                document.optString("url", "")
            );
            JSONObject nested = document.optJSONObject("apkRelease");
            if (value.isEmpty() && nested != null) {
                value = firstNonBlank(
                    nested.optString("releaseServerBaseUrl", ""),
                    nested.optString("release_server_base_url", ""),
                    nested.optString("baseUrl", ""),
                    nested.optString("base_url", ""),
                    nested.optString("url", "")
                );
            }
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String readChannelFromSettingsFiles() {
        for (File settingsFile : settingsMirrorFiles()) {
            JSONObject document = readSettingsDocument(settingsFile);
            if (document == null) {
                continue;
            }
            String value = firstNonBlank(
                document.optString("channel", ""),
                document.optString("releaseChannel", ""),
                document.optString("release_channel", "")
            );
            JSONObject nested = document.optJSONObject("apkRelease");
            if (value.isEmpty() && nested != null) {
                value = firstNonBlank(
                    nested.optString("channel", ""),
                    nested.optString("releaseChannel", ""),
                    nested.optString("release_channel", "")
                );
            }
            if (!value.isEmpty()) {
                return normalizeChannel(value);
            }
        }
        return "";
    }

    private static void writeReleaseSettingsMirrors(String serverUrl, String channel) {
        for (File settingsFile : settingsMirrorFiles()) {
            writeReleaseSettings(settingsFile, serverUrl, channel);
        }
    }

    private static File[] settingsMirrorFiles() {
        return new File[] {
            new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                "var/lib/proot-distro/containers/ubuntu/rootfs/root/smallphoneai-repos/smallphone-home/openhouseai-settings.json"),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH,
                "smallphoneai-repos/smallphone-home/openhouseai-settings.json"),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH,
                ".config/openhouseai/settings.json"),
        };
    }

    private static JSONObject readSettingsDocument(File settingsFile) {
        if (settingsFile == null || !settingsFile.isFile()) {
            return null;
        }
        try (FileInputStream inputStream = new FileInputStream(settingsFile)) {
            byte[] data = new byte[(int) settingsFile.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = inputStream.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new JSONObject(new String(data, 0, offset, StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    private static void writeReleaseSettings(File settingsFile, String serverUrl, String channel) {
        if (settingsFile == null) {
            return;
        }
        JSONObject document = readSettingsDocument(settingsFile);
        if (document == null) {
            document = new JSONObject();
        }
        try {
            document.put("schemaVersion", document.optInt("schemaVersion", 1));
            document.put("kind", firstNonBlank(document.optString("kind", ""), SETTINGS_KIND));
            document.put("releaseServerBaseUrl", serverUrl);
            document.put("channel", normalizeChannel(channel));
            document.put("updatedAt", utcNow());

            JSONObject nested = document.optJSONObject("apkRelease");
            if (nested == null) {
                nested = new JSONObject();
            }
            nested.put("releaseServerBaseUrl", serverUrl);
            nested.put("channel", normalizeChannel(channel));
            nested.put("updatedAt", document.optString("updatedAt", ""));
            document.put("apkRelease", nested);

            File parent = settingsFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            if (parent == null) {
                return;
            }
            File tempFile = new File(parent, "." + settingsFile.getName() + ".tmp");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                outputStream.write((document.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!tempFile.renameTo(settingsFile)) {
                try (FileOutputStream outputStream = new FileOutputStream(settingsFile)) {
                    outputStream.write((document.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        } catch (IOException | JSONException ignored) {
            // Settings mirrors are best-effort; SharedPreferences still keeps the UI usable.
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeServerUrl(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static boolean isReleaseRoot(String leafName) {
        for (String releaseRootLeafName : RELEASE_ROOT_LEAF_NAMES) {
            if (releaseRootLeafName.equals(leafName)) {
                return true;
            }
        }
        return false;
    }

    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return "";
        }
        int slashIndex = trimmed.lastIndexOf('/');
        return slashIndex >= 0 ? trimmed.substring(slashIndex + 1) : trimmed;
    }

    private static String appendChannelQuery(String url, String channel) throws IOException {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "channel=" + URLEncoder.encode(normalizeChannel(channel), "UTF-8");
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
