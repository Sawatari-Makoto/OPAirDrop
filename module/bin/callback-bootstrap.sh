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
        /system/bin/sh "$MODDIR/bin/app-check.sh" original
        exit 0
    fi
    rm -f "$STATE/callback-off"
fi
gms_pid=$(pidof com.google.android.gms || true)
if same_boot "$AUTH/boot" && [ -n "$gms_pid" ] &&
   [ "$(cat "$AUTH/gms-pid" 2>/dev/null || true)" = "$gms_pid" ]; then
    /system/bin/sh "$MODDIR/bin/callback.sh" ensure
    exit 0
fi
/system/bin/sh "$MODDIR/bin/transfer-idle.sh"
# control.sh's passive baseline has already stopped the old provider/radio.
# `restore` and `ensure` swap the APK without touching the switched-off
# marker, so an interrupted swap cannot disable the patch for the next boot.
/system/bin/sh "$MODDIR/bin/callback.sh" restore
/system/bin/sh "$MODDIR/bin/provider.sh" ensure
n=0;registered=0
while [ "$n" -lt 20 ]; do
    dump=$(framework timeout 6 dumpsys activity service com.google.android.gms/.chimera.PersistentApiServiceNoInstantApps) || dump=
    if printf '%s\n' "$dump" | grep -Eq '[|] com\.google\.android\.mosey [|] Version:'; then
        registered=1;break
    fi
    sleep 1;n=$((n+1))
done
[ "$registered" != 1 ] || startup_trace AUTH_REGISTERED
/system/bin/sh "$MODDIR/bin/provider.sh" release
reply=$(framework timeout 15 service call "$SERVICE" 2 i32 1 i32 4)
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
gms_pid=$(pidof com.google.android.gms)
printf '%s\n' "$gms_pid" > "$AUTH/gms-pid"
boot_id > "$AUTH/boot"
startup_trace PATCH_AUTH_READY
echo "CALLBACK_AUTH_PRIMED gms_pid=$gms_pid"
