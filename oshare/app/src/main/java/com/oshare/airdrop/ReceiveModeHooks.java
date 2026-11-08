package com.oshare.airdrop;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Injects a fourth reception option, "iPhone（10 分钟）", into the OShare
 *  settings screen and owns what it means. Since 1.1.18 only this option
 *  arms the module AirDrop session: it selects the official everyone state
 *  (oshare_state == 1, the full native write path) and records a marker
 *  (Settings.System "opairshare_ios_receive" == 1) the root supervisor
 *  requires before arming AWDL. Every official selection - everyone,
 *  contacts-only, receive-off and every countdown fallback - clears the
 *  marker, so the official options keep their untouched, pure-native
 *  behaviour with no module session at all.
 *
 *  The option is injected at runtime through the preference APIs instead of
 *  patching resources: the payload APK pipeline must not rebuild arsc.
 *
 *  The host bundles androidx.preference and COUI preference classes with
 *  R8-obfuscated members (class names preserved). Every reflective call
 *  therefore tries the clear name first, then the known obfuscated name of
 *  the current ColorOS 17 build (confirmed against the decompiled APK):
 *  getPreferenceScreen=q, findPreference=i1, addPreference=g1 (delegates to
 *  h1, which is the add implementation itself), getPreferenceCount=m1
 *  (j1 is a trap: getInitialExpandedChildrenCount), getPreference=l1,
 *  setOrder=V0, getOrder=M, group re-sort=r1, notify refresh=o0,
 *  getParent=P, getKey=B, setTitle=Z0, getTitle=e0,
 *  setChecked=h1(boolean), isChecked=g1. setKey does not exist in this
 *  build (stripped by R8), so the injected option is identified by its
 *  title instead of a preference key. There is no removePreference in
 *  this build either (R8 stripped it), so the option is positioned by
 *  explicit preference orders plus the group's r1() re-sort. */
public final class ReceiveModeHooks {
    private static final String TAG = "OShareAirDrop";
    /** Settings.System marker read by receive-session.sh: 1 = iPhone mode selected. */
    public static final String MARKER = "opairshare_ios_receive";
    private static final String OSHARE_STATE = "oshare_state";
    private static final String SETTINGS_FRAGMENT = "com.oplus.oshare.ui.ShareSettingsFragment";
    private static final String RADIO_CLASS = "com.oplus.oshare.ui.COUIRadioPreference";
    private static final String WRITE_ENTRY = "com.oplus.oshare.utils.p1";
    private static final String IOS_KEY = "key_reception_range_ios";
    private static final String KEY_ALL = "key_reception_range_all";
    private static final String KEY_CONTACTS = "key_reception_range_contacts";
    private static final String KEY_OFF = "key_reception_range_off";

    private static final String[] GET_SCREEN = {"getPreferenceScreen", "q"};
    private static final String[] FIND = {"findPreference", "i1"};
    private static final String[] ADD = {"addPreference", "g1"};
    private static final String[] SET_ORDER = {"setOrder", "V0"};
    private static final String[] GET_ORDER = {"getOrder", "M"};
    /** r1() is PreferenceGroup's Collections.sort over the live child list;
     *  it survives R8 under this name only. o0() is Preference's notify
     *  hook that makes the adapter rebind after a sort. */
    private static final String[] SORT = {"r1"};
    private static final String[] NOTIFY = {"o0"};
    /** getPreferenceCount is m1 (the live list size). j1 is a trap: it is
     *  getInitialExpandedChildrenCount, which defaults to Integer.MAX_VALUE
     *  and once sent a walker into a two-billion-iteration spin (ANR). */
    private static final String[] COUNT = {"getPreferenceCount", "m1"};
    private static final String[] GET_AT = {"getPreference", "l1"};
    private static final String[] GET_PARENT = {"getParent", "P"};
    private static final String[] GET_KEY = {"getKey", "B"};
    private static final String[] SET_TITLE = {"setTitle", "Z0"};
    private static final String[] GET_TITLE = {"getTitle", "e0"};
    /** COUIMarkPreference.p1(CharSequence) writes the blue assignment line a
     *  radio shows under its title (the countdown copy lives there). The
     *  static grey subtitle ("可被附近所有设备发现" of the official rows) is
     *  the standard androidx Preference summary, whose writer survives R8
     *  under the name W0 (verified against the decompiled build: W0 checks
     *  the SummaryProvider guard, stores the field and notifies). */
    private static final String[] SET_SUMMARY = {"p1"};
    private static final String[] SET_SUMMARY_STD = {"setSummary", "W0"};
    private static final String MARK_PREF = "com.coui.appcompat.preference.COUIMarkPreference";
    /** Shared with InlinePanelHooks (SystemUI control-centre panel row). */
    public static final String IOS_TITLE = "iPhone（10 分钟）";
    public static final String IOS_SUBTITLE = "可被附近iPhone设备发现";
    /** COUIRadioPreference inherits TwoStatePreference whose setChecked is
     *  obfuscated to h1(boolean) on this build; match the exact boolean
     *  signature so the unrelated PreferenceGroup.h1(Preference) is never
     *  picked up. */
    private static final String[] CHECK_WRITERS = {"setChecked", "h1"};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Entry module;
    private static ClassLoader loader;
    private static volatile WeakReference<Object> fragmentRef;
    /** Guard so the marker write of our own iPhone-mode activation survives
     *  the p1.u interception that clears it for every official write. */
    private static volatile boolean enabling;

