package com.oshare.airdrop;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Injects the "iPhone（10 分钟）" option into the SystemUI control-centre
 *  panel (OShareSysUiInlineViewService, opened by long-pressing the OShare
 *  tile). The panel view is inflated by the oshare process itself, so the
 *  fourth row is added programmatically right after the official "everyone"
 *  row, reusing the host's own themed widgets with their look copied from the
 *  official rows. Tapping it selects iPhone reception mode through
 *  ReceiveModeHooks.enableIosMode; official taps keep their untouched write
 *  path. The one extra on that path: the official "everyone" row writes
 *  state 1, which the p1.u interception otherwise treats as a harmless
 *  iPhone-mode echo, so the marker is cleared explicitly when that row is
 *  chosen (ReceiveModeHooks.onOfficialEveryoneTap).
 *
 *  While iPhone mode is active the per-second countdown copy - which the
 *  official tick (OShareSysUiInlineViewService$f.run) writes on the
 *  everyone-row summary - is moved to the iPhone row, and a countdown expiry
 *  lands on receive-off: the service's contacts-support flag (f9788o) is
 *  temporarily cleared so the official expiry code takes its own receive-off
 *  branch, exactly mirroring the settings-screen behaviour. */
public final class InlinePanelHooks {
    private static final String TAG = "OShareAirDrop";
    private static final String SERVICE = "com.oplus.oshare.OShareSysUiInlineViewService";
    private static final String TICK = "com.oplus.oshare.OShareSysUiInlineViewService$f";
    private static final String ROW_TYPE = "com.coui.appcompat.preference.ListSelectedItemLayout";
    private static final String TEXTVIEW = "androidx.appcompat.widget.AppCompatTextView";
    private static final String RADIO = "androidx.appcompat.widget.AppCompatRadioButton";
    private static final String ROW_TAG = "opairshare_ios_row";
    private static final String SUMMARY_TAG = "opairshare_ios_summary";
    private static final String RADIO_TAG = "opairshare_ios_radio";
    private static final String OSHARE_STATE = "oshare_state";

    private static Entry module;
    private static volatile WeakReference<View> viewRef;
    private static volatile WeakReference<Object> serviceRef;

    private InlinePanelHooks() {}

    public static synchronized void install(Entry owner, ClassLoader cl) {
        module = owner;
        try {
            Class<?> service = cl.loadClass(SERVICE);
            hookAfter(cl, SERVICE, "t", new Class<?>[]{String.class, Bundle.class},
                    self -> resync());
            hookAfter(cl, SERVICE, "h0", new Class<?>[]{int.class}, self -> resync());
            hookAfter(cl, SERVICE, "S", new Class<?>[]{}, self -> {
                if (self instanceof Context) ReceiveModeHooks.onOfficialEveryoneTap((Context) self);
                resync();
            });
            hookAfter(cl, SERVICE, "T", new Class<?>[]{}, self -> resync());
            hookAfter(cl, SERVICE, "U", new Class<?>[]{}, self -> resync());
            hookCreateView(service);
            hookTick(cl);
            log(Log.INFO, "inline panel iPhone option hooks installed");
        } catch (Throwable t) {
            log(Log.WARN, "inline panel injection unavailable", t);
        }
    }

