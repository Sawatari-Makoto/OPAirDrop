package com.oshare.airdrop;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
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
                    registerStateObserver(chain.getThisObject());
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

    /** The panel view can outlive a mode change made elsewhere (the settings
     *  screen): the lifecycle hooks only fire on panel events, so a mode
     *  picked in HelpActivity would never re-sync an already-inflated panel.
     *  Watch the two reception settings keys directly instead. */
    private static volatile boolean observerRegistered;

    private static void registerStateObserver(Object service) {
        if (observerRegistered || !(service instanceof Context)) return;
        try {
            Context ctx = (Context) service;
            android.database.ContentObserver obs = new android.database.ContentObserver(
                    new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { resync(); }
            };
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(ReceiveModeHooks.MARKER), false, obs);
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(OSHARE_STATE), false, obs);
            observerRegistered = true;
            log(Log.INFO, "panel settings observer registered");
        } catch (Throwable t) { log(Log.WARN, "panel settings observer failed", t); }
    }

    /** Builds the fourth row by cloning the structure and look of the official
     *  rows, then inserts it directly below the "everyone" row. Idempotent per
     *  panel view via the row tag.
     *
     *  Style template is the contacts row, not the everyone row: at inject
     *  time (straight after inflation) the everyone row can still be mid
     *  layout with collapsed metrics, and the official row structure is
     *  [column(title+summary) | radio-on-the-right] - the earlier radio-first
     *  build rendered indented next to the official rows. */
    private static void injectRow(Object service, View root) throws Exception {
        if (root.findViewWithTag(ROW_TAG) != null) return;
        int allId = id(root, "root_reception_all");
        View officialRow = allId == 0 ? null : root.findViewById(allId);
        if (!(officialRow instanceof ViewGroup)) return;
        ViewGroup official = (ViewGroup) officialRow;
        Context ctx = root.getContext();
        ClassLoader cl = ctx.getClassLoader();

        int contactsId = id(root, "root_reception_contacts");
        View templateView = contactsId == 0 ? null : root.findViewById(contactsId);
        if (!(templateView instanceof ViewGroup)) templateView = official;
        ViewGroup template = (ViewGroup) templateView;
        View templateInner = template.getChildCount() > 0 ? template.getChildAt(0) : null;

        View row = (View) newInstance1(cl.loadClass(ROW_TYPE), ctx);
        ViewGroup rowGroup = (ViewGroup) row;
        rowGroup.setClipChildren(false);
        rowGroup.setClipToPadding(false);
        row.setFocusable(true);
        row.setClickable(true);
        ViewGroup.LayoutParams srcLp = template.getLayoutParams();
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
        if (templateInner != null) {
            inner.setPadding(templateInner.getPaddingLeft(), templateInner.getPaddingTop(),
                    templateInner.getPaddingRight(), templateInner.getPaddingBottom());
            inner.setMinimumHeight(templateInner.getMinimumHeight());
            if (templateInner.getLayoutParams() instanceof LinearLayout.LayoutParams)
                inner.setLayoutParams(new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) templateInner.getLayoutParams()));
        }

        RelativeLayout column = new RelativeLayout(ctx);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView officialTitle = textView(root, "tv_reception_contacts_title");
        if (officialTitle == null) officialTitle = textView(root, "tv_reception_all_title");
        TextView title = (TextView) newInstance1(cl.loadClass(TEXTVIEW), ctx);
        copyTextLook(officialTitle, title);
        title.setText(ReceiveModeHooks.IOS_TITLE);
        int titleId = View.generateViewId();
        title.setId(titleId);
        column.addView(title, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView officialSummary = textView(root, "tv_reception_contacts_summary");
        if (officialSummary == null) officialSummary = textView(root, "tv_reception_all_summary");
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

        CompoundButton officialRadio = (CompoundButton) find(root, "rb_reception_contacts");
        if (officialRadio == null) officialRadio = (CompoundButton) find(root, "rb_reception_all");
        CompoundButton radio = (CompoundButton) newInstance1(cl.loadClass(RADIO), ctx);
        if (officialRadio != null) {
            // A Drawable instance carries ONE state shared by every view it is
            // assigned to: reusing the official radio's instance made the
            // iPhone mark mirror whichever official radio was checked last
            // (and vice versa). Derive an independent copy instead.
            radio.setButtonDrawable(independentDrawable(officialRadio.getButtonDrawable()));
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
        inner.addView(column);
        inner.addView(radio);

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
        int at = parent.indexOfChild(official) + 1;
        // Official rows are separated by a 0.33dp no-id View (the layout XML
        // puts one below the everyone row). Our row insertion used to push
        // that divider under the new row, leaving no line between "everyone"
        // and the iPhone row - clone it for the gap above the row.
        View below = at < parent.getChildCount() ? parent.getChildAt(at) : null;
        View dividerClone = below != null && below.getId() == View.NO_ID
                && !(below instanceof ViewGroup) ? cloneDivider(below, ctx) : null;
        if (dividerClone != null) { parent.addView(dividerClone, at); at++; }
        parent.addView(row, at);
        log(Log.INFO, "inline panel iPhone row inserted");

        // When contacts or receive-off claim the check, iPhone mode must step
        // down: the panel writes its state on its own path and never clears
        // the marker, which left two options checked at once. The everyone
        // row is deliberately NOT listened to - the iPhone enable path itself
        // writes state 1, which natively re-checks the everyone radio, and
        // explicit everyone taps are already covered by the S() hook.
        CompoundButton.OnCheckedChangeListener stepDown = (btn, isChecked) -> {
            if (!isChecked || ReceiveModeHooks.enablingIos()) return;
            try {
                ReceiveModeHooks.onOfficialModeChosen(btn.getContext());
                CompoundButton ios = (CompoundButton) root.findViewWithTag(RADIO_TAG);
                if (ios != null && ios != btn) ios.setChecked(false);
                // official state write may still be in flight; resync after it
                root.postDelayed(() -> resync(), 150);
            } catch (Throwable t) { log(Log.WARN, "inline official radio step-down failed", t); }
        };
        for (String n : new String[]{"rb_reception_contacts", "rb_reception_off"}) {
            CompoundButton rb = (CompoundButton) find(root, n);
            if (rb != null) rb.setOnCheckedChangeListener(stepDown);
        }

        pinPanelHeight(root);
    }

    private static View cloneDivider(View src, Context ctx) {
        try {
            View v = new View(ctx);
            v.setBackground(independentDrawable(src.getBackground()));
            ViewGroup.LayoutParams lp = src.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams)
                v.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp));
            else if (lp != null)
                v.setLayoutParams(new ViewGroup.LayoutParams(lp));
            return v;
        } catch (Throwable t) { return null; }
    }

    /** Copies a drawable into an independent instance: the source view keeps
     *  its own state machine, the copy reacts only to our writes. */
    private static android.graphics.drawable.Drawable independentDrawable(
            android.graphics.drawable.Drawable src) {
        if (src == null) return null;
        android.graphics.drawable.Drawable.ConstantState cs = src.getConstantState();
        try { return cs != null ? cs.newDrawable() : src.mutate(); }
        catch (Throwable t) { return src.mutate(); }
    }

    /** The panel root is wrap_content, but SystemUI sizes the inline surface
     *  from the very first measure, where a row can still report degenerate
     *  metrics (the everyone row once measured 3px) - which squeezes the
     *  surface and leaves the four options scrollable. Measure the finished
     *  panel here and pin an explicit height with a dimens-based floor:
     *  title 66dp + 4 rows (minHeight 44dp + 2x10dp margins) + 3 dividers. */
    private static void pinPanelHeight(View root) {
        try {
            Context ctx = root.getContext();
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = root.getMeasuredHeight();
            float density = dm.density;
            int floor = Math.round(66f * density + 4 * (44f + 20f) * density + 3 * 0.33f * density);
            if (h < floor) h = floor;
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp == null) lp = new ViewGroup.LayoutParams(width, h);
            else lp.height = h;
            root.setLayoutParams(lp);
            log(Log.INFO, "inline panel height pinned to " + h);
        } catch (Throwable t) { log(Log.WARN, "inline panel height pin failed", t); }
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
            // the static description stays visible like on the official rows;
            // while the countdown runs, redirectText swaps it for the tick copy
            TextView iosSummary = (TextView) root.findViewWithTag(SUMMARY_TAG);
            if (iosSummary != null) iosSummary.setText(ReceiveModeHooks.IOS_SUBTITLE);
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
            // COUI view classes are XML-inflation only: they ship no
            // (Context) constructor at all - ListSelectedItemLayout only
            // offers (Context, AttributeSet[,...]). Inflate-style call with
            // a null AttributeSet is what every View subclass accepts.
            try {
                Constructor<?> ctor = type.getDeclaredConstructor(Context.class, AttributeSet.class);
                ctor.setAccessible(true);
                return ctor.newInstance(ctx, null);
            } catch (NoSuchMethodException ignored) {
                throw e;
            }
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
