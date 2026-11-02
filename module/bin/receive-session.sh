#!/system/bin/sh
# Root-owned background admission; independent of OShare's native visibility.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/coex-wifi.sh"
. "$MODDIR/bin/share-mode.sh"
[ "$(id -u)" = 0 ] || exit 2
init_state

receive_wanted() {
    [ "$(saved_share_mode)" = "$(active_share_mode)" ] || return 1
    [ "$(active_share_mode)" != quickshare ] || return 1
    # Whether the AirDrop wireless session may be opened at all: the module is
    # enabled, the stack is owned and coexistence is available. This is the
    # admission gate used when the share surface is open.
    same_boot "$STATE/stack-boot" && [ ! -e "$STATE/disabled" ] &&
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] &&
        coex_available
}

# Background (always-on) reception only. The session still arms whenever the
# share surface is open regardless of this flag; this only governs the
# resident/keep-alive path that holds the radio armed with no UI. It is OFF by
# default so the AWDL session is not held 24/7, and the user opts in with the
# "一加互传 常驻" (keep-alive) toggle.
airdrop_keepalive() {
    [ -e "$STATE/airdrop-keepalive" ]
}
receive_background() {
    receive_wanted && airdrop_keepalive
}

case "${1:-wanted}" in
    wanted)
        if receive_background; then echo 1; else echo 0; fi
        ;;
    keepalive)
        if airdrop_keepalive; then echo on; else echo off; fi
        ;;
    keepalive-on)
        [ "$(id -u)" = 0 ] || exit 2
        touch "$STATE/airdrop-keepalive"
        echo ON
        ;;
    keepalive-off)
        [ "$(id -u)" = 0 ] || exit 2
        rm -f "$STATE/airdrop-keepalive"
        echo OFF
        ;;
    ensure-ui)
        receive_wanted || exit 9
        records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
        # Start the non-exported host as root only when absent. No Activity is
        # opened and no native OShare visibility setting is written.
        if ! printf '%s\n' "$records" | grep -Eq 'com\.oplus\.oshare\.OShareServer|isFgsDelegate=true'; then
            receive_wanted || exit 9
            reply=$(framework timeout 8 am startservice --user 0 -n com.coloros.oshare/com.oplus.oshare.OShareServer) || exit 9
            printf '%s\n' "$reply" | grep -q '^Starting service:' || exit 9
        fi
        records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
        if ! printf '%s\n' "$records" | grep -q 'isFgsDelegate=true';then
            framework timeout 8 cmd activity set-foreground-service-delegate --user 0 com.coloros.oshare start || exit 9
            # Own only the delegate started here; native foreground services
            # and any delegate already present are not ours to release.
            boot_id > "$STATE/receive-delegate-owned"
            records=$(framework timeout 8 dumpsys activity services com.coloros.oshare) || exit 9
            printf '%s\n' "$records" | grep -q 'isFgsDelegate=true' || exit 9
        fi
        ;;
    ready)
        # Only advertise in an owned, current-boot coexistence session. Legacy
        # UI-only operation may restore/reconnect STA and is not background RX.
        # When the firmware pauses the STA for AWDL the association cannot be
        # read at all, so an armed AWDL session stands in for that check.
        if receive_wanted && same_boot "$STATE/ui-session" &&
           same_boot "$STATE/coex-session/boot" &&
           { [ "$coex_wifi_network" = "$(cat "$STATE/coex-session/network" 2>/dev/null)" ] ||
             awdl_session_armed; }; then
            /system/bin/setprop debug.opairshare.receive.ready 1
            echo 1
        else
            /system/bin/setprop debug.opairshare.receive.ready 0
            echo 0
        fi
        ;;
    clear)
        /system/bin/setprop debug.opairshare.receive.ready 0
        ;;
    release-host)
        if same_boot "$STATE/receive-delegate-owned";then
            # Clearing admission is allowed during a transfer; removing its UI
            # host's foreground lifetime is allowed only after transfer cleanup.
            /system/bin/sh "$MODDIR/bin/transfer-idle.sh" || exit 9
            framework timeout 8 cmd activity set-foreground-service-delegate --user 0 com.coloros.oshare stop || exit 9
            rm -f "$STATE/receive-delegate-owned"
        fi
        ;;
    *) echo 'Usage: receive-session.sh wanted|keepalive|keepalive-on|keepalive-off|ensure-ui|ready|clear|release-host' >&2; exit 2;;
esac
