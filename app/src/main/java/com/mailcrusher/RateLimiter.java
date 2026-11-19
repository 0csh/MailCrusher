package com.mailcrusher;

import android.content.Context;
import android.content.SharedPreferences;

public final class RateLimiter {
    private static final String PREFS = "mailcrusher_rate_limit";
    private static final String LAST_SEND = "last_send_ms";
    private static final long MIN_INTERVAL_MS = 3_000L;

    private RateLimiter() {}

    public static long remainingMs(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long remaining = MIN_INTERVAL_MS - (System.currentTimeMillis() - p.getLong(LAST_SEND, 0));
        return Math.max(0L, remaining);
    }

    public static boolean canSend(Context context) {
        return remainingMs(context) == 0L;
    }

    public static void recordSendAttempt(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(LAST_SEND, System.currentTimeMillis()).apply();
    }
}
