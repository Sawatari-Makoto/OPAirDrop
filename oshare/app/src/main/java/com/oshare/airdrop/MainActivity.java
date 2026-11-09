package com.oshare.airdrop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Companion-app UI, restyled: three horizontally swipeable
 * pages (Home / Status / Settings) under a floating realtime-blur capsule
 * header and a floating realtime-blur bottom navigation bar whose glow
 * follows the finger. Scrolling a page down hides the nav bar, scrolling
 * back up restores it. Light and dark palettes both supported.
 * All module operations go through the same settings.sh backend reached via
 * su, so the app itself needs no privileges beyond a one-time root grant.
 */
public final class MainActivity extends Activity {

    private static final String SETTINGS =
        "/system/bin/sh /data/adb/modules/opairshare/bin/settings.sh";

    // Speed recorder backend; same su channel as settings.sh. Auto-started
    // by the module's ui-lifecycle hook, the app only reads its status.
    private static final String SPEED_HISTORY =
        "/system/bin/sh /data/adb/modules/opairshare/bin/speed-history.sh";

    // Provider host package; the module disables it after a session release
    // to save power and re-enables it during the next arm.
    private static final String PROVIDER_PKG = "com.google.android.mosey";

    private static final String[][] ROOT_MANAGERS = {
        {"me.weishu.kernelsu", "KernelSU"},
        {"com.rifsxd.ksunext", "KernelSU Next"},
        {"com.sukisu.ultra", "SukiSU Ultra"},
        {"me.bmax.apatch", "APatch"},
        {"com.topjohnwu.magisk", "Magisk"},
        {"io.github.huskydg.magisk", "Magisk Delta"},
    };

    // ---------------------------------------------------------- palette
    private static final class Scheme {
        final int bg, card, cardHi, rowPress, navTint, accent, accentD, text, textDim,
            green, red, orange, gray, divider, heroStart, heroEnd, glow, veil, ripple;

        Scheme(int bg, int card, int cardHi, int rowPress, int navTint, int accent,
               int accentD, int text, int textDim, int green, int red, int orange,
               int gray, int divider, int heroStart, int heroEnd, int glow, int veil,
               int ripple) {
            this.bg = bg; this.card = card; this.cardHi = cardHi;
            this.rowPress = rowPress; this.navTint = navTint;
            this.accent = accent; this.accentD = accentD; this.text = text;
            this.textDim = textDim; this.green = green; this.red = red;
            this.orange = orange; this.gray = gray; this.divider = divider;
            this.heroStart = heroStart; this.heroEnd = heroEnd;
            this.glow = glow; this.veil = veil; this.ripple = ripple;
        }
    }

    private static Scheme dark() {
        // palette for the dark theme:
        // page #1F1F1F, cards #303030, hi #3A3A3A
        return new Scheme(
            0xFF1F1F1F, 0xFF303030, 0xFF3A3A3A, 0xFF3A3A3A, 0x991F1F1F,
            0xFF398FFE, 0xFF1D4ED8, 0xFFF5F5F5, 0xFF9E9E9E,
            0xFF22C55E, 0xFFEF4444, 0xFFF59E0B, 0xFF8A8A8A,
            0x1FFFFFFF, 0xFF2563EB, 0xFF1D4ED8, 0x403B82F6, 0xD91F1F1F,
            0x1AFFFFFF); // ripple: white 10%, matches the row highlight pressedAlpha
    }

    private static Scheme light() {
        // palette for the light theme:
        // page #F0F1F3, cards #FFFFFF, hi #F1F3F6
        return new Scheme(
            0xFFF0F1F3, 0xFFFFFFFF, 0xFFF1F3F6, 0xFFECECEC, 0xB3F0F1F3,
            0xFF2563EB, 0xFF1D4ED8, 0xFF17181A, 0xFF5F6368,
            0xFF16A34A, 0xFFDC2626, 0xFFD97706, 0xFF9E9E9E,
            0x14000000, 0xFF3B82F6, 0xFF1E40AF, 0x382563EB, 0xDDF0F1F3,
            0x14000000); // ripple: black 8% over white cards (#FFFFFF→#ECECEC)
    }

    private Scheme S;
    private boolean lightMode;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SnapPager pager;
    private FrameLayout[] pages;
    private LinearLayout[] pageBodies;
    private View activityRoot;
    private IconView[] tabIcons;
    private TextView[] tabLabels;
    private FrameLayout navBar, topBar;
    private RealtimeBlurView navBlur, topBlur, frostVeil;
    private int currentTab;
    private boolean navShown = true;
    /** 0-1 strength of the title capsule's scroll-linked boundary (rim +
     *  shadow): 0 while a page sits at its top, 1 once it has scrolled up.
     *  -1 forces the first update to apply. */
    private float topEdge = -1f;
    private boolean busy;

    private int insetTop, insetBottom;

    // notices
    private TextView noticeBar;
    private RealtimeBlurView noticeBlur;
    private FrameLayout noticeWrap;
    private final Runnable noticeHide = this::hideNotice;

    // transfer status poller: samples the speed recorder every 2.5s, but only
    // while the status page is the visible tab
    private final Runnable historyPoll = new Runnable() {
        @Override public void run() {
            if (gone) return;
            if (pager != null && pager.currentPage == 1 && txPill != null) {
                refreshHistory();
            }
            main.postDelayed(this, 2500);
        }
    };

    // environment card widgets
    private TextView lspPill, rootPill, lspSubtitle, rootSubtitle;
    private LinearLayout lspRow, rootRow;
    private LspServiceBridge lspService;

    // device info widgets
    private TextView devModel, devBuild, devKernel, devColoros, devAndroid,
        devPatch;

    // status widgets
    private TextView stAwdl, stQuickshare, stOshare;

    // transfer status widgets (legacy WebUI speed card, now native)
    private TextView txPill, txValue, rxValue, historyCount;
    /** single 传输记录 row's chevrons + the expanded floating sheet */
    private TextView arrowUp, arrowDown;
    private LinearLayout historyList;
    private FrameLayout historyOverlay;
    private boolean historyExpanded;

    /** LSP scope card state. The card is an IN-ACTIVITY overlay, not a
     *  Dialog window — a separate dialog window never covers the gesture
     *  bar on ColorOS 17, which triggers a white SystemUI contrast band. */
    private boolean scopeExpanded;
    private FrameLayout scopeOverlay;
    /** the 传输记录 row's bounds (root coords) + the sheet's target edges,
     *  kept for the expand-from-row / collapse-into-row animations */
    private int historyRowTop, historyRowBottom, historyTopEdge, historyBotFree;
    private volatile boolean gone;
    private boolean historyBusy;

    // settings widgets
    private View[] modeOptions;
    private TextView modeOptionDot0, modeOptionDot1, modeOptionDot2;
    private EditText nameInput, directoryInput;
    private TextView nameSummary, directorySummary;
    private TextView logView;
    private boolean settingsBuilt;