    private ReceiveModeHooks() {}

    /** Installs the settings-screen hooks. Capability failures are logged and
     *  swallowed: without them the module keeps running with three options. */
    public static synchronized void install(Entry owner, Context context, ClassLoader cl) {
        module = owner;
        loader = cl;
        try {
            Class<?> fragment = cl.loadClass(SETTINGS_FRAGMENT);
            hookCreatePreferences(fragment);
            hookClickDispatch(fragment);
            hookStateRebind(fragment);
            hookWriteEntry(cl);
            hookSummaryRedirect(cl);
            log(Log.INFO, "iPhone reception option hooks installed");
        } catch (Throwable failure) {
            log(Log.WARN, "iPhone reception option injection unavailable", failure);
        }
    }

    private static void hookCreatePreferences(Class<?> fragment) throws Exception {
        Method create = null;
        for (Method m : fragment.getDeclaredMethods()) {
            if (!"u".equals(m.getName()) || m.getParameterCount() != 2) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types[0].getName().endsWith("Bundle") && types[1] == String.class) { create = m; break; }
        }
        if (create == null) throw new NoSuchMethodException(SETTINGS_FRAGMENT + ".u(Bundle,String)");
        create.setAccessible(true);
        module.hook(create).intercept(chain -> {
            Object result = chain.proceed();
            try { injectIosOption(chain.getThisObject()); }
            catch (Throwable t) { log(Log.WARN, "iPhone option injection failed", t); }
            return result;
        });
    }

    private static void hookClickDispatch(Class<?> fragment) throws Exception {
        Method click = null;
        for (Method m : fragment.getDeclaredMethods()) {
            if (!"d".equals(m.getName()) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0].getName().endsWith("Preference")) { click = m; break; }
        }
        if (click == null) throw new NoSuchMethodException(SETTINGS_FRAGMENT + ".d(Preference)");
        click.setAccessible(true);
        module.hook(click).intercept(chain -> {
            Object result = chain.proceed();
            try { onPreferenceClicked(chain.getThisObject(), chain.getArg(0)); }
            catch (Throwable t) { log(Log.WARN, "iPhone option click handling failed", t); }
            return result;
        });
    }

    /** ShareSettingsFragment.k0(int) is the single rebind entry for the three
     *  official radios (state observer, OSHARE_STATE broadcast and onStart all
     *  funnel into it) and it re-checks the "everyone" radio whenever the state
     *  is 1 - asynchronously, later than any scheduled sync. While iPhone mode
     *  is active that re-check is exactly the observed double tick: hook it and
     *  move the tick to the iPhone radio instead. Official states (marker==0)
     *  keep the untouched original behaviour. */
    private static void hookStateRebind(Class<?> fragment) throws Exception {
        Method rebind = null;
        for (Method m : fragment.getDeclaredMethods()) {
            if (!"k0".equals(m.getName()) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] == int.class) { rebind = m; break; }
        }
        if (rebind == null) throw new NoSuchMethodException(SETTINGS_FRAGMENT + ".k0(int)");
        rebind.setAccessible(true);
        module.hook(rebind).intercept(chain -> {
            Object result = chain.proceed();
            try { fixRadiosAfterRebind(chain.getThisObject(), (Integer) chain.getArg(0)); }
            catch (Throwable t) { log(Log.WARN, "iPhone radio rebind fix failed", t); }
            return result;
        });
    }

    private static void fixRadiosAfterRebind(Object fragment, int state) {
        if (fragment == null) return;
        Context context = contextOf(fragment);
        if (context == null) return;
        int marker;
        try { marker = Settings.System.getInt(context.getContentResolver(), MARKER, 0); }
        catch (Throwable t) { return; }
        Object screen;
        try { screen = invokeAny(fragment, GET_SCREEN); } catch (Throwable t) { return; }
        if (screen == null) return;
        if (state == 1 && marker == 1) {
            // iPhone mode owns the "state 1" tick now: everyone stays unchecked.
            setCheckedByKey(screen, KEY_ALL, false);
            Object ios = findByTitle(screen, IOS_TITLE);
            if (ios != null) setCheckedOn(ios, true);
        } else {
            Object ios = findByTitle(screen, IOS_TITLE);
            if (ios != null) setCheckedOn(ios, false);
            if (marker == 1 && state != 1) scheduleSync(fragment);
        }
    }

    /** COUIMarkPreference.p1(CharSequence) is where the 10-minute countdown copy
     *  lands: ShareSettingsFragment$d.run() and its cleanup handler write it on
     *  the "everyone" radio every second. In iPhone mode that copy belongs to
     *  the iPhone radio, so redirect the call: the iPhone row gets the text and
     *  the everyone row is force-cleared (no stale countdown under it). Calls
     *  on any other preference are untouched. */
    private static void hookSummaryRedirect(ClassLoader cl) throws Exception {
        Class<?> mark = cl.loadClass(MARK_PREF);
        Method write = null;
        for (Method m : mark.getDeclaredMethods()) {
            if (!"p1".equals(m.getName()) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] == CharSequence.class) { write = m; break; }
        }
        if (write == null) throw new NoSuchMethodException(MARK_PREF + ".p1(CharSequence)");
        write.setAccessible(true);
        module.hook(write).intercept(chain -> {
            try {
                Object self = chain.getThisObject();
                Object fragment = fragmentRef == null ? null : fragmentRef.get();
                if (fragment != null && isEveryoneRadio(fragment, self)) {
                    Context context = contextOf(fragment);
                    int marker = context == null ? 0
                            : Settings.System.getInt(context.getContentResolver(), MARKER, 0);
                    if (marker == 1) {
                        Object screen = invokeAny(fragment, GET_SCREEN);
                        Object ios = screen == null ? null : findByTitle(screen, IOS_TITLE);
                        if (ios != null && ios != self)
                            invokeAny(ios, SET_SUMMARY, (CharSequence) chain.getArg(0));
                        return chain.proceed(new Object[]{null});
                    }
                }
            } catch (Throwable t) { log(Log.WARN, "countdown summary redirect failed", t); }
            return chain.proceed();
        });
    }

    private static boolean isEveryoneRadio(Object fragment, Object preference) {
        if (preference == null) return false;
        try {
            Object screen = invokeAny(fragment, GET_SCREEN);
            return screen != null && preference.equals(findByKey(screen, KEY_ALL));
        } catch (Throwable t) { return false; }
    }

    /** p1.u(Context,int) is the choke point of every official reception state
     *  write (putInt + state listener + active-state mirror). Any write that
     *  does not come from our own activation means the user - or the official
     *  countdown fallback - left iPhone mode: clear the marker and refresh
     *  the radios. */
    private static void hookWriteEntry(ClassLoader cl) throws Exception {
        Class<?> writer = cl.loadClass(WRITE_ENTRY);
        Method write = null;
        for (Method m : writer.getDeclaredMethods()) {
            if (!"u".equals(m.getName()) || m.getParameterCount() != 2) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types[0] == Context.class && types[1] == int.class) { write = m; break; }
        }
        if (write == null) throw new NoSuchMethodException(WRITE_ENTRY + ".u(Context,int)");
        write.setAccessible(true);
        module.hook(write).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (!enabling) {
                    // iPhone mode is encoded as state=1 + marker=1, so a later
                    // official u(ctx,1) echo (radio UI churn, countdown refresh)
                    // does NOT mean the user left iPhone mode. Only a write of
                    // another state - contacts, off, or countdown expiry - does.
                    int written = (chain.getArg(1) instanceof Integer) ? (Integer) chain.getArg(1) : -1;
                    if (written != 1) {
                        log(Log.INFO, "official reception write " + written + "; clearing iPhone marker");
                        clearMarker((Context) chain.getArg(0));
                        scheduleSync(null);
                    }
                }
            } catch (Throwable t) { log(Log.WARN, "receive marker reset failed", t); }
            return result;
        });
    }

    private static void onPreferenceClicked(Object fragment, Object preference) {
        if (preference == null) return;
        boolean ios;
        String key;
        try {
            key = stringOf(preference, GET_KEY);
            ios = IOS_KEY.equals(key);
            if (!ios) ios = IOS_TITLE.equals(stringOf(preference, GET_TITLE));
        } catch (Throwable t) { log(Log.WARN, "clicked radio identification failed", t); return; }
        if (ios) {
            Context context = contextOf(fragment);
            if (context != null) enableIosMode(context);
        } else {
            if (KEY_ALL.equals(key) || KEY_CONTACTS.equals(key) || KEY_OFF.equals(key)) {
                // The write path itself clears the marker; this is a direct
                // fallback for builds where p1.u does not resolve.
                clearMarker(contextOf(fragment));
            }
        }
        scheduleSync(fragment);
    }

    /** Opening "与 iPhone 互传" (IOSShareActivity) is an explicit intent to
     *  talk to iPhones: switch to iPhone reception mode so the session arms
     *  while the page is up. A cheap no-op when the mode is already active. */
    public static void autoEnableIosMode(Activity activity) {
        if (module == null || activity == null) return;
        Context context = activity.getApplicationContext() != null
                ? activity.getApplicationContext() : activity;
        try {
            int state = Settings.System.getInt(context.getContentResolver(), OSHARE_STATE, 0);
            int marker = Settings.System.getInt(context.getContentResolver(), MARKER, 0);
            if (state == 1 && marker == 1) return;
        } catch (Throwable ignored) {}
        log(Log.INFO, "IOSShareActivity resumed; enabling iPhone reception mode");
        enableIosMode(context);
    }

    /** Selects the injected option: the full official everyone write path
     *  (p1.v -> p1.u -> broadcast) plus the module marker. The marker is
     *  written after p1.v returns so the guarded u interception cannot clear
     *  it again. */
    public static void enableIosMode(Context context) {
        if (context == null) return;
        try {
            int state = Settings.System.getInt(context.getContentResolver(), OSHARE_STATE, 0);
            int marker = Settings.System.getInt(context.getContentResolver(), MARKER, 0);
            if (state != 1 || marker != 1) {
                enabling = true;
                try { invokeWriteEntry(context, 1); }
                finally { enabling = false; }
                Settings.System.putInt(context.getContentResolver(), MARKER, 1);
                log(Log.INFO, "enableIosMode wrote state=1 marker=1");
            }
        } catch (Throwable failure) {
            log(Log.WARN, "iPhone reception mode enable failed", failure);
            try { Settings.System.putInt(context.getContentResolver(), MARKER, 1); }
            catch (Throwable ignored) {}
        }
        scheduleSync(null);
    }

    private static void invokeWriteEntry(Context context, int state) throws Exception {
        Class<?> writer = loader.loadClass(WRITE_ENTRY);
        for (Method m : writer.getDeclaredMethods()) {
            if (!"v".equals(m.getName()) || m.getParameterCount() != 2) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types[0] == Context.class && types[1] == int.class) {
                m.setAccessible(true);
                m.invoke(null, context, state);
                return;
            }
        }
        throw new NoSuchMethodException(WRITE_ENTRY + ".v(Context,int)");
    }

    private static void clearMarker(Context context) {
        if (context == null) return;
        try {
            if (Settings.System.getInt(context.getContentResolver(), MARKER, 0) != 0)
                Settings.System.putInt(context.getContentResolver(), MARKER, 0);
        } catch (Throwable t) { log(Log.WARN, "receive marker clear failed", t); }
    }

    /** Public entry for surfaces outside the settings screen (the SystemUI
     *  inline panel) whose official "everyone" tap bypasses the settings click
     *  dispatch: the panel writes state 1, which the p1.u interception treats
     *  as a harmless echo, so the marker must be cleared here explicitly. */
    public static void onOfficialEveryoneTap(Context context) {
        if (enabling) return;
        clearMarker(context);
        scheduleSync(null);
    }

    /** True while the module itself is driving the official write path to
     *  select iPhone mode - native side effects of that write (the everyone
     *  radio being re-checked) must not be read as user choices. */
    public static boolean enablingIos() { return enabling; }

    /** Same contract as onOfficialEveryoneTap for the other official panel
     *  rows (contacts / receive-off): choosing any official mode ends iPhone
     *  mode. The panel writes its state on its own path and never clears the
     *  marker, which used to leave two options checked at once. */
    public static void onOfficialModeChosen(Context context) {
        if (enabling) return;
        clearMarker(context);
        scheduleSync(null);
    }

    /** True when the iPhone reception mode is selected (marker == 1). */
    public static boolean iosModeActive(Context context) {
        if (context == null) return false;
        try { return Settings.System.getInt(context.getContentResolver(), MARKER, 0) == 1; }
        catch (Throwable t) { return false; }
    }

    /** Inserts the iPhone option between "everyone" and "contacts-only".
     *  This build has no removePreference (R8 stripped it), so the option is
     *  positioned by order instead: the reception category holds only these
     *  four radios (settings_preferences.xml), so they get explicit
     *  consecutive orders, addPreference's binarySearch places the new radio
     *  between everyone and contacts, and the group's r1() re-sort plus o0()
     *  notify settle the final layout. Re-running is idempotent: presence is
     *  detected by title because this R8 build has no setKey. */
    private static void injectIosOption(Object fragment) throws Exception {
        Object screen = invokeAny(fragment, GET_SCREEN);
        if (screen == null) return;
        Object all = findByKey(screen, KEY_ALL);
        if (all == null) return;
        fragmentRef = new WeakReference<>(fragment);
        probeRuntimeAnimator(fragment);
        if (findByTitle(screen, IOS_TITLE) != null) { scheduleSync(null); return; }
        Object contacts = findByKey(screen, KEY_CONTACTS);
        Object off = findByKey(screen, KEY_OFF);
        Object radio = createRadio(contextOf(fragment));
        if (radio == null) { log(Log.WARN, "COUIRadioPreference construction unavailable", null); return; }
        setTitle(radio, IOS_TITLE);
        // The grey subtitle must stay visible whether the option is on or off,
        // so it goes to the standard summary, not the assignment line (which
        // would flash blue and get overwritten by the countdown redirect).
        try { invokeAny(radio, SET_SUMMARY_STD, (CharSequence) IOS_SUBTITLE); }
        catch (Throwable t) { log(Log.WARN, "iPhone option summary set failed", t); }
        Object group = groupOf(all, screen);
        if (group == null) { log(Log.WARN, "reception category not found", null); return; }
        int base = orderOf(all);
        setOrder(all, base);
        setOrder(radio, base + 1);
        if (contacts != null) setOrder(contacts, base + 2);
        if (off != null) setOrder(off, base + 3);
        invokeAny(group, ADD, radio);
        try { invokeAny(group, SORT); } catch (Throwable ignored) {}
        try { invokeAny(group, NOTIFY); } catch (Throwable ignored) {}
        log(Log.INFO, "iPhone reception option inserted at order " + (base + 1));
        scheduleSync(null);
    }

    private static int orderOf(Object preference) {
        try { return (Integer) invokeAny(preference, GET_ORDER); }
        catch (Throwable t) { return 0; }
    }

    private static void setOrder(Object preference, int order) {
        try { invokeAny(preference, SET_ORDER, order); }
        catch (Throwable t) { log(Log.WARN, "radio order set failed", t); }
    }

    private static Object createRadio(Context context) {
        if (context == null || loader == null) return null;
        try {
            Class<?> type = loader.loadClass(RADIO_CLASS);
            try {
                Constructor<?> ctor = type.getDeclaredConstructor(Context.class, AttributeSetHolder.ATTRS);
                ctor.setAccessible(true);
                return ctor.newInstance(context, null);
            } catch (NoSuchMethodException ignored) {}
            try {
                Constructor<?> ctor = type.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                return ctor.newInstance(context);
            } catch (NoSuchMethodException ignored) {}
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 1 && types[0].isInstance(context)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(context);
                }
            }
        } catch (Throwable t) { log(Log.WARN, "radio preference construction failed", t); }
        return null;
    }

    /** Never instantiated; only the class object is needed as the
     *  AttributeSet constructor marker. */
    private static final class AttributeSetHolder {
        static final Class<android.util.AttributeSet> ATTRS = android.util.AttributeSet.class;
    }

    private static Object groupOf(Object preference, Object screen) {
        try { return invokeAny(preference, GET_PARENT); }
        catch (Throwable ignored) {}
        try { return containingGroup(screen, preference); }
        catch (Throwable ignored) { return null; }
    }

    private static Object containingGroup(Object screen, Object preference) throws Exception {
        // Straight scan: every direct child group's children.
        int total = countOf(screen);
        for (int i = 0; i < total; i++) {
            Object group = invokeAt(screen, i);
            if (group == null) continue;
            try {
                int size = countOf(group);
                for (int j = 0; j < size; j++)
                    if (preference.equals(invokeAt(group, j))) return group;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Aligns every radio with the real reception state: the iPhone option is
     *  checked only for the marker combination it owns, and the official
     *  "everyone" radio is unchecked while iPhone mode is active (the native
     *  receiver sees oshare_state == 1 and would otherwise highlight it). */
    private static void syncRadios() {
        Object fragment = fragmentRef == null ? null : fragmentRef.get();
        if (fragment == null) return;
        Context context = contextOf(fragment);
        if (context == null) return;
        try {
            Object screen = invokeAny(fragment, GET_SCREEN);
            if (screen == null) return;
            int state = Settings.System.getInt(context.getContentResolver(), OSHARE_STATE, 0);
            int marker = Settings.System.getInt(context.getContentResolver(), MARKER, 0);
            setCheckedByKey(screen, KEY_ALL, state == 1 && marker != 1);
            setCheckedByKey(screen, KEY_CONTACTS, state == 2);
            setCheckedByKey(screen, KEY_OFF, state == 0);
            Object ios = findByTitle(screen, IOS_TITLE);
            if (ios != null) {
                boolean active = state == 1 && marker == 1;
                setCheckedOn(ios, active);
                // The grey subtitle mirrors the official rows: always visible,
                // on and off alike. The blue assignment line below it carries
                // only the live countdown - while the mode is active the
                // redirected p1 tick owns it, and whenever the mode is off a
                // single empty write collapses it (p1 hides empty marks by
                // GONE-ing the assignment view; repeated writes are no-ops
                // because p1 short-circuits on equal text).
                try { invokeAny(ios, SET_SUMMARY_STD, (CharSequence) IOS_SUBTITLE); }
                catch (Throwable t) { log(Log.WARN, "iPhone option summary sync failed", t); }
                if (!active) {
                    try { invokeAny(ios, SET_SUMMARY, (CharSequence) ""); }
                    catch (Throwable t) { log(Log.WARN, "iPhone option countdown clear failed", t); }
                }
            }
        } catch (Throwable t) { log(Log.WARN, "reception option sync failed", t); }
    }

    private static void setCheckedByKey(Object screen, String key, boolean checked) {
        Object preference = findByKey(screen, key);
        if (preference != null) setCheckedOn(preference, checked);
    }

    private static void setCheckedOn(Object preference, boolean checked) {
        for (String name : CHECK_WRITERS) {
            for (Class<?> c = preference.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, boolean.class);
                    m.setAccessible(true);
                    m.invoke(preference, checked);
                    return;
                } catch (NoSuchMethodException ignored) {}
                catch (Throwable t) { log(Log.WARN, "radio update failed", t); return; }
            }
        }
    }

    /** Two passes beat the official state listener that re-checks the
     *  "everyone" radio right after our write lands. */
    private static void scheduleSync(Object fragment) {
        if (fragment != null) fragmentRef = new WeakReference<>(fragment);
        MAIN.postDelayed(ReceiveModeHooks::syncRadios, 100);
        MAIN.postDelayed(ReceiveModeHooks::syncRadios, 500);
    }

    /** The grey band flashing at the bottom of an option row while the list
     *  expands it is the RecyclerView change animation: notifyItemChanged
     *  cross-fades the old (short) item view with the new (tall) one, and
     *  mid-fade the old view's pressed highlight is still painted, reading
     *  as a full-width grey strip for a couple of frames. Clearing the
     *  transient view states of the outgoing holder the instant the change
     *  animation starts keeps the cross-fade (the expansion stays smooth)
     *  while removing what it paints over the fresh card. */
    /** Logs which ItemAnimator implementation the host actually installs on
     *  its RecyclerViews: if it is a COUI subclass overriding animateChange,
     *  the DefaultItemAnimator hook below would never fire. NOTE: the old
     *  setItemAnimator-based probe was removed - the host's obfuscated androidx
     *  no longer exposes a RecyclerView$ItemAnimator nested class (CNFE aborted
     *  the whole install chain), the callback never fired in practice anyway
     *  (the animator is created inside the RecyclerView constructor), and
     *  probeRuntimeAnimator below covers discovery directly. */

    /** COUI ships its own ItemAnimator subclass; if it overrides animateChange
     *  the DefaultItemAnimator hook never fires. Read the animator actually
     *  installed on the settings RecyclerView and hook that implementation
     *  directly. */
    private static volatile boolean runtimeAnimatorHooked;
    private static int probeAttempts;

    private static void probeRuntimeAnimator(Object fragment) {
        if (runtimeAnimatorHooked) return;
        probeAttempts = 0;
        scheduleProbe(fragment);
    }

    /** The settings RecyclerView only exists after onCreateView, which runs
     *  after the onCreatePreferences call this probe piggybacks on - retry on
     *  the main thread until the list is reachable. */
    private static void scheduleProbe(Object fragment) {
        if (probeAttempts >= 5 || runtimeAnimatorHooked) {
            if (!runtimeAnimatorHooked)
                log(Log.WARN,
                        "runtime animator probe gave up after " + probeAttempts + " attempts");
            return;
        }
        probeAttempts++;
        MAIN.postDelayed(() -> {
            if (runtimeAnimatorHooked) return;
            try {
                Class<?> rvClass = loader.loadClass("androidx.recyclerview.widget.RecyclerView");
                Object rv = recyclerViewOf(fragment, rvClass);
                if (rv == null) {
                    scheduleProbe(fragment);
                    return;
                }
                Object animator = invokeExact(rv, "getItemAnimator", 0);
                runtimeAnimatorHooked = true;
                if (animator == null) return;
                hookAnimatorChange(animator.getClass());
            } catch (Throwable t) {
                log(Log.WARN, "runtime animator probe failed", t);
            }
        }, 300);
    }

    /** The host ships a renamed androidx ("androidx.recyclerview.widget.i" is
     *  what the settings list actually uses) with member names obfuscated too,
     *  so animateChange cannot be found by name. Signature instead: it is the
     *  only ItemAnimator method taking (holder, holder, int, int, int, int) -
     *  add/remove take one argument and move takes five. */
    private static void hookAnimatorChange(Class<?> cls) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 6) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types[2] != int.class || types[3] != int.class
                        || types[4] != int.class || types[5] != int.class) continue;
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                m.setAccessible(true);
                final Class<?> owner = k;
                final String name = m.getName();
                module.hook(m).intercept(chain -> {
                    // The tapped row stays pressed on BOTH surfaces of the
                    // change animation (old and new holder can be different
                    // views); clear them now and again next frame, in case
                    // the ripple is re-entered by the touch stream.
                    for (int i = 0; i < 2; i++) {
                        final Object holder = chain.getArg(i);
                        if (holder == null) continue;
                        try { clearTransientStates(holder); }
                        catch (Throwable t) { log(Log.WARN, "outgoing holder state clear failed", t); }
                        MAIN.postDelayed(() -> {
                            try { clearTransientStates(holder); }
                            catch (Throwable ignored) {}
                        }, 32);
                    }
                    return chain.proceed();
                });
                log(Log.INFO, "change feedback hook on " + k.getName() + "." + name);
                return;
            }
        }
        log(Log.WARN, "no concrete animateChange in chain of " + cls.getName());
    }

    private static void clearTransientStates(Object holder) throws Exception {
        if (holder == null) return;
        android.view.View v = itemViewOf(holder);
        if (v != null) settleView(v);
    }

    /** The host renames androidx members, so ViewHolder.itemView's field name
     *  cannot be trusted - find it by type instead. The field is declared on
     *  RecyclerView.ViewHolder itself, so prefer the base-class declaration
     *  over whatever View-typed field the (obfuscated) concrete holder adds. */
    private static android.view.View itemViewOf(Object holder) {
        // Preferred: the View-typed field declared on the ViewHolder base
        // hierarchy (itemView), looked up on the holder class's own loader.
        try {
            ClassLoader cl = holder.getClass().getClassLoader();
            Class<?> base = cl.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
            for (Class<?> k = base; k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!android.view.View.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object value = f.get(holder);
                        if (value instanceof android.view.View) return (android.view.View) value;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        // Fallback: nearest View-typed instance field up the holder's chain.
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!android.view.View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value instanceof android.view.View) return (android.view.View) value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** Clears transient (press/selection) state and, crucially, jumps every
     *  drawable to its current state: setPressed(false) merely starts the
     *  COUI ripple's own fade-out, which keeps painting the lighter pressed
     *  background for hundreds of milliseconds - exactly the grey band the
     *  cross-fade then renders. jumpDrawablesToCurrentState ends it at once. */
    private static void settleView(android.view.View v) {
        v.setPressed(false);
        v.setSelected(false);
        v.setActivated(false);
        v.setHovered(false);
        v.jumpDrawablesToCurrentState();
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View child = group.getChildAt(i);
                if (child != null) settleView(child);
            }
        }
    }

    private static Object recyclerViewOf(Object fragment, Class<?> rvClass) {
        for (Class<?> k = fragment.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || !rvClass.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(fragment);
                    if (rvClass.isInstance(value)) return value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Context contextOf(Object fragment) {
        if (fragment == null) return null;
        for (String name : new String[]{"getContext", "requireContext"}) {
            try { return (Context) invokeExact(fragment, name, 0); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object findByKey(Object screen, String key) {
        try { return invokeAny(screen, FIND, key); }
        catch (Throwable ignored) { return null; }
    }

    private static Object findByTitle(Object screen, String title) {
        return walk(screen, title, 0);
    }

    private static Object walk(Object group, String title, int depth) {
        if (group == null || depth > 4) return null;
        int total;
        try { total = countOf(group); } catch (Throwable t) { return null; }
        for (int i = 0; i < total; i++) {
            Object child;
            try { child = invokeAt(group, i); }
            catch (Throwable t) { break; } // index list desync: stop, never spin
            if (child == null) continue;
            String childTitle = stringOf(child, GET_TITLE);
            if (title.equals(childTitle)) return child;
            // Categories are nested preference groups; recurse shallowly.
            if (isPreferenceGroup(child)) {
                Object found = walk(child, title, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Sanity cap: a real preference group never holds more; anything above
     *  means a misresolved method and must abort the walk instead of
     *  spinning. */
    private static final int MAX_CHILDREN = 256;

    private static boolean isPreferenceGroup(Object node) {
        for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass())
            if ("androidx.preference.PreferenceGroup".equals(c.getName())) return true;
        return false;
    }

    private static int countOf(Object group) throws Exception {
        Method m = declaredMethod(group, "getPreferenceCount", "m1", 0);
        int count = (Integer) m.invoke(group);
        if (count < 0 || count > MAX_CHILDREN)
            throw new IllegalStateException("implausible preference count " + count);
        return count;
    }

    private static Object invokeAt(Object group, int index) throws Exception {
        Method m = declaredMethod(group, "getPreference", "l1", 1);
        return m.invoke(group, index);
    }

    private static void setTitle(Object preference, String title) throws Exception {
        Method m = declaredMethod(preference, "setTitle", "Z0", 1);
        m.invoke(preference, title);
    }

    private static String stringOf(Object target, String[] names) {
        for (String name : names) {
            try {
                Method m = declaredMethod(target, name, null, 0);
                Object value = m.invoke(target);
                return value == null ? null : String.valueOf(value);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object invokeAny(Object target, String[] names, Object... args) throws Exception {
        Exception last = null;
        for (String name : names) {
            try {
                Method m = declaredMethod(target, name, null, args.length);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) { last = e; }
        }
        throw last != null ? last : new NoSuchMethodException("no candidate on " + target.getClass().getName());
    }

    private static Object invokeExact(Object target, String name, int count) throws Exception {
        Method m = declaredMethod(target, name, null, count);
        return m.invoke(target);
    }

    /** Name + parameter-count match over the whole hierarchy; the generic
     *  preference framework exposes exactly one overload for each name used
     *  here, so no instance-class signature matching is needed. */
    private static Method declaredMethod(Object target, String name, String alternate, int count)
            throws NoSuchMethodException {
        NoSuchMethodException last = new NoSuchMethodException(target.getClass().getName()
                + "." + name + "/" + count);
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != count) continue;
                if (name.equals(m.getName()) || (alternate != null && alternate.equals(m.getName()))) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw last;
    }

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
