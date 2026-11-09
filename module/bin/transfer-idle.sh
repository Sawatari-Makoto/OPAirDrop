#!/system/bin/sh
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
. "$MODDIR/bin/idle-sleep.sh"
# Exit codes:
#   0  - the provider is idle, a session/stack change may proceed
#   9  - a transfer is running right now, the session must not change
#   12 - the provider state cannot be confirmed yet (provider not ready).
#        This is a transient condition, not a rejection: during the early
#        phase of a cold boot the framework has not registered the provider
#        service yet, so the dump carries no usable state.
if [ -z "$(pidof "$PKG" 2>/dev/null || true)" ];then
    # No provider process at all means no transfer can be running.
    exit 0
fi
records=$(framework timeout 8 dumpsys activity service "$COMP") || exit 12
if printf '%s\n' "$records" | grep -Eq 'OPAIRSHARE_RECEIVE busy=1([[:space:]]|$)|isTransferFinished=false|activeTransfers=\[[^]]+\]|Active Transfers \([1-9][0-9]*\)|Receive provider transferring: true';then
    # A running transfer counts as activity, so the idle policy does not
    # release the radio in the middle of it.
    idle_touch
    echo 'Active transfer; refusing session/stack change';exit 9
fi
if [ -z "$records" ];then
    echo 'Empty provider dump while process exists; provider not ready';exit 12
fi
if printf '%s\n' "$records" | grep -Eq '^No services match:|^No service found';then
    exit 0
fi
if printf '%s\n' "$records" | grep -q 'ExternalSharingService state:' &&
   printf '%s\n' "$records" | grep -Eq 'Active Transfers \(0\)' &&
   printf '%s\n' "$records" | grep -q 'Receive provider transferring: false';then
    exit 0
fi
echo 'Unrecognized provider transfer state; provider not ready';exit 12
