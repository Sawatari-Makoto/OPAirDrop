#!/system/bin/sh
# Shared install/start device gates: Oplus brand, sun platform, a kernel of
# the ABI family the payload was compiled for, and a WLAN driver that exposes
# the AWDL and coexistence knobs the module drives.
#
# The driver check reports what is missing instead of only refusing to
# continue, so a device on a different firmware can be diagnosed from the
# install log.
compatibility_error() { echo "Compatibility: $*" >&2; return 2; }

# The identity helpers live in the shared runtime library next to this file.
_compat_dir=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd) || _compat_dir=
if [ -n "$_compat_dir" ] && [ -f "$_compat_dir/common.sh" ]; then
    . "$_compat_dir/common.sh"
elif [ -n "$_compat_dir" ] && [ -f "$_compat_dir/bin/common.sh" ]; then
    . "$_compat_dir/bin/common.sh"
elif [ -n "${MODPATH:-}" ] && [ -f "$MODPATH/bin/common.sh" ]; then
    # The installer sources this file, so $0 is not the script path.
    . "$MODPATH/bin/common.sh"
fi

# The kernel gate. The shipped drivers carry a vermagic, so they only load on
# a kernel of the family they were compiled for. The device release string
# carries a build id of its own, so the family is matched as a prefix.
compatibility_kernel() {
    if ! command -v kernel_family_gap >/dev/null 2>&1; then
        # The gate must never be skipped silently.
        compatibility_error 'the runtime library did not load, the kernel gate cannot run'
        return 2
    fi
    gap=$(kernel_family_gap)
    if [ -n "$gap" ]; then
        compatibility_error "$gap"
        return 2
    fi
    return 0
}

compatibility_device() (
    brands=$(getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.vendor.manufacturer; getprop ro.product.vendor.brand)
    printf '%s\n' "$brands" | tr '[:upper:]' '[:lower:]' | grep -Eq '^(oplus|oppo|oneplus|realme)$' || {
        compatibility_error 'Oplus device required (OPPO / OnePlus / realme)'; exit 2;
    }
    board=$(getprop ro.board.platform)
    [ "$board" = sun ] || {
        compatibility_error "sun platform required; found $board"; exit 2;
    }
    compatibility_kernel || exit 2
)

# Knobs the AWDL path depends on. A driver that misses any of them cannot
# carry the AirDrop session, no matter which build it is.
compatibility_awdl_knobs() {
    P=/sys/module/qca_cld3_peach_v2/parameters
    for entry in passthru_native_data_rx:Y passthru_awdl_raw_rx:Y \
                passthru_awdl_backpressure:Y passthru_awdl_target_auto:Y \
                passthru_awdl_rx_auto:Y passthru_awdl_rx_rearm:Y \
                passthru_sta_regular_rx:Y passthru_sta_action_rx:Y \
                passthru_awdl_wmi_raw:Y; do
        knob=${entry%%:*}
        want=${entry##*:}
        got=''
        [ -r "$P/$knob" ] && got=$(head -c 64 "$P/$knob" 2>/dev/null)
        [ "$got" = "$want" ] || { echo "$knob"; return; }
    done
    mode=''
    [ -r "$P/passthru_sta_coexist" ] && mode=$(head -c 64 "$P/passthru_sta_coexist" 2>/dev/null)
    [ "$mode" = 3 ] || { echo 'passthru_sta_coexist'; return; }
    for entry in opairshare_channel_state opairshare_awdl_rx_mac_filter opairshare_bridge_pending; do
        [ -r "$P/$entry" ] || { echo "$entry"; return; }
    done
    echo ''
}

# The stock driver only exposes the AWDL knobs once the module's own driver
# has been installed, so the check runs against the shipped payload rather
# than against the running module.
compatibility_payload() (
    MODDIR=$1
    [ -d "$MODDIR" ] || { compatibility_error 'module payload missing'; exit 2; }
    driver=$(ls -1 "$MODDIR"/payload/kernel/*.ko 2>/dev/null | head -1)
    [ -n "$driver" ] || { compatibility_error 'kernel payload missing'; exit 2; }
    # The payload has to carry the AWDL entry points. A kernel built without
    # the OnePlusOSS wonder patches cannot provide them.
    if ! grep -aq 'opairshare_channel_state' "$driver" 2>/dev/null; then
        compatibility_error 'kernel payload lacks the AWDL parameters'
        exit 2
    fi
    return 0
)
