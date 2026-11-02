#!/system/bin/sh
STATE=/data/adb/opairshare
PKG=com.google.android.mosey
COMP=$PKG/.ExternalSharingService
SERVICE=com.google.android.moseyservice.IMoseyService/default
boot_id() { cat /proc/sys/kernel/random/boot_id; }
loaded() { grep -q "^$1 " /proc/modules; }
same_boot() { [ -r "$1" ] && [ "$(cat "$1")" = "$(boot_id)" ]; }
module_note() { od -An -tx1 "/sys/module/$1/notes/.note.gnu.build-id" 2>/dev/null | tr -d ' \n'; }
pid_live() {
    case "$1" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$1/stat" ] || return 1
    read -r ps_pid ps_name ps_state ps_rest < "/proc/$1/stat" || return 1
    case "$ps_state" in Z|X) return 1;; esac
}
pid_is() {
    pid_live "$1" || return 1
    timeout 2 grep -aFq -- "$2" "/proc/$1/cmdline" 2>/dev/null
}
init_state() { mkdir -p "$STATE"; chmod 700 "$STATE"; }
# Locate the module bin directory from the sourcing script, so the identity
# file is found whether common.sh is sourced from bin/ or from the module
# root (service.sh, post-fs-data.sh).
_common_dir=$(CDPATH= cd -- "${0%/*}" 2>/dev/null && pwd) || _common_dir=
if [ -n "$_common_dir" ] && [ -f "$_common_dir/common.sh" ]; then
    MODULE_BIN_DIR=$_common_dir
elif [ -n "$_common_dir" ] && [ -f "$_common_dir/bin/common.sh" ]; then
    MODULE_BIN_DIR=$_common_dir/bin
elif [ -n "${MODPATH:-}" ] && [ -f "$MODPATH/bin/common.sh" ]; then
    # Installers source this file, so $0 does not point at the script. The
    # module path the manager exports is the reliable location.
    MODULE_BIN_DIR=$MODPATH/bin
else
    MODULE_BIN_DIR=
