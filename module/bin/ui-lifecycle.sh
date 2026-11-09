#!/system/bin/sh
# Keep the driver/native server resident; share provider ownership between UI
# and continuous module-owned background reception.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/receive-startup.sh"
. "$MODDIR/bin/coex-wifi.sh"
. "$MODDIR/bin/kernel-identity.sh"
. "$MODDIR/bin/share-mode.sh"
. "$MODDIR/bin/idle-sleep.sh"
init_state
exec 8> "$STATE/provider.flock"
flock -n 8 8>&8 || exit 0
echo "$$" > "$STATE/supervisor.pid"
cleanup() {
    receive_warmup_cleanup
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&- || echo 'Cannot clear AirDrop receive readiness' >&2
    [ "$(cat "$STATE/supervisor.pid" 2>/dev/null || true)" != "$$" ] || rm -f "$STATE/supervisor.pid"
    [ "$(cat "$STATE/supervisor-ready" 2>/dev/null || true)" != "$(boot_id) $$" ] || rm -f "$STATE/supervisor-ready"
    rm -f "$STATE/foreground-$$.txt"
    exec 8>&-
}
trap cleanup EXIT
trap 'exit 0' INT TERM

# A prior supervisor may have died before its EXIT trap. Never inherit its
# admission gate before validating this boot's coex/provider ownership.
/system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-

/system/bin/sh "$MODDIR/bin/speed-history.sh" start 8>&-

wifi_session() {
    CLASSPATH="$MODDIR/payload/native/session-wifi.jar" \
        /system/bin/timeout -k 2 18 /system/bin/app_process /system/bin OPAirShareSessionWifi "$1" "$STATE/session-wifi" 8>&-
}

# Reads the resumed activity and reports open/closed/unknown for the
# current share surface. A dump can be truncated when the activity manager
# is busy, which used to hide a visible share surface for a single sample.
# That single false "closed" sample closed the receiver gate and dropped
# this device from the iPhone AirDrop list, so a closed result is confirmed
# with a second dump before it is trusted.
# Tells whether the resumed activity belongs to a share surface.
# $1 = resumed activity lines, $2 = full activity dump.
foreground_matches() {
    _resumed="$1"
    _activities="$2"
    # A visible OShare share surface is an explicit user intent to share, so
    # it always counts as an open surface. It used to be hidden while the
    # applied share mode was "quickshare", because the mode property changes
    # during a mode switch. That turned a visible share surface into a
    # "closed" reading for a moment, which closed the receiver gate and
    # removed this device from the iPhone AirDrop list.
    if printf '%s\n' "$_resumed" | grep -Eq '(com\.coloros\.oshare/(\.ui\.(IOS)?ShareActivity|com\.(oplus|coloros)\.oshare\.ui\.(IOS)?ShareActivity)|com\.android\.intentresolver/(\.ChooserActivity(Launcher)?|com\.android\.intentresolver\.ChooserActivity(Launcher)?)|com\.android\.internal\.app\.ResolverActivity)([ }]|$)'; then
        return 0
    fi
    if [ "$SHARE_MODE" != oshare ] && printf '%s\n' "$_resumed" | grep -Eq 'com\.google\.android\.gms/[^ ]*nearby\.sharing\.'; then
        return 0
    fi
    if [ "$SHARE_MODE" != oshare ] && [ -e "$STATE/ui-session" ] &&
       printf '%s\n' "$_resumed" | grep -Eq '(com\.android\.documentsui|com\.google\.android\.documentsui)/';then
        _task=$(printf '%s\n' "$_resumed" | sed -n 's/.* t\([0-9][0-9]*\).*/\1/p' | head -n 1)
        if [ -n "$_task" ] &&
           grep -Eq "com\.google\.android\.gms/[^ ]*nearby\.sharing\..* t${_task}[ }]" "$_activities";then
            return 0
        fi
    fi
    return 1
}

