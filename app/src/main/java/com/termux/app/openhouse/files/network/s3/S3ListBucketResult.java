package com.termux.app.openhouse.files.network.s3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class S3ListBucketResult {

    private final List<S3ObjectEntry> entries;
    private final boolean truncated;
    private final String nextContinuationToken;

    public S3ListBucketResult(List<S3ObjectEntry> entries, boolean truncated, String nextContinuationToken) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.truncated = truncated;
        this.nextContinuationToken = nextContinuationToken == null ? "" : nextContinuationToken;
    }

    public List<S3ObjectEntry> getEntries() {
        return entries;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public String getNextContinuationToken() {
        return nextContinuationToken;
    }
}
