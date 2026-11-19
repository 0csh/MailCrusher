package com.mailcrusher;

import android.content.Context;
import android.content.SharedPreferences;

public final class RateLimiter {
    private static final String PREFS = "mailcrusher_rate_limit";
    private static final String LAST_SEND = "last_send_ms";
    public static final long MIN_INTERVAL_MS = 1_00L;

    private RateLimiter() {}

    public static long remainingMs(Context context) {
        return remainingMs(context, MIN_INTERVAL_MS);
    }

    public static long remainingMs(Context context, long intervalMs) {
        long safeInterval = Math.max(MIN_INTERVAL_MS, intervalMs);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long remaining = safeInterval - (System.currentTimeMillis() - p.getLong(LAST_SEND, 0));
        return Math.max(0L, remaining);
    }

    public static boolean canSend(Context context) {
        return canSend(context, MIN_INTERVAL_MS);
    }

    public static boolean canSend(Context context, long intervalMs) {
        return remainingMs(context, intervalMs) == 0L;
    }

    public static void recordSendAttempt(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(LAST_SEND, System.currentTimeMillis()).apply();
    }
}
