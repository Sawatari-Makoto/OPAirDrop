#!/system/bin/sh
# ColorOS kills cached apps that keep binder transactions in flight
# ("excessive binder traffic during cached", AOSP CachedAppOptimizer
# handleBinderFreezerFailure, gated by DeviceConfig
# activity_manager_native_boot/freeze_binder_enabled, default on). The Mosey
# provider process never goes idle: the native server, the share UI and the
# synthetic mDNS responder constantly exchange binder traffic, so while the
# process is cached the binder-freezer kill removes it within minutes and the
# iPhone silently loses this device from its AirDrop list mid-session.
#
# Disable the binder-freezer kill path and the cached-app freezer for the
# whole boot. The provider keeps its normal oom adj, so memory-pressure
# handling is untouched; this only stops the "busy while cached" kill.
#
# Idempotent and self-gating: one assertion per boot (marker is stamped with
# the boot id), so the resident loops can call it every tick for free.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
marker="$STATE/freezer-guard"
if same_boot "$marker"; then
    echo 'freezer guard already asserted for this boot'
    exit 0
fi
# Flag sync may push the platform default back at any time; keep the override
# resident. "persistent" itself survives reboots.
/system/bin/cmd device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
if ! /system/bin/cmd device_config put activity_manager_native_boot freeze_binder_enabled false 2>/dev/null; then
    echo 'cmd device_config unavailable; relying on the settings fallbacks' >&2
fi
# AOSP CachedAppOptimizer reads Settings.Global cached_apps_freezer (the
# master switch) and the platform adds cached_apps_freezer_enabled. Both
# were separately observed on this build; keep both off.
/system/bin/settings put global cached_apps_freezer 0 2>/dev/null || true
/system/bin/settings put global cached_apps_freezer_enabled 0 2>/dev/null || true
state=$(/system/bin/cmd device_config get activity_manager_native_boot freeze_binder_enabled 2>/dev/null || echo unknown)
boot_id > "$marker"
echo "FREEZER_GUARD_APPLIED freeze_binder_enabled=$state"