fi
# Load the packaged identities and the identities captured on this device.
# The device file only carries the stock build ids, so it never overwrites
# the build ids of the modules this package ships.
identity_load() {
    if [ -n "$MODULE_BIN_DIR" ] && [ -f "$MODULE_BIN_DIR/kernel-identity.sh" ]; then
        . "$MODULE_BIN_DIR/kernel-identity.sh"
    fi
    if [ -f "$STATE/kernel-identity.sh" ]; then
        . "$STATE/kernel-identity.sh"
    fi
    return 0
}
# The build id this device ships for an original module. It is read from the
# device itself, so the same package works on every device.
stock_note() {
    case "$1" in
        qca_cld3_peach_v2) printf '%s' "${STOCK_NOTE_qca_cld3_peach_v2:-}";;
        mac80211) printf '%s' "${STOCK_NOTE_mac80211:-}";;
        *) printf '%s' '';;
    esac
}
# The vendor copy of a kernel module carries the same GNU build id as the
# live module. Reading it identifies the stock build of this device even
# when the patched driver is the one currently loaded, which is the case on
# every upgrade of an already running installation.
VENDOR_MODULE_DIRS="/vendor/lib/modules /vendor_dlkm/lib/modules /odm/lib/modules"
file_build_id() {
    file=$1
    [ -f "$file" ] || return 1
    offsets=$(grep -abo 'GNU' "$file" 2>/dev/null | cut -d: -f1)
    for off in $offsets; do
        [ "$off" -ge 12 ] || continue
        start=$((off - 12))
        blk=$((start / 4096))
        rem=$((start % 4096))
        head=$(dd if="$file" bs=4096 skip=$blk count=1 2>/dev/null | dd bs=1 skip=$rem count=16 2>/dev/null | od -An -tx1 | tr -d ' \n')
        [ "${#head}" -eq 32 ] || continue
        # namesz, descsz and type of the ELF note, little endian.
        [ "$(printf '%s' "$head" | cut -c17-24)" = "03000000" ] || continue
        namesz=$(printf '%d' "0x$(printf '%s' "$head" | cut -c1-2)")
        descsz=$(printf '%d' "0x$(printf '%s' "$head" | cut -c9-10)")
        [ "$namesz" -ge 3 ] && [ "$namesz" -le 16 ] || continue
        [ "$descsz" -ge 1 ] && [ "$descsz" -le 64 ] || continue
        total=$((12 + namesz + descsz))
        hex=$(dd if="$file" bs=4096 skip=$blk count=1 2>/dev/null | dd bs=1 skip=$rem count=$total 2>/dev/null | od -An -tx1 | tr -d ' \n')
        [ "${#hex}" -ge $((total * 2)) ] || continue
        from=$(((12 + namesz) * 2 + 1))
        to=$((from + descsz * 2 - 1))
        desc=$(printf '%s' "$hex" | cut -c$from-$to)
        [ "${#desc}" -eq $((descsz * 2)) ] || continue
        # The same shape the kernel exposes under /sys/module/<name>/notes.
        printf '%s474e5500%s' "$(printf '%s' "$head" | cut -c1-24)" "$desc"
        return 0
    done
    return 1
}
vendor_note() {
    name=$1
    for dir in $VENDOR_MODULE_DIRS; do
        file="$dir/$name.ko"
        [ -f "$file" ] || continue
        id=$(file_build_id "$file") || continue
        [ -n "$id" ] || continue
        printf '%s' "$id"
        return 0
    done
    return 1
}
# Capture the original build ids of this device. The live modules are read
# first, while the original drivers are still the loaded ones. When the
# patched driver is already loaded, the vendor copy of the module file is
# read instead. The builds this package installed are never recorded.
capture_stock_identity() {
    init_state
    tmp=$STATE/kernel-identity.sh.new
    : > "$tmp" || return 1
    old_qca=
    old_mac=
    if [ -f "$STATE/kernel-identity.sh" ]; then
        old_qca=$(. "$STATE/kernel-identity.sh" 2>/dev/null; printf '%s' "${STOCK_NOTE_qca_cld3_peach_v2:-}")
        old_mac=$(. "$STATE/kernel-identity.sh" 2>/dev/null; printf '%s' "${STOCK_NOTE_mac80211:-}")
    fi
    for name in qca_cld3_peach_v2 mac80211; do
        own=$(eval echo "\$NOTE_$name")
        value=
        live=$(module_note "$name")
        if [ -n "$live" ] && { [ -z "$own" ] || [ "$live" != "$own" ]; }; then
            value=$live
        fi
        if [ -z "$value" ]; then
            cand=$(vendor_note "$name") || cand=
            if [ -n "$cand" ] && { [ -z "$own" ] || [ "$cand" != "$own" ]; }; then
                value=$cand
            fi
        fi
        if [ -z "$value" ]; then
            case "$name" in
                qca_cld3_peach_v2) value=$old_qca;;
                mac80211) value=$old_mac;;
            esac
        fi
        [ -n "$value" ] || continue
        printf 'STOCK_NOTE_%s=%s\n' "$name" "$value" >> "$tmp"
    done
    if [ -s "$tmp" ]; then
        chmod 600 "$tmp"
        mv "$tmp" "$STATE/kernel-identity.sh" || return 1
    else
        rm -f "$tmp"
    fi
    return 0
}
# The kernel ABI family the shipped payload was compiled for. The build ids
# of a device differ per firmware, but the kernel ABI family does not: a .ko
# carries a vermagic and only loads on a kernel of the same family. The
# release string of a device carries a build id of its own, so the family is
# matched as a prefix instead of comparing the whole string.
kernel_family_gap() {
    family=${KERNEL_ABI_FAMILY:-}
    case "$family" in
        ''|BUILD_REQUIRED) echo 'kernel ABI family is unknown'; return 0;;
    esac
    rel=$(uname -r 2>/dev/null || echo '')
    case "$rel" in
        "$family"|"$family"-*) ;;
        *) echo "kernel $rel is outside the supported ABI family $family"; return 0;;
    esac
    size=${KERNEL_ABI_PAGESIZE:-}
    case "$size" in
        ''|BUILD_REQUIRED) ;;
        *)
            # Only a trailing "<n>k" segment is a page size. A release that
            # carries none (a plain family string) says nothing about it.
            tail=${rel##*-}
            case "$tail" in
                [0-9]*k) ;;
                *) tail= ;;
            esac
            if [ -n "$tail" ] && [ "$tail" != "$size" ]; then
                echo "kernel ABI family $family requires page size $size but the kernel uses $tail"
                return 0
            fi
            ;;
    esac
    echo ''
}
startup_trace() {
    # Same monotonic time base as Android elapsedRealtime, independent of the
    # wall clock being corrected during boot. Milestones contain no user data.
    trace_ms=$(awk '{printf "%.0f",$1*1000}' /proc/uptime) || return 0
    printf 'STARTUP stage=%s elapsed_ms=%s\n' "$1" "$trace_ms" >> "$STATE/startup-trace.log" || true
}
framework() {
    # KernelSU standalone ash prefers BusyBox applets even with Android PATH.
    # Pin framework tools; Binder commands still receive pipe FDs via capture.
    fw_tool=$1; shift
    case "$fw_tool" in
        timeout)
            fw_seconds=$1; shift
            fw_child=$1; shift
            case "$fw_child" in
                am|cmd|dumpsys|pm|service|svc) ;;
                *) echo "Unsupported timed framework tool: $fw_child" >&2; return 2;;
            esac
            set -- /system/bin/timeout "$fw_seconds" "/system/bin/$fw_child" "$@"
            ;;
        am|cmd|dumpsys|pm|service|svc) set -- "/system/bin/$fw_tool" "$@";;
        /*) set -- "$fw_tool" "$@";;
        *) echo "Unsupported framework tool: $fw_tool" >&2; return 2;;
    esac
    if fw_output=$("$@" 2>&1); then fw_rc=0; else fw_rc=$?; fi
    printf '%s\n' "$fw_output"
    return "$fw_rc"
}
wifi_restore() {
    case "$1" in 1) wifi_action=enable;; 0) wifi_action=disable;; *) return 3;; esac
    echo "WIFI_RESTORE_REQUEST desired=$1"
    startup_trace WIFI_RESTORE_REQUEST
    framework timeout 5 svc wifi "$wifi_action" || return 3
    wifi_wait "$1" || return 3
    echo "WIFI_RESTORE_CONFIRMED desired=$1"
    startup_trace WIFI_RESTORE_CONFIRMED
}
wifi_state() {
    wifi_text=$(framework timeout 5 cmd wifi status) || return 3
    case "$wifi_text" in
        'Wifi is enabled'*) echo 1;;
        'Wifi is disabled'*) echo 0;;
        *) echo 'Cannot determine runtime Wi-Fi state' >&2; return 3;;
    esac
}
wifi_wait() {
    case "$1" in 0|1) ;; *) return 3;; esac
    wifi_attempt=0
    while [ "$wifi_attempt" -lt 20 ];do
        wifi_actual=$(wifi_state) || wifi_actual=unknown
        [ "$wifi_actual" != "$1" ] || return 0
        sleep 1;wifi_attempt=$((wifi_attempt+1))
    done
    echo "WIFI_STATE_TIMEOUT desired=$1 actual=$wifi_actual" >&2
    return 3
}
wifi_startup_state() {
    # At boot the framework can still report disabled while bringing up a
    # user-enabled radio. Preserve the saved toggle before our temporary stop.
    wifi_runtime=$(wifi_state) || return 3
    wifi_saved=$(/system/bin/timeout 4 /system/bin/settings get global wifi_on 2>/dev/null) || wifi_saved=unknown
    case "$wifi_saved" in
        1|2) wifi_desired=1;;
        0|3) wifi_desired=0;;
        *) wifi_desired=$wifi_runtime;;
    esac
    echo "WIFI_STARTUP_SNAPSHOT runtime=$wifi_runtime persisted=$wifi_saved desired=$wifi_desired" >&2
    echo "$wifi_desired"
}
sysext_path() {
    if [ -f "$MODDIR/system/system_ext/$1" ]; then echo "$MODDIR/system/system_ext/$1";
    else echo "$MODDIR/system_ext/$1"; fi
}
active_services() {
    framework timeout 8 dumpsys activity services "$PKG" |
        sed -n '/  User 0 active services:/,$p'
}
kernel_matches() {
    # The stock WLAN and mac80211 builds differ per firmware and per device,
    # so their build ids cannot be compared against a fixed value. What the
    # module actually needs is a kernel of the ABI family the payload was
    # compiled for, plus a driver that exposes the AWDL and coexistence knobs
    # it drives, which the capability probe verifies.
    gap=$(kernel_family_gap)
    if [ -n "$gap" ]; then
        echo "$gap"
        return 1
    fi
    driver_compatible
}
# The capability probe lives here because common.sh is sourced from scripts
# that run in different directories, so a separate file could not be located
# reliably.
DRIVER_PARAMS=/sys/module/qca_cld3_peach_v2/parameters
# Knobs the AWDL path depends on, with the value each one has to carry.
DRIVER_REQUIRED_KNOBS='passthru_native_data_rx:Y
passthru_awdl_raw_rx:Y
passthru_awdl_backpressure:Y
passthru_awdl_target_auto:Y
passthru_awdl_rx_auto:Y
passthru_awdl_rx_rearm:Y
passthru_sta_regular_rx:Y
passthru_sta_action_rx:Y
passthru_awdl_wmi_raw:Y'
DRIVER_REQUIRED_COEXIST=3
driver_param() {
    [ -r "$DRIVER_PARAMS/$1" ] || return 1
    head -c 64 "$DRIVER_PARAMS/$1" 2>/dev/null
}
# Report the first missing capability, or an empty string when all are there.
driver_capability_gap() {
    loaded qca_cld3_peach_v2 || { echo 'wlan driver not loaded';return; }
    for entry in $DRIVER_REQUIRED_KNOBS; do
        knob=${entry%%:*}
        want=${entry##*:}
        got=$(driver_param "$knob" 2>/dev/null || true)
        if [ "$got" != "$want" ]; then
            echo "missing $knob (expected $want, found ${got:-absent})"
            return
        fi
    done
    mode=$(driver_param passthru_sta_coexist 2>/dev/null || true)
    if [ "$mode" != "$DRIVER_REQUIRED_COEXIST" ]; then
        echo "passthru_sta_coexist is ${mode:-absent}, expected $DRIVER_REQUIRED_COEXIST"
        return
    fi
    for entry in opairshare_channel_state opairshare_awdl_rx_mac_filter opairshare_bridge_pending; do
        if [ ! -r "$DRIVER_PARAMS/$entry" ]; then
            echo "missing $entry"
            return
        fi
    done
    echo ''
}
# The modules this package installed have to be the ones that are loaded, so
# the stack can be taken down again on unload.
owned_modules_match() {
    for name in wonder opairshare_wonder_provider; do
        loaded "$name" || continue
        expected=$(eval echo "\$NOTE_$name")
        [ -n "$expected" ] || continue
        [ "$(module_note "$name")" = "$expected" ] || {
            echo "module $name was replaced by another build"
            return 1
        }
    done
    return 0
}
# Full compatibility decision used by the supervisor and the control script.
# Prints a reason when the stack is not usable.
driver_compatible() {
    gap=$(driver_capability_gap)
    if [ -n "$gap" ]; then
        echo "$gap"
        return 1
    fi
    if ! owned_modules_match; then
        echo 'module-owned driver was replaced by another build'
        return 1
    fi
    return 0
}
turbo_module_args() {
    # Persistent choice made with bin/turbo.sh; default is ON.
    [ -e "$STATE/turbo-off" ] && return 0
    if [ -e "$STATE/turbo-fast" ]; then
        echo "passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=5 passthru_awdl_amsdu_bytes=7935 passthru_awdl_phy=3 passthru_awdl_mcs=8 passthru_awdl_nss=2 passthru_awdl_collect_us=1000"
        return 0
    fi
    if [ -e "$STATE/turbo-vht" ]; then phy=3; mcs=8; else phy=2; mcs=7; fi
    echo "passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=4 passthru_awdl_amsdu_bytes=6000 passthru_awdl_phy=$phy passthru_awdl_mcs=$mcs"
}
# Load the identities as soon as the library is sourced, so every script
# sees the packaged build ids and the stock build ids of this device.
identity_load
