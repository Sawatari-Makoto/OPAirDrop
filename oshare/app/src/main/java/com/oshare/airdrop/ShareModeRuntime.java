package com.oshare.airdrop;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** The root supervisor publishes an applied mode only after the old session is idle. */
final class ShareModeRuntime {
    private static ShareMode fallback;

    /** SystemProperties.get resolved once. `current()` runs on per-second
     *  countdown ticks and every hook, so the per-call Class.forName lookup
     *  was pure repeated cost. */
    private static final java.lang.reflect.Method PROP_GET;
    static {
        java.lang.reflect.Method method;
        try {
            method = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
        } catch (Throwable failure) { method = null; }
        PROP_GET = method;
    }

    static synchronized ShareMode initialize(Context context) {
        if (fallback == null) {
            String value = property(ShareMode.PROPERTY);
            try {
                if (value.isEmpty()) value = Settings.Global.getString(context.getContentResolver(), ShareMode.SETTING);
            } catch (Exception failure) { Log.w(Entry.TAG, "share mode unavailable; using coexistence", failure); }
            fallback = ShareMode.parse(value);
        }
        return current();
    }

    private static String property(String key) {
        if (PROP_GET == null) return "";
        try { return (String) PROP_GET.invoke(null, key, ""); }
        catch (Exception failure) { return ""; }
    }

    static ShareMode current() {
        String value = property(ShareMode.PROPERTY);
        return value.isEmpty() ? (fallback == null ? ShareMode.BOTH : fallback) : ShareMode.parse(value);
    }

    static boolean switching() { return "1".equals(property("debug.opairshare.share_switching")); }

    static Integer result(String method) {
        if (switching() && ShareMode.startsWork(method)) return 1;
        return current().nativeResult(method);
    }
}
