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
#   * keep waiting for root for a long window (a temporary-root grant usually
#     needs a manual approval, which can happen minutes after the boot);
#   * retry when root IS available, to absorb the PackageManager scan race for
#     the privileged Mosey app and any boot-time lock contention;
#   * only latch the module off when the failure is genuinely fatal (the
#     patched drivers were rolled back), never on a recoverable failure that
#     kept the patched stack in place;
#   * clear a stale `disabled` automatically if a later boot with root succeeds.
# ---------------------------------------------------------------------------
root_seen=0
attempt=0
max_attempts=${OPAIRSHARE_BOOT_MAX_ATTEMPTS:-40}
retry_sleep=${OPAIRSHARE_BOOT_RETRY_SLEEP:-15}
# After the bounded window the loop keeps running at a much lower cadence
# instead of giving up: a sleeping shell costs no CPU, and transient early-boot
# failures (PackageManager scan races, framework services not yet published)
# often resolve minutes later on temporary-root devices. The cadence decays
# 15s -> 60s -> 300s so a device that never becomes healthy stays quiet.
slow_sleep=${OPAIRSHARE_BOOT_RETRY_SLOW_SLEEP:-60}
slow_attempts=${OPAIRSHARE_BOOT_RETRY_SLOW_ATTEMPTS:-10}
idle_sleep=${OPAIRSHARE_BOOT_RETRY_IDLE_SLEEP:-300}
fatal_attempts=0
while :; do
    if [ "$attempt" -lt "$max_attempts" ]; then
        pause=$retry_sleep
    elif [ "$attempt" -lt "$((max_attempts+slow_attempts))" ]; then
        pause=$slow_sleep
    else
        pause=$idle_sleep
    fi
    attempt=$((attempt+1))
    if [ "$(id -u)" != "0" ]; then
        # No root yet (temporary-root device before escalation). Keep auto-start
        # armed and wait; do NOT disable. If the user escalates within this
        # window, the next iteration will pick it up and start automatically.
        echo "Root not available yet (attempt $attempt); deferring start" > "$STATE/boot-start.log"
        sleep "$pause"
        continue
    fi
    root_seen=1
    start_rc=0
    /system/bin/sh "$MODDIR/bin/control.sh" start > "$STATE/boot-start.log" 2>&1 || start_rc=$?
    if [ "$start_rc" = 0 ]; then
        rm -f "$STATE/disabled"
        log -t opairshare-quickshare 'r24 resident driver, transfer callbacks and UI lifecycle ready'
        # Watchdog. A supervisor exit 6/8 (native server lost, session restore
        # failed) used to leave the module silently dead until the next boot.
        # A sleeping shell costs no CPU, so the boot service stays resident and
        # re-runs `control.sh start` when the supervisor dies: the same-boot
        # retry path keeps the patched drivers in place and only restarts the
        # native server and the supervisor. A transfer in progress makes the
        # retry exit 9; that is benign and simply retries on the next tick.
        watchdog_interval=${OPAIRSHARE_WATCHDOG_INTERVAL:-60}
        while :; do
            sleep "$watchdog_interval"
            # An explicit opt-out (module disabled/removed) ends the watchdog;
            # a new boot has its own service.sh instance, so exit on boot rollover.
            if [ -e "$STATE/disabled" ] || [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
                exit 0
            fi
            if ! same_boot "$STATE/stack-boot"; then exit 0; fi
            sup=$(cat "$STATE/supervisor.pid" 2>/dev/null || true)
            if pid_is "$sup" 'ui-lifecycle.sh'; then continue; fi
            echo "Supervisor not running (pid=$sup); watchdog restart" >> "$STATE/boot-start.log"
            /system/bin/sh "$MODDIR/bin/control.sh" start >> "$STATE/boot-start.log" 2>&1 || true
        done
    fi
    # A recoverable failure kept the patched stack in place: the retry only
    # has to restart the native server and the provider, so it is cheap and
    # must not be treated as fatal.
    if grep -q 'RECOVERABLE_START_FAILURE' "$STATE/boot-start.log" 2>/dev/null; then
        fatal_attempts=0
        echo "Recoverable start failure (rc=$start_rc) on attempt $attempt; retrying in ${pause}s" >> "$STATE/boot-start.log"
        sleep "$pause"
        continue
    fi
    # The provider was not registered yet (early cold boot). Nothing was
    # swapped and nothing was rolled back, so the start is simply retried.
    if [ "$start_rc" = 12 ]; then
        fatal_attempts=0
        echo "Provider not ready (rc=12) on attempt $attempt; retrying in ${pause}s" >> "$STATE/boot-start.log"
        sleep "$pause"
        continue
    fi
    # Fatal: the drivers were rolled back to the factory build. Retry a couple
    # of times (the boot race can be transient) and only then latch the module
    # off.
    fatal_attempts=$((fatal_attempts+1))
    echo "Start failed rc=$start_rc (fatal $fatal_attempts) on attempt $attempt" >> "$STATE/boot-start.log"
    [ "$fatal_attempts" -ge 3 ] && break
    sleep "$pause"
done
if [ "$root_seen" = "1" ] && [ "$fatal_attempts" -ge 3 ]; then
    touch "$STATE/disabled"
    log -t opairshare-quickshare 'r24 startup failed fatally; no automatic retry'
    exit 3
fi
exit 0
