#!/system/bin/sh
# Idle policy for the module-owned background reception.
#
# A permanently armed AWDL session keeps the wonder interface up, keeps the
# mDNS advertisement running and holds the STA out of power saving, which
# costs battery all day even when nothing is transferred. The session is
# therefore released after a period without activity and rebuilt as soon as
# the user shows the intent to share again.
#
# The file is both a sourceable library and a command line helper. The command
# line part only runs when the script is executed directly.
set -eu
# The file is sourced by settings.sh and the supervisor, so the module
# directory has to come from the caller instead of this file's own path.
if [ -z "${MODDIR:-}" ];then
    MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
fi
. "$MODDIR/bin/common.sh"
init_state
IDLE_STATE="$STATE/idle-policy"

# The default keeps the session armed for two minutes so a short gap between
# two share actions does not tear the session down.
IDLE_DEFAULT_TIMEOUT=120
IDLE_TIMEOUT_KEY=idle_timeout

idle_timeout_seconds() {
    value=$(/system/bin/settings get global "$IDLE_TIMEOUT_KEY" 2>/dev/null || true)
    case "$value" in
        ''|*[!0-9]*) value=$IDLE_DEFAULT_TIMEOUT;;
    esac
    printf '%s\n' "$value"
}

idle_now_ms() { awk '{printf "%.0f",$1*1000}' /proc/uptime; }

# Record activity. Called whenever the user opens a share surface or a
# transfer runs.
idle_touch() {
    mkdir -m 700 "$IDLE_STATE" 2>/dev/null || return 0
    idle_now_ms > "$IDLE_STATE/last-active" 2>/dev/null || true
    return 0
}

# Seconds left before the session is allowed to sleep.
idle_remaining() {
    limit=$(idle_timeout_seconds)
    [ "$limit" -gt 0 ] || { echo 0;return; }
    last=$(cat "$IDLE_STATE/last-active" 2>/dev/null || true)
    case "$last" in ''|*[!0-9]*) echo "$limit";return;; esac
    elapsed=$(( $(idle_now_ms) - last ))
    left=$((limit - elapsed / 1000))
    [ "$left" -ge 0 ] || left=0
    echo "$left"
}

# Report whether the session may go to sleep.
#   0 -> sleep is allowed
#   1 -> keep the session
idle_may_sleep() {
    limit=$(idle_timeout_seconds)
    [ "$limit" -gt 0 ] || { echo 1;return; }
    last=$(cat "$IDLE_STATE/last-active" 2>/dev/null || true)
    case "$last" in ''|*[!0-9]*) idle_touch;echo 1;return;; esac
    elapsed=$(( $(idle_now_ms) - last ))
    [ "$elapsed" -ge $((limit * 1000)) ] || { echo 1;return; }
    echo 0
}

# Only run the command line interface when executed directly.
case "${0##*/}" in
    idle-sleep.sh)
        case "${1:-status}" in
            touch) idle_touch;echo 'IDLE_ACTIVITY_RECORDED';;
            may-sleep) idle_may_sleep;;
            remaining) idle_remaining;;
            timeout) idle_timeout_seconds;;
            set-timeout)
                [ "$#" = 2 ] || exit 2
                case "$2" in ''|*[!0-9]*) exit 2;; esac
                [ "$2" -le 86400 ] || exit 2
                /system/bin/settings put global "$IDLE_TIMEOUT_KEY" "$2" >/dev/null || exit 1
                [ "$(idle_timeout_seconds)" = "$2" ] || exit 1
                idle_touch
                echo "$2";;
            reset)
                rm -rf "$IDLE_STATE"
                idle_touch
                echo 'IDLE_POLICY_RESET';;
            status)
                printf 'idle_timeout=%s\n' "$(idle_timeout_seconds)"
                printf 'idle_remaining=%s\n' "$(idle_remaining)"
                printf 'idle_active_ms=%s\n' "$(cat "$IDLE_STATE/last-active" 2>/dev/null || echo 0)";;
            *) echo 'Usage: idle-sleep.sh touch|may-sleep|remaining|timeout|set-timeout|reset|status' >&2;exit 2;;
        esac
        ;;
esac
