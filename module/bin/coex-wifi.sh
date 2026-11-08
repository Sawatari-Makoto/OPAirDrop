#!/system/bin/sh
# Sourced after common.sh. Read-only connection checks; never change Wi-Fi.
#
# AWDL is pinned to 5745 MHz while the STA may stay on any 2.4/5/6 GHz channel;
# the firmware chooses SCC, MCC or DBS. This helper only classifies the current
# association. It never returns a value that authorizes a Wi-Fi change.
#
# coex_wifi_class:
#   same149      5745 MHz, same channel as AWDL
#   cross5g      another 5 GHz channel
#   band24       2.4 GHz
#   band6        6 GHz
#   disconnected enabled but not associated
#   disabled     framework Wi-Fi disabled
#   unsupported  a frequency outside the supported bands
#
# Return 0 when the state is understood and can be served by the mode selected
# by coex_scope(), 1 for an understood state that the current scope excludes,
# and 2 when the framework state cannot be confirmed.
coex_wifi_read() {
    coex_wifi_frequency=
    coex_wifi_network=
    coex_wifi_class=
    coex_wifi_band=
    coex_wifi_reason=wifi_unavailable
    coex_wifi_text=$(framework timeout 5 cmd wifi status 2>/dev/null) || return 2
    case "$coex_wifi_text" in
        'Wifi is disabled'*)
            coex_wifi_class=disabled;coex_wifi_band=none
            coex_wifi_reason=wifi_disabled;return 1;;
        'Wifi is enabled'*) ;;
        *) return 2;;
    esac
    if ! printf '%s\n' "$coex_wifi_text" | grep -q '^Wifi is connected to ';then
        coex_wifi_class=disconnected;coex_wifi_band=none
        coex_wifi_reason=wifi_disconnected;return 1
    fi
    coex_wifi_frequency=$(printf '%s\n' "$coex_wifi_text" | sed -n 's/.*Frequency: \([0-9][0-9]*\)MHz.*/\1/p' | head -1)
    coex_wifi_network=$(printf '%s\n' "$coex_wifi_text" | sed -n 's/.*Net ID: \([0-9][0-9]*\).*/\1/p' | head -1)
    [ -n "$coex_wifi_frequency" ] || return 2
    case "$coex_wifi_frequency" in
        5745)
            coex_wifi_class=same149;coex_wifi_band=5g;;
        *)
            coex_wifi_band=$(awk -v f="$coex_wifi_frequency" 'BEGIN {
                if (f >= 2400 && f <= 2484) print "2.4g";
                else if (f >= 5150 && f <= 5925) print "5g";
                else if (f > 5925 && f <= 7125) print "6g";
            }')
            case "$coex_wifi_band" in
                2.4g) coex_wifi_class=band24;;
                5g) coex_wifi_class=cross5g;;
                6g) coex_wifi_class=band6;;
                *)
                    coex_wifi_class=unsupported;coex_wifi_band=none
                    coex_wifi_reason=wifi_unsupported;return 1;;
            esac;;
    esac
    [ -n "$coex_wifi_network" ] &&
        printf '%s\n' "$coex_wifi_text" | grep -q 'Supplicant state: COMPLETED' || return 2
    coex_wifi_reason=
}

# Coexistence is always enabled and follows every supported STA band.
coex_scope() { echo auto; }
coex_requested() { return 0; }
# Wi-Fi enabled but not associated is a valid state for an AWDL-only session:
# the AirDrop data path runs on the AWDL interface (wonder0, 5745 MHz) and
# never on the STA, so an unassociated STA contributes no channel constraint
# and arming AWDL is the one coexistence mode the firmware handles for free.
# Report frequency=0 so snapshots and status output can tell it apart from a
# real STA channel.
coex_wifi_eligible() {
    if coex_wifi_read;then return 0;fi
    if [ "$coex_wifi_reason" = wifi_disconnected ];then
        coex_wifi_frequency=0
        coex_wifi_network=
        return 0
    fi
    return 1
}

# True while the kernel-side AWDL session is armed on the AWDL channel.
#
# On platforms whose radio reports no concurrent capacity (this one logs
# OPAIRSHARECOEXHW: dbs=0 sbs=0), arming AWDL makes the firmware pause the
# STA association. The AirDrop data path runs on the AWDL interface
# (wonder0), never on the STA, so an armed AWDL session is a valid
# coexistence state even while the STA is disconnected.
awdl_session_armed() {
    P=/sys/module/qca_cld3_peach_v2/parameters
    [ -r "$P/opairshare_channel_state" ] || return 1
    grep -q 'accepting=1' "$P/opairshare_channel_state" || return 1
    grep -q 'freq=5745' "$P/opairshare_channel_state" || return 1
    [ -r "$P/opairshare_awdl_rx_mac_filter" ] || return 1
    grep -q 'armed=1' "$P/opairshare_awdl_rx_mac_filter" || return 1
    grep -q 'active=1' "$P/opairshare_awdl_rx_mac_filter" || return 1
    grep -q 'fault=0' "$P/opairshare_awdl_rx_mac_filter" || return 1
    return 0
}

coex_available() {
    coex_requested && [ ! -e "$STATE/coex-session/invalidated" ] &&
        { coex_wifi_eligible || awdl_session_armed; }
}
