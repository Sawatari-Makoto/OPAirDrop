#!/system/bin/sh
set -u
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state
# Permission grants are best-effort. The Mosey provider package is a privileged
# system app and is not always resolvable by PackageManager in the narrow window
# right after sys.boot_completed, so a failed grant must NOT abort the whole
# module start (it used to exit 3 and, under set -e in control.sh, disabled the
# module on every cold boot). Retry briefly; if it still fails, log and continue.
for permission in android.permission.NEARBY_WIFI_DEVICES android.permission.ACCESS_LOCAL_NETWORK android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_ADVERTISE android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_BACKGROUND_LOCATION android.permission.ACCESS_MEDIA_LOCATION; do
    n=0; ok=0
    while [ "$n" -lt 6 ]; do
        if framework pm grant --user 0 "$PKG" "$permission" >> "$STATE/permission-grants.log" 2>&1; then ok=1; break; fi
        n=$((n+1)); sleep 1
    done
    [ "$ok" = 1 ] || echo "Permission grant not ready (non-fatal, app will request at runtime): $permission"
done
