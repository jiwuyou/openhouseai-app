package com.wuxianpi.openhouse.core;

public final class DesktopResidencyPolicy {
    public static final long DEFAULT_RELEASE_DELAY_MILLIS = 10L * 60L * 1000L;

    public enum Mode { RELEASE_AFTER_INACTIVITY, KEEP_BEST_EFFORT }

    public final Mode mode;
    public final long releaseDelayMillis;

    public DesktopResidencyPolicy(Mode mode, long releaseDelayMillis) {
        if (releaseDelayMillis <= 0) throw new IllegalArgumentException("releaseDelayMillis must be positive");
        this.mode = mode == null ? Mode.RELEASE_AFTER_INACTIVITY : mode;
        this.releaseDelayMillis = releaseDelayMillis;
    }

    public static DesktopResidencyPolicy defaultPolicy() {
        return new DesktopResidencyPolicy(Mode.RELEASE_AFTER_INACTIVITY, DEFAULT_RELEASE_DELAY_MILLIS);
    }

    public static DesktopResidencyPolicy keepBestEffort() {
        return new DesktopResidencyPolicy(Mode.KEEP_BEST_EFFORT, DEFAULT_RELEASE_DELAY_MILLIS);
    }

    public boolean shouldRelease(long inactiveMillis) {
        return mode == Mode.RELEASE_AFTER_INACTIVITY && inactiveMillis >= releaseDelayMillis;
    }

    /** Keeping is process-local only; Android may still reclaim the process. */
    public boolean guaranteesProcessSurvival() {
        return false;
    }
}
