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
# `Active Transfers (N)` is deliberately not an activity signal by itself:
# the provider keeps finished records (isTransferFinished=true, job
# cancelled) in that list until the next session clears them, and treating
# the count alone as activity used to block every session/stack change for
# the rest of the boot. Only per-record in-flight flags count here.
if printf '%s\n' "$records" | grep -Eq 'OPAIRSHARE_RECEIVE busy=1([[:space:]]|$)|isTransferFinished=false|activeTransfers=\[[^]]+\]|Receive provider transferring: true';then
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
# Reaching this point means line 23 found no in-flight signal at all, so a
# non-zero Active Transfers count that only holds finished records
# (isTransferFinished=true) is still idle. Require the service header and a
# quiescent receive provider; the count itself stays unchecked.
if printf '%s\n' "$records" | grep -q 'ExternalSharingService state:' &&
   printf '%s\n' "$records" | grep -q 'Receive provider transferring: false';then
    exit 0
fi
echo 'Unrecognized provider transfer state; provider not ready';exit 12
