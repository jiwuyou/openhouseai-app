package com.termux.app.openhouse.files.network.s3;

import com.termux.app.openhouse.files.model.FileOperationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;

public final class S3V4Signer {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private S3V4Signer() {
    }

    public static Request sign(Request request, S3ObjectStoreConfig config, String payloadSha256, Date signingTime)
        throws FileOperationException {
        String amzDate = amzDate(signingTime);
        String dateStamp = dateStamp(signingTime);
        String host = hostHeader(request.url());
        Request.Builder builder = request.newBuilder()
            .header("Host", host)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadSha256);
        if (config.hasSessionToken()) builder.header("x-amz-security-token", config.getSessionToken());
        Request prepared = builder.build();
        String credentialScope = credentialScope(dateStamp, config.getRegion());
        String canonicalRequest = canonicalRequest(prepared, payloadSha256);
        String stringToSign = stringToSign(amzDate, credentialScope, canonicalRequest);
        String signature = signature(config.getSecretKey(), dateStamp, config.getRegion(), stringToSign);
        String authorization = ALGORITHM
            + " Credential=" + config.getAccessKey() + "/" + credentialScope
            + ", SignedHeaders=" + signedHeaders(prepared)
            + ", Signature=" + signature;
        return prepared.newBuilder().header("Authorization", authorization).build();
    }

    public static String canonicalRequest(Request request, String payloadSha256) {
        return request.method() + '\n'
            + canonicalUri(request.url()) + '\n'
            + canonicalQueryString(request.url()) + '\n'
            + canonicalHeaders(request) + '\n'
            + signedHeaders(request) + '\n'
            + payloadSha256;
    }

    public static String stringToSign(String amzDate, String credentialScope, String canonicalRequest)
        throws FileOperationException {
        return ALGORITHM + '\n'
            + amzDate + '\n'
            + credentialScope + '\n'
            + sha256Hex(canonicalRequest);
    }

    public static String credentialScope(String dateStamp, String region) {
        return dateStamp + "/" + region + "/s3/aws4_request";
    }

    public static String amzDate(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    public static String dateStamp(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    public static String sha256Hex(String value) throws FileOperationException {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) throws FileOperationException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot calculate SHA-256", e);
        }
    }

    public static String awsEncode(String value, boolean encodeSlash) {
        StringBuilder result = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~') {
                result.append((char) c);
            } else if (c == '/' && !encodeSlash) {
                result.append('/');
            } else {
                result.append('%');
                char high = Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16));
                char low = Character.toUpperCase(Character.forDigit(c & 0xf, 16));
                result.append(high).append(low);
            }
        }
        return result.toString();
    }

    static String canonicalUri(HttpUrl url) {
        List<String> segments = url.pathSegments();
        if (segments.isEmpty()) return "/";
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            result.append('/').append(awsEncode(segment, true));
        }
        String canonical = result.toString();
        return canonical.isEmpty() ? "/" : canonical;
    }

    static String canonicalQueryString(HttpUrl url) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < url.querySize(); i++) {
            String name = url.queryParameterName(i);
            String value = url.queryParameterValue(i);
            pairs.add(awsEncode(name == null ? "" : name, true)
                + "="
                + awsEncode(value == null ? "" : value, true));
        }
        Collections.sort(pairs);
        StringBuilder result = new StringBuilder();
        for (String pair : pairs) {
            if (result.length() > 0) result.append('&');
            result.append(pair);
        }
        return result.toString();
    }

    static String canonicalHeaders(Request request) {
        TreeMap<String, List<String>> headers = normalizedHeaders(request);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            result.append(entry.getKey()).append(':');
            List<String> values = entry.getValue();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) result.append(',');
                result.append(values.get(i));
            }
            result.append('\n');
        }
        return result.toString();
    }

    static String signedHeaders(Request request) {
        TreeMap<String, List<String>> headers = normalizedHeaders(request);
        StringBuilder result = new StringBuilder();
        for (String name : headers.keySet()) {
            if (result.length() > 0) result.append(';');
            result.append(name);
        }
        return result.toString();
    }

    private static TreeMap<String, List<String>> normalizedHeaders(Request request) {
        TreeMap<String, List<String>> headers = new TreeMap<>();
        Headers requestHeaders = request.headers();
        for (String name : requestHeaders.names()) {
            String lowerName = name.toLowerCase(Locale.US);
            if ("authorization".equals(lowerName)) continue;
            List<String> values = new ArrayList<>();
            for (String value : requestHeaders.values(name)) {
                values.add(normalizeHeaderValue(value));
            }
            Collections.sort(values, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return left.compareTo(right);
                }
            });
            headers.put(lowerName, values);
        }
        if (!headers.containsKey("host")) {
            List<String> host = new ArrayList<>();
            host.add(hostHeader(request.url()));
            headers.put("host", host);
        }
        return headers;
    }

    private static String normalizeHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String signature(String secretKey, String dateStamp, String region, String stringToSign)
        throws FileOperationException {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmac(kSecret, dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        return hex(hmac(kSigning, stringToSign));
    }

    private static byte[] hmac(byte[] key, String value) throws FileOperationException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot calculate HMAC-SHA256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return result.toString();
    }

    private static String hostHeader(HttpUrl url) {
        int port = url.port();
        boolean defaultPort = ("http".equals(url.scheme()) && port == 80) || ("https".equals(url.scheme()) && port == 443);
        return defaultPort ? url.host() : url.host() + ":" + port;
    }
}