    /** k(String,Bundle,Context) = onCreateInlineView: the inflated panel is
     *  returned to SystemUI here, so the extra row is added before it leaves. */
    private static void hookCreateView(Class<?> service) throws Exception {
        Method create = null;
        for (Method m : service.getDeclaredMethods()) {
            if (!"k".equals(m.getName()) || m.getParameterCount() != 3) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types[0] == String.class && types[1] == Bundle.class
                    && Context.class.isAssignableFrom(types[2])) { create = m; break; }
        }
        if (create == null) throw new NoSuchMethodException(SERVICE + ".k(String,Bundle,Context)");
        create.setAccessible(true);
        module.hook(create).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (result instanceof View) {
                    View root = (View) result;
                    viewRef = new WeakReference<>(root);
                    serviceRef = new WeakReference<>(chain.getThisObject());
                    injectRow(chain.getThisObject(), root);
                    syncChecks(root);
                }
            } catch (Throwable t) { log(Log.WARN, "inline panel iPhone row injection failed", t); }
            return result;
        });
    }

    /** The per-second tick of the panel countdown (inner class $f.run). Before
     *  the original body runs, iPhone mode forces the receive-off expiry branch
     *  by clearing the contacts-support flag; after it, the countdown copy the
     *  body wrote on the everyone-row summary is moved onto the iPhone row. */
    private static void hookTick(ClassLoader cl) throws Exception {
        Class<?> tick = cl.loadClass(TICK);
        Method run = tick.getDeclaredMethod("run");
        run.setAccessible(true);
        module.hook(run).intercept(chain -> {
            Object inner = chain.getThisObject();
            Object service = Entry.outerInstance(inner, loadSilently(inner, SERVICE));
            Field flag = null;
            boolean restore = false;
            try {
                if (service instanceof Context && ReceiveModeHooks.iosModeActive((Context) service)) {
                    flag = field(service.getClass(), "f9788o");
                    if (flag != null && flag.getBoolean(service)) {
                        flag.setBoolean(service, false);
                        restore = true;
                    }
                }
            } catch (Throwable ignored) { flag = null; restore = false; }
            try {
                Object result = chain.proceed();
                try {
                    if (service instanceof Context && ReceiveModeHooks.iosModeActive((Context) service)) {
                        View root = view();
                        if (root != null) redirectText(root);
                    }
                } catch (Throwable t) { log(Log.WARN, "inline countdown text redirect failed", t); }
                return result;
            } finally {
                if (restore && flag != null) {
                    try { flag.setBoolean(service, true); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static Class<?> loadSilently(Object inner, String name) {
        try { return inner.getClass().getClassLoader().loadClass(name); }
        catch (Throwable t) { return null; }
    }

    private static void hookAfter(ClassLoader cl, String className, String method,
                                  Class<?>[] types, After after) throws Exception {
        Method m = cl.loadClass(className).getDeclaredMethod(method, types);
        m.setAccessible(true);
        module.hook(m).intercept(chain -> {
            Object result = chain.proceed();
            try { after.run(chain.getThisObject()); }
            catch (Throwable t) { log(Log.WARN, "inline panel post-hook failed: " + method, t); }
            return result;
        });
    }

    private interface After { void run(Object self) throws Exception; }

    private static void resync() {
        View root = view();
        if (root != null) syncChecks(root);
    }

    /** Builds the fourth row by cloning the structure and look of the official
     *  "everyone" row, then inserts it directly below it. Idempotent per panel
     *  view via the row tag. */
    private static void injectRow(Object service, View root) throws Exception {
        if (root.findViewWithTag(ROW_TAG) != null) return;
        int allId = id(root, "root_reception_all");
        View officialRow = allId == 0 ? null : root.findViewById(allId);
        if (!(officialRow instanceof ViewGroup)) return;
        ViewGroup official = (ViewGroup) officialRow;
        Context ctx = root.getContext();
        ClassLoader cl = ctx.getClassLoader();

        View row = (View) newInstance1(cl.loadClass(ROW_TYPE), ctx);
        ViewGroup rowGroup = (ViewGroup) row;
        rowGroup.setClipChildren(false);
        rowGroup.setClipToPadding(false);
        row.setFocusable(true);
        row.setClickable(true);
        ViewGroup.LayoutParams srcLp = official.getLayoutParams();
        if (srcLp instanceof LinearLayout.LayoutParams)
            row.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) srcLp));
        else if (srcLp instanceof ViewGroup.MarginLayoutParams)
            row.setLayoutParams(new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) srcLp));
        else
            row.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        View officialInner = official.getChildAt(0);
        if (officialInner != null) {
            inner.setPadding(officialInner.getPaddingLeft(), officialInner.getPaddingTop(),
                    officialInner.getPaddingRight(), officialInner.getPaddingBottom());
            inner.setMinimumHeight(officialInner.getMinimumHeight());
            if (officialInner.getLayoutParams() instanceof LinearLayout.LayoutParams)
                inner.setLayoutParams(new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) officialInner.getLayoutParams()));
        }

        RelativeLayout column = new RelativeLayout(ctx);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView officialTitle = textView(root, "tv_reception_all_title");
        TextView title = (TextView) newInstance1(cl.loadClass(TEXTVIEW), ctx);
        copyTextLook(officialTitle, title);
        title.setText(ReceiveModeHooks.IOS_TITLE);
        int titleId = View.generateViewId();
        title.setId(titleId);
        column.addView(title, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView officialSummary = textView(root, "tv_reception_all_summary");
        TextView summary = (TextView) newInstance1(cl.loadClass(TEXTVIEW), ctx);
        copyTextLook(officialSummary, summary);
        summary.setText(ReceiveModeHooks.IOS_SUBTITLE);
        summary.setTag(SUMMARY_TAG);
        RelativeLayout.LayoutParams summaryLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.addRule(RelativeLayout.BELOW, titleId);
        if (officialSummary != null && officialSummary.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams src =
                    (ViewGroup.MarginLayoutParams) officialSummary.getLayoutParams();
            summaryLp.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
        }
        column.addView(summary, summaryLp);

        CompoundButton officialRadio = (CompoundButton) find(root, "rb_reception_all");
        CompoundButton radio = (CompoundButton) newInstance1(cl.loadClass(RADIO), ctx);
        if (officialRadio != null) {
            radio.setButtonDrawable(officialRadio.getButtonDrawable());
            ViewGroup.MarginLayoutParams src = officialRadio.getLayoutParams()
                    instanceof ViewGroup.MarginLayoutParams
                    ? (ViewGroup.MarginLayoutParams) officialRadio.getLayoutParams() : null;
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.gravity = Gravity.CENTER_VERTICAL;
            if (src != null) rlp.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
            radio.setLayoutParams(rlp);
        }
        radio.setBackground(null);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setTag(RADIO_TAG);
        radio.setChecked(false);
        inner.addView(radio);

        inner.addView(column);
        rowGroup.addView(inner);
        row.setTag(ROW_TAG);
        row.setOnClickListener(v -> {
            try {
                ReceiveModeHooks.enableIosMode(v.getContext());
                resync();
                restartCountdown(serviceRef == null ? null : serviceRef.get());
            } catch (Throwable t) { log(Log.WARN, "inline iPhone row tap failed", t); }
        });

        ViewGroup parent = (ViewGroup) official.getParent();
        parent.addView(row, parent.indexOfChild(official) + 1);
        log(Log.INFO, "inline panel iPhone row inserted");
    }

    /** Aligns the four check marks with the real reception state, the way the
     *  settings screen sees it: the iPhone mark owns marker==1 && state==1 and
     *  the official marks follow the raw state otherwise. */
    private static void syncChecks(View root) {
        try {
            Context ctx = root.getContext();
            int marker = Settings.System.getInt(ctx.getContentResolver(), ReceiveModeHooks.MARKER, 0);
            int state = Settings.System.getInt(ctx.getContentResolver(), OSHARE_STATE, 0);
            boolean active = marker == 1;
            CompoundButton all = (CompoundButton) find(root, "rb_reception_all");
            CompoundButton contacts = (CompoundButton) find(root, "rb_reception_contacts");
            CompoundButton off = (CompoundButton) find(root, "rb_reception_off");
            CompoundButton ios = (CompoundButton) root.findViewWithTag(RADIO_TAG);
            if (all != null) all.setChecked(!active && state == 1);
            if (contacts != null) contacts.setChecked(!active && state == 2);
            if (off != null) off.setChecked(!active && state == 0);
            if (ios != null) ios.setChecked(active && state == 1);
            TextView iosSummary = (TextView) root.findViewWithTag(SUMMARY_TAG);
            if (iosSummary != null)
                iosSummary.setText(active && state == 1 ? ReceiveModeHooks.IOS_SUBTITLE : "");
        } catch (Throwable t) { log(Log.WARN, "inline panel check sync failed", t); }
    }

    /** Moves the countdown copy the tick just wrote on the everyone-row summary
     *  to the iPhone row and restores the official static description under the
     *  everyone row, mirroring the settings-screen p1 redirect. */
    private static void redirectText(View root) {
        TextView allSummary = textView(root, "tv_reception_all_summary");
        TextView iosSummary = (TextView) root.findViewWithTag(SUMMARY_TAG);
        if (allSummary == null || iosSummary == null) return;
        CharSequence text = allSummary.getText();
        if (text == null || text.length() == 0) return;
        iosSummary.setText(text);
        int descId = root.getResources().getIdentifier("reception_range_all_description",
                "string", root.getContext().getPackageName());
        allSummary.setText(descId == 0 ? "" : root.getContext().getString(descId));
    }

    private static void restartCountdown(Object service) {
        if (service == null) return;
        try {
            Method restart = service.getClass().getDeclaredMethod("V");
            restart.setAccessible(true);
            restart.invoke(service);
        } catch (Throwable t) { log(Log.WARN, "inline countdown restart failed", t); }
    }

    private static View find(View root, String name) {
        int i = id(root, name);
        return i == 0 ? null : root.findViewById(i);
    }

    private static TextView textView(View root, String name) {
        View v = find(root, name);
        return v instanceof TextView ? (TextView) v : null;
    }

    private static int id(View root, String name) {
        return root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
    }

    private static void copyTextLook(TextView src, TextView dst) {
        if (src == null) return;
        dst.setTextSize(0, src.getTextSize());
        dst.setTypeface(src.getTypeface());
        dst.setTextColor(src.getCurrentTextColor());
        dst.setIncludeFontPadding(src.getIncludeFontPadding());
        dst.setTextDirection(src.getTextDirection());
        dst.setLetterSpacing(src.getLetterSpacing());
    }

    private static Object newInstance1(Class<?> type, Context ctx) throws Exception {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ctx);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                Class<?>[] ps = ctor.getParameterTypes();
                if (ps.length == 1 && ps[0].isInstance(ctx)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(ctx);
                }
            }
            throw e;
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static View view() { return viewRef == null ? null : viewRef.get(); }

    private static void log(int priority, String message) { log(priority, message, null); }

    private static void log(int priority, String message, Throwable t) {
        if (module != null) {
            if (t != null) module.log(priority, TAG, message, t);
            else module.log(priority, TAG, message);
        } else {
            Log.println(priority, TAG, message + (t == null ? "" : ": " + t));
        }
    }
}
