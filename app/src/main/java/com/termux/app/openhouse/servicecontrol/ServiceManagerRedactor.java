package com.termux.app.openhouse.servicecontrol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServiceManagerRedactor {

    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
        "(?i)(\"(?:api[_-]?key|auth[_-]?token|access[_-]?token|refresh[_-]?token|token|password|secret|authorization|cookie|set-cookie)\"\\s*:\\s*\")([^\"]{8,})(\")"
    );
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
        "(?i)(\\b(?:api[_-]?key|auth[_-]?token|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|cookie|set-cookie)\\b\\s*[:=]\\s*[\"']?)([^\\s\"',;}{]{8,})([\"']?)"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile(
        "(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]{8,})"
    );
    private static final Pattern OPENAI_STYLE_KEY_PATTERN = Pattern.compile(
        "\\bsk-[A-Za-z0-9_-]{12,}\\b"
    );
    private static final Pattern URL_SECRET_PATTERN = Pattern.compile(
        "(?i)([?&](?:api[_-]?key|auth[_-]?token|access[_-]?token|refresh[_-]?token|token|key|secret)=)([^&#\\s]+)"
    );

    private ServiceManagerRedactor() {}

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String redacted = redactThreeGroupPattern(JSON_SECRET_PATTERN, value);
        redacted = redactThreeGroupPattern(KEY_VALUE_SECRET_PATTERN, redacted);
        redacted = redactTwoGroupPattern(BEARER_PATTERN, redacted);
        redacted = OPENAI_STYLE_KEY_PATTERN.matcher(redacted).replaceAll("sk-***");
        redacted = redactTwoGroupPattern(URL_SECRET_PATTERN, redacted);
        return redacted;
    }

    private static String redactThreeGroupPattern(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                matcher.group(1) + mask(matcher.group(2)) + matcher.group(3)
            ));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String redactTwoGroupPattern(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                matcher.group(1) + mask(matcher.group(2))
            ));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "***";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }
}
