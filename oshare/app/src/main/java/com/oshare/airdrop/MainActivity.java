package com.oshare.airdrop;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Companion-app UI, restyled after FreeShift: three tabs (Home / Status /
 * Settings) over a dark rounded-card layout with a bottom navigation bar.
 * All module operations go through the same settings.sh backend reached via
 * su, so the app itself needs no privileges beyond a one-time root grant.
 */
public final class MainActivity extends Activity {

    private static final String SETTINGS =
        "/system/bin/sh /data/adb/modules/opairshare/bin/settings.sh";

    // Provider host package; the module disables it after a session release
    // to save power and re-enables it during the next arm.
    private static final String PROVIDER_PKG = "com.google.android.mosey";

    // palette (FreeShift dark style)
    private static final int BG       = 0xFF0B0E14;
    private static final int CARD     = 0xFF161B26;
    private static final int CARD_HI  = 0xFF1E2532;
    private static final int NAV_BG   = 0xFF10141D;
    private static final int ACCENT   = 0xFF3B82F6;
    private static final int ACCENT_D = 0xFF1D4ED8;
    private static final int TEXT     = 0xFFF2F4F8;
    private static final int TEXT_DIM = 0xFF8A93A6;
    private static final int GREEN    = 0xFF22C55E;
    private static final int ORANGE   = 0xFFF59E0B;
    private static final int RED      = 0xFFEF4444;
    private static final int GRAY     = 0xFF6B7280;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private FrameLayout[] pages;
    private IconView[] tabIcons;
    private TextView[] tabLabels;
    private int currentTab;
    private boolean busy;

    // notices
    private TextView noticeBar;
    private final Runnable noticeHide = this::hideNotice;

    // home widgets
    private CircleButton powerButton;
    private TextView homeVersionBadge, homeModeBadge;
    private TextView homeNameValue, homeDirValue, homeStateValue;

    // status widgets
    private TextView stVersion, stMode, stActive;
    private TextView stAwdl, stQuickshare, stOshare;

    // settings widgets
    private View[] modeOptions;
    private TextView modeOptionDot0, modeOptionDot1, modeOptionDot2;
    private EditText nameInput, directoryInput;
    private TextView nameSummary, directorySummary;
    private TextView logView;
    private boolean settingsBuilt;

    private Status lastStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(NAV_BG);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
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
        bg.setColor(CARD);
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
        TextView tv = text(value, 12.5f, false, TEXT_DIM);
        return tv;
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

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private LinearLayout keyRow(String label, TextView valueView) {
        LinearLayout line = row();
        TextView key = text(label, 14.5f, false, TEXT_DIM);
        key.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(key);
        if (valueView != null) {
            line.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
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

    private TextView mainButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 15, true, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(ACCENT);
        b.setBackground(bg);
        b.setPadding(dp(20), dp(13), dp(20), dp(13));
        b.setOnClickListener(listener);
        pressable(b);
        return b;
    }

    private TextView ghostButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, false, TEXT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(CARD_HI);
        b.setBackground(bg);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setOnClickListener(listener);
        pressable(b);
        return b;
    }