foreground_state() {
    SHARE_MODE=$(active_share_mode)
    activities_file="$STATE/foreground-$$.txt"
    if ! /system/bin/timeout 6 /system/bin/dumpsys activity activities > "$activities_file" 2>/dev/null; then
        echo unknown;return
    fi
    resumed=$(grep -E 'topResumedActivity=|mResumedActivity:|ResumedActivity:' "$activities_file" || true)
    if [ -z "$resumed" ];then echo unknown;return;fi
    if ! foreground_matches "$resumed" "$activities_file";then
        # The dump may have been cut short while the activity manager was
        # busy. Re-read it once before reporting that no share surface is
        # visible.
        if /system/bin/timeout 6 /system/bin/dumpsys activity activities > "$activities_file" 2>/dev/null; then
            resumed=$(grep -E 'topResumedActivity=|mResumedActivity:|ResumedActivity:' "$activities_file" || true)
            if [ -z "$resumed" ];then echo unknown;return;fi
        fi
    fi
    if foreground_matches "$resumed" "$activities_file";then
        echo open;return
    fi
    echo closed
}

close_session() {
    /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&- || return 8
    # Inspect App transfer state before releasing the original provider.
    /system/bin/sh "$MODDIR/bin/transfer-idle.sh" 8>&- || return 9
    # AirDrop reception ownership now follows the OShare receive switch
    # (oshare_state) directly, so a close here is always an explicit stop:
    # release the provider fully and let the radio return to power saving
    # until the switch or a share surface re-arms the session. "force" skips
    # the foreground re-check: it is used when the activity dump has been
    # unreadable for a full minute while reception is not wanted, where
    # waiting forever would keep the AWDL session (and the paused Wi-Fi)
    # resident with no way out.
    if [ "${1:-normal}" != recover ] && [ "${1:-normal}" != force ];then
        [ "$(foreground_state)" = closed ] || return 10
    fi
    /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" release 8>&- || return 6
    [ -z "$(pidof "$PKG" || true)" ] || return 6
    reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4) || return 6
    printf '%s\n' "$reply" > "$STATE/ui-native-stop.txt"
    echo "$reply" | grep -q fffffff && return 6
    P=/sys/module/qca_cld3_peach_v2/parameters
    [ "$(cat "$P/opairshare_bridge_pending")" = 0 ] || return 7
    driver_radio_idle || return 7
    if [ -e "$STATE/session-wifi" ]; then wifi_session restore || return 8; fi
    /system/bin/sh "$MODDIR/bin/coex-session.sh" restore 8>&- || return 8
    /system/bin/sh "$MODDIR/bin/receive-session.sh" release-host 8>&- || return 8
    rm -f "$STATE/ui-session"
    echo 'UI_CLOSED_RADIO_RELEASED_DRIVER_RETAINED'
}