    private Status lastStatus;
    private Env env;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // predictive-back dispatcher owns the system back gesture (see
        // backInvoker); the manifest flag enableOnBackInvokedCallback is on
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvoker);
        // Theme.DeviceDefault.DayNight carries an action bar by default;
        // kill it — the floating top capsule replaces it
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean light = (getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_NO;
        lightMode = light;
        S = light ? light() : dark();

        Window w = getWindow();
        // targetSdk 35+ enforces edge-to-edge: bar colors / decor fitting are
        // deprecated no-ops, transparent bars are the default now
        w.setNavigationBarContrastEnforced(false);

        setContentView(buildUi());

        int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        android.view.WindowInsetsController ic = w.getInsetsController();
        if (ic != null) {
            ic.setSystemBarsAppearance(light ? appearance : 0, appearance);
        }

        // libxposed service: framework delivers its binder
        // via the XposedServiceGate provider when the module is enabled
        LspServiceBridge.register(service -> main.post(() -> {
            lspService = service;
            if (env != null) applyEnvironment(env); else probeEnvironment();
        }));

        // Device info rows fill instantly: all six values are pure
        // in-process reads (Build API / reflected SystemProperties / uname),
        // no reason to gate them behind the slow su + LSP probe below.
        fillDeviceInfoFast();

        refresh();
        probeEnvironment();
        main.postDelayed(historyPoll, 1500);
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvoker);
        super.onDestroy();
        gone = true;
        main.removeCallbacks(historyPoll);
        io.shutdown();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is declared in android:configChanges, so day/night flips
        // arrive here instead of tearing the activity down — retheme in place
        boolean light = (newConfig.uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_NO;
        if (light == lightMode) return;
        retheme(light);
    }

    /** Shared back logic: peel transient layers first, then the tab stack;
     *  false means nothing left to peel — the caller may exit. */
    private boolean peelBack() {
        if (scopeExpanded) {
            collapseScope();
            return true;
        }
        if (historyExpanded) {
            collapseHistorySheet();
            return true;
        }
        if (currentTab != 0) {
            selectTab(0, true);
            return true;
        }
        return false;
    }

    /** Timestamp (uptimeMillis) of the last handled back — some ROMs
     *  dispatch one back press through BOTH the predictive-back callback
     *  and the legacy key path; the second must be a no-op. */
    private long backHandledAt;

    private boolean backAlreadyHandled() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - backHandledAt < 300) return true;
        backHandledAt = now;
        return false;
    }

    /** Predictive-back entry point (API 33+). Requires
     *  android:enableOnBackInvokedCallback="true". On standard AOSP the
     *  gesture dispatch lands here; ColorOS 17, however, still delivers
     *  back as a legacy KEYCODE event (see onKeyDown) and never calls
     *  this — hence the dual path. */
    private final android.window.OnBackInvokedCallback backInvoker =
        new android.window.OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                if (backAlreadyHandled()) return;
                if (!peelBack()) finish();
            }
        };

    /** Legacy back entry point: ColorOS 17 routes the back gesture and
     *  nav-button through KEYCODE_BACK instead of OnBackInvokedCallback,
     *  so without this the default Activity behaviour would finish the
     *  app outright instead of peeling the tab stack first. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK
            && event.getAction() == android.view.KeyEvent.ACTION_DOWN
            && !backAlreadyHandled() && peelBack()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Rebuild the whole UI in place under the new day/night scheme. Every
     * page is constructed in code, so a full rebuild is the only way to
     * re-derive all programmatic colors; live state (tab, status, env,
     * device info) is replayed into the fresh widgets, and the running
     * pollers keep working since they read the very fields the rebuild
     * reassigns. The cold-start splash background needs no handling here —
     * the values-night resource override covers it system-side.
     */
    private void retheme(boolean light) {
        lightMode = light;
        S = light ? light() : dark();
        int tab = currentTab;

        // drop any open floating sheet outright (its host view is discarded)
        if (historyOverlay != null && historyOverlay.getParent() != null) {
            ((ViewGroup) historyOverlay.getParent()).removeView(historyOverlay);
        }
        historyOverlay = null;
        historyList = null;
        historyExpanded = false;
        if (scopeOverlay != null && scopeOverlay.getParent() != null) {
            ((ViewGroup) scopeOverlay.getParent()).removeView(scopeOverlay);
        }
        scopeOverlay = null;
        scopeExpanded = false;
        cancelNavMorph();

        setContentView(buildUi());

        // buildUi resets to tab 0 — restore where the user was
        if (tab != 0) selectTab(tab, false);

        // status-bar icon contrast follows the new scheme
        int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) ic.setSystemBarsAppearance(light ? appearance : 0, appearance);

        // replay everything the first onCreate filled in
        fillDeviceInfoFast();
        if (env != null) applyEnvironment(env);
        if (lastStatus != null) applyStatus(lastStatus);
        else refresh();
        if (tab == 1 && txPill != null) refreshHistory();
    }

    // ------------------------------------------------------------------ ui

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout card() {
        LinearLayout box = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(S.card);
        bg.setCornerRadius(dp(22));
        box.setBackground(bg);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        return box;
    }

    private TextView text(String value, float sizeSp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
            : Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(color);
        return tv;
    }

    private TextView caption(String value) {
        return text(value, 12.5f, false, S.textDim);
    }

    private LinearLayout.LayoutParams mm(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private LinearLayout.LayoutParams margins(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = mm(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /** Divider params: a plain View has no intrinsic size, so the height must
     *  be pinned or the line collapses to zero. */
    private LinearLayout.LayoutParams fixedDivider(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    /** Pill badge with a colored dot, e.g. service state. */
    private TextView badge(String label, int color) {
        TextView tv = text(label, 12.5f, true, color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2A000000);
        bg.setCornerRadius(dp(999));
        tv.setBackground(bg);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        return tv;
    }

    private void setBadge(TextView tv, String label, int color) {
        tv.setText(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2A000000);
        bg.setCornerRadius(dp(999));
        tv.setBackground(bg);
        tv.setTextColor(color);
    }

    /** Passive status pill used by the environment card (
     *  blue when healthy, red when not). Not clickable — the whole row is. */
    private TextView statusPill(String label, int color) {
        TextView tv = text(label, 13, true, color);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x66000000);
        tv.setBackground(bg);
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        return tv;
    }

    private void setPill(TextView tv, String label, int color) {
        tv.setText(label);
        tv.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x66000000);
        tv.setBackground(bg);
    }

    private TextView mainButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 15, true, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(S.accent);
        b.setBackground(bg);
        b.setPadding(dp(20), dp(13), dp(20), dp(13));
        b.setOnClickListener(listener);
        pressable(b);
        return b;
    }

    private TextView ghostButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, false, S.text);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(S.cardHi);
        b.setBackground(bg);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setOnClickListener(listener);
        pressable(b);
        return b;
    }

    /** Subtle scale press feedback. */
    private void pressable(View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false;
        });
    }

    /** Row press corner radii, dp, order {topLeft, topRight, bottomRight,
     *  bottomLeft}: first row of a card rounds its top, last row its bottom. */
    private static final float[] CORNERS_TOP = {22, 22, 0, 0};
    private static final float[] CORNERS_BOTTOM = {0, 0, 22, 22};

    /** Press highlight (Compose Material3 ripple,
     *  pressedAlpha 0.1): a platform RippleDrawable that expands a soft radial
     *  highlight from the touch point (~250-300ms, strongest at the press
     *  center) and fades back out on release. cornerDp is {topLeft, topRight,
     *  bottomRight, bottomLeft} in dp, null = square. */
    private void pressableRow(View row, float[] cornerDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(0xFFFFFFFF); // mask uses alpha only: must be opaque or the ripple is fully clipped
        if (cornerDp != null) {
            mask.setCornerRadii(new float[] {
                dp(cornerDp[0]), dp(cornerDp[0]),
                dp(cornerDp[1]), dp(cornerDp[1]),
                dp(cornerDp[2]), dp(cornerDp[2]),
                dp(cornerDp[3]), dp(cornerDp[3])});
        }
        RippleDrawable ripple = new RippleDrawable(
            ColorStateList.valueOf(S.ripple), null, mask);
        row.setBackground(ripple); // idle = fully transparent, no re-layout
        row.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ripple.setHotspot(ev.getX(), ev.getY());
                    view.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    break;
            }
            // clickable rows must return false so onTouchEvent runs and the
            // click fires; non-clickable rows must consume (return true) so
            // UP/CANCEL always reaches this row instead of being lost to the
            // enclosing ScrollView, which would leave the fill stuck pressed
            return !view.isClickable();
        });
    }

    /** Toggle a whole-row button: flat gray fill press feedback spanning the
     *  full row area (no shadow/scale) + click action while
     *  enabled, inert while disabled (row shows the "healthy" state then). */
    private void setRowAction(View row, boolean enabled, float[] cornerDp,
                              Runnable action) {
        if (enabled) {
            row.setOnClickListener(v -> action.run());
            pressableRow(row, cornerDp);
        } else {
            row.setOnClickListener(null);
            row.setOnTouchListener(null);
            row.setClickable(false);
            row.setLongClickable(false);
            row.setBackground(null);
        }
    }

    private EditText darkInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(S.text);
        et.setHintTextColor(0xFF5A6376);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(S.cardHi);
        bg.setCornerRadius(dp(14));
        et.setBackground(bg);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        return et;
    }

    private TextView sectionTitle(String label) {
        TextView tv = text(label, 12.5f, true, S.textDim);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(6), 0, dp(6), dp(8));
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(S.divider);
        return v;
    }

    /** Icon inside a rounded tile, feature-row style. */
    private View iconTile(int iconType) {
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((S.accent & 0x00FFFFFF) | 0x1F000000);
        bg.setCornerRadius(dp(14));
        tile.setBackground(bg);
        IconView icon = new IconView(this, iconType);
        icon.setColor(S.accent);
        tile.addView(icon, new FrameLayout.LayoutParams(
            dp(22), dp(22), Gravity.CENTER));
        return tile;
    }

    /** Feature row: icon tile + title/subtitle on the left, widget on right. */
    private LinearLayout featureRow(int iconType, String title, String subtitle,
                                    View right) {
        return featureRow(iconType, title, subtitle, right, null);
    }

    /** Same, but hands the subtitle TextView back through subOut[0] so the
     *  caller can update it dynamically (state-dependent label). */
    private LinearLayout featureRow(int iconType, String title, String subtitle,
                                    View right, TextView[] subOut) {
        LinearLayout line = row();
        View tile = iconTile(iconType);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        line.addView(tile);

        LinearLayout mid = column();
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(text(title, 15, true, S.text));
        TextView sub = caption(subtitle);
        sub.setPadding(0, dp(2), 0, 0);
        mid.addView(sub);
        if (subOut != null) subOut[0] = sub;
        line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null) {
            line.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return line;
    }

    /** Device info row: icon tile on the left, the title
     *  with the prop value stacked beneath it on the right of the tile.
     *  Dividers between rows inset to the text edge (74dp = 18 pad + 44 tile
     *  + 12 gap). */
    private LinearLayout deviceRow(int iconType, String title, TextView valueView) {
        LinearLayout line = row();
        View tile = iconTile(iconType);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        line.addView(tile);

        LinearLayout mid = column();
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(text(title, 15, true, S.text));
        valueView.setTextSize(13.5f);
        valueView.setTextColor(S.textDim);
        valueView.setPadding(0, dp(3), 0, 0);
        mid.addView(valueView);
        line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    // ------------------------------------------------------------ root ui

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(S.bg);
        // let the nav pill's elevation shadow spill past its bounds
        root.setClipChildren(false);
        root.setClipToPadding(false);
        activityRoot = root;

        pager = new SnapPager(this);
        pager.setFillViewport(true);
        pager.setBackgroundColor(S.bg);
        root.addView(pager, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // full-width frosted veil (毛玻璃遮罩): spans from the status bar
        // down to the title capsule's bottom edge, above the content and
        // BENEATH the floating title capsule. Solid frost over the status
        // bar band; across the capsule's own height the frost dissolves
        // bottom-up (alpha full at the capsule's top edge → nothing at its
        // bottom edge), so no hard horizontal line under the pill. Height
        // and fade band are synced to the capsule in syncFrostVeil().
        frostVeil = new RealtimeBlurView(this);
        frostVeil.setCaptureRoot(pager);
        frostVeil.setFill(S.bg);
        // byte-identical frost recipe to the title/nav capsules
        frostVeil.setBlurRadius(5f);
        frostVeil.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        frostVeil.setGlassLift(lightMode ? 8 : 0);
        frostVeil.setOpacity(240);
        frostVeil.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        frostVeil.setLiquidGlass(true);
        // flat sheet: no rounded outline, no rim, no feather — the only
        // boundary is the bottom dissolve handled by the vertical fade mask
        frostVeil.setCornerRadius(0f);
        // fade in AS GLASS: no opaque backing mid-fade — the content behind
        // must read through the frost at every strength
        frostVeil.setBackingEnabled(false);
        root.addView(frostVeil, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0));

        pages = new FrameLayout[] {
            (FrameLayout) wrapPage(buildHomePage()),
            (FrameLayout) wrapPage(buildStatusPage()),
            (FrameLayout) wrapPage(buildSettingsPage()),
        };
        LinearLayout pagerRow = new LinearLayout(this);
        pagerRow.setOrientation(LinearLayout.HORIZONTAL);
        for (FrameLayout p : pages) {
            pagerRow.addView(p, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.addView(pagerRow, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        pager.setOnPage(index -> selectTab(index, false));

        // remember each page's scrolling body so insets can pad around the
        // floating top capsule and bottom nav pill
        pageBodies = new LinearLayout[3];
        for (int i = 0; i < 3; i++) {
            ScrollView scroll = (ScrollView) pages[i].getChildAt(0);
            pageBodies[i] = (LinearLayout) scroll.getChildAt(0);
        }

        // floating notice bar above the nav: realtime-blur pill, no stroke.
        // The wrap must derive its size from the text layer: the blur child
        // default-measures to the whole available space (View.getDefaultSize
        // honours AT_MOST), which would blow the pill up to full screen.
        noticeWrap = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                measureChild(noticeBar, widthSpec, heightSpec);
                setMeasuredDimension(
                    resolveSize(noticeBar.getMeasuredWidth(), widthSpec),
                    resolveSize(noticeBar.getMeasuredHeight(), heightSpec));
                noticeBlur.measure(
                    MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        noticeWrap.setElevation(dp(8));
        noticeWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        noticeBar = text("", 13, true, S.text);
        noticeBar.setGravity(Gravity.CENTER);
        GradientDrawable nb = new GradientDrawable();
        nb.setCornerRadius(dp(14));
        // mode-adaptive tint over the blur layer (slightly translucent so the
        // frosted content behind stays visible)
        nb.setColor(lightMode ? 0xD9F5F5F5 : 0xD95F5F5F);
        noticeBar.setBackground(nb);
        noticeBar.setPadding(dp(18), dp(11), dp(18), dp(11));

        RealtimeBlurView noticeBlur = this.noticeBlur = new RealtimeBlurView(this);
        noticeBlur.setCaptureRoot(pager);
        noticeBlur.setCornerRadius(dp(14));
        noticeBlur.setFill(S.bg);
        // liquid-glass saturation (2.2x) like the nav/title capsules, so the
        // bar reads as glass rather than a flat frosted strip
        noticeBlur.setLiquidGlass(true);
        noticeWrap.addView(noticeBlur, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noticeWrap.addView(noticeBar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        noticeWrap.setVisibility(View.GONE);
        root.addView(noticeWrap, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        root.addView(buildTopCapsule());
        root.addView(buildNavPill());

        // keep both floating capsules' realtime blur in lockstep with pager
        // scrolling: during and after page transitions every scroll frame
        // forces a re-capture, not just PreDraw-driven refreshes
        pager.setOnScrollListener(() -> {
            if (topBlur != null) topBlur.refresh();
            if (navBlur != null) navBlur.refresh();
            if (frostVeil != null) frostVeil.refresh();
            // the frost fade tracks the fractional tab position, so the
            // title/veil glass cross-fades DURING a page slide
            updateTopEdge();
        });

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsets(
                android.view.WindowInsets.Type.systemBars());
            insetTop = sys.top;
            insetBottom = sys.bottom;
            applyInsets();
            return insets;
        });
        // initial highlight without scrolling (currentTab is already 0)
        selectTab(0, false);
        return root;
    }

    private void applyInsets() {
        FrameLayout.LayoutParams top = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        top.topMargin = Math.max(dp(8), insetTop);
        topBar.setLayoutParams(top);

        FrameLayout.LayoutParams nav = (FrameLayout.LayoutParams) navBar.getLayoutParams();
        nav.bottomMargin = insetBottom + dp(22);
        navBar.setLayoutParams(nav);

        FrameLayout.LayoutParams notice = (FrameLayout.LayoutParams) noticeWrap.getLayoutParams();
        notice.bottomMargin = insetBottom + dp(96);
        noticeWrap.setLayoutParams(notice);

        syncFrostVeil();

        if (pageBodies != null) {
            for (LinearLayout body : pageBodies) {
                // body sits 15dp higher: 70dp was too airy under the capsule
                body.setPadding(dp(18), insetTop + dp(55), dp(18),
                    insetBottom + dp(100));
            }
        }
    }

    /** Keep the frosted veil in lockstep with the title capsule: the veil
     *  spans the full width from the top of the screen down to one capsule
     *  height BELOW the capsule's bottom edge; the fully-frosted zone covers
     *  the capsule plus one extra capsule of height, and the vertical fade
     *  band dissolves across the last capsule height (full frost → clear).
     *  Called on insets changes and whenever the capsule lays out. */
    private void syncFrostVeil() {
        if (frostVeil == null || topBar == null || topBar.getHeight() == 0) return;
        float pillTop = topBar.getY();
        int pillH = topBar.getHeight();
        // veil spans from the screen top down to ONE CAPSULE HEIGHT BELOW
        // the capsule's bottom edge: the fully-frosted zone now covers the
        // capsule plus an extra capsule of height, and the dissolve band
        // below it stays one capsule tall
        int veilH = (int) (pillTop + pillH * 2);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frostVeil.getLayoutParams();
        if (lp.height != veilH) {
            lp.height = veilH;
            frostVeil.setLayoutParams(lp);
        }
        if (veilH > 0) {
            // full frost down to capsuleBottom + capsuleHeight, dissolve
            // across the last capsule height of the pane
            frostVeil.setVerticalFade((pillTop + pillH) / veilH, 1f);
        }
    }

    private View wrapPage(View page) {
        FrameLayout holder = new FrameLayout(this);
        holder.addView(page, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    /** Vertical scroll view that hides the floating nav bar while scrolling
     *  up (content moves up) and restores it when scrolling back down. */
    private ScrollView scrollOf(LinearLayout body) {
        BounceScrollView scroll = new BounceScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        scroll.setOnScrollChangeListener((v, sl, st, ol, ot) -> {
            int dy = st - ot;
            if (dy > dp(2)) setNavVisible(false);
            else if (dy < -dp(2)) setNavVisible(true);
            // the title capsule's boundary follows the scroll continuously
            updateTopEdge();
        });
        return scroll;
    }

    /** A page's frost-fade source [0..1]: how far its content has scrolled
     *  past the top edge (~dp(28) reaches full strength). */
    private float pageScrollT(int i) {
        if (pages == null || i < 0 || i >= pages.length) return 0f;
        ScrollView sc = (ScrollView) pages[i].getChildAt(0);
        return sc == null ? 0f
            : Math.max(0f, Math.min(1f, sc.getScrollY() / (float) dp(28)));
    }

    /** Fade the title capsule's boundary (dual rim + elevation shadow, the
     *  nav pill's treatment) with the current page's scroll offset: fully
     *  hidden while the page sits at the top, fully visible once the
     *  content has scrolled up ~dp(28). The mapping is continuous in BOTH
     *  directions, so scrolling back up to the top fades it out again. */
    private void updateTopEdge() {
        if (topBar == null || topBlur == null || pages == null) return;
        // Blend the neighbouring pages' scroll positions by the pager's
        // FRACTIONAL position: during a tab slide the visible frost must
        // cross-fade between "old page scrolled" and "new page at top"
        // continuously instead of popping when currentTab flips. At rest
        // frac is an exact page index and this reduces to that page's t.
        float t = 0f;
        if (pager != null && pager.pageWidth > 0) {
            float frac = pager.getScrollX() / (float) pager.pageWidth;
            int i0 = Math.max(0, Math.min(pages.length - 1, (int) frac));
            int i1 = Math.max(0, Math.min(pages.length - 1, i0 + 1));
            float f = Math.max(0f, Math.min(1f, frac - i0));
            float t0 = pageScrollT(i0), t1 = pageScrollT(i1);
            t = t0 + (t1 - t0) * f;
        }
        if (t == topEdge) return;
        topEdge = t;
        // the frost stack fades with scroll: at the top only the floating
        // title text shows; scrolling up fades the frost + rim + sheen back
        // in. Frost strength (NOT view alpha) keeps an opaque page-colored
        // backing under the pane mid-fade, so live content never bleeds
        // through as a gray veil while the capsule is half-faded.
        topBlur.setFrostStrength(t);
        // the full-width top veil rides the same curve: fully transparent
        // while the page sits at its top, fading in as translucent glass
        // (no opaque backing) as the content scrolls up, and back out on
        // the return — continuously, both directions
        if (frostVeil != null) frostVeil.setFrostStrength(t);
        // shadow strength rides on elevation (0 → no cast, dp(12) → nav-like)
        topBar.setElevation(dp(12) * t);
        topBlur.setRimStrength(t);
        // dark mode: the frost's edge keeps a wide dissolve while at the top
        // and hardens as the boundary fades in. Light mode: no feather at
        // all — the frost edge itself stays, only rim + shadow fade.
        if (!lightMode) topBlur.setSoftEdge(true, dp(14) * (1f - t));
    }

    /** Immersive nav pill: while scrolling up it collapses toward the SCREEN
     *  bottom centre like a bubble sinking — parabolic velocity arc with
     *  squash distortion (the vertical drops first, squeezing the pill
     *  flat, with a last widening before it goes); scrolling back down
     *  re-inflates it out of the same screen-bottom point like a bubble
     *  rising: the vertical leads, the horizontal lags with a bulge past 1,
     *  then it settles with a slight overshoot above the rest point. */
    private android.animation.ValueAnimator navMorph;

    private void setNavVisible(boolean show) {
        if (show == navShown || navBar == null) return;
        navShown = show;
        if (navMorph != null) navMorph.cancel();
        // pivot at the SCREEN bottom centre, not the pill's own bottom edge:
        // the pill sinks into that point when collapsing and re-inflates out
        // of the very same point when expanding, so the two runs share one
        // coherent origin
        ViewGroup.LayoutParams glp = navBar.getLayoutParams();
        int bm = glp instanceof FrameLayout.LayoutParams
            ? ((FrameLayout.LayoutParams) glp).bottomMargin : 0;
        navBar.setPivotX(navBar.getWidth() / 2f);
        navBar.setPivotY(navBar.getHeight() + bm);
        // start from wherever a cancelled morph left the pill, so an
        // interrupted collapse/expand never jumps
        float tiny = 0.01f, tyMax = dp(24);
        float sx0 = navBar.getScaleX(), sy0 = navBar.getScaleY();
        float a0 = navBar.getAlpha(), ty0 = navBar.getTranslationY();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setInterpolator(new AccelerateDecelerateInterpolator());
        if (show) {
            va.setDuration(380);
            va.addUpdateListener(a -> {
                float p = (Float) a.getAnimatedValue();
                // vertical leads: fully inflated at ~75% of the run
                float v = Math.min(1f, p / 0.75f);
                float lead = 1f - (1f - v) * (1f - v);            // easeOutQuad
                float sy = sy0 + (1f - sy0) * lead;
                // horizontal lags, then bulges past 1 mid-flight and settles
                float lag = Math.min(1f, p / 0.9f);
                float hcurve = lag < 0.5f ? 2f * lag * lag          // in-out quad
                    : 1f - 2f * (1f - lag) * (1f - lag);
                float bulge = 0.18f * (float) Math.sin(Math.PI
                    * Math.min(1f, Math.max(0f, (p - 0.5f) / 0.5f)));
                float sx = sx0 + (1f - sx0) * hcurve + bulge * (1f - sx0);
                // rise with a slight overshoot above the rest point
                float r = Math.min(1f, p / 0.8f);
                float rise = 1f - (1f - r) * (1f - r);
                navBar.setTranslationY(ty0 * (1f - rise)
                    - dp(5) * (float) Math.sin(Math.PI * p));
                navBar.setScaleX(sx);
                navBar.setScaleY(sy);
                navBar.setAlpha(Math.min(1f, a0 + p * 1.6f));
            });
        } else {
            va.setDuration(320);
            va.addUpdateListener(a -> {
                float p = (Float) a.getAnimatedValue();
                // vertical drops first, squeezing the pill flat
                float v = Math.min(1f, p / 0.7f);
                float sy = sy0 + (tiny - sy0) * v * v;           // easeInQuad
                // horizontal holds a beat, one last widening, then collapses
                float lag = Math.min(1f, Math.max(0f, (p - 0.15f) / 0.85f));
                float hcurve = lag < 0.5f ? 2f * lag * lag
                    : 1f - 2f * (1f - lag) * (1f - lag);
                float widen = Math.max(0f, sx0 - tiny) * 0.10f
                    * (float) Math.sin(Math.PI * Math.min(1f, p / 0.4f));
                float sx = sx0 + (tiny - sx0) * hcurve + widen;
                navBar.setTranslationY(ty0 + (tyMax - ty0) * p * p); // parabola
                navBar.setScaleX(Math.max(tiny, sx));
                navBar.setScaleY(Math.max(tiny, sy));
                navBar.setAlpha(a0 * (1f - Math.min(1f, p * 1.8f)));
            });
        }
        navMorph = va;
        va.start();
    }

    private LinearLayout pageBody() {
        LinearLayout body = column();
        body.setPadding(dp(18), dp(4), dp(18), dp(28));
        return body;
    }

    // -------------------------------------------------- floating top bar

    private View buildTopCapsule() {
        // text-only capsule: the pill hugs the title. The blur backdrop must
        // NOT participate in the wrap_content measurement (a MATCH_PARENT
        // child would blow the capsule up to the whole screen), so the frame
        // hides it for the first measure pass, sizes itself to the text, and
        // re-measures with exact dimensions so the blur fills the pill.
        topBar = new FrameLayout(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                boolean shown = topBlur.getVisibility() == VISIBLE;
                topBlur.setVisibility(GONE);
                super.onMeasure(wSpec, hSpec);
                topBlur.setVisibility(shown ? VISIBLE : GONE);
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        // no background shape: the frost is built entirely by the blur view.
        // The boundary (rim + shadow) is scroll-linked and starts hidden.
        topBar.setBackground(null);
        // scroll-state shadow (滚动时的四周阴影): an elevation shadow cast
        // from a pill outline. clipToOutline must stay OFF — an outline used
        // for clipping never casts a shadow; the blur clips itself. The
        // radius is read live so it stays a true capsule at any text size.
        topBar.setClipToOutline(false);
        topBar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                    view.getHeight() / 2f);
            }
        });
        topBar.setElevation(0f);

        topBlur = new RealtimeBlurView(this);
        topBlur.setCaptureRoot(pager);
        topBlur.setFill(S.bg);
        // liquid glass frost: hard-saturated backdrop + faint diagonal sheen,
        // so the pane reads vivid glass — NOT a translucent white sheet
        topBlur.setLiquidGlass(true);
        // identical frost recipe to the nav pill: same blur radius, no extra
        // veil — the old white topFade was what made the title read as
        // semi-transparent while the nav bar looked different
        // near-clear frost: radius 0.5 (fractional — half of a radius-1
        // scratch blur) keeps content readable through the pane
        topBlur.setBlurRadius(5f);
        topBlur.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        // glass brightness lift sells the pane in light mode; in dark mode
        // any lift makes the glass lighter than the page and re-draws the
        // boundary, so it stays neutral there
        topBlur.setGlassLift(lightMode ? 8 : 0);
        // near-opaque frost: a hint of live content shimmer
        topBlur.setOpacity(240);
        topBlur.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        // press glow: same config as the nav pill so the frost recipe is
        // byte-identical (it only renders while the pane is touched, which
        // the title never is — kept purely for parity)
        topBlur.setGlow(true, lightMode ? 0x30000000 : 0x4DFFFFFF);
        // no boundary of any kind at the top: no rim, no shadow, no glow.
        // The boundary (dual rim lit at both edges + cast shadow) fades in
        // with scroll via updateTopEdge(). Light mode never feathers the
        // frost edge; dark mode keeps the wide dissolve that hides the
        // brighter glass edge until the rim takes over.
        topBlur.setSoftEdge(!lightMode, lightMode ? 0 : dp(14));
        topBlur.setRim(true, dp(1.4f),
            lightMode ? 0xB3FFFFFF : 0x73FFFFFF,
            lightMode ? 0x33FFFFFF : 0x1AFFFFFF);
        topBlur.setRimDual(true);
        topBlur.setRimStrength(0f);
        // initial state = page top: frost fully faded out (updateTopEdge
        // fades it back in with scroll)
        topBlur.setFrostStrength(0f);
        topBar.addView(topBlur, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout line = row();
        // compact capsule: tight padding around the unchanged 20sp title
        line.setPadding(dp(11), dp(4), dp(11), dp(4));
        line.addView(text("OPAirDrop", 20, true, S.text));
        topBar.addView(line, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // make every radius exactly half the height so it renders as a true
        // pill at any text size
        topBar.post(() -> {
            float r = topBar.getHeight() / 2f;
            topBlur.setCornerRadius(r);
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP);
        // left edge flush with the content cards (body padding is dp(18))
        lp.leftMargin = dp(18);
        topBar.setLayoutParams(lp);
        // the frost veil's height + fade band hang off the capsule's
        // geometry, so re-sync whenever the capsule moves/resizes
        topBar.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or2, ob) -> syncFrostVeil());
        return topBar;
    }

    // ------------------------------------------- floating navigation bar

    private View buildNavPill() {
        navBar = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(28));
        shape.setColor(S.navTint);
        navBar.setBackground(shape);
        // shadow around the pill (四周边界阴影): an elevation shadow cast from
        // a rounded outline. clipToOutline must stay OFF — an outline used
        // for clipping never casts a shadow; the blur clips itself.
        navBar.setClipToOutline(false);
        navBar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                    dp(28));
            }
        });
        navBar.setElevation(dp(12));

        navBlur = new RealtimeBlurView(this);
        navBlur.setCaptureRoot(pager);
        navBlur.setCornerRadius(dp(28));
        navBlur.setFill(S.bg);
        // same liquid-glass frost as the title capsule: hard-saturated
        // backdrop + diagonal sheen + same blur radius
        navBlur.setLiquidGlass(true);
        navBlur.setBlurRadius(5f);
        navBlur.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        navBlur.setGlassLift(lightMode ? 8 : 0);
        // near-opaque frost, same as the title capsule
        navBlur.setOpacity(240);
        navBlur.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        // press glow: light-blue particle light (淡蓝色光效) — fades in on
        // touch, trails the finger with satellite sparks, fades out on
        // release. Dark mode uses the exact same glow as light mode (same
        // alpha, size and particle behaviour); only the hue differs, keeping
        // the ice cyan-blue #80D0FF. Range is doubled via setGlowScale
        navBlur.setGlow(true, lightMode ? 0x597EC8FF : 0x5980D0FF);
        navBlur.setGlowScale(2f);
        navBlur.setGlowParticles(true);
        // dark mode: plain tracking glow only — no particle wandering, no
        // white-hot ignition — the same base follow-finger glow as light
        // mode, colour stays the ice cyan-blue #80D0FF
        navBlur.setGlowPlain(!lightMode);
        // luminous rim lit at BOTH edges: bright top → dim middle → bright
        // bottom, so the pill reads as lit from above and below
        navBlur.setRim(true, dp(1.4f),
            lightMode ? 0xB3FFFFFF : 0x73FFFFFF,
            lightMode ? 0x33FFFFFF : 0x1AFFFFFF);
        navBlur.setRimDual(true);
        navBar.addView(navBlur, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tabs = row();
        tabs.setPadding(dp(10), dp(6), dp(10), dp(8));

        tabIcons = new IconView[3];
        tabLabels = new TextView[3];
        String[] labels = {"首页", "状态", "设置"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout tab = column();
            tab.setGravity(Gravity.CENTER);

            IconView icon = new IconView(this, i);
            tabIcons[i] = icon;
            tab.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView label = text(labels[i], 11, false, S.textDim);
            label.setPadding(0, dp(3), 0, 0);
            tabLabels[i] = label;
            tab.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams tlp = mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tab.setLayoutParams(tlp);
            tabs.addView(tab);
        }

        // the whole pill is one touch surface split into three regions
        // (left/centre/right) mapping to 首页/状态/设置. A plain tap (no
        // slide) switches to the tapped tab with the page-settle animation.
        // Sliding uses a RELATIVE mapping anchored at the press position:
        // the finger travels three page-widths per pill width, so region
        // centre to region centre is exactly one page, and the pager
        // follows live from wherever it currently is (e.g. on 设置, press
        // 状态 centre and slide to 首页 centre → the page settles exactly
        // on 状态; keep sliding to the left edge → 首页 shows past half and
        // release drops it on 首页). The glow fades in on press, trails the
        // finger, and fades out on release. The pill deforms like jelly
        // towards the drag direction and springs back with a wobble.
        final float[] downX = {0f}, downY = {0f}, startPos = {0f}, pos = {0f};
        final boolean[] moved = {false};
        final int[] hover = {-1};
        tabs.setOnTouchListener((view, ev) -> {
            float x = ev.getX(), y = ev.getY();
            ViewGroup group = (ViewGroup) view;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = x;
                    downY[0] = y;
                    moved[0] = false;
                    // tap target: the region (left/centre/right) under the finger
                    hover[0] = Math.min(2, Math.max(0,
                        (int) (x / Math.max(1f, view.getWidth()) * 3f)));
                    // slide anchor: the pager's current fractional position,
                    // so the drag continues from wherever the page is
                    startPos[0] = pager.pageWidth > 0
                        ? pager.getScrollX() / (float) pager.pageWidth
                        : currentTab;
                    pos[0] = startPos[0];
                    if (navBar != null) navBar.animate().cancel();
                    cancelNavMorph();
                    group.getChildAt(hover[0]).animate().scaleX(0.94f).scaleY(0.94f)
                        .setDuration(90).start();
                    if (navBlur != null) navBlur.showGlow(x);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (!moved[0] && (Math.abs(x - downX[0]) > view.getWidth() * 0.06f
                            || Math.abs(y - downY[0]) > dp(14))) moved[0] = true;
                    if (moved[0]) {
                        // relative drag mapping: 3 pages per pill width,
                        // anchored at the press point — no jump on touch-down
                        pos[0] = Math.max(0f, Math.min(2f, startPos[0]
                            + (x - downX[0]) * 3f
                                / Math.max(1f, view.getWidth())));
                        pager.scrubTo(pos[0]);
                        int near = Math.max(0, Math.min(2, Math.round(pos[0])));
                        if (near != hover[0]) {
                            if (hover[0] >= 0) group.getChildAt(hover[0]).animate()
                                .scaleX(1f).scaleY(1f).setDuration(120).start();
                            hover[0] = near;
                        }
                        group.getChildAt(near).animate().scaleX(0.94f).scaleY(0.94f)
                            .setDuration(120).start();
                        deformNav(x - downX[0], y - downY[0]);
                        if (navBlur != null) navBlur.moveGlow(x);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    springNav();
                    if (hover[0] >= 0) group.getChildAt(hover[0]).animate()
                        .scaleX(1f).scaleY(1f).setDuration(140).start();
                    if (navBlur != null) navBlur.hideGlow();
                    if (!moved[0]) {
                        // plain tap: system click haptic, then switch to the
                        // tapped tab with animation
                        tapVibrate();
                        if (hover[0] != currentTab) selectTab(hover[0], true);
                    } else {
                        // settle on the nearest tab; the fractional .5 cases
                        // lean toward the direction the finger was travelling
                        float dir = x - downX[0];
                        float lean = dir < 0 ? -0.05f : dir > 0 ? 0.05f : 0f;
                        int target = Math.max(0, Math.min(2,
                            Math.round(pos[0] + lean)));
                        if (target != currentTab) selectTab(target, true);
                        else pager.scrollToPage(currentTab, true);
                    }
                    hover[0] = -1;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    springNav();
                    if (hover[0] >= 0) group.getChildAt(hover[0]).animate()
                        .scaleX(1f).scaleY(1f).setDuration(140).start();
                    if (navBlur != null) navBlur.hideGlow();
                    hover[0] = -1;
                    break;
            }
            return true;
        });
        navBar.addView(tabs, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56),
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(34);
        lp.rightMargin = dp(34);
        lp.bottomMargin = dp(22);
        navBar.setLayoutParams(lp);
        return navBar;
    }

    /** Elastic nav deformation: horizontal drags STRETCH the pill toward
     *  both sides symmetrically (a subtle vertical squeeze keeps the area
     *  constant, like pulled taffy); vertical drags still drift after the
     *  finger with rubber resistance. */
    private void deformNav(float dx, float dy) {
        if (navBar == null) return;
        float w = navBar.getWidth(), h = navBar.getHeight();
        if (w == 0 || h == 0) return;
        navBar.animate().cancel();
        cancelNavMorph();
        float maxY = dp(36);
        float shiftY = maxY * (dy / (maxY + Math.abs(dy)));
        float amtY = Math.abs(shiftY) / maxY;
        // horizontal stretch: reaches full elongation after ~150dp of travel
        float amtX = Math.min(1f, Math.abs(dx) / dp(150));
        navBar.setTranslationX(0f);
        navBar.setTranslationY(shiftY * 0.30f);
        navBar.setPivotX(w / 2f);
        navBar.setPivotY(dy > 0 ? 0 : h);
        navBar.setScaleX(1f + 0.10f * amtX);
        navBar.setScaleY(1f + 0.05f * amtY - 0.04f * amtX);
    }

    /** rigid release: the elastically stretched nav pill snaps straight
     *  back to its rest shape — no wobble */
    private void springNav() {
        if (navBar == null) return;
        navBar.animate().cancel();
        cancelNavMorph();
        navBar.animate().translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(new LinearInterpolator())
            .start();
    }

    /** stop the immersive bubble morph so touch-driven deformation never
     *  fights a running collapse/expand animation */
    private void cancelNavMorph() {
        if (navMorph != null) navMorph.cancel();
    }

    /** short system click haptic for nav tab taps (system vibration effect) */
    private void tapVibrate() {
        try {
            android.os.VibratorManager vm = (android.os.VibratorManager)
                getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            android.os.Vibrator vib = vm.getDefaultVibrator();
            if (vib == null || !vib.hasVibrator()) return;
            vib.vibrate(android.os.VibrationEffect.createPredefined(
                android.os.VibrationEffect.EFFECT_CLICK));
        } catch (Exception e) {
            // haptics are cosmetic: never let a ROM quirk crash the app
        }
    }

    // ------------------------------------------------------------- pages

    private View buildHomePage() {
        LinearLayout body = pageBody();

        // hero card — adaptive surface, same palette family as the cards
        LinearLayout hero = column();
        GradientDrawable heroBg = new GradientDrawable();
        heroBg.setColor(S.card);
        heroBg.setCornerRadius(dp(24));
        hero.setBackground(heroBg);
        hero.setPadding(dp(22), dp(24), dp(22), dp(24));

        // single intro line in the LSP-row title spec (15sp bold); wraps
        // onto as many lines as needed, the card grows with it
        TextView heroTitle = text(
            "OPAirDrop，让 ColorOS 设备与附近的 Apple 设备无缝互传。",
            15, true, S.text);
        hero.addView(heroTitle);
        body.addView(hero, margins(0, 16));

        // environment card: LSP framework + root access
        body.addView(sectionTitle("环境"));
        LinearLayout envCard = card();
        // zero card padding: rows are full-bleed so the press highlight spans
        // divider-to-divider / edge-to-edge; insets move into the rows
        envCard.setPadding(0, 0, 0, 0);

        // status pill is passive; the whole row acts as the button
        lspPill = statusPill("检测中", S.gray);
        TextView[] lspSub = new TextView[1];
        lspRow = featureRow(3, "LSP 框架", "未连接框架服务", lspPill, lspSub);
        lspSubtitle = lspSub[0];
        lspRow.setPadding(dp(18), 0, dp(18), 0);
        envCard.addView(lspRow, mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), 0f));

        // divider inset to the text edge (row pad + tile + gap), matching
        // the device info card below
        View envDivider = divider();
        LinearLayout.LayoutParams envDlp =
            mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
        envDlp.leftMargin = dp(74);
        envCard.addView(envDivider, envDlp);

        rootPill = statusPill("检测中", S.gray);
        TextView[] rootSub = new TextView[1];
        rootRow = featureRow(4, "root 权限", "未获得 Root 权限", rootPill, rootSub);
        rootSubtitle = rootSub[0];
        rootRow.setPadding(dp(18), 0, dp(18), 0);
        envCard.addView(rootRow, mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), 0f));
        body.addView(envCard, margins(0, 16));

        // device information block, spec rows: icon tile + stacked
        // title/value; dividers inset to the text edge, not the card edge
        body.addView(sectionTitle("设备信息"));
        LinearLayout devCard = card();
        devCard.setPadding(0, 0, 0, 0);

        LinearLayout devRow1, devRow2, devRow3, devRow4, devRow5, devRowK;
        devModel = text("—", 13.5f, false, S.textDim);
        devRow1 = deviceRow(5, "设备型号", devModel);
        devBuild = text("—", 13.5f, false, S.textDim);
        devRow2 = deviceRow(6, "版本号", devBuild);
        devKernel = text("—", 13.5f, false, S.textDim);
        devRowK = deviceRow(10, "内核版本", devKernel);
        fillKernelInfo(devKernel);
        devColoros = text("—", 13.5f, false, S.textDim);
        devRow3 = deviceRow(7, "ColorOS 版本", devColoros);
        devAndroid = text("—", 13.5f, false, S.textDim);
        devRow4 = deviceRow(8, "Android 版本", devAndroid);
        devPatch = text("—", 13.5f, false, S.textDim);
        devRow5 = deviceRow(9, "安全补丁", devPatch);
        LinearLayout[] devRows =
            {devRow1, devRow2, devRowK, devRow3, devRow4, devRow5};
        for (int i = 0; i < devRows.length; i++) {
            LinearLayout r = devRows[i];
            r.setPadding(dp(18), 0, dp(18), 0);
            devCard.addView(r, mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), 0f));
            if (i < devRows.length - 1) {
                // divider starts where the text starts: row pad + tile + gap
                View d = divider();
                LinearLayout.LayoutParams dlp =
                    mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
                dlp.leftMargin = dp(74);
                devCard.addView(d, dlp);
            }
            pressableRow(r, i == 0 ? CORNERS_TOP
                : i == devRows.length - 1 ? CORNERS_BOTTOM : null);
        }
        body.addView(devCard, margins(0, 16));
        return scrollOf(body);
    }

    private View buildStatusPage() {
        LinearLayout body = pageBody();

        body.addView(sectionTitle("服务状态"));

        // service card, spec-row layout like the environment /
        // device info cards: 70dp full-bleed rows with dividers between,
        // status pill matching the env card's 已连接/未连接 pill
        LinearLayout services = card();
        services.setPadding(0, 0, 0, 0);
        String[][] svcDefs = {
            {"AWDL", "Apple 无线直连发现协议"},
            {"QuickShare", "Google 快享服务"},
            {"OShare", "系统互传服务"}};
        TextView[] svcPills = {null, null, null};
        for (int i = 0; i < svcDefs.length; i++) {
            svcPills[i] = statusPill("—", S.gray);
            if (i == 0) stAwdl = svcPills[i];
            else if (i == 1) stQuickshare = svcPills[i];
            else stOshare = svcPills[i];
            LinearLayout r = serviceRow(svcDefs[i][0], svcDefs[i][1],
                svcPills[i]);
            r.setPadding(dp(18), 0, dp(18), 0);
            services.addView(r, mm(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(66), 0f));
            if (i < svcDefs.length - 1) {
                // divider inset to the text edge (18dp row pad)
                View d = divider();
                LinearLayout.LayoutParams dlp =
                    mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
                dlp.leftMargin = dp(18);
                services.addView(d, dlp);
            }
        }
        body.addView(services, margins(0, 12));

        // refresh button sits BETWEEN the 服务状态 and 传输状态 sections;
        // spacing is symmetric: card→button gap (12+8) equals button→title
        // gap (8+12), so the button floats evenly between the two sections
        TextView refreshBtn = mainButton("刷新状态", v -> {
            refresh();
            refreshHistory();
            showNotice("已刷新", S.green);
        });
        body.addView(refreshBtn, margins(8, 8));

        // —— 传输状态 —— legacy WebUI real-time speed card, now native:
        // live send/receive rates plus a single expandable 传输记录 row
        body.addView(sectionTitle("传输状态"), margins(12, 0));

        LinearLayout liveCard = card();
        LinearLayout liveHead = row();
        liveHead.addView(text("实时传输", 16, true, S.text),
            mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        txPill = statusPill("读取中", S.gray);
        liveHead.addView(txPill, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        liveCard.addView(liveHead);

        // two-column speed display with a vertical divider between
        LinearLayout speeds = row();
        speeds.setPadding(0, dp(20), 0, dp(4));
        txValue = text("—", 30, true, S.text);
        speeds.addView(speedColumn("↑ 发送", txValue),
            mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View speedDivider = divider();
        speeds.addView(speedDivider, new LinearLayout.LayoutParams(
            dp(1), dp(64)));
        rxValue = text("—", 30, true, S.text);
        LinearLayout rxCol = speedColumn("↓ 接收", rxValue);
        rxCol.setPadding(dp(24), 0, 0, 0);
        speeds.addView(rxCol, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        liveCard.addView(speeds);
        body.addView(liveCard, margins(0, 16));

        // single 传输记录 row: title + record count, up/down chevrons on the
        // right; tapping it opens the floating records sheet below
        LinearLayout recRow = card();
        recRow.setOnClickListener(v -> toggleHistorySheet(recRow));
        pressable(recRow);
        LinearLayout recLine = row();
        recLine.addView(text("传输记录", 15, true, S.text),
            mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        historyCount = caption("—");
        recLine.addView(historyCount, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout chevrons = column();
        chevrons.setPadding(dp(10), 0, 0, 0);
        arrowUp = text("⌃", 12, true, S.textDim);
        arrowUp.setGravity(Gravity.CENTER);
        arrowUp.setPadding(0, 0, 0, dp(1));
        arrowDown = text("⌄", 12, true, S.accent);
        arrowDown.setGravity(Gravity.CENTER);
        chevrons.addView(arrowUp);
        chevrons.addView(arrowDown);
        recLine.addView(chevrons, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        recRow.addView(recLine);
        LinearLayout.LayoutParams recLp = margins(0, 16);
        recLp.height = dp(66);
        body.addView(recRow, recLp);
        return scrollOf(body);
    }

    /** One half of the live speed display: direction caption, big tabular
     *  number, MB/s unit below. */
    private LinearLayout speedColumn(String dir, TextView value) {
        LinearLayout col = column();
        col.addView(caption(dir));
        value.setFontFeatureSettings("tnum");
        value.setPadding(0, dp(8), 0, 0);
        col.addView(value);
        TextView unit = caption("MB/s");
        unit.setPadding(0, dp(2), 0, 0);
        col.addView(unit);
        return col;
    }

    private LinearLayout serviceRow(String title, String desc, TextView badgeView) {
        LinearLayout line = row();
        LinearLayout left = column();
        left.addView(text(title, 15, true, S.text));
        TextView d = caption(desc);
        d.setPadding(0, dp(2), 0, 0);
        left.addView(d);
        line.addView(left, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(badgeView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private View buildSettingsPage() {
        LinearLayout body = pageBody();
        settingsBuilt = true;

        body.addView(sectionTitle("分享入口"));
        LinearLayout modeCard = card();
        modeCard.addView(text("与 Apple 设备收发", 16, true, S.text));
        TextView modeDesc = caption("决定哪个入口提供 AirDrop 桥接。");
        modeDesc.setPadding(0, dp(3), 0, 0);
        modeCard.addView(modeDesc);

        modeOptions = new View[3];
        modeOptionDot0 = new TextView(this);
        modeOptionDot1 = new TextView(this);
        modeOptionDot2 = new TextView(this);
        modeOptions[0] = modeOption("Quick Share", "使用 Quick Share 与 Apple 收发",
            modeOptionDot0, v -> pickMode("quickshare"));
        modeOptions[1] = modeOption("OShare", "使用系统互传与 Apple 收发",
            modeOptionDot1, v -> pickMode("oshare"));
        modeOptions[2] = modeOption("二者共存", "两个入口均可发送",
            modeOptionDot2, v -> pickMode("both"));
        for (int i = 0; i < modeOptions.length; i++) {
            modeCard.addView(modeOptions[i], margins(i == 0 ? 12 : 10, i == 2 ? 0 : 2));
        }
        body.addView(modeCard, margins(0, 16));

        body.addView(sectionTitle("设备"));
        LinearLayout nameCard = card();
        nameCard.addView(text("AirDrop 名称", 16, true, S.text));
        nameSummary = text("读取中…", 13, false, S.textDim);
        nameSummary.setPadding(0, dp(3), 0, 0);
        nameCard.addView(nameSummary);
        nameInput = darkInput("显示名称");
        nameCard.addView(nameInput, margins(12, 12));
        LinearLayout nameBtns = row();
        TextView nameReset = ghostButton("恢复默认", v -> runAction("恢复默认名称", "name-reset"));
        TextView nameSave = mainButton("保存名称", v -> saveName());
        nameReset.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameSave.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) nameSave.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        nameBtns.addView(nameReset);
        nameBtns.addView(nameSave);
        nameCard.addView(nameBtns);
        body.addView(nameCard, margins(0, 16));

        body.addView(sectionTitle("文件接收"));
        LinearLayout dirCard = card();
        dirCard.addView(text("接收文件保存位置", 16, true, S.text));
        TextView dirDesc = caption("仅限 Download 或 Documents 下的目录，留空按类型保存。");
        dirDesc.setPadding(0, dp(3), 0, 0);
        dirCard.addView(dirDesc);
        directorySummary = text("读取中…", 13, false, S.textDim);
        directorySummary.setPadding(0, dp(3), 0, 0);
        dirCard.addView(directorySummary);
        directoryInput = darkInput("Download/OPAirShare");
        directoryInput.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        dirCard.addView(directoryInput, margins(12, 12));
        LinearLayout dirBtns = row();
        TextView dirReset = ghostButton("恢复默认",
            v -> runAction("恢复默认保存位置", "receive-directory-reset"));
        TextView dirSave = mainButton("保存位置", v -> saveDirectory());
        dirReset.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dirSave.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) dirSave.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        dirBtns.addView(dirReset);
        dirBtns.addView(dirSave);
        dirCard.addView(dirBtns);
        body.addView(dirCard, margins(0, 16));

        body.addView(sectionTitle("通用"));
        LinearLayout logCard = card();
        logCard.addView(text("日志", 16, true, S.text));
        logView = text("", 12, false, S.textDim);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setVisibility(View.GONE);
        LinearLayout logBtns = row();
        TextView logLoad = ghostButton("查看日志", v -> loadLogs());
        TextView logExport = ghostButton("导出日志", v -> runAction("导出日志", "export-logs", true));
        logLoad.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logExport.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) logExport.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        logBtns.addView(logLoad);
        logBtns.addView(logExport);
        logCard.addView(logBtns, margins(12, 12));
        logCard.addView(logView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        body.addView(logCard, margins(0, 0));
        return scrollOf(body);
    }

    private View modeOption(String title, String desc, TextView dot,
                            View.OnClickListener listener) {
        LinearLayout line = row();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(S.cardHi);
        line.setBackground(bg);
        line.setPadding(dp(14), dp(12), dp(14), dp(12));
        line.setOnClickListener(listener);
        pressable(line);

        dot.setTextSize(16);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(S.textDim);
        dot.setText("○");
        line.addView(dot, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout mid = column();
        mid.setPadding(dp(12), 0, 0, 0);
        TextView t = text(title, 14.5f, true, S.text);
        TextView d = caption(desc);
        d.setPadding(0, dp(2), 0, 0);
        mid.addView(t);
        mid.addView(d);
        line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    private void pickMode(String mode) {
        if (busy) return;
        runAction("切换分享入口", "share-mode " + mode);
    }

    private void highlightMode(String mode) {
        TextView[] dots = {modeOptionDot0, modeOptionDot1, modeOptionDot2};
        String[] ids = {"quickshare", "oshare", "both"};
        for (int i = 0; i < dots.length; i++) {
            boolean sel = ids[i].equals(mode);
            dots[i].setText(sel ? "●" : "○");
            dots[i].setTextColor(sel ? S.accent : S.textDim);
            View opt = modeOptions != null ? modeOptions[i] : null;
            if (opt instanceof LinearLayout) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                bg.setColor(sel ? 0x143B82F6 : S.cardHi);
                opt.setBackground(sel ? strokeBorder(bg) : bg);
            }
        }
    }

    private GradientDrawable strokeBorder(GradientDrawable bg) {
        bg.setStroke(dp(1), S.accent);
        return bg;
    }

    // ------------------------------------------------------ tab switching

    private void selectTab(int index, boolean fromUser) {
        if (pages == null) return;
        if (fromUser && index == currentTab) return;
        currentTab = index;
        pager.scrollToPage(index, fromUser);
        updateTabs();
        setNavVisible(true);
        // the new page may be scrolled: re-evaluate the title boundary
        updateTopEdge();
    }

    private void updateTabs() {
        for (int i = 0; i < 3; i++) {
            boolean sel = i == currentTab;
            tabIcons[i].setColor(sel ? S.accent : S.textDim);
            tabLabels[i].setTextColor(sel ? S.accent : S.textDim);
            tabIcons[i].animate().scaleX(sel ? 1.12f : 1f).scaleY(sel ? 1.12f : 1f)
                .setDuration(180).start();
        }
    }

    // --------------------------------------------------------- notices

    private void showNotice(String message, int color) {
        main.removeCallbacks(noticeHide);
        main.post(() -> {
            noticeBar.setText(message);
            noticeBar.setTextColor(color);
            noticeWrap.setVisibility(View.VISIBLE);
            noticeWrap.setAlpha(0f);
            noticeWrap.setTranslationY(dp(12));
            noticeWrap.animate().alpha(1f).translationY(0f).setDuration(180).start();
            main.postDelayed(noticeHide, 3600);
        });
    }

    private void hideNotice() {
        noticeWrap.animate().alpha(0f).translationY(dp(12)).setDuration(160)
            .withEndAction(() -> noticeWrap.setVisibility(View.GONE)).start();
    }

    // -------------------------------------------------------- backend

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private Result exec(String args) {
        return shell(new String[]{"su", "-c", SETTINGS + " " + args});
    }

    /** Arbitrary command through the same su channel (environment probing). */
    private Result suExec(String cmd) {
        return shell(new String[]{"su", "-c", cmd});
    }

    private Result shell(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            int code = p.waitFor();
            return new Result(code, out, err);
        } catch (Exception e) {
            return new Result(-1, "", String.valueOf(e));
        }
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private interface Job {
        void run();
    }

    private void async(Job job) {
        io.execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                showNotice(String.valueOf(e), S.red);
            }
        });
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        async(() -> {
            Result r = exec("status");
            if (r.code != 0) {
                busy = false;
                showNotice(suFailure(r), S.red);
                return;
            }
            try {
                Status s = Status.parse(r.out);
                lastStatus = s;
                main.post(() -> applyStatus(s));
            } catch (Exception e) {
                showNotice("模块状态无法解析：" + e.getMessage(), S.red);
            }
            busy = false;
        });
    }

    private static String suFailure(Result r) {
        String err = r.err != null && !r.err.trim().isEmpty() ? r.err.trim() : "su 不可用";
        if (err.contains("not allowed") || err.contains("denied") || r.code == -1) {
            return "无法执行模块命令（" + err + "）。请在 KernelSU 管理器中授权本应用。";
        }
        return "模块操作失败：" + err;
    }

    private void runAction(String label, String args) {
        runAction(label, args, false);
    }

    private void runAction(String label, String args, boolean raw) {
        if (busy) return;
        busy = true;
        async(() -> {
            ensureProviderEnabled(args);
            Result r = exec(args);
            if (r.code != 0) {
                busy = false;
                showNotice(label + "失败：" + suFailure(r), S.red);
                return;
            }
            String note = raw ? (label + "完成\n" + r.out.trim()) : null;
            Result st = exec("status");
            if (st.code == 0) {
                try {
                    Status s = Status.parse(st.out);
                    lastStatus = s;
                    main.post(() -> applyStatus(s));
                } catch (Exception ignored) {
                    // status refresh failed but the action itself succeeded
                }
            }
            busy = false;
            main.post(() -> showNotice(note != null ? note : label + "完成", S.green));
        });
    }

    /**
     * App-side safety net for provider rescue. The module disables the mosey
     * provider package after a session release to save power and re-enables
     * it during the next arm, but an interrupted arm (e.g. COEX_BLOCKED with
     * wifi down) leaves the package disabled; a later bridge boot then fails
     * with ActivityNotFoundException ("AirDrop 服务未启用"). Reading the
     * enabled setting of another package needs no permission; re-enabling
     * goes through the existing su channel. Runs only on share-mode switches
     * so idle releases keep their power saving, and only fires when actually
     * disabled to avoid the PACKAGE_CHANGED broadcast that restarts the
     * provider process and tears down the mDNS advertisement.
     */
    private void ensureProviderEnabled(String args) {
        if (args == null || !args.startsWith("share-mode")) return;
        try {
            int state = getPackageManager().getApplicationEnabledSetting(PROVIDER_PKG);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return;
            }
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "/system/bin/pm enable --user 0 " + PROVIDER_PKG});
            read(p.getInputStream());
            read(p.getErrorStream());
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private void saveName() {
        if (busy) return;
        String value = nameInput.getText().toString().trim();
        if (!validName(value)) {
            showNotice("名称最多 64 个字符，不能包含换行或控制字符", S.red);
            return;
        }
        String hex = hex(value);
        runAction("保存名称", hex.isEmpty() ? "name-reset" : "name-set " + hex);
    }

    private void saveDirectory() {
        if (busy) return;
        String value = directoryInput.getText().toString().trim();
        if (!validDirectory(value)) {
            showNotice("请输入 Download 或 Documents 下的目录，不能包含隐藏目录或非法字符", S.red);
            return;
        }
        String hex = hex(value);
        runAction("保存位置", hex.isEmpty() ? "receive-directory-reset"
            : "receive-directory-set " + hex);
    }

    private void loadLogs() {
        if (busy) return;
        busy = true;
        async(() -> {
            Result r = exec("logs");
            main.post(() -> {
                logView.setVisibility(View.VISIBLE);
                logView.setText(r.code == 0
                    ? (r.out.isEmpty() ? "暂无日志" : r.out)
                    : "读取失败：" + suFailure(r));
            });
            busy = false;
        });
    }

    private void applyStatus(Status s) {
        // status page
        applyService(stAwdl, s.awdl);
        applyService(stQuickshare, s.quickshare);
        applyService(stOshare, s.oshare);

        // settings page
        highlightMode(s.shareMode);
        nameSummary.setText(s.airdropName.isEmpty()
            ? "当前：" + s.airdropNameDefault + "（默认）" : "当前：" + s.airdropName);
        nameInput.setHint(s.airdropNameDefault);
        if (!nameInput.hasFocus()) nameInput.setText(s.airdropNameCustom);
        directorySummary.setText(s.receiveDirectory.isEmpty()
            ? "当前：按类型保存到默认目录" : "当前：" + s.receiveDirectory);
        if (!directoryInput.hasFocus()) directoryInput.setText(s.receiveDirectory);
    }

    private void applyService(TextView tv, String state) {
        String label = service(state);
        int color = switch (state == null ? "" : state) {
            case "running" -> S.green;
            case "stopped" -> S.orange;
            case "not_installed" -> S.red;
            case "idle" -> S.gray;
            default -> S.gray;
        };
        setPill(tv, label, color);
    }

    private static String service(String value) {
        if (value == null || value.isEmpty()) return "未知";
        switch (value) {
            case "running": return "运行中";
            case "stopped": return "未启动";
            case "idle": return "未运行";
            case "not_installed": return "未安装";
            default: return value;
        }
    }

    // --------------------------------------------- transfer status (speed card)

    private Result execHistory() {
        return shell(new String[]{"su", "-c", SPEED_HISTORY + " status"});
    }

    /** One speed-history session record, mirroring the module's
     *  speed-history.sh record_json output. */
    private static final class SpeedRecord {
        String id, state, kind;
        long startedAt;
        double durationS, txBps, rxBps, peakTx, peakRx, avgTx, avgRx;
        long sent, recv;

        static SpeedRecord parse(JSONObject o) {
            SpeedRecord r = new SpeedRecord();
            r.id = o.optString("id");
            r.state = o.optString("state");
            r.kind = o.optString("kind");
            r.startedAt = o.optLong("started_at");
            r.durationS = o.optDouble("duration_s");
            r.sent = o.optLong("sent_bytes");
            r.recv = o.optLong("received_bytes");
            r.peakTx = o.optDouble("peak_tx_bps");
            r.peakRx = o.optDouble("peak_rx_bps");
            r.avgTx = o.optDouble("average_tx_bps");
            r.avgRx = o.optDouble("average_rx_bps");
            r.txBps = o.optDouble("tx_bps");
            r.rxBps = o.optDouble("rx_bps");
            return r;
        }
    }

    /** Poll the speed recorder. Runs on a timer while the status page is on
     *  screen, and from the 刷新状态 button. Failures stay silent: the next
     *  poll retries, and a missing recorder just keeps the idle display. */
    private void refreshHistory() {
        if (historyBusy || gone) return;
        historyBusy = true;
        async(() -> {
            Result r = execHistory();
            historyBusy = false;
            if (gone || r.code != 0) return;
            try {
                JSONObject o = new JSONObject(r.out);
                boolean recorderOn = "on".equals(o.optString("recorder"));
                SpeedRecord live = o.has("live") && !o.isNull("live")
                    ? SpeedRecord.parse(o.getJSONObject("live")) : null;
                List<SpeedRecord> done = new ArrayList<>();
                org.json.JSONArray arr = o.optJSONArray("history");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        done.add(SpeedRecord.parse(arr.getJSONObject(i)));
                    }
                }
                main.post(() -> applyTransfer(recorderOn, live, done));
            } catch (Exception ignored) {
                // malformed payload: keep the previous rendering
            }
        });
    }

    private void applyTransfer(boolean recorderOn, SpeedRecord live,
                               List<SpeedRecord> done) {
        boolean recording = recorderOn && live != null
            && "recording".equals(live.state);
        if (recording) {
            setPill(txPill, "正在记录", S.green);
        } else {
            setPill(txPill, recorderOn ? "等待传输" : "等待服务", S.gray);
        }
        txValue.setText(recorderOn && live != null
            ? speed(live.txBps) : "—");
        rxValue.setText(recorderOn && live != null
            ? speed(live.rxBps) : "—");

        // history: completed records newest first; while a session is being
        // recorded it is prepended as 进行中 (same rule as the old WebUI)
        List<SpeedRecord> records = new ArrayList<>();
        if (recording && !live.id.isEmpty()
            && ("transfer".equals(live.kind) || live.sent + live.recv >= 65536)) {
            records.add(live);
        }
        for (SpeedRecord r : done) {
            if (recording && r.id.equals(live.id)) continue;
            records.add(r);
        }
        historyCount.setText(records.size() + " 条");

        // records live inside the floating sheet; refresh it in place while
        // it is open (the poller keeps running behind the overlay)
        if (historyList == null) return;
        historyList.removeAllViews();
        if (records.isEmpty()) {
            TextView empty = caption("暂无记录");
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, dp(28));
            historyList.addView(empty, mm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
            return;
        }
        int show = Math.min(records.size(), 30);
        for (int i = 0; i < show; i++) {
            SpeedRecord r = records.get(i);
            boolean isLive = i == 0 && recording;
            historyList.addView(historyCard(r, isLive), margins(0, 8));
        }
    }

    /** Expand / collapse the floating 传输记录 sheet: a frosted card whose
     *  page backdrop is left untouched (no window-level blur), horizontally matching
     *  the body width, its top edge at the title capsule's bottom edge and
     *  its bottom edge as far above the screen bottom as the nav pill's.
     *  Opening: the card accelerates out of the 传输记录 row's top and
     *  bottom edges; closing: it accelerates back into the row. The nav
     *  pill immerses while open and returns as the sheet closes. Records
     *  scroll beneath frosted gradient header/footer masks. */
    private void toggleHistorySheet(View row) {
        if (historyExpanded) {
            collapseHistorySheet();
            return;
        }
        historyExpanded = true;
        setHistoryArrows(true);
        setNavVisible(false);
        FrameLayout root = (FrameLayout) activityRoot;

        FrameLayout overlay = new FrameLayout(this);

        // the page content behind the sheet stays UNBLURRED — the sheet is
        // the only frosted element, the backdrop shows through untouched.
        // captureRoot of the card's pane MUST be the pager, never
        // activityRoot: the overlay is ITSELF a child of activityRoot, so
        // an activityRoot capture would traverse the sheet and record its
        // own pane's flat backing into the frost source (messy blur,
        // invisible card boundary) and every nav glow frame would re-dirty
        // the capture (flickering backdrop).
        overlay.setOnClickListener(v -> collapseHistorySheet());

        // frosted card: veil backing + live blur pane, body-card corners
        FrameLayout sheet = new FrameLayout(this);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(S.veil);
        frameBg.setCornerRadius(dp(22));
        sheet.setBackground(frameBg);
        sheet.setClipToOutline(true);
        sheet.setElevation(dp(16));
        RealtimeBlurView cardBlur = new RealtimeBlurView(this);
        cardBlur.setCaptureRoot(pager);
        cardBlur.setCornerRadius(dp(22));
        cardBlur.setFill(S.bg);
        sheet.addView(cardBlur, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = column();
        LinearLayout head = row();
        head.setPadding(dp(18), dp(16), dp(18), dp(10));
        head.addView(text("传输记录", 16, true, S.text),
            mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(caption(recordsText()), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(head);

        // scroll area with frosted gradient masks: the header fades upward
        // into the card edge (bottom→top), the footer fades downward —
        // records slide beneath them like glass edge fog
        FrameLayout scrollerHost = new FrameLayout(this);
        BounceScrollView sc = new BounceScrollView(this);
        sc.setFillViewport(false);
        sc.setVerticalScrollBarEnabled(false);
        historyList = column();
        historyList.setPadding(dp(14), dp(4), dp(14), dp(12));
        sc.addView(historyList);
        scrollerHost.addView(sc, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int frostH = dp(36);
        View topFade = new View(this);
        GradientDrawable topG = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{S.bg, (S.bg & 0x00FFFFFF)});
        topFade.setBackground(topG);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, frostH, Gravity.TOP);
        scrollerHost.addView(topFade, tlp);
        View botFade = new View(this);
        GradientDrawable botG = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{(S.bg & 0x00FFFFFF), S.bg});
        botFade.setBackground(botG);
        scrollerHost.addView(botFade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, frostH, Gravity.BOTTOM));
        content.addView(scrollerHost, mm(ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1f));
        sheet.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // target frame: full body area between the title bar's bottom and
        // the nav pill's bottom offset; seed the card at the row's bounds
        int rootH = root.getHeight();
        historyTopEdge = (int) topBar.getY() + topBar.getHeight();
        historyBotFree = insetBottom + dp(22);
        int[] rl = new int[2], rt = new int[2];
        row.getLocationOnScreen(rl);
        root.getLocationOnScreen(rt);
        historyRowTop = rl[1] - rt[1];
        historyRowBottom = historyRowTop + row.getHeight();

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // body width: same left/right margins as the page content, so the
        // card is horizontally centered at exactly the text column's width
        sheetLp.leftMargin = dp(18);
        sheetLp.rightMargin = dp(18);
        sheetLp.topMargin = historyRowTop;
        sheetLp.bottomMargin = rootH - historyRowBottom;
        overlay.addView(sheet, sheetLp);
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(150).start();

        // entrance: top and bottom edges accelerate apart from the row
        android.animation.ValueAnimator va =
            android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(340);
        va.setInterpolator(new AccelerateInterpolator(1.6f));
        va.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            sheetLp.topMargin = (int) (historyRowTop
                + (historyTopEdge - historyRowTop) * p);
            sheetLp.bottomMargin = (int) ((rootH - historyRowBottom)
                + (historyBotFree - (rootH - historyRowBottom)) * p);
            sheet.setLayoutParams(sheetLp);
        });
        // frost ready for frame one: synchronous capture OUTSIDE any draw
        // pass, before the entrance animation starts
        cardBlur.captureNow();
        va.start();
        historyOverlay = overlay;
        refreshHistory();
    }

    private void collapseHistorySheet() {
        if (!historyExpanded || historyOverlay == null) return;
        historyExpanded = false;
        setHistoryArrows(false);
        setNavVisible(true);
        FrameLayout overlay = historyOverlay;
        historyOverlay = null;
        historyList = null;
        FrameLayout sheet = (FrameLayout) overlay.getChildAt(0);
        int rootH = activityRoot.getHeight();
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) sheet.getLayoutParams();
        // collapse: the card accelerates back into the row's edges
        android.animation.ValueAnimator va =
            android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(300);
        va.setInterpolator(new AccelerateInterpolator(1.6f));
        va.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            lp.topMargin = (int) (historyTopEdge
                + (historyRowTop - historyTopEdge) * p);
            lp.bottomMargin = (int) (historyBotFree
                + ((rootH - historyRowBottom) - historyBotFree) * p);
            sheet.setLayoutParams(lp);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(
                android.animation.Animator a) {
                overlay.animate().alpha(0f).setDuration(140)
                    .withEndAction(() -> {
                        if (overlay.getParent() != null)
                            ((ViewGroup) overlay.getParent())
                                .removeView(overlay);
                    }).start();
            }
        });
        va.start();
    }

    private String recordsText() {
        return historyCount != null && historyCount.getText() != null
            ? historyCount.getText().toString() : "—";
    }

    /** Chevron emphasis follows the sheet state: ⌃ (up) lights while the
     *  sheet is expanded, ⌄ (down) while collapsed. */
    private void setHistoryArrows(boolean expanded) {
        if (arrowUp == null || arrowDown == null) return;
        arrowUp.setTextColor(expanded ? S.accent : S.textDim);
        arrowDown.setTextColor(expanded ? S.textDim : S.accent);
    }

    private View historyCard(SpeedRecord r, boolean inProgress) {
        // entry tile INSIDE the floating sheet: cardHi recipe (inner highlight
        // tile) so it reads against the sheet's S.card backing
        LinearLayout c = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(S.cardHi);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout head = row();
        head.addView(text(fmtTime(r.startedAt), 13.5f, true, S.text),
            mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(caption((inProgress ? "进行中 · " : "")
            + String.format(java.util.Locale.US, "%.1f", r.durationS) + "秒"));
        c.addView(head);
        TextView p1 = caption("发送 " + size(r.sent)
            + " · 接收 " + size(r.recv));
        p1.setPadding(0, dp(8), 0, dp(2));
        c.addView(p1);
        TextView p2 = caption("峰值 ↑ " + peak(r.peakTx, r.avgTx)
            + " · ↓ " + peak(r.peakRx, r.avgRx) + " MB/s");
        p2.setPadding(0, 0, 0, dp(2));
        c.addView(p2);
        c.addView(caption("平均 ↑ " + speed(r.avgTx)
            + " · ↓ " + speed(r.avgRx) + " MB/s"));
        return c;
    }

    private static String speed(double bps) {
        return String.format(java.util.Locale.US, "%.2f", bps / 1e6);
    }

    /** Same anomaly guard as the old WebUI: a peak below its own average is a
     *  known statistics floor in old records — display the average with a ≥. */
    private static String peak(double value, double average) {
        return value < average ? "≥ " + speed(average) : speed(value);
    }

    private static String size(long value) {
        if (value >= 1e9) return String.format(java.util.Locale.US, "%.2f GB", value / 1e9);
        if (value >= 1e6) return String.format(java.util.Locale.US, "%.2f MB", value / 1e6);
        return String.format(java.util.Locale.US, "%.1f KB", value / 1e3);
    }

    private String fmtTime(long epochSeconds) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date(epochSeconds * 1000L));
    }

    // ------------------------------------------------- environment probe

    private static final class Env {
        boolean rootGranted;
        String managerPkg, managerName, managerVersion;
        boolean lspFrameworkFound;
        boolean lspConnected;
        List<String> lspScopes = new ArrayList<>();
        String[] props = new String[6];
    }

    /** Reflection read of android.os.SystemProperties — the same data the
     *  getprop binary returns, but in-process: no exec, no shell, no su.
     *  Restricted (signature|privileged) keys return empty, which the UI
     *  renders as 未知. */
    private static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class)
                .invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    private void probeEnvironment() {        async(() -> {
            Env e = new Env();

            // --- root grant ---
            Result id = suExec("id -u");
            e.rootGranted = id.code == 0 && id.out.trim().startsWith("0");

            // --- root manager (works without grant too) ---
            PackageManager pm = getPackageManager();
            for (String[] cand : ROOT_MANAGERS) {
                try {
                    PackageInfo pi = pm.getPackageInfo(cand[0], 0);
                    e.managerPkg = cand[0];
                    e.managerName = pi.applicationInfo != null
                        ? String.valueOf(pm.getApplicationLabel(pi.applicationInfo))
                        : cand[1];
                    e.managerVersion = pi.versionName;
                    break;
                } catch (Exception ignored) {
                }
            }

            // --- device props: pure in-process, zero exec. Three of the six
            //     come from android.os.Build public API; the two oplus/ROM
            //     keys have no public API and are read via reflection on
            //     android.os.SystemProperties — same data getprop returns,
            //     no shell, no su, works in the plain app domain. ---
            e.props[0] = sysProp("ro.vendor.oplus.market.name");   // 市场名，无公开 API
            e.props[1] = android.os.Build.MODEL;                   // ro.product.model
            e.props[2] = android.os.Build.DISPLAY;                 // ro.build.display.id
            e.props[3] = sysProp("ro.build.version.oplusrom");     // ColorOS 版本，无公开 API
            e.props[4] = android.os.Build.VERSION.RELEASE;         // ro.build.version.release
            e.props[5] = android.os.Build.VERSION.SECURITY_PATCH;  // API 23+

            // --- LSPosed framework: read the daemon's config database ---
            if (e.rootGranted) {
                readLspState(e);
            }

            env = e;
            main.post(() -> applyEnvironment(e));
        });
    }

    private void readLspState(Env e) {
        // internal cache — a real filesystem (no FUSE), so the chown/chmod
        // below actually stick and SQLite can write the WAL replay state
        java.io.File dir = new java.io.File(getCacheDir(), "lsp");
        java.io.File db = new java.io.File(dir, "modules_config.db");
        try {
            String d = dir.getAbsolutePath();
            suExec("mkdir -p '" + d + "' && cp /data/adb/lspd/config/modules_config.db"
                + " /data/adb/lspd/config/modules_config.db-wal"
                + " /data/adb/lspd/config/modules_config.db-shm '" + d + "/'"
                + " 2>/dev/null; chown " + android.os.Process.myUid()
                + " '" + d + "'/* 2>/dev/null; chmod 660 '" + d + "'/* 2>/dev/null; true");
            if (!db.isFile()) return;
            e.lspFrameworkFound = true;
            // NOTE: must open read-write — a WAL-mode database cannot be
            // opened read-only (SQLite cannot init the -shm index), and the
            // copied -wal holds the freshest module state
            try (android.database.sqlite.SQLiteDatabase sq = android.database.sqlite
                    .SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)) {
                try (android.database.Cursor c = sq.rawQuery(
                        "SELECT enabled FROM modules_state "
                        + "WHERE module_pkg_name='com.oshare.airdrop'", null)) {
                    while (c.moveToNext()) {
                        if (c.getInt(0) != 0) e.lspConnected = true;
                    }
                }
                try (android.database.Cursor c = sq.rawQuery(
                        "SELECT app_pkg_name FROM scope "
                        + "WHERE module_pkg_name='com.oshare.airdrop' "
                        + "GROUP BY app_pkg_name", null)) {
                    while (c.moveToNext()) e.lspScopes.add(c.getString(0));
                }
            }
        } catch (Exception ignored) {
            // corrupt copy (concurrent WAL write) or path unavailable; treat
            // as not connected rather than showing a wrong status
        } finally {
            suExec("rm -f '" + dir.getAbsolutePath() + "'/* 2>/dev/null; true");
        }
    }

    private void applyEnvironment(Env e) {
        // --- LSP row: tap toggles the scope panel when connected, toasts
        //     "go enable the module" when not
        boolean lsp = e.lspConnected;
        setPill(lspPill, lsp ? "已连接" : "未连接", lsp ? S.accent : S.red);
        if (lsp) {
            // module version + API as plain dim text — same format as the
            // root row's manager name/version subtitle (caption style)
            lspSubtitle.setText("v" + versionName() + " API " + moduleApi());
            lspSubtitle.setTextColor(S.textDim);
            lspSubtitle.setBackground(null);
            lspSubtitle.setPadding(0, dp(2), 0, 0);

            // connected: the whole row opens the floating scope panel
            setRowAction(lspRow, true, CORNERS_TOP, this::showScopeDialog);
        } else {
            lspSubtitle.setText("未连接框架服务");
            lspSubtitle.setTextColor(S.textDim);
            lspSubtitle.setBackground(null);
            lspSubtitle.setPadding(0, dp(2), 0, 0);
            setRowAction(lspRow, true, CORNERS_TOP, this::handleLspDisconnectedTap);
        }

        // --- root row: tap re-detects the grant state (no manager jump —
        //     users may use any superuser manager)
        setPill(rootPill, e.rootGranted ? "已授权" : "未授权",
            e.rootGranted ? S.accent : S.red);
        if (e.rootGranted && e.managerName != null) {
            rootSubtitle.setText(e.managerName
                + (e.managerVersion != null ? " " + e.managerVersion : ""));
        } else if (e.rootGranted) {
            rootSubtitle.setText("已获得 Root 权限");
        } else {
            rootSubtitle.setText("未获得 Root 权限");
        }
        setRowAction(rootRow, !e.rootGranted, CORNERS_BOTTOM, this::refreshRootState);

        // device info
        setInfo(devModel, firstNonEmpty(e.props[0], e.props[1]));
        setInfo(devBuild, e.props[2]);
        setInfo(devColoros, e.props[3]);
        setInfo(devAndroid, firstNonEmpty(e.props[4],
            android.os.Build.VERSION.RELEASE));
        setInfo(devPatch, e.props[5]);
    }

    private static void setInfo(TextView tv, String value) {
        tv.setText(value == null || value.isEmpty() ? "未知" : value);
    }

    /** Synchronous, instant fill of the five non-kernel device info rows.
     *  Same data applyEnvironment writes, but without waiting for the
     *  background su/LSP environment probe to finish. */
    private void fillDeviceInfoFast() {
        if (devModel == null) return;
        setInfo(devModel, firstNonEmpty(sysProp("ro.vendor.oplus.market.name"),
            android.os.Build.MODEL));
        setInfo(devBuild, android.os.Build.DISPLAY);
        setInfo(devColoros, sysProp("ro.build.version.oplusrom"));
        setInfo(devAndroid, android.os.Build.VERSION.RELEASE);
        setInfo(devPatch, android.os.Build.VERSION.SECURITY_PATCH);
    }

    /** Full kernel release string (e.g.
     *  "6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k") straight
     *  from uname(2) — world-readable, no shell/root needed. */
    private static void fillKernelInfo(TextView tv) {
        try {
            String release = android.system.Os.uname().release;
            setInfo(tv, release);
        } catch (Throwable t) {
            tv.setText("未知");
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Parse our own APK's META-INF/xposed/module.prop for the API level. */
    private String moduleApi() {
        try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
            ZipEntry entry = zip.getEntry("META-INF/xposed/module.prop");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    for (String line : read(in).split("\n")) {
                        if (line.startsWith("minApiVersion=")) {
                            return line.substring("minApiVersion=".length()).trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "102";
    }

    /** Recommended scopes from our own APK's META-INF/xposed/scope.list. */
    private List<String> recommendedScopes() {
        List<String> list = new ArrayList<>();
        try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
            ZipEntry entry = zip.getEntry("META-INF/xposed/scope.list");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    for (String line : read(in).split("\n")) {
                        String pkg = line.trim();
                        if (!pkg.isEmpty() && !pkg.startsWith("#")) list.add(pkg);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    // ------------------------------------------------- manager deep links

    /** Deep link into LSPosed manager's module page for this module.
     *  The manager understands "module://<pkg>:<userId>" VIEW intents and
     *  routes them straight to the module detail screen. */
    /** LSP row tapped while the framework service is not connected. The
     *  module cannot ask LSPosed anything in this state (LSPosed only
     *  delivers the service binder to *enabled* modules), so just tell the
     *  user what to do — no manager jump. */
    private void handleLspDisconnectedTap() {
        Toast.makeText(this, "请前往 LSPosed 启用模块", Toast.LENGTH_LONG).show();
    }

    /** Recommended scopes not granted yet. */
    private List<String> missingScopes(List<String> checked) {
        List<String> missing = new ArrayList<>();
        for (String r : recommendedScopes()) {
            if (!checked.contains(r)) missing.add(r);
        }
        return missing;
    }

    /** Scope request via the framework service: ask LSPosed
     *  to grant the missing recommended scopes through its own prompt /
     *  notification instead of jumping to the manager UI. */
    private void requestMissingScopes() {
        if (lspService == null || !lspService.isAlive()) {
            showNotice("框架服务未就绪，请稍后重试", S.orange);
            return;
        }
        List<String> targets = missingScopes(env != null
            ? env.lspScopes : new ArrayList<>());
        if (targets.isEmpty()) {
            targets = java.util.Collections.singletonList(getPackageName());
        }
        showNotice("已在 LSPosed 发起作用域请求，请在授权提示或通知中确认",
            S.textDim);
        LspServiceBridge.ScopeCallback cb = new LspServiceBridge.ScopeCallback() {
            @Override public void onScopeApproved() {
                main.post(() -> {
                    showNotice("LSPosed 作用域授权成功", S.green);
                    probeEnvironment();
                });
            }
            @Override public void onScopeFailed(String message) {
                main.post(() -> showNotice("作用域请求未通过："
                    + (message == null ? "已在管理器中取消" : message),
                    S.orange));
            }
        };
        try {
            lspService.requestScope(targets, cb);
        } catch (Exception e) {
            showNotice("作用域请求失败：" + e.getMessage(), S.red);
        }
    }

    /** Root row tapped while unauthorized: re-run the grant / manager
     *  detection (does not launch any manager — the user may use KernelSU,
     *  APatch, Magisk or anything else). */
    private void refreshRootState() {
        showNotice("正在重新检测 root 授权状态…", S.textDim);
        probeEnvironment();
    }

    // ---------------------------------------------------- scope dialog

    private void showScopeDialog() {
        if (env == null) return;
        PackageManager pm = getPackageManager();

        LinearLayout body = column();
        body.setPadding(dp(22), dp(20), dp(22), dp(14));
        body.addView(text("模块作用域", 17, true, S.text));
        TextView sub = caption("已勾选的作用域与推荐作用域对照");
        sub.setPadding(0, dp(4), 0, dp(6));
        body.addView(sub);

        List<String> recommended = recommendedScopes();
        List<String> checked = env.lspScopes;
        List<String> missing = missingScopes(checked);
        List<String> all = new ArrayList<>(checked);
        for (String r : recommended) {
            if (!all.contains(r)) all.add(r);
        }

        if (all.isEmpty()) {
            body.addView(caption("暂无作用域信息"));
        }
        for (int i = 0; i < all.size(); i++) {
            String pkg = all.get(i);
            boolean isChecked = checked.contains(pkg);
            boolean isRecommended = recommended.contains(pkg);

            LinearLayout line = row();
            TextView state = text(isChecked ? "✓" : "!", 14, true,
                isChecked ? S.green : S.orange);
            state.setGravity(Gravity.CENTER);
            state.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
            line.addView(state);

            LinearLayout mid = column();
            mid.setPadding(dp(10), 0, dp(8), 0);
            mid.addView(text(appLabel(pm, pkg), 14.5f, true, S.text));
            TextView pkgView = caption(pkg);
            pkgView.setPadding(0, dp(1), 0, 0);
            mid.addView(pkgView);
            line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String tag;
            int tagColor;
            if (isChecked) {
                tag = isRecommended ? "已勾选" : "已勾选（额外）";
                tagColor = S.green;
            } else if (isRecommended) {
                tag = "推荐未勾选";
                tagColor = S.orange;
            } else {
                tag = "未勾选";
                tagColor = S.gray;
            }
            TextView badgeView = badge(tag, tagColor);
            line.addView(badgeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(line, margins(0, 10));
            if (i < all.size() - 1) {
                body.addView(divider(), fixedDivider(0, 10));
            }
        }

        // scope request entry, relocated from the old inline expandable panel
        if (!missing.isEmpty()) {
            TextView req = ghostButton("请求缺失作用域 ▸", v -> requestMissingScopes());
            body.addView(req, margins(6, 4));
        }

        // window blur: a self-contained RealtimeBlurView draws the activity
        // behind the dialog into the window's own rounded surface — no
        // system blur-behind on the whole background
        FrameLayout dialogFrame = new FrameLayout(this);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(S.veil);
        frameBg.setCornerRadius(dp(24));
        dialogFrame.setBackground(frameBg);
        dialogFrame.setClipToOutline(true);
        // same rule as the sheet panes: capture the pager content, NOT the
        // activity root — the activity tree contains this dialog's sibling
        // panes (nav/top blur), whose flat backing would be baked into the
        // frost (wrong regions) and whose glow animations would re-dirty the
        // capture every frame (flickering backdrop).
        RealtimeBlurView winBlur = new RealtimeBlurView(this);
        winBlur.setCaptureRoot(pager);
        winBlur.setCornerRadius(dp(24));
        winBlur.setFill(S.bg);
        dialogFrame.addView(winBlur, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView close = mainButton("完成", d -> collapseScope());

        LinearLayout dialogRoot = column();
        // the card is full-height now: the scope list scrolls in the middle
        // band, the button row pins to the card's bottom edge
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(body);
        dialogRoot.addView(scroller, mm(ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1f));
        LinearLayout btnRow = row();
        btnRow.setPadding(dp(22), dp(4), dp(22), dp(16));
        btnRow.addView(close, mm(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dialogRoot.addView(btnRow);
        dialogFrame.addView(dialogRoot, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // full-height placement: the card's top edge lines up with the title
        // capsule's bottom edge, its bottom edge sits as far above the screen
        // bottom as the nav pill's bottom edge, and its width matches the
        // body content — achieved by padding a full-screen window to that
        // frame and letting the card fill the rest
        int topEdge = (int) topBar.getY() + topBar.getHeight();
        FrameLayout windowPad = new FrameLayout(this);
        windowPad.setPadding(dp(18), topEdge, dp(18), insetBottom + dp(22));
        windowPad.addView(dialogFrame, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // IN-ACTIVITY OVERLAY, not a Dialog window. Diagnosis: on ColorOS 17
        // a separate dialog window is force-inset above the gesture bar (its
        // frame stays 2208px tall on a 2376px screen — setDecorFitsSystemWin
        // dows(false), FLAG_LAYOUT_NO_LIMITS and contrastEnforced(false) are
        // all ignored). The system then paints a white contrast-protection
        // band behind the nav pill (a SystemUI ScreenDecorOverlay layer that
        // is EXCLUDED from screenshots/screen recordings — eyes see it,
        // captures don't). Hosting the card in the activity's own
        // edge-to-edge window keeps the window covering the full display, so
        // no protection band appears and the backdrop stays immersive.
        FrameLayout overlay = new FrameLayout(this);
        // same dimming the dialog's 0.25 dimAmount produced
        overlay.setBackground(new android.graphics.drawable.ColorDrawable(0x40000000));
        // click outside the card = dismiss
        overlay.setOnClickListener(v -> collapseScope());
        overlay.addView(windowPad, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((FrameLayout) activityRoot).addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // remember the card frame so collapseScope can animate it back out
        overlay.setTag(dialogFrame);
        scopeExpanded = true;
        scopeOverlay = overlay;
        // frost ready for frame one: synchronous capture OUTSIDE any draw
        // pass, before the entrance animation starts
        winBlur.captureNow();
        // entrance: the card sweeps up from the screen bottom on a parabolic
        // (ease-in-out) velocity curve
        dialogFrame.setTranslationY(activityRoot.getHeight());
        dialogFrame.animate().translationY(0f).setDuration(420)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();
    }

    /** Close the scope card with the reverse of its entrance: a parabolic
     *  slide back down to the screen bottom, then remove the overlay. */
    private void collapseScope() {
        if (!scopeExpanded || scopeOverlay == null) return;
        scopeExpanded = false;
        FrameLayout overlay = scopeOverlay;
        scopeOverlay = null;
        View frame = (View) overlay.getTag();
        frame.animate().translationY(activityRoot.getHeight())
            .setDuration(340)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                if (overlay.getParent() != null)
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
            })
            .start();
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ----------------------------------------------------- validation

    private static boolean validName(String value) {
        if (value.isEmpty()) return true;
        if (value.codePointCount(0, value.length()) > 64) return false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp < 32 || (cp >= 127 && cp <= 159) || cp == 0x2028 || cp == 0x2029
                || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean validDirectory(String value) {
        if (value.isEmpty()) return true;
        String[] parts = value.split("/");
        if (parts.length == 0) return false;
        if (!"Download".equals(parts[0]) && !"Documents".equals(parts[0])) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".")
                || !part.equals(part.trim())
                || part.matches(".*[<>:\"\\\\|?*].*")
                || part.getBytes(StandardCharsets.UTF_8).length > 200) return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 512;
    }

    private static String hex(String value) {
        if (value.isEmpty()) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------- status

    private static final class Status {
        final String version;
        final String shareMode;
        final String shareModeActive;
        final String airdropName;
        final String airdropNameDefault;
        final String airdropNameCustom;
        final String receiveDirectory;
        final String awdl;
        final String quickshare;
        final String oshare;

        Status(String version, String shareMode, String shareModeActive,
               String airdropName, String airdropNameDefault, String airdropNameCustom,
               String receiveDirectory, String awdl, String quickshare, String oshare) {
            this.version = version;
            this.shareMode = shareMode;
            this.shareModeActive = shareModeActive;
            this.airdropName = airdropName;
            this.airdropNameDefault = airdropNameDefault;
            this.airdropNameCustom = airdropNameCustom;
            this.receiveDirectory = receiveDirectory;
            this.awdl = awdl;
            this.quickshare = quickshare;
            this.oshare = oshare;
        }

        static Status parse(String json) throws Exception {
            JSONObject o = new JSONObject(json);
            return new Status(
                str(o, "version"),
                str(o, "share_mode"),
                str(o, "share_mode_active"),
                str(o, "airdrop_name"),
                str(o, "airdrop_name_default"),
                str(o, "airdrop_name_custom"),
                str(o, "receive_directory"),
                str(o, "awdl"),
                str(o, "quickshare"),
                str(o, "oshare"));
        }

        private static String str(JSONObject o, String key) {
            try {
                return o.has(key) && !o.isNull(key) ? o.getString(key) : "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ----------------------------------------------------- custom views

    /** Minimal vector icons drawn on canvas: 0 home, 1 radar, 2 sliders,
     *  3 LSP shield, 4 superuser. */
    private static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int type;
        private int color = 0xFF8A93A6;

        IconView(android.content.Context ctx, int type) {
            super(ctx);
            this.type = type;
        }

        void setColor(int value) {
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(w * 0.08f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);

            if (type == 0) { // home: roof + body, footprint ≈ the sliders icon
                float s = w * 0.35f;              // walls at ±0.35w → 0.70w span
                float eave = cy - h * 0.02f;      // roof/wall junction
                float apex = cy - h * 0.30f;      // roof peak
                float floor = cy + h * 0.28f;     // base line
                c.drawLine(cx - s, eave, cx, apex, paint);
                c.drawLine(cx + s, eave, cx, apex, paint);
                c.drawLine(cx - s, eave, cx - s, floor, paint);
                c.drawLine(cx + s, eave, cx + s, floor, paint);
                c.drawLine(cx - s, floor, cx + s, floor, paint);
            } else if (type == 1) { // radar: two rings + dot, d ≈ 0.70w
                c.drawCircle(cx, cy, w * 0.20f, paint);
                c.drawCircle(cx, cy, w * 0.35f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, w * 0.07f, paint);
            } else if (type == 3) { // shield: LSPosed / framework
                paint.setStyle(Paint.Style.STROKE);
                Path shield = new Path();
                shield.moveTo(cx, cy - h * 0.42f);
                shield.lineTo(cx + w * 0.36f, cy - h * 0.22f);
                shield.lineTo(cx + w * 0.36f, cy + h * 0.10f);
                shield.quadTo(cx + w * 0.30f, cy + h * 0.36f,
                    cx, cy + h * 0.44f);
                shield.quadTo(cx - w * 0.30f, cy + h * 0.36f,
                    cx - w * 0.36f, cy + h * 0.10f);
                shield.lineTo(cx - w * 0.36f, cy - h * 0.22f);
                shield.close();
                c.drawPath(shield, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - h * 0.02f, w * 0.09f, paint);
            } else if (type == 4) { // superuser: terminal prompt
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(w * 0.10f, h * 0.16f, w * 0.90f, h * 0.84f,
                    w * 0.18f, w * 0.18f, paint);
                paint.setStrokeWidth(w * 0.07f);
                c.drawLine(cx - w * 0.22f, cy - h * 0.04f,
                    cx - w * 0.10f, cy + h * 0.06f, paint);
                c.drawLine(cx - w * 0.10f, cy + h * 0.06f,
                    cx - w * 0.22f, cy + h * 0.16f, paint);
                c.drawLine(cx, cy + h * 0.16f, cx + w * 0.24f, cy + h * 0.16f, paint);
            } else if (type == 5) { // phone: rounded body + home indicator
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(w * 0.20f, h * 0.06f, w * 0.80f, h * 0.94f,
                    w * 0.16f, w * 0.16f, paint);
                paint.setStrokeWidth(w * 0.07f);
                c.drawLine(cx, h * 0.79f, cx, h * 0.85f, paint);
            } else if (type == 6) { // id card: rounded rect + two text lines
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(w * 0.10f, h * 0.20f, w * 0.90f, h * 0.80f,
                    w * 0.16f, w * 0.16f, paint);
                paint.setStrokeWidth(w * 0.07f);
                c.drawLine(w * 0.24f, h * 0.40f, w * 0.56f, h * 0.40f, paint);
                c.drawLine(w * 0.24f, h * 0.60f, w * 0.72f, h * 0.60f, paint);
            } else if (type == 7) { // chip: CPU square + pins on all sides
                paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(w * 0.28f, h * 0.28f, w * 0.72f, h * 0.72f,
                    w * 0.10f, w * 0.10f, paint);
                paint.setStrokeWidth(w * 0.07f);
                float[] pins = {0.36f, 0.50f, 0.64f};
                for (float p : pins) {
                    c.drawLine(w * p, h * 0.10f, w * p, h * 0.28f, paint);
                    c.drawLine(w * p, h * 0.72f, w * p, h * 0.90f, paint);
                    c.drawLine(w * 0.10f, h * p, w * 0.28f, h * p, paint);
                    c.drawLine(w * 0.72f, h * p, w * 0.90f, h * p, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, w * 0.05f, paint);
            } else if (type == 8) { // android: head dome + antennae + eyes
                paint.setStyle(Paint.Style.STROKE);
                float r = w * 0.34f;
                RectF dome = new RectF(cx - r, cy - h * 0.10f, cx + r, cy - h * 0.10f + 2 * r);
                c.drawArc(dome, 180, 180, false, paint);
                c.drawLine(cx - r, cy - h * 0.10f, cx + r, cy - h * 0.10f, paint);
                paint.setStrokeWidth(w * 0.07f);
                c.drawLine(cx - w * 0.20f, cy - h * 0.28f, cx - w * 0.34f, cy - h * 0.44f, paint);
                c.drawLine(cx + w * 0.20f, cy - h * 0.28f, cx + w * 0.34f, cy - h * 0.44f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - w * 0.13f, cy + h * 0.02f, w * 0.045f, paint);
                c.drawCircle(cx + w * 0.13f, cy + h * 0.02f, w * 0.045f, paint);
            } else if (type == 9) { // shield check: security patch
                paint.setStyle(Paint.Style.STROKE);
                Path shield = new Path();
                shield.moveTo(cx, cy - h * 0.42f);
                shield.lineTo(cx + w * 0.36f, cy - h * 0.22f);
                shield.lineTo(cx + w * 0.36f, cy + h * 0.10f);
                shield.quadTo(cx + w * 0.30f, cy + h * 0.36f,
                    cx, cy + h * 0.44f);
                shield.quadTo(cx - w * 0.30f, cy + h * 0.36f,
                    cx - w * 0.36f, cy + h * 0.10f);
                shield.lineTo(cx - w * 0.36f, cy - h * 0.22f);
                shield.close();
                c.drawPath(shield, paint);
                paint.setStrokeWidth(w * 0.075f);
                c.drawLine(cx - w * 0.14f, cy + h * 0.01f,
                    cx - w * 0.04f, cy + h * 0.11f, paint);
                c.drawLine(cx - w * 0.04f, cy + h * 0.11f,
                    cx + w * 0.16f, cy - h * 0.11f, paint);
            } else if (type == 10) { // kernel: gear ring + teeth + hub
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, w * 0.24f, paint);
                paint.setStrokeWidth(w * 0.07f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                    c.drawLine(cx + dx * w * 0.26f, cy + dy * w * 0.26f,
                        cx + dx * w * 0.38f, cy + dy * w * 0.38f, paint);
                }
                c.drawCircle(cx, cy, w * 0.09f, paint);
            } else { // sliders: three lines with knobs
                float[] knob = {0.28f, 0.62f, 0.40f};
                for (int i = 0; i < 3; i++) {
                    float y = h * (0.25f + 0.25f * i);
                    c.drawLine(w * 0.15f, y, w * 0.85f, y, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(w * knob[i], y, w * 0.10f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
        }
    }

    /** Horizontal snap pager for the three tabs. Each page is as wide as the
     *  viewport; releasing snaps to the nearest page and reports the change.
     *  During swipes the pages move continuously . */
    private static final class SnapPager extends HorizontalScrollView {
        /** reports the page the user snapped to */
        interface PageListener {
            void onPage(int index);
        }

        /** fired on every scroll-position change (drag/fling/snap frames) so
         *  the floating blur capsules can re-capture in realtime */
        interface ScrollListener {
            void onScroll();
        }

        private PageListener onPage;
        private ScrollListener onScroll;
        private int pageWidth = 0;
        private int currentPage = 0;
        private boolean scrolling;
        /** retry budget for scrollToPage() called before the first layout
         *  pass (pageWidth still 0); bounded so a never-measured pager
         *  cannot spin the message loop forever */
        private int anchorRetries;
        /** true once the first real layout pass has completed; after that a
         *  pageWidth change (rotation) must re-anchor the scroll offset */
        private boolean firstLayoutDone;
        private VelocityTracker tracker;

        // drag state for the custom rubber-band gesture
        private float downX;
        private int startScroll;
        private ValueAnimator settle;
        private ValueAnimator bounce;
        /** how much of the overshoot past an edge bleeds into the view */
        private static final float RUBBER = 0.06f;

        SnapPager(android.content.Context ctx) {
            super(ctx);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        void setOnPage(PageListener listener) {
            onPage = listener;
        }

        void setOnScrollListener(ScrollListener listener) {
            onScroll = listener;
        }

        /**
         * HorizontalScrollView measures its child strip with an UNSPECIFIED
         * width spec, so pages added as MATCH_PARENT degrade to wrap-content
         * on the very first layout pass — the long kernel release string
         * stretches its page past the screen edge and one over-wide frame
         * is drawn before onSizeChanged pins the widths back. Pin every
         * page to the pager's own (EXACTLY) width BEFORE measuring, so the
         * first frame is already correct — in either orientation. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int wSpec = MeasureSpec.getSize(widthMeasureSpec);
            if (wSpec > 0 && wSpec != pageWidth
                    && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                    && windowRotationAgrees(wSpec, heightMeasureSpec)) {
                int oldPageWidth = pageWidth;
                pageWidth = wSpec;
                ViewGroup content = (ViewGroup) getChildAt(0);
                if (content != null) {
                    for (int i = 0; i < content.getChildCount(); i++) {
                        ViewGroup.LayoutParams lp = content.getChildAt(i).getLayoutParams();
                        lp.width = wSpec;
                        content.getChildAt(i).setLayoutParams(lp);
                    }
                }
                // after a rotation (configChanges keeps the activity alive)
                // the scroll offset still sits at currentPage * OLD pageWidth
                // and would show slivers of two pages; re-anchor once layout
                // has settled on the new geometry
                if (oldPageWidth > 0 && firstLayoutDone) {
                    post(() -> scrollToPage(currentPage, false));
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        /** A window whose aspect ratio disagrees with the display rotation is
         *  the system's one-shot pre-rotation measure pass (seen on cold
         *  start: 2376x1080 while rotation was still 0). Pinning that size
         *  flashes a wrong-aspect layout; skip it and wait for the corrected
         *  measure that always follows. Unlike a blind w>h guard this keeps
         *  working in real landscape use, where w>h is simply correct. */
        private boolean windowRotationAgrees(int w, int heightMeasureSpec) {
            Display d = getDisplay();
            if (d == null) return true;
            boolean winLandscape = w > MeasureSpec.getSize(heightMeasureSpec);
            boolean rotLandscape = d.getRotation() == Surface.ROTATION_90
                || d.getRotation() == Surface.ROTATION_270;
            return winLandscape == rotLandscape;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            boolean steal = super.onInterceptTouchEvent(ev);
            // when the drag is stolen from a child, onTouchEvent receives the
            // stream from the current MOVE onward (no replayed DOWN), so the
            // drag anchor must be taken here, while the pointer position is
            // still paired with the actual scroll position
            if (steal || ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                startScroll = getScrollX();
                if (settle != null) settle.cancel();
                if (bounce != null) bounce.cancel();
            }
            return steal;
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (onScroll != null) onScroll.onScroll();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            android.util.Log.d("OPAD", "pager onSizeChanged " + w + "x" + h
                + " old " + oldw + "x" + oldh + " pageWidth=" + pageWidth);
            firstLayoutDone = true;
            // onMeasure already pins pageWidth (and, after a rotation, re-anchors
            // the scroll) before this layout pass; this stays as a fallback for
            // size changes that bypass the EXACTLY-measure path.
            if (w > 0 && w != pageWidth) {
                pageWidth = w;
                // a running settle was animated toward the OLD pageWidth's
                // coordinates — left alone it would park the pager between
                // two pages after a resize; kill it and re-anchor
                if (settle != null) settle.cancel();
                if (bounce != null) bounce.cancel();
                ViewGroup content = (ViewGroup) getChildAt(0);
                if (content != null) {
                    for (int i = 0; i < content.getChildCount(); i++) {
                        ViewGroup.LayoutParams lp = content.getChildAt(i).getLayoutParams();
                        lp.width = w;
                        content.getChildAt(i).setLayoutParams(lp);
                    }
                }
                scrollToPage(currentPage, false);
            }
        }

        void scrollToPage(int index, boolean smooth) {
            currentPage = index;
            if (pageWidth > 0) {
                anchorRetries = 0;
                if (smooth) animateScrollTo(index * pageWidth);
                else scrollTo(index * pageWidth, 0);
            } else if (index != 0 && anchorRetries < 10) {
                // Called before the first layout pass (e.g. retheme()
                // restoring the tab right after setContentView): geometry
                // is still unknown, so retry on the message loop until the
                // first measure pins pageWidth and the real scrollTo lands.
                anchorRetries++;
                post(() -> scrollToPage(currentPage, false));
            }
        }

        /** Slow, eased page settle (the stock HorizontalScrollView
         *  fling is fixed at ~250ms and feels abrupt). The natural-end
         *  listener hard-lands on the exact target: if frames were dropped
         *  mid-animation the last scrollTo update could leave the pager
         *  short of the page boundary — visually stuck showing slivers of
         *  two pages — so the end callback pins the final position. */
        private void animateScrollTo(int target) {
            if (settle != null) settle.cancel();
            settle = ValueAnimator.ofInt(getScrollX(), target);
            settle.setInterpolator(new DecelerateInterpolator(1.6f));
            settle.setDuration(460);
            settle.addUpdateListener(a -> scrollTo((Integer) a.getAnimatedValue(), 0));
            settle.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (a == settle) {
                        settle = null;
                        if (!cancelled) scrollTo(target, 0);
                    }
                }
            });
            settle.start();
        }

        /** rubber-band past an edge: translate AND stretch the strip in the
         *  drag direction (distortion stretch). The stretch is computed
         *  against the VISIBLE width, not the 3-page strip, so it reads as
         *  a real jelly stretch instead of a barely visible sub-percent
         *  scale. The pivot is pinned at the edge being overscrolled, so
         *  the near edge follows the finger and the content elongates
         *  toward the drag direction. */
        private void setRubber(float tx) {
            View content = getChildAt(0);
            if (content == null) return;
            content.setTranslationX(tx);
            float vw = getWidth();
            float over = Math.abs(tx);
            if (over > 0.5f && vw > 0) {
                // dragged right past the left edge -> anchor the screen-left
                // edge and grow rightward; dragged left past the right edge
                // -> anchor the screen-right edge and grow leftward
                content.setPivotX(tx > 0 ? getScrollX() : getScrollX() + vw);
                content.setScaleX((vw + over) / vw);
            } else {
                content.setScaleX(1f);
                content.setPivotX(vw / 2f);
            }
            if (onScroll != null) onScroll.onScroll();
        }

        /** Continuous page scrubbing driven by the nav bar: follow the given
         *  fractional page position (0..2) live while the finger slides. */
        void scrubTo(float pos) {
            if (pageWidth <= 0) return;
            if (settle != null) settle.cancel();
            if (bounce != null) bounce.cancel();
            View content = getChildAt(0);
            int stripMax = content == null ? 0
                : Math.max(0, content.getWidth() - pageWidth);
            int x = Math.round(pos * pageWidth);
            setRubber(0f);
            scrollTo(Math.max(0, Math.min(stripMax, x)), 0);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (tracker == null) tracker = VelocityTracker.obtain();
            tracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    startScroll = getScrollX();
                    if (settle != null) settle.cancel();
                    if (bounce != null) bounce.cancel();
                    scrolling = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float desired = startScroll + (downX - ev.getX());
                    ViewGroup content = (ViewGroup) getChildAt(0);
                    int stripMax = content == null ? 0
                        : Math.max(0, content.getWidth() - getWidth());
                    // one page per swipe: the drag may only travel a single
                    // page width; anything beyond resists like rubber
                    float lo = Math.max(0, startScroll - pageWidth);
                    float hi = Math.min(stripMax, startScroll + pageWidth);
                    float clamped = Math.max(lo, Math.min(hi, desired));
                    scrollTo((int) clamped, 0);
                    setRubber((clamped - desired) * RUBBER);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    tracker.computeCurrentVelocity(1000, ViewConfiguration
                        .get(getContext()).getScaledMaximumFlingVelocity());
                    float vx = tracker.getXVelocity();
                    tracker.recycle();
                    tracker = null;
                    scrolling = false;
                    // rigid release: the stretched strip snaps straight back
                    // to rest — no elastic overshoot
                    View content = getChildAt(0);
                    float tx = content == null ? 0f : content.getTranslationX();
                    if (tx != 0f) {
                        if (bounce != null) bounce.cancel();
                        bounce = ValueAnimator.ofFloat(tx, 0f);
                        bounce.setInterpolator(new LinearInterpolator());
                        bounce.setDuration(180);
                        bounce.addUpdateListener(a -> setRubber((Float) a.getAnimatedValue()));
                        bounce.start();
                    }
                    snap(vx);
                    return true;
                }
            }
            return super.onTouchEvent(ev);
        }

        /** Velocity-aware snap: a fling advances exactly one page — the
         *  drag itself can never travel further than one page either, so
         *  a single gesture always flips at most one page. */
        private void snap(float vx) {
            if (pageWidth <= 0) return;
            float pos = (float) getScrollX() / pageWidth;
            int pageCount = ((ViewGroup) getChildAt(0)).getChildCount();
            int target;
            int minFling = ViewConfiguration.get(getContext())
                .getScaledMinimumFlingVelocity();
            if (Math.abs(vx) > minFling) {
                target = vx < 0 ? currentPage + 1 : currentPage - 1;
            } else {
                target = Math.round(pos);
            }
            target = Math.max(currentPage - 1,
                Math.min(currentPage + 1, target));
            target = Math.max(0, Math.min(pageCount - 1, target));
            if (target != currentPage || getScrollX() != target * pageWidth) {
                animateScrollTo(target * pageWidth);
                currentPage = target;
                if (onPage != null) onPage.onPage(target);
            }
        }
    }

    /** Vertical scroll view with iOS-style rubber-band overscroll: dragging
     *  past an edge translates AND squashes the content against the opposite
     *  edge (distortion stretch) and springs back with a
     *  rigid release (no overshoot). Fling overscroll snaps back too,
     *  detected when the scroller stops moving with residual translation. */
    private static final class BounceScrollView extends ScrollView {
        private ValueAnimator bounce;
        private boolean dragging;
        /** our own fling scroller: ScrollView's is private, and we need a
         *  reliable fling-finished signal for the rubber-band spring-back */
        private final android.widget.OverScroller flingScroller;
        /** accumulated overshoot past the edge, in scroll px (negative at
         *  top, positive at bottom). ScrollView clamps its own scrollY at
         *  the edge and overScrollBy only carries the per-frame increment,
         *  so the out-of-bounds amount has to be accumulated here. */
        private float overscrollAccum;
        /** near-rigid rubber: a subtle, iOS-like give past the edge */
        private static final float RUBBER = 0.06f;
        /** fling-impact rubber band: peak = |velocity| × FLING_GAIN seconds
         *  worth of scroll — a hard fling visibly dives into the edge while
         *  a gentle one barely nudges it */
        private static final float FLING_GAIN = 0.06f;
        /** animator driving the fling-impact dive-in (see computeScroll) */
        private ValueAnimator rubberOut;

        /** cancel both rubber animators (bounce back and fling dive-in) */
        private void stopRubberAnims() {
            if (bounce != null) bounce.cancel();
            if (rubberOut != null) rubberOut.cancel();
        }

        /** animate the fling-impact dive from the current rubber position to
         *  the impact peak, then chain into the spring-back. This replaces
         *  the OverScroller's own edge spring, whose travel (≤48dp) is so
         *  short at fling speed that the page visually SLAMS to a stop —
         *  the "flash" users reported. */
        private void startRubberOut(float from, float to) {
            stopRubberAnims();
            rubberOut = ValueAnimator.ofFloat(from, to);
            rubberOut.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            rubberOut.setDuration(260);
            rubberOut.addUpdateListener(an -> setRubberY((Float) an.getAnimatedValue()));
            rubberOut.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (a == rubberOut) {
                        rubberOut = null;
                        if (!cancelled && !dragging) bounceBack();
                    }
                }
            });
            rubberOut.start();
        }

        BounceScrollView(android.content.Context ctx) {
            super(ctx);
            flingScroller = new android.widget.OverScroller(ctx);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        private int scrollRange() {
            View content = getChildAt(0);
            if (content == null) return 0;
            return Math.max(0, content.getHeight() - getHeight()
                + getPaddingTop() + getPaddingBottom());
        }

        @Override
        public void fling(int velocityY) {
            View content = getChildAt(0);
            if (content == null) { super.fling(velocityY); return; }
            // overshoot past the edge by a small fixed amount; a half-viewport
            // budget made the scroller's internal spring eat the whole overshoot
            // so the rubber band took ages to come back after a hard fling
            flingScroller.fling(getScrollX(), getScrollY(), 0, velocityY,
                0, 0, 0, scrollRange(), 0,
                (int) (48 * getResources().getDisplayMetrics().density + 0.5f));
            postInvalidateOnAnimation();
        }

        /** rubber band position: translate past the edge and stretch the
         *  content in the drag direction. The pivot is pinned at the edge
         *  being overscrolled, so the near edge follows the finger and the
         *  content elongates away from it. */
        private void setRubberY(float accum) {
            View content = getChildAt(0);
            if (content == null) return;
            // hard cap: sustained drags and violent flings must never stretch
            // the rubber beyond one screen height
            float h = getHeight();
            float cap = h > 0 ? h : 2400f;
            accum = Math.max(-cap, Math.min(cap, accum));
            overscrollAccum = accum;
            content.setTranslationY(-accum * RUBBER);
            float over = Math.abs(accum) * RUBBER;
            if (over > 0.5f && h > 0) {
                // pulled down past the top -> anchor the top edge and grow
                // downward; pulled up past the bottom -> anchor the bottom
                // edge and grow upward
                content.setPivotY(accum < 0 ? 0 : h);
                content.setScaleY((h + over) / h);
            } else {
                content.setScaleY(1f);
                content.setPivotY(h / 2f);
            }
        }

        @Override
        protected boolean overScrollBy(int deltaX, int deltaY, int oldScrollX,
                int oldScrollY, int scrollRangeX, int scrollRangeY,
                int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
            View content = getChildAt(0);
            int effDeltaY = deltaY;
            if (content != null && deltaY != 0) {
                if (overscrollAccum != 0f) {
                    // rubber state is only trustworthy while the scroll is
                    // actually pinned at the matching edge — anything else
                    // (bounce cancelled mid-flight by a new gesture, fling
                    // residue) must resync or the absorbed amount diverges
                    // and the page scrolls away into permanent blank
                    boolean pinned = (overscrollAccum < 0f && oldScrollY <= 0)
                        || (overscrollAccum > 0f && oldScrollY >= scrollRangeY);
                    if (pinned) {
                        float accum = overscrollAccum + deltaY;
                        if ((overscrollAccum < 0f && accum >= 0f)
                                || (overscrollAccum > 0f && accum <= 0f)) {
                            // dragged back through the edge: hand the residual
                            // to the normal scroll and drop the rubber band
                            effDeltaY = Math.round(accum);
                            setRubberY(0f);
                        } else {
                            effDeltaY = 0;
                            // hard cap: a sustained drag must never stretch
                            // the rubber beyond one screen height
                            float cap = getHeight() > 0 ? getHeight() : 2400f;
                            setRubberY(Math.max(-cap, Math.min(cap, accum)));
                        }
                        stopRubberAnims();
                        return super.overScrollBy(deltaX, effDeltaY, oldScrollX,
                            oldScrollY, scrollRangeX, scrollRangeY, 0, 0,
                            isTouchEvent);
                    }
                    // stale state: resync to the real scroll position and
                    // fall through to the normal in-range handling
                    setRubberY(0f);
                }
                int desired = oldScrollY + deltaY;
                int clamped = Math.max(0, Math.min(scrollRangeY, desired));
                float over = desired - clamped;
                if (over != 0f) {
                    // past an edge: absorb into the rubber band
                    effDeltaY = 0;
                    setRubberY(over);
                } else {
                    // in range: let the scroll happen normally
                    effDeltaY = deltaY;
                    setRubberY(0f);
                }
                stopRubberAnims();
            }
            return super.overScrollBy(deltaX, effDeltaY, oldScrollX,
                oldScrollY, scrollRangeX, scrollRangeY, 0, 0, isTouchEvent);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopRubberAnims();
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    bounceBack();
                    break;
            }
            return super.onTouchEvent(ev);
        }

        @Override
        public void computeScroll() {
            super.computeScroll();
            if (flingScroller.computeScrollOffset()) {
                int y = flingScroller.getCurrY();
                int range = scrollRange();
                int clamped = Math.max(0, Math.min(range, y));
                if (clamped != y) {
                    // scroller is past an edge: y - clamped is the ABSOLUTE
                    // out-of-bounds amount, not a per-frame increment. Feeding
                    // it through overScrollBy's incremental drag accounting
                    // does accum += fullOffset every frame, so the band
                    // compounds explosively within a few frames (the visible
                    // "flash + displaced cards" on hard flings). Pin the
                    // scroll and mirror the scroller directly instead.
                    if (getScrollY() != clamped) scrollTo(getScrollX(), clamped);
                    if (rubberOut != null && rubberOut.isRunning()) {
                        // dive-in already animating: leave it alone
                    } else {
                        // At fling speed the OverScroller's own edge spring
                        // travels at most 48dp — a few visible px — so a hard
                        // fling SLAMS to a stop in one frame. Replace it with
                        // a velocity-proportional dive-in the eye can follow.
                        float d = getResources().getDisplayMetrics().density;
                        float peak = Math.abs(flingScroller.getCurrVelocity())
                            * FLING_GAIN;
                        if (peak < 48 * d) {
                            // gentle fling: the scroller's tiny spring is fine
                            if (bounce != null && bounce.isRunning())
                                bounce.cancel();
                            setRubberY(y - clamped);
                        } else {
                            float cap = getHeight() * 0.35f;
                            float a0 = overscrollAccum;
                            float target = Math.signum(y - clamped)
                                * Math.min(cap, Math.max(Math.abs(a0), peak));
                            flingScroller.forceFinished(true);
                            startRubberOut(a0, target);
                        }
                    }
                } else if (y != getScrollY()) {
                    overScrollBy(0, y - getScrollY(), 0, getScrollY(),
                        0, range, 0, 0, true);
                } else if (overscrollAccum != 0f) {
                    // scroller settled back onto the pinned edge with rubber
                    // left over: spring the band back
                    boolean atEdge = (overscrollAccum < 0f && getScrollY() <= 0)
                        || (overscrollAccum > 0f && getScrollY() >= range);
                    if (atEdge) {
                        if (!dragging) bounceBack();
                    } else {
                        setRubberY(0f); // stale state: resync
                    }
                }
                postInvalidateOnAnimation();
            } else if (!dragging && overscrollAccum != 0f) {
                // fling finished with rubber remaining: it clamped at an
                // edge — spring the band back
                bounceBack();
            }
        }

        private void bounceBack() {
            View content = getChildAt(0);
            if (content == null) return;
            float a = overscrollAccum;
            if (a == 0f) return;
            if (bounce != null && bounce.isRunning()) return;
            // the dive-in chains into this on end; while it runs computeScroll
            // fires every frame and would re-enter here each time
            if (rubberOut != null && rubberOut.isRunning()) return;
            bounce = ValueAnimator.ofFloat(a, 0f);
            bounce.setInterpolator(new LinearInterpolator());
            bounce.setDuration(180);
            bounce.addUpdateListener(an -> setRubberY((Float) an.getAnimatedValue()));
            bounce.start();
        }
    }
}
