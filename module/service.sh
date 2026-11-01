#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/root-runtime.sh"
root_enter_global "$0" "$@" || exit 2
. "$MODDIR/bin/common.sh"
init_state
rm -f "$STATE/coex-off" "$STATE/coex-same149-only"
: > "$STATE/startup-trace.log"
startup_trace BOOT_SERVICE_ENTER
i=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 90 ];do sleep 2;i=$((i+1));done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 2
startup_trace ANDROID_READY
. "$MODDIR/bin/share-mode.sh"
init_share_mode || exit 3
# Complete a recovery-mode install or an LSPosed configuration deferred at install.
if [ -f "$STATE/oshare-setup-pending" ]; then
    /system/bin/sh "$MODDIR/bin/install-oshare.sh" "$MODDIR" boot >> "$STATE/oshare-install.log" 2>&1 || true
fi
# Auto-start is enabled by default. customize.sh creates `autostart` at install
# unless the user explicitly opted out via `autostart-off`. Honor an explicit
# opt-out; otherwise default to ON, so a missing/vanishing `autostart` marker
# (e.g. removed by a stray `autostart-off` or a debug step) does not silently
# leave the module dead on a temporary-root device.
[ -e "$STATE/autostart-off" ] && exit 0
if [ -e "$STATE/startup-incomplete" ];then
    # A marker left behind by an interrupted attempt (module replaced while the
    # system was running, reboot during the driver swap, crash) must not kill
    # the module on the next boot. The kernel stack is rebuilt from scratch on
    # every boot, so a marker from an earlier boot is only a hint. Clearing it
    # and retrying keeps the module alive instead of latching it off.
    echo 'Previous startup did not finish; retrying from a clean state' > "$STATE/boot-start.log"
    rm -f "$STATE/startup-incomplete"
fi
# ---------------------------------------------------------------------------
# Startup with resilience for temporary-root devices.
#
# On devices where KernelSU only gains root AFTER the first cold boot (the user
# escalates manually, then hot-reboots), the very first boot legitimately has no
# usable root and `control.sh start` cannot succeed. The original logic disabled
# auto-start permanently on the first failure, which permanently killed the
# module on those devices. We now:
#   * never permanently disable when root was never available (defer instead),
#     so a later boot (or a manual start after escalation) can still bring it up;
#   * retry a handful of times when root IS available, to absorb the
#     PackageManager scan race for the privileged Mosey app;
#   * only disable after root is present AND start keeps failing (genuine fatal);
#   * clear a stale `disabled` automatically if a later boot with root succeeds.
# ---------------------------------------------------------------------------
root_seen=0
attempt=0
max_attempts=18
while [ "$attempt" -lt "$max_attempts" ]; do
    attempt=$((attempt+1))
    if [ "$(id -u)" != "0" ]; then
        # No root yet (temporary-root device before escalation). Keep auto-start
        # armed and wait; do NOT disable. If the user escalates within this
        # window, the next iteration will pick it up and start automatically.
        echo "Root not available yet (attempt $attempt/$max_attempts); deferring start" > "$STATE/boot-start.log"
        sleep 15
        continue
    fi
    root_seen=1
    if /system/bin/sh "$MODDIR/bin/control.sh" start > "$STATE/boot-start.log" 2>&1; then
        rm -f "$STATE/disabled"
        log -t opairshare-quickshare 'r24 resident driver, transfer callbacks and UI lifecycle ready'
        exit 0
    fi
    # Root present but start failed. Could be the PackageManager scan race for
    # the privileged Mosey app; retry a few times before giving up.
    [ "$attempt" -ge 4 ] && break
    sleep 8
done
if [ "$root_seen" = "1" ]; then
    touch "$STATE/disabled"
    log -t opairshare-quickshare 'r24 startup failed; no automatic retry'
fi
exit 3