# The framework Wi-Fi status read (`cmd wifi status`) is a binder round-trip
# plus an app_process fork on every loop tick, which made the resident
# supervisor one of the biggest idle-awake costs of the module. Association
# state changes on the order of seconds, so the classification is refreshed
# every OPAIRSHARE_WIFI_POLL_INTERVAL ticks and reused in between; the first
# tick always reads fresh and every other consumer (coex-session prepare,
# restore paths) still reads directly.
wifi_poll_count=0
wifi_poll_cached=0
monitor_coex_wifi() {
    coex_recover=0
    exec 6> "$STATE/settings.flock"
    if ! flock -n 6 6>&6;then
        exec 6>&-
        return 1
    fi
    wifi_poll_count=$((wifi_poll_count+1))
    if [ "$wifi_poll_cached" = 0 ] ||
       [ "$wifi_poll_count" -ge "${OPAIRSHARE_WIFI_POLL_INTERVAL:-3}" ];then
        wifi_poll_count=0
        if coex_wifi_eligible;then wifi_poll_cached=1;else wifi_poll_cached=0;fi
    fi
    if [ "$wifi_poll_cached" = 1 ];then
        if [ -e "$STATE/coex-wifi-blocked" ];then
            rm -f "$STATE/coex-wifi-blocked" || { exec 6>&-;return 1; }
            echo "COEX_WIFI_ELIGIBLE class=$coex_wifi_class frequency=${coex_wifi_frequency:-none}"
        fi
        # A class/scope/network change needs a fresh profile. A channel
        # change inside the same class is handled by the driver policy.
        #
        # Exception: while the AWDL data interface (mosey0) is up, the
        # firmware parks the STA association (dbs=0 hardware), so the
        # class flip same149<->disconnected is the expected side effect
        # of arming the AirDrop session, not a network change. Rebuilding
        # the session on that flip released Mosey, which brought the STA
        # back, which re-armed the session: a ~10s connect/disconnect
        # loop for the whole window. A real network change cannot be
        # observed while the STA is parked anyway; it becomes visible as
        # soon as the session releases and mosey0 disappears, and this
        # check handles it then.
        if [ -d "$STATE/coex-session" ] &&
           { [ "$(cat "$STATE/coex-session/class" 2>/dev/null)" != "$coex_wifi_class" ] ||
             [ "$(cat "$STATE/coex-session/scope" 2>/dev/null)" != "$(coex_scope)" ] ||
             [ "$(cat "$STATE/coex-session/network" 2>/dev/null)" != "$coex_wifi_network" ]; } &&
           [ ! -e /sys/class/net/mosey0 ];then
            if [ ! -e "$STATE/coex-session/invalidated" ];then
                touch "$STATE/coex-session/invalidated" || { exec 6>&-;return 1; }
                echo 'COEX_WIFI_CHANGED: rebuilding session when idle'
            fi
        fi
        # A foreground legacy session must stop before upgrading to coex.
        if [ -e "$STATE/ui-session" ] && [ ! -d "$STATE/coex-session" ];then coex_recover=1;fi
    else
        if [ "$(cat "$STATE/coex-wifi-blocked" 2>/dev/null)" != "$coex_wifi_reason" ];then
            printf '%s\n' "$coex_wifi_reason" > "$STATE/coex-wifi-blocked.tmp" &&
                mv "$STATE/coex-wifi-blocked.tmp" "$STATE/coex-wifi-blocked" || { exec 6>&-;return 1; }
            echo "COEX_WIFI_SUSPENDED reason=$coex_wifi_reason class=${coex_wifi_class:-unknown} frequency=${coex_wifi_frequency:-unknown}"
        fi
        # AirDrop traffic runs on the AWDL interface (wonder0), never on the
        # STA association. This platform reports no concurrent capacity
        # (OPAIRSHARECOEXHW: dbs=0 sbs=0), so arming AWDL makes the firmware
        # pause the STA association. Releasing the session on that drop
        # disabled the Mosey provider and restarted the native server, which
        # re-applied the regulatory domain and flushed the armed wondertap -
        # that destroyed the AirDrop session within ~2s of every start. Keep
        # the armed session instead; the normal UI-close path still releases
        # it, and a network change once Wi-Fi is back rebuilds it with a fresh
        # policy through the class/scope/network check above.
        if [ -d "$STATE/coex-session" ];then
            echo 'COEX_WIFI_STA_PAUSED: keeping armed AWDL session'
        fi
    fi
    if [ -e "$STATE/coex-session/invalidated" ];then coex_recover=1;fi
    exec 6>&-
}

