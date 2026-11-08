#!/system/bin/sh
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
[ "$(id -u)" = 0 ] || exit 2
init_state
case "${1:-status}" in
    status)
        records=$(active_services)
        echo "provider_pid=$(pidof "$PKG" || true)"
        if echo "$records" | grep -q 'ExternalSharingService'; then echo provider_active=yes; else echo provider_active=no; fi
        if echo "$records" | grep -q 'isFgsDelegate=true'; then echo foreground_delegate=yes; else echo foreground_delegate=no; fi
        ;;
    ensure|adopt)
        # Re-enable a package left disabled by a previous release before any
        # validation. Validation below may fail (an updated APK split, a
        # native service that is momentarily absent); failing before the
        # enable left the package stuck in disabled-user, the provider could
        # never start again, and the device vanished from every iPhone's
        # AirDrop list until the package was enabled by hand. Enabling an
        # already enabled package would fire a PACKAGE_CHANGED broadcast,
        # which restarts the provider process and tears down the AirDrop
        # mDNS advertisement, so this stays guarded by package_disabled.
        if package_disabled; then framework pm enable --user 0 "$PKG" >/dev/null; fi
        /system/bin/sh "$MODDIR/bin/app-check.sh" || exit 3
        framework service check "$SERVICE" | grep -q ': found' || { echo 'Native service absent'; exit 3; }
        if component_disabled; then framework pm enable --user 0 "$COMP" >/dev/null; fi
        trap 'framework cmd deviceidle tempwhitelist -r "$PKG" >/dev/null 2>&1 || true' EXIT
        records=$(active_services)
        if ! echo "$records" | grep -q 'ExternalSharingService'; then
            framework cmd deviceidle tempwhitelist -u 0 -d 90000 "$PKG" >/dev/null
            result=$(framework am startservice --user 0 -n "$COMP")
            echo "$result"
            echo "$result" | grep -q '^Starting service:' || exit 3
        fi
        n=0;launched_delegate=0;provider_ready=0
        while [ "$n" -lt 15 ]; do
            records=$(active_services)
            provider_pid=$(pidof "$PKG" || true)
            if echo "$records" | grep -q 'ExternalSharingService' &&
               echo "$records" | grep -q 'app=ProcessRecord' && pid_live "$provider_pid"; then
                # A live service plus a live process is already acceptable:
                # the cached freezer and the binder-freezer kill are disabled
                # system-wide and the supervisor unfreezes periodically, so
                # the delegate is a best-effort priority boost, not a gate.
                provider_ready=2
                if ! echo "$records" | grep -q 'isFgsDelegate=true'; then
                    framework cmd activity set-foreground-service-delegate --user 0 "$PKG" start || true
                    records=$(active_services)
                    if echo "$records" | grep -q 'isFgsDelegate=true'; then launched_delegate=1; fi
                else
                    launched_delegate=1
                fi
                if [ "$launched_delegate" = 1 ]; then provider_ready=1;break;fi
            fi
            sleep 1;n=$((n+1))
        done
        if [ "$provider_ready" = 0 ]; then exit 5; fi
        if [ "$launched_delegate" = 1 ] || [ "${1:-}" = adopt ]; then boot_id > "$STATE/delegate-owned";fi
        if [ "$provider_ready" = 2 ]; then
            echo "PROVIDER_READY pid=$(pidof "$PKG") foreground_delegate=no"
        else
            echo "PROVIDER_READY pid=$(pidof "$PKG") foreground_delegate=yes"
        fi
        ;;
    release)
        framework pm disable-user --user 0 "$PKG"
        framework am force-stop --user 0 "$PKG"
        if same_boot "$STATE/delegate-owned"; then
            framework cmd activity set-foreground-service-delegate --user 0 "$PKG" stop || true
        fi
        rm -f "$STATE/delegate-owned"
        ;;
    suspend)
        # Stop the provider without disabling the package. Used when the idle
        # policy releases the radio: the app stays enabled so the user can
        # still open Quick Share by hand, the module simply owns no
        # foreground delegate at the moment.
        framework am force-stop --user 0 "$PKG"
        if same_boot "$STATE/delegate-owned"; then
            framework cmd activity set-foreground-service-delegate --user 0 "$PKG" stop || true
        fi
        rm -f "$STATE/delegate-owned"
        ;;
    *) echo 'Usage: provider.sh status|ensure|adopt|release|suspend';exit 2;;
esac
