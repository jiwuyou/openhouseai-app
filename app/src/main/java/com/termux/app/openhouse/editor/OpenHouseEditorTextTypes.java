package com.termux.app.openhouse.editor;

import java.net.URLConnection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class OpenHouseEditorTextTypes {

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
        "txt", "text", "md", "markdown", "mkd", "json", "jsonl", "xml", "html", "htm",
        "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt", "kts", "gradle",
        "properties", "ini", "conf", "config", "cfg", "yaml", "yml", "toml", "env",
        "sh", "bash", "zsh", "fish", "py", "rb", "go", "rs", "c", "h", "cpp", "cc",
        "cxx", "hpp", "cs", "php", "swift", "sql", "lua", "pl", "r", "dart", "vue",
        "svelte", "csv", "tsv", "log", "gitignore", "dockerfile", "makefile"
    ));

    private static final Set<String> TEXT_FILE_NAMES = new HashSet<>(Arrays.asList(
        "dockerfile", "makefile", "rakefile", "gemfile", "podfile", "gradlew", ".gitignore",
        ".env", ".profile", ".bashrc", ".zshrc", ".npmrc", ".yarnrc"
    ));

    private OpenHouseEditorTextTypes() {
    }

    public static boolean isMarkdown(String displayName, String mimeType) {
        String normalizedMimeType = normalizeMimeType(mimeType);
        if ("text/markdown".equals(normalizedMimeType) || "text/x-markdown".equals(normalizedMimeType)) {
            return true;
        }
        String extension = extensionOf(displayName);
        return "md".equals(extension) || "markdown".equals(extension) || "mkd".equals(extension);
    }

    public static boolean isLikelyText(String displayName, String mimeType) {
        String normalizedMimeType = normalizeMimeType(mimeType);
        if (normalizedMimeType.startsWith("text/")) {
            return true;
        }
        if (normalizedMimeType.startsWith("application/")) {
            String subtype = normalizedMimeType.substring("application/".length());
            if (subtype.contains("json") || subtype.contains("xml") || subtype.contains("yaml")
                || subtype.contains("javascript") || subtype.contains("toml")
                || subtype.equals("x-sh") || subtype.equals("sql")) {
                return true;
            }
        }
        String normalizedName = normalizeName(displayName);
        if (TEXT_FILE_NAMES.contains(normalizedName)) {
            return true;
        }
        return TEXT_EXTENSIONS.contains(extensionOf(normalizedName));
    }

    public static String guessMimeType(String displayName) {
        if (isMarkdown(displayName, null)) {
            return "text/markdown";
        }
        String guessed = URLConnection.guessContentTypeFromName(displayName);
        if (guessed != null && !guessed.trim().isEmpty()) {
            return guessed;
        }
        if (isLikelyText(displayName, null)) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    static String normalizeMimeType(String mimeType) {
        if (mimeType == null) return "";
        int semicolon = mimeType.indexOf(';');
        String value = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return value.trim().toLowerCase(Locale.US);
    }

    private static String extensionOf(String displayName) {
        String normalizedName = normalizeName(displayName);
        int slash = Math.max(normalizedName.lastIndexOf('/'), normalizedName.lastIndexOf('\\'));
        if (slash >= 0) normalizedName = normalizedName.substring(slash + 1);
        int dot = normalizedName.lastIndexOf('.');
        if (dot < 0 || dot == normalizedName.length() - 1) return normalizedName;
        return normalizedName.substring(dot + 1);
    }

    private static String normalizeName(String displayName) {
        return displayName == null ? "" : displayName.trim().toLowerCase(Locale.US);
    }
}
