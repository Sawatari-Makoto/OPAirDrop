#!/system/bin/sh
# Validate every active split against the selected original/patched manifest.
set -eu
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
case "${1:-runtime}" in
    original) manifest="$MODDIR/APP-SHA256SUMS";;
    patched) manifest="$MODDIR/APP-CALLBACK-SHA256SUMS";;
    runtime)
        # The manifest follows the actually active base.apk, not a leftover
        # marker: the static payload ships the patched APK (no callback
        # mount exists) while the dynamic mount exposes the same patched
        # hash — in both cases the patched manifest is the right one.
        base=$(framework pm path --user 0 "$PKG" | sed -n 's/^package:\(.*\/base.apk\)$/\1/p' | head -n1)
        patched_hash=$(awk '$2=="base.apk" {print $1}' "$MODDIR/APP-CALLBACK-SHA256SUMS")
        manifest="$MODDIR/APP-SHA256SUMS"
        if [ -n "$base" ] && [ "$(sha256sum "$base" | awk '{print $1}')" = "$patched_hash" ]; then
            manifest="$MODDIR/APP-CALLBACK-SHA256SUMS"
        fi;;
    *) exit 2;;
esac
paths=$(framework pm path --user 0 "$PKG" | sed -n 's/^package://p')
[ -n "$paths" ] || { echo 'Mosey is not installed for user0'; exit 3; }
[ "$(printf '%s\n' "$paths" | wc -l | tr -d ' ')" = "$(wc -l < "$manifest" | tr -d ' ')" ] || {
    echo 'Active APK split count differs'; exit 3;
}
while read -r expected name; do
    active=$(printf '%s\n' "$paths" | while read -r path; do
        [ "${path##*/}" != "$name" ] || printf '%s\n' "$path"
    done)
    [ -n "$active" ] && [ "$(printf '%s\n' "$active" | wc -l | tr -d ' ')" = 1 ] || exit 3
    [ "$(sha256sum "$active" | awk '{print $1}')" = "$expected" ] || {
        echo "Active APK differs: $name (${1:-runtime})"; exit 3;
    }
done < "$manifest"
echo "MOSEY_60715_SPLITS_VERIFIED ${1:-runtime}"