off_samples=0
provider_samples=0
provider_failures=0
provider_ready=0
receive_published=0
receive_samples=2
foreground_was=unknown
unknown_streak=0
close_force=0
close_failures=0
# Number of consecutive closed readings required before the receiver gate is
# closed. The share surface is judged from an activity dump, and one bad
# dump must not be able to remove this device from the iPhone AirDrop list.
FOREGROUND_CLOSE_STREAK=3
foreground_closed_streak=0
# AirDrop payload traffic runs on the AWDL data interface (mosey0). Sampling
# its byte counters lets the Xposed layer know a transfer happened recently,
# which is what renews the OShare "everyone" 10-minute countdown. The window
# restarts when a transfer ENDS, not when it starts: a running transfer keeps
# the radio busy anyway, and the fresh 10-minute window after the last byte
# (or after the transfer was interrupted) is what the user expects.
last_tx=
last_rx=
# Transfer-activity state machine: a >=64KiB one-second delta marks the
# transfer as running; three consecutive quiet samples mark it finished (or
# interrupted) and only then stamp last_transfer for the Xposed renewal.
transfer_active=0
transfer_quiet=0
transfer_stamped() {
    /system/bin/setprop debug.opairshare.last_transfer "$(date +%s)" 2>/dev/null || true
}
while same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] &&
      [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
    kernel_matches || { echo 'Driver identity changed; no automatic reload'; exit 6; }
    if ! same_boot "$STATE/server-boot" ||
       ! pid_is "$(cat "$STATE/server.pid" 2>/dev/null || true)" '/system_ext/bin/mosey_server'; then
        echo 'Native server lost; explicit recovery required'; exit 6
    fi
    printf '%s %s\n' "$(boot_id)" "$$" > "$STATE/supervisor-ready"
    if ! apply_share_mode;then
        receive_published=0
        sleep 1;continue
    fi
    if ! monitor_coex_wifi;then
        echo 'GATECLEAR reason=coex_monitor_failed'
        /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
        receive_published=0
        sleep 1;continue
    fi
    /system/bin/sh "$MODDIR/bin/coex-session.sh" tick 8>&- || echo 'COEX_RX_RETRY: retaining session'
    if [ "$coex_recover" = 1 ];then
        echo 'GATECLEAR reason=coex_recover'
        /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
        receive_published=0
        if close_session recover;then
            provider_failures=0;provider_ready=0;provider_samples=0;receive_samples=2
            echo 'COEX_WIFI_SESSION_RELEASED'
        else
            rc=$?
            if [ "$rc" != 9 ];then echo "Wi-Fi recovery uncertain rc=$rc; session retained";exit "$rc";fi
        fi
        sleep 1;continue
    fi
    # Only current Mosey sockets on mosey0; leave global TCP settings alone.
    socket_owner=$(pidof "$PKG" || true)
    case "$socket_owner" in
        ''|*[!0-9]*) ;;
        *)
            if /system/bin/timeout -k 1 2 "$MODDIR/payload/native/opairshare-socket-buffer" "$socket_owner" --apply 8>&-; then
                socket_guard_failed=0
            elif [ "${socket_guard_failed:-0}" = 0 ]; then
                echo 'Mosey socket-buffer guard unavailable; session retained'
                socket_guard_failed=1
            fi
            ;;
    esac
    # Sample the AWDL data interface byte counters. Only a substantial delta
    # counts as AirDrop payload: discovery traffic (mDNS announcements, action
    # frames, service broadcasts) also moves bytes on mosey0 every second, and
    # treating that as a transfer kept the OShare "everyone" 10-minute window
    # renewed forever while idle, so it never fell back to receive-off and the
    # armed AWDL session drained the battery. A real transfer moves at least a
    # few hundred kilobytes; 64 KiB per one-second sample is far above any
    # discovery chatter and far below any meaningful payload. The stamp is
    # written when the transfer ends (three quiet seconds) or when the
    # interface disappears mid-transfer (interruption), so the countdown
    # restarts at 10:00 exactly when the user's transfer finished.
    tx_now=$(cat /sys/class/net/mosey0/statistics/tx_bytes 2>/dev/null || true)
    rx_now=$(cat /sys/class/net/mosey0/statistics/rx_bytes 2>/dev/null || true)
    if [ -n "$tx_now" ] && [ -n "$rx_now" ];then
        if [ -n "$last_tx" ] && [ -n "$last_rx" ];then
            delta_tx=$((tx_now - last_tx))
            delta_rx=$((rx_now - last_rx))
            [ "$delta_tx" -lt 0 ] && delta_tx=0
            [ "$delta_rx" -lt 0 ] && delta_rx=0
            if [ $((delta_tx + delta_rx)) -ge 65536 ];then
                transfer_active=1
                transfer_quiet=0
            elif [ "$transfer_active" = 1 ];then
                transfer_quiet=$((transfer_quiet+1))
                if [ "$transfer_quiet" -ge 3 ];then
                    transfer_stamped
                    transfer_active=0
                fi
            fi
        fi
        last_tx=$tx_now
        last_rx=$rx_now
    elif [ "$transfer_active" = 1 ];then
        # The AWDL interface vanished while bytes were still moving: the
        # transfer was interrupted. Stamp now so the window restarts.
        transfer_stamped
        transfer_active=0
        last_tx=
        last_rx=
    fi
    receive_requested=$(/system/bin/sh "$MODDIR/bin/receive-session.sh" wanted 8>&-)
    receive_eligible=0
    receive_samples=$((receive_samples+1))
    if [ "$receive_requested" = 1 ];then receive_eligible=1;fi
    # The per-second activity dump is one of the largest awake costs of this
    # loop. Since 1.1.18 admission is owned by the iPhone reception mode
    # alone, so when reception is wanted the foreground reading adds nothing
    # and the dump is skipped entirely. Polling resumes the moment the mode
    # leaves iPhone reception, which is when the foreground state matters
    # again for closing the session.
    if [ "$receive_eligible" = 1 ];then
        foreground=eligible
        foreground_closed_streak=0
    else
        foreground=$(foreground_state)
    fi
    # A visible share surface is an explicit intent to send or receive, so it
    # keeps the session armed and refreshes the idle timer. The timer is
    # refreshed on the transition only, so a surface that stays open does not
    # keep the file written every second.
    if [ "$foreground" = open ];then
        if [ "$foreground_was" != open ];then idle_touch;fi
        foreground_was=open
    else
        foreground_was=$foreground
    fi
    # Background reception is owned by the receive switch itself. When the
    # user sets the OShare receive switch to everyone(1) or contacts-only(2)
    # the session stays armed for as long as the switch says so - no idle
    # timeout applies, because the switch is the explicit, persistent user
    # intent (the 10-minute "everyone" window falls back to receive-off on
    # its own, which closes the session through the not-wanted path below).
    # Admission is opened while the share surface is open OR background
    # reception is wanted. It must be closed under exactly the same
    # condition: clearing while the share surface is still visible closes
    # the receiver gate for a moment, the provider stops advertising, and the
    # iPhone loses this device from its AirDrop list.
    # A closed reading is only trusted after it was seen repeatedly. A share
    # surface can disappear from a single activity dump while the surface is
    # still visible, and closing the gate on that sample tears the
    # advertisement down, which removes this device from the iPhone AirDrop
    # list until the next share surface is opened.
    if [ "$foreground" = open ];then
        foreground_closed_streak=0
    elif [ "$foreground" = closed ];then
        foreground_closed_streak=$((foreground_closed_streak+1))
    else
        foreground_closed_streak=0
    fi
    if [ "$receive_eligible" != 1 ] && [ "$foreground" != open ] &&
       [ "$foreground_closed_streak" -ge "$FOREGROUND_CLOSE_STREAK" ] && [ "$receive_published" = 1 ];then
        echo "GATECLEAR reason=not_wanted foreground=$foreground streak=$foreground_closed_streak"
        /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
        receive_published=0
    fi
    if [ "$foreground" = unknown ] && [ "$receive_eligible" != 1 ];then
        # A single unreadable dump must not tear down a live session, but an
        # activity manager that stays unreadable forever would pin the armed
        # session (and the paused Wi-Fi) with no way out. After a minute of
        # unreadable foreground with reception not wanted, close anyway.
        unknown_streak=$((unknown_streak+1))
        if [ "$unknown_streak" -lt 30 ];then
            off_samples=0;echo 'Cannot read foreground UI; retain current session';sleep 2;continue
        fi
        echo 'Foreground unreadable for a minute; closing stalled session'
        off_samples=99
        close_force=1
        unknown_streak=0
    else
        unknown_streak=0
    fi
    # Arm the session only when background reception is wanted. Since 1.1.18
    # that is the injected "iPhone (10 minutes)" option alone (oshare_state==1
    # plus marker opairshare_ios_receive==1, read by receive_wanted above).
    # Share surfaces no longer arm the session by themselves: ShareActivity
    # and the system Chooser serve the official Android paths without a
    # module session, and IOSShareActivity switches the app into iPhone mode,
    # which makes it eligible through the switch. The switch is
    # authoritative, so no idle gate applies: the session stays armed until
    # the mode changes away (including the 10-minute window falling back to
    # receive-off), and re-arms the moment iPhone mode is selected again.
    if [ "$receive_eligible" = 1 ]; then
        off_samples=0
        if [ ! -e "$STATE/ui-session" ]; then
            # Requested coexistence never mutates association/autojoin. When
            # the current association cannot be served (disconnected, Wi-Fi
            # disabled or a strict-scope mismatch), keep the share UI without a
            # module-owned session and wait for a supported state.
            if coex_requested && [ -e "$STATE/coex-wifi-blocked" ];then
                sleep 1;continue
            fi
            # A stale same-boot snapshot belongs to an interrupted session.
            [ ! -e "$STATE/session-wifi" ] || { echo 'Unrestored UI session; stop required'; exit 8; }
            exec 6> "$STATE/settings.flock"
            if ! flock -n 6 6>&6;then exec 6>&-;sleep 1;continue;fi
            # A transient prepare failure retries; the user's preference stays.
            if ! OPAIRSHARE_SETTINGS_LOCK_HELD=1 /system/bin/sh "$MODDIR/bin/coex-session.sh" prepare 8>&- 6>&6; then
                /system/bin/sh "$MODDIR/bin/coex-session.sh" restore 8>&- || exit 8
                exec 6>&-
                sleep 3
                continue
            fi
            [ -d "$STATE/coex-session" ] || { exec 6>&-;sleep 1;continue; }
            # Snapshot the current STA association while it is still up.
            # Arming AWDL pauses the STA on this platform (no concurrent
            # radio capacity), and close_session reconnects the saved network
            # from this metadata after the session is released. Without the
            # capture the restore path is dead code and Wi-Fi stays paused
            # until the user reconnects manually. A failed capture must not
            # leave partial metadata behind, or the close path would refuse
            # to release the session.
            if ! wifi_session capture 8>&-; then
                rm -f "$STATE/session-wifi" 2>/dev/null || true
            fi
            boot_id > "$STATE/ui-session"
            startup_trace COEX_READY
            exec 6>&-
            receive_warmup_start
            startup_trace PROVIDER_START_BEGIN
            if ! /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-; then
                provider_ready=0
                provider_failures=1
                echo 'Provider activation failed; retain ownership for session cleanup'
            else
                provider_ready=1
                echo 'UI_OR_RECEIVE_ORIGINAL_PROVIDER_READY'
            fi
            startup_trace PROVIDER_START_DONE
            receive_warmup_wait
            provider_samples=0
        else
            provider_samples=$((provider_samples+1))
            if [ -z "$(pidof "$PKG" || true)" ];then
                provider_ready=0
                echo 'GATECLEAR reason=provider_process_lost'
                /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                receive_published=0
                provider_samples=10
            fi
            # Only re-run the activation sequence when the provider is not
            # healthy. Re-activating a live provider resets its advertising
            # state, so a healthy provider is left completely untouched.
            if [ "$provider_samples" -ge 10 ] && [ "$provider_failures" -lt 3 ] && [ "$provider_ready" -ne 1 ]; then
                if /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-;then
                    provider_failures=0;provider_ready=1
                else
                    provider_ready=0
                    # A start sequence that ran concurrently with this loop
                    # (KernelSU re-ran service.sh mid-boot) can leave the
                    # callback overlay unmounted: every provider ensure then
                    # fails on the APK integrity check while the radio itself
                    # is fine. Rebuild the patch before counting the failure,
                    # otherwise the supervisor gives up after three retries
                    # and the AirDrop session stays dead until the next boot.
                    if ! /system/bin/sh "$MODDIR/bin/app-check.sh" patched 8>&-; then
                        echo 'CALLBACK_OVERLAY_LOST; rebuilding the discovery patch'
                        /system/bin/sh "$MODDIR/bin/callback.sh" restore 8>&- || true
                        if /system/bin/sh "$MODDIR/bin/callback.sh" ensure 8>&-; then
                            if /system/bin/timeout -k 2 35 /system/bin/sh "$MODDIR/bin/provider.sh" ensure 8>&-;then
                                provider_failures=0;provider_ready=1
                                echo 'PROVIDER_READY after overlay rebuild'
                            fi
                        else
                            echo 'CALLBACK_OVERLAY_REBUILD_FAILED'
                        fi
                    fi
                    if [ "$provider_ready" -ne 1 ]; then
                        echo 'GATECLEAR reason=provider_ensure_failed'
                        /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                        receive_published=0
                        provider_failures=$((provider_failures+1));echo "Provider retry failed count=$provider_failures"
                    fi
                fi
                provider_samples=0
            fi
        fi
        # Open admission while background reception is wanted. Since 1.1.18
        # only the iPhone reception mode qualifies (see receive_wanted), so
        # with the mode active the session is fully resident and share
        # surfaces alone never open admission.
        if [ "$receive_eligible" = 1 ] && [ "$provider_ready" = 1 ] && [ "$receive_samples" -ge 2 ];then
            if /system/bin/sh "$MODDIR/bin/receive-session.sh" ensure-ui 8>&-;then
                [ "$receive_published" = 1 ] || startup_trace RECEIVE_HOST_READY
                # Recheck module/coexistence ownership before opening admission.
                receive_previous=$receive_published
                receive_published=$(/system/bin/sh "$MODDIR/bin/receive-session.sh" ready 8>&-)
                if [ "$receive_previous" != 1 ] && [ "$receive_published" = 1 ];then startup_trace RECEIVE_GATE_OPEN;fi
            else
                echo 'GATECLEAR reason=ensure_ui_failed'
                /system/bin/sh "$MODDIR/bin/receive-session.sh" clear 8>&-
                receive_published=0
                echo 'OShare receiver UI unavailable; AirDrop receive admission closed'
            fi
            receive_samples=0
        fi
    else
        # The share surface is gone and background reception is not wanted.
        # Reception ownership follows the iPhone reception mode directly, so
        # there is no idle warm window any more. Switching away from iPhone
        # mode closes immediately (the published state cannot survive a mode
        # that no longer wants it); a still-armed but unpublished session is
        # released after the closed-streak so one bad reading cannot tear it
        # down. A transfer in progress is still honoured: close_session
        # defers until the transfer finishes.
        off_samples=$((off_samples+1))
        if { [ "$off_samples" -ge 3 ] || [ "$receive_published" = 1 ]; } && [ -e "$STATE/ui-session" ]; then
            if close_session ${close_force:+force}; then
                provider_failures=0;provider_ready=0;receive_published=0
                close_failures=0;close_force=0
            else
                rc=$?
                if [ "$rc" = 10 ] || [ "$rc" = 9 ];then
                    off_samples=0;close_force=0
                elif [ "${close_failures:-0}" -lt 2 ];then
                    # A failed release can leave the provider half stopped.
                    # Retry the full close twice before giving up: exiting
                    # here strands the armed session (and the paused Wi-Fi)
                    # until the next reboot, which is exactly the "receive
                    # is off but AirDrop is still running" report.
                    close_failures=$((close_failures+1));off_samples=0
                    echo "UI close failed rc=$rc; retry ${close_failures}/2"
                else
                    echo "UI close failed rc=$rc three times; no driver reload"; exit "$rc"
                fi
            fi
        fi
    fi
    sleep 1
done