    /** Subtle scale press feedback, FreeShift style. */
    private void pressable(View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false;
        });
    }

    private EditText darkInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(TEXT);
        et.setHintTextColor(0xFF5A6376);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_HI);
        bg.setCornerRadius(dp(14));
        et.setBackground(bg);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        return et;
    }

    private TextView sectionTitle(String label) {
        TextView tv = text(label, 12.5f, true, TEXT_DIM);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(6), 0, dp(6), dp(8));
        return tv;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout columnRoot = column();
        root.addView(columnRoot, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        columnRoot.addView(buildTopBar());

        content = new FrameLayout(this);
        pages = new FrameLayout[] {
            (FrameLayout) wrapPage(buildHomePage()),
            (FrameLayout) wrapPage(buildStatusPage()),
            (FrameLayout) wrapPage(buildSettingsPage()),
        };
        for (int i = 1; i < pages.length; i++) pages[i].setVisibility(View.GONE);
        for (FrameLayout p : pages) {
            content.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        columnRoot.addView(content, mm(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // floating notice bar above the nav
        noticeBar = text("", 13, true, 0xFFFFFFFF);
        noticeBar.setGravity(Gravity.CENTER);
        GradientDrawable nb = new GradientDrawable();
        nb.setCornerRadius(dp(14));
        nb.setColor(0xEE1E2532);
        noticeBar.setBackground(nb);
        noticeBar.setPadding(dp(18), dp(11), dp(18), dp(11));
        noticeBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nlp.bottomMargin = dp(84);
        root.addView(noticeBar, nlp);

        columnRoot.addView(buildBottomNav());
        return root;
    }

    private View wrapPage(View page) {
        FrameLayout holder = new FrameLayout(this);
        holder.addView(page, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    private ScrollView scrollOf(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(body);
        return scroll;
    }

    private LinearLayout pageBody() {
        LinearLayout body = column();
        body.setPadding(dp(18), dp(4), dp(18), dp(28));
        return body;
    }

    // ----------------------------------------------------------- top bar

    private View buildTopBar() {
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(14), dp(20), dp(10));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView title = text("OShare AirDrop", 18, true, TEXT);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(mm(0, 1, 1f));
        bar.addView(spacer);

        powerButton = new CircleButton(this);
        powerButton.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(40));
        bar.addView(powerButton, plp);
        return bar;
    }

    // ------------------------------------------------------------ home

    private View buildHomePage() {
        LinearLayout body = pageBody();

        // hero card
        LinearLayout hero = column();
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF2563EB, ACCENT_D});
        heroBg.setCornerRadius(dp(24));
        hero.setBackground(heroBg);
        hero.setPadding(dp(22), dp(24), dp(22), dp(24));

        TextView heroTitle = text("AirDrop，无处不在。", 24, true, 0xFFFFFFFF);
        TextView heroSub = text("让 ColorOS 设备与附近的 Apple 设备无缝互传。", 13.5f, false,
            0xCCFFFFFF);
        heroSub.setPadding(0, dp(8), 0, 0);
        hero.addView(heroTitle);
        hero.addView(heroSub);
        body.addView(hero, margins(0, 16));

        // environment card
        LinearLayout env = card();
        env.addView(text("运行环境", 16, true, TEXT));

        homeVersionBadge = badge("—", ACCENT);
        LinearLayout vRow = keyRow("模块版本", null);
        vRow.addView(homeVersionBadge, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        env.addView(vRow, margins(10, 10));

        homeModeBadge = badge("—", ORANGE);
        LinearLayout mRow = keyRow("分享入口", null);
        mRow.addView(homeModeBadge, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        env.addView(mRow, margins(0, 10));

        homeNameValue = text("读取中…", 14.5f, false, TEXT);
        env.addView(keyRow("AirDrop 名称", homeNameValue), margins(0, 10));

        homeDirValue = text("…", 14.5f, false, TEXT);
        homeDirValue.setMaxLines(1);
        env.addView(keyRow("接收目录", homeDirValue), margins(0, 10));

        homeStateValue = text("读取中…", 14.5f, false, TEXT_DIM);
        env.addView(keyRow("桥接状态", homeStateValue), margins(0, 0));
        body.addView(env, margins(0, 16));

        body.addView(sectionTitle("快捷操作"));
        LinearLayout actions = row();
        TextView refreshBtn = ghostButton("刷新状态", v -> refresh());
        refreshBtn.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView logsBtn = ghostButton("查看日志", v -> { selectTab(2); loadLogs(); });
        logsBtn.setLayoutParams(mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) logsBtn.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        actions.addView(refreshBtn);
        actions.addView(logsBtn);
        body.addView(actions, margins(0, 4));

        body.addView(caption("更改设置请前往「设置」页；模块由 LSPosed 注入系统互传。"));
        body.addView(caption(""), margins(0, 8));
        return scrollOf(body);
    }

    // ---------------------------------------------------------- status

    private View buildStatusPage() {
        LinearLayout body = pageBody();

        body.addView(sectionTitle("服务"));

        LinearLayout services = card();
        stAwdl = badge("—", GRAY);
        services.addView(serviceRow("AWDL", "Apple 无线直连发现协议", stAwdl), margins(0, 12));
        stQuickshare = badge("—", GRAY);
        services.addView(serviceRow("QuickShare", " ColorOS 快享服务", stQuickshare), margins(0, 12));
        stOshare = badge("—", GRAY);
        services.addView(serviceRow("OShare", "系统互传服务", stOshare), margins(0, 0));
        body.addView(services, margins(0, 16));

        body.addView(sectionTitle("模块"));
        LinearLayout info = card();
        stVersion = text("—", 14.5f, false, TEXT);
        info.addView(keyRow("版本", stVersion), margins(0, 10));
        stMode = text("—", 14.5f, false, TEXT);
        info.addView(keyRow("目标入口", stMode), margins(0, 10));
        stActive = text("—", 14.5f, false, TEXT);
        info.addView(keyRow("生效入口", stActive), margins(0, 0));
        body.addView(info, margins(0, 16));

        TextView refreshBtn = mainButton("刷新状态", v -> refresh());
        body.addView(refreshBtn, margins(0, 4));
        return scrollOf(body);
    }

    private LinearLayout serviceRow(String title, String desc, TextView badgeView) {
        LinearLayout line = row();
        LinearLayout left = column();
        left.addView(text(title, 15, true, TEXT));
        TextView d = caption(desc);
        d.setPadding(0, dp(2), 0, 0);
        left.addView(d);
        line.addView(left, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(badgeView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    // -------------------------------------------------------- settings

    private View buildSettingsPage() {
        LinearLayout body = pageBody();
        settingsBuilt = true;

        body.addView(sectionTitle("分享入口"));
        LinearLayout modeCard = card();
        modeCard.addView(text("与 Apple 设备收发", 16, true, TEXT));
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
        nameCard.addView(text("AirDrop 名称", 16, true, TEXT));
        nameSummary = text("读取中…", 13, false, TEXT_DIM);
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
        dirCard.addView(text("接收文件保存位置", 16, true, TEXT));
        TextView dirDesc = caption("仅限 Download 或 Documents 下的目录，留空按类型保存。");
        dirDesc.setPadding(0, dp(3), 0, 0);
        dirCard.addView(dirDesc);
        directorySummary = text("读取中…", 13, false, TEXT_DIM);
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
        logCard.addView(text("日志", 16, true, TEXT));
        logView = text("", 12, false, TEXT_DIM);
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
        bg.setColor(CARD_HI);
        line.setBackground(bg);
        line.setPadding(dp(14), dp(12), dp(14), dp(12));
        line.setOnClickListener(listener);
        pressable(line);

        dot.setTextSize(16);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(TEXT_DIM);
        dot.setText("○");
        line.addView(dot, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout mid = column();
        mid.setPadding(dp(12), 0, 0, 0);
        TextView t = text(title, 14.5f, true, TEXT);
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
            dots[i].setTextColor(sel ? ACCENT : TEXT_DIM);
            View opt = modeOptions != null ? modeOptions[i] : null;
            if (opt instanceof LinearLayout) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                bg.setColor(sel ? 0x143B82F6 : CARD_HI);
                opt.setBackground(sel ? strokeBorder(bg) : bg);
            }
        }
    }

    private GradientDrawable strokeBorder(GradientDrawable bg) {
        bg.setStroke(dp(1), ACCENT);
        return bg;
    }

    // ------------------------------------------------------ bottom nav

    private View buildBottomNav() {
        LinearLayout nav = row();
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(NAV_BG);
        nav.setPadding(dp(8), dp(10), dp(8), dp(14));

        tabIcons = new IconView[3];
        tabLabels = new TextView[3];
        String[] labels = {"首页", "状态", "设置"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout tab = column();
            tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(v -> selectTab(idx));

            IconView icon = new IconView(this, i);
            tabIcons[i] = icon;
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            tab.addView(icon, ilp);

            TextView label = text(labels[i], 11, false, TEXT_DIM);
            label.setPadding(0, dp(4), 0, 0);
            tabLabels[i] = label;
            tab.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams tlp = mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nav.addView(tab, tlp);
        }
        updateTabs(0);
        return nav;
    }

    private void selectTab(int index) {
        if (index == currentTab || pages == null) return;
        int dir = index > currentTab ? 1 : -1;
        FrameLayout in = pages[index];
        pages[currentTab].setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(dp(26 * dir));
        in.animate().alpha(1f).translationX(0f).setDuration(220)
            .setInterpolator(new DecelerateInterpolator()).start();
        currentTab = index;
        updateTabs(dir);
    }

    private void updateTabs(int dir) {
        for (int i = 0; i < 3; i++) {
            boolean sel = i == currentTab;
            tabIcons[i].setColor(sel ? ACCENT : TEXT_DIM);
            tabLabels[i].setTextColor(sel ? ACCENT : TEXT_DIM);
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
            noticeBar.setVisibility(View.VISIBLE);
            noticeBar.setAlpha(0f);
            noticeBar.setTranslationY(dp(12));
            noticeBar.animate().alpha(1f).translationY(0f).setDuration(180).start();
            main.postDelayed(noticeHide, 3600);
        });
    }

    private void hideNotice() {
        noticeBar.animate().alpha(0f).translationY(dp(12)).setDuration(160)
            .withEndAction(() -> noticeBar.setVisibility(View.GONE)).start();
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
        try {
            Process p = Runtime.getRuntime().exec(
                new String[]{"su", "-c", SETTINGS + " " + args});
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
                showNotice(String.valueOf(e), RED);
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        main.post(() -> {
            if (powerButton != null) powerButton.setEnabled(!value);
        });
    }

    private void refresh() {
        if (busy) return;
        setBusy(true);
        async(() -> {
            Result r = exec("status");
            if (r.code != 0) {
                setBusy(false);
                main.post(() -> powerButton.setState(CircleButton.STATE_DOWN));
                showNotice(suFailure(r), RED);
                return;
            }
            try {
                Status s = Status.parse(r.out);
                lastStatus = s;
                main.post(() -> applyStatus(s));
            } catch (Exception e) {
                showNotice("模块状态无法解析：" + e.getMessage(), RED);
            }
            setBusy(false);
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
        setBusy(true);
        async(() -> {
            ensureProviderEnabled(args);
            Result r = exec(args);
            if (r.code != 0) {
                setBusy(false);
                showNotice(label + "失败：" + suFailure(r), RED);
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
            setBusy(false);
            main.post(() -> showNotice(note != null ? note : label + "完成", GREEN));
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
        android.util.Log.i("OPAirDropApp", "rescue check: args=" + args);
        if (args == null || !args.startsWith("share-mode")) return;
        try {
            int state = getPackageManager().getApplicationEnabledSetting(PROVIDER_PKG);
            android.util.Log.i("OPAirDropApp", "rescue state=" + state);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return;
            }
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "/system/bin/pm enable --user 0 " + PROVIDER_PKG});
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            int code = p.waitFor();
            android.util.Log.i("OPAirDropApp", "rescue su code=" + code
                + " out=" + out.trim() + " err=" + err.trim());
        } catch (Exception e) {
            android.util.Log.w("OPAirDropApp", "rescue failed", e);
        }
    }

    private void saveName() {
        if (busy) return;
        String value = nameInput.getText().toString().trim();
        if (!validName(value)) {
            showNotice("名称最多 64 个字符，不能包含换行或控制字符", RED);
            return;
        }
        String hex = hex(value);
        runAction("保存名称", hex.isEmpty() ? "name-reset" : "name-set " + hex);
    }

    private void saveDirectory() {
        if (busy) return;
        String value = directoryInput.getText().toString().trim();
        if (!validDirectory(value)) {
            showNotice("请输入 Download 或 Documents 下的目录，不能包含隐藏目录或非法字符", RED);
            return;
        }
        String hex = hex(value);
        runAction("保存位置", hex.isEmpty() ? "receive-directory-reset"
            : "receive-directory-set " + hex);
    }

    private void loadLogs() {
        if (busy) return;
        setBusy(true);
        async(() -> {
            Result r = exec("logs");
            main.post(() -> {
                logView.setVisibility(View.VISIBLE);
                logView.setText(r.code == 0
                    ? (r.out.isEmpty() ? "暂无日志" : r.out)
                    : "读取失败：" + suFailure(r));
            });
            setBusy(false);
        });
    }

    private void applyStatus(Status s) {
        // home
        homeVersionBadge.setText("v" + s.version);
        String modeName = modeName(s.shareMode);
        homeModeBadge.setText(modeName);
        setBadge(homeModeBadge, modeName, ACCENT);
        homeNameValue.setText(s.airdropName.isEmpty()
            ? s.airdropNameDefault + "（默认）" : s.airdropName);
        homeDirValue.setText(s.receiveDirectory.isEmpty() ? "默认目录" : s.receiveDirectory);
        boolean ok = s.shareMode.equals(s.shareModeActive);
        homeStateValue.setText(ok ? "已生效" : "切换中");
        homeStateValue.setTextColor(ok ? GREEN : ORANGE);
        powerButton.setState(CircleButton.STATE_ON);

        // status page
        stVersion.setText("v" + s.version);
        stMode.setText(modeName);
        stActive.setText(modeName(s.shareModeActive));
        stMode.setTextColor(ok ? TEXT : ORANGE);
        stActive.setTextColor(ok ? TEXT : ORANGE);
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
            case "running" -> GREEN;
            case "stopped" -> ORANGE;
            case "not_installed" -> RED;
            case "idle" -> GRAY;
            default -> GRAY;
        };
        setBadge(tv, label, color);
    }

    private static String modeName(String mode) {
        if (mode == null) return "未知";
        switch (mode) {
            case "quickshare": return "Quick Share";
            case "oshare": return "OShare";
            case "both": return "二者共存";
            default: return mode;
        }
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

    /** Power-style round button: ring + dot, colored by state. */
    private static final class CircleButton extends View {
        static final int STATE_OFF = 0;
        static final int STATE_ON = 1;
        static final int STATE_DOWN = 2;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int state = STATE_OFF;
        private int color = TEXT_DIM;

        CircleButton(android.content.Context ctx) {
            super(ctx);
        }

        void setState(int value) {
            if (state != value) {
                animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                    .withEndAction(() -> animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start()).start();
            }
            state = value;
            color = value == STATE_ON ? GREEN : value == STATE_DOWN ? RED : TEXT_DIM;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(color);
            c.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r * 0.36f, paint);
        }
    }

    /** Minimal vector icons drawn on canvas: 0 home, 1 radar, 2 sliders. */
    private static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int type;
        private int color = TEXT_DIM;

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

            if (type == 0) { // home: roof + body
                float s = w * 0.30f;
                c.drawLine(cx - s, cy, cx, cy - s * 0.9f, paint);
                c.drawLine(cx + s, cy, cx, cy - s * 0.9f, paint);
                c.drawLine(cx - s, cy, cx - s, cy + s * 0.85f, paint);
                c.drawLine(cx + s, cy, cx + s, cy + s * 0.85f, paint);
                c.drawLine(cx - s, cy + s * 0.85f, cx + s, cy + s * 0.85f, paint);
            } else if (type == 1) { // radar: two rings + dot
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, w * 0.30f, paint);
                c.drawCircle(cx, cy, w * 0.42f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, w * 0.10f, paint);
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
}
