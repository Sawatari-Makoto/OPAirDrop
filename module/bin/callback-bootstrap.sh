#!/system/bin/sh
# Authenticate the signed APK before exposing the callback-patched APK.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
AUTH="$STATE/callback-auth"
startup_trace AUTH_BEGIN
mkdir -p "$AUTH"
if [ -e "$STATE/callback-off" ]; then
    # The marker is written by the internal APK swap. A swap that was
    # interrupted left it behind, and the early exit then kept the discovery
    # patch unmounted for the whole boot: the receive provider never started,
    # nothing was advertised over mDNS and the AWDL session stayed blocked.
    # Only a patch that is really mounted is treated as "switched off".
    if same_boot "$STATE/callback/boot"; then
        if ! /system/bin/sh "$MODDIR/bin/app-check.sh" original; then
            # A mosey app update after the patch was switched off makes the
            # original-manifest check fail. That is expected and must not
            # abort the whole start: the patch is off and stays off here.
            echo 'APP_CHECK_ORIGINAL_MISMATCH while callback-off; ignoring'
        fi
        exit 0
    fi
    rm -f "$STATE/callback-off"
fi
gms_pid=$(pidof com.google.android.gms || true)
if same_boot "$AUTH/boot" && [ -n "$gms_pid" ] &&
   [ "$(cat "$AUTH/gms-pid" 2>/dev/null || true)" = "$gms_pid" ]; then
    # Fast path: the patch is already mounted and the same GMS process owns
    # it. An ensure failure here is transient; never abort the start for it.
    /system/bin/sh "$MODDIR/bin/callback.sh" ensure || echo 'CALLBACK_ENSURE_FASTPATH_FAILED; continuing'
    exit 0
fi
idle_rc=0
/system/bin/sh "$MODDIR/bin/transfer-idle.sh" || idle_rc=$?
if [ "$idle_rc" = 9 ]; then
    # A transfer is genuinely running; this is a real rejection, not noise.
    echo 'TRANSFER_ACTIVE during bootstrap; aborting start'
    exit 9
fi
# rc=12 (provider state not confirmable yet) is transient and expected: this
# script runs before the provider is (re)started below.
# control.sh's passive baseline has already stopped the old provider/radio.
# `restore` and `ensure` swap the APK without touching the switched-off
# marker, so an interrupted swap cannot disable the patch for the next boot.
/system/bin/sh "$MODDIR/bin/callback.sh" restore || echo 'CALLBACK_RESTORE_FAILED; the ensure loop below will retry'
provider_ok=0
n=0
while [ "$n" -lt 3 ]; do
    if /system/bin/sh "$MODDIR/bin/provider.sh" ensure; then provider_ok=1;break;fi
    n=$((n+1));sleep 5
done
[ "$provider_ok" = 1 ] || echo 'PROVIDER_ENSURE_FAILED; continuing (patch gate below still applies)'
n=0;registered=0
while [ "$n" -lt 20 ]; do
    dump=$(framework timeout 6 dumpsys activity service com.google.android.gms/.chimera.PersistentApiServiceNoInstantApps) || dump=
    if printf '%s\n' "$dump" | grep -Eq '[|] com\.google\.android\.mosey [|] Version:'; then
        registered=1;break
    fi
    sleep 1;n=$((n+1))
done
[ "$registered" != 1 ] || startup_trace AUTH_REGISTERED
# A failed release only leaves the package enabled for this boot; that is
# harmless here and must not abort the patch application below.
/system/bin/sh "$MODDIR/bin/provider.sh" release || echo 'PROVIDER_RELEASE_FAILED; continuing'
# The native service may not be published yet on a slow early boot; a failed
# stop call only means the old native service lingers a bit longer.
reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4) || reply='SERVICE_CALL_FAILED'
printf '%s\n' "$reply" > "$AUTH/native-stop.txt"
if [ "$registered" != 1 ]; then
    echo 'CALLBACK_AUTH_NOT_READY: applying discovery patch without GMS auth'
fi
# Apply the callback (discovery) patch regardless of GMS auth outcome. The
# APK bind-mount works independently of Google registration, and leaving
# callback-off would block the AWDL coexist session on builds where GMS
# registration is unavailable (e.g. CN GMS). Only abort if the patch itself
# cannot be mounted.
# The radio may still be settling after the provider was released, so retry
# before giving up: without the patch nothing is advertised over mDNS.
patched=0
n=0
while [ "$n" -lt 4 ]; do
    if /system/bin/sh "$MODDIR/bin/callback.sh" ensure; then patched=1;break;fi
    n=$((n+1));sleep 5
done
[ "$patched" = 1 ] || { echo 'CALLBACK_PATCH_FAILED'; exit 6; }
# GMS may have exited between the auth probe and here; an empty gms-pid only
# costs one extra full bootstrap on the next start (the fast path requires a
# non-empty pid), never a wrong decision.
gms_pid=$(pidof com.google.android.gms || true)
printf '%s\n' "$gms_pid" > "$AUTH/gms-pid"
boot_id > "$AUTH/boot"
startup_trace PATCH_AUTH_READY
echo "CALLBACK_AUTH_PRIMED gms_pid=$gms_pid"
