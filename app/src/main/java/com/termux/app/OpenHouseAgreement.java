package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class OpenHouseAgreement {

    private static final String PREFS_NAME = "openhouse_agreement";
    private static final String KEY_ACCEPTED_HASH = "accepted_hash";
    private static final String KEY_ACCEPTED_AT = "accepted_at";

    private OpenHouseAgreement() {}

    public static boolean hasAcceptedCurrentVersion(Context context) {
        SharedPreferences preferences = getPreferences(context);
        String acceptedHash = preferences.getString(KEY_ACCEPTED_HASH, null);
        return buildCurrentHash(context).equals(acceptedHash);
    }

    public static void acceptCurrentVersion(Context context) {
        getPreferences(context).edit()
            .putString(KEY_ACCEPTED_HASH, buildCurrentHash(context))
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply();
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String buildCurrentHash(Context context) {
        String source = context.getString(R.string.agreement_human_readable_title) + "\n"
            + context.getString(R.string.agreement_human_readable_content) + "\n"
            + context.getString(R.string.agreement_formal_title) + "\n"
            + context.getString(R.string.agreement_formal_content);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
