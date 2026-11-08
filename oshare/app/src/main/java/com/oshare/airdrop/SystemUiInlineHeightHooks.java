package com.oshare.airdrop;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Raises the height of the OShare control-centre inline panel.
 *
 *  Real pipeline (runtime-probe verified, 2026-11-06):
 *  ViewDialogContainerBinder$collectShowDialogEvent.emit
 *    -> OplusQSDetailInteractor.createDialogView / prepareDetailView
 *    -> DetailAdapter.createDetailView
 *    -> com.oplus.systemui.qs.tileex.CustomTileEx$CustomTileDetailAdapter
 *         .createDetailView
 *    -> OplusQsInlineHostView.setExpectedHeight(
 *         CustomTileEx$TileServiceConfig.getDialogHeight(context));
 *
 *  getDialogHeight resolves the height dimen (sys_ui_view_height, 780px =
 *  260dp on this device) from the TILE APP's own resources via
 *  PackageManager.getResourcesForApplication(detailComponent.packageName),
 *  so the panel height is dictated by the oshare APK metadata.
 *  OplusQsInlineHostView.onMeasure then measures itself AT_MOST that value
 *  with no lower clamp - the returned height is the single source of truth.
 *
 *  This hook wraps getDialogHeight and, only when the config's
 *  detailComponent belongs to com.coloros.oshare (the airdrop tile), returns
 *  330dp instead so the injected iPhone row fits without scrolling. All
 *  other tiles (battery, eye-protect, dolby, ...) keep their declared
 *  height, and native panels (Wi-Fi / Bluetooth) never enter this class. */
public final class SystemUiInlineHeightHooks {
    private static final String TAG = "OShareAirDrop";
    private static final String CONFIG_CLASS =
            "com.oplus.systemui.qs.tileex.CustomTileEx$TileServiceConfig";
    private static final String TILE_PACKAGE = "com.coloros.oshare";
    /** 260dp official height clips the 969px inline content once the iPhone
     *  row is injected; 330dp (990px @3x) leaves headroom for it. */
    private static final float PANEL_HEIGHT_DP = 330f;

    private static Entry module;

    private SystemUiInlineHeightHooks() {}

    public static void install(Entry owner, ClassLoader cl) {
        module = owner;
        try {
            Class<?> cfg = Class.forName(CONFIG_CLASS, false, cl);
            Method m = cfg.getDeclaredMethod("getDialogHeight", Context.class);
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    ComponentName comp = findComponent(self);
                    if (comp != null && comp.getPackageName() != null
                            && comp.getPackageName().contains(TILE_PACKAGE)) {
                        Context ctx = (Context) chain.getArg(0);
                        float density = ctx.getResources().getDisplayMetrics().density;
                        int h = Math.round(PANEL_HEIGHT_DP * density);
                        log(Log.INFO, "inline dialog height " + result + " -> "
                                + h + " for " + comp);
                        return h;
                    }
                } catch (Throwable t) {
                    log(Log.WARN, "inline dialog height hook error", t);
                }
                return result;
            });
            log(Log.INFO, "inline panel height hook installed on "
                    + CONFIG_CLASS + ".getDialogHeight");
        } catch (Throwable t) {
            log(Log.WARN, "inline panel height hook failed", t);
        }
    }

    /** The config's tile component is the only reliable discriminator between
     *  the airdrop tile and other third-party tiles routed through the same
     *  CustomTileEx pipeline. Field is private final, name may change across
     *  builds, so look it up by type instead. */
    private static ComponentName findComponent(Object cfg) {
        for (Class<?> c = cfg.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == ComponentName.class) {
                    try {
                        f.setAccessible(true);
                        return (ComponentName) f.get(cfg);
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    private static void log(int priority, String message) {
        log(priority, message, null);
    }

    private static void log(int priority, String message, Throwable t) {
        if (module != null) module.log(priority, TAG, message, t);
        else Log.println(priority, TAG, message + (t == null ? "" : Log.getStackTraceString(t)));
    }
}
