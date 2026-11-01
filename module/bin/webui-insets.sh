#!/system/bin/sh
# Measure the system bar insets in CSS pixels and report them to the WebUI.
#
# The WebUI host lays the WebView out edge to edge, so the page has to
# reserve the space the status and navigation bars occupy itself. The
# window manager reports those sizes as insets in device pixels; this script
# reads them and converts them to CSS pixels with the display density the
# WebView renders at.
#
# The parsing is done with awk because the shell's sed on this platform does
# not support the alternation and repetition the frame pattern needs.
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state

# The density the WebView renders at. The framework baseline is 160 dpi, so
# one device pixel is 160/density CSS pixels.
density() {
    value=$(wm density 2>/dev/null | sed -n 's/.*Override density: \([0-9][0-9]*\).*/\1/p' | head -1)
    [ -n "$value" ] || value=$(wm density 2>/dev/null | sed -n 's/.*Physical density: \([0-9][0-9]*\).*/\1/p' | head -1)
    [ -n "$value" ] || value=160
    printf '%s\n' "$value"
}

# Heights of the status and navigation bar frames in device pixels.
bar_heights() {
    dumpsys window displays 2>/dev/null | awk '
        /InsetsSource id=/ {
            kind = ""
            if (index($0, "type=statusBars") > 0) kind = "status"
            else if (index($0, "type=navigationBars") > 0) kind = "nav"
            if (kind == "") next
            # The frame is reported as [left,top][right,bottom].
            line = $0
            sub(/.*frame=/, "", line)
            sub(/ visible=.*/, "", line)
            gsub(/[\[\]]/, " ", line)
            gsub(/,/, " ", line)
            split(line, edge, " ")
            if (edge[2] == "" || edge[4] == "") next
            height = edge[4] - edge[2]
            if (height < 0) next
            if (kind == "status" && status == "") status = height
            else if (kind == "nav" && nav == "") nav = height
        }
        END { printf "%d %d\n", status + 0, nav + 0 }
    '
}

heights=$(bar_heights)
top=$(printf '%s\n' "$heights" | cut -d' ' -f1)
bottom=$(printf '%s\n' "$heights" | cut -d' ' -f2)
case "$top" in ''|*[!0-9]*) top=0;; esac
case "$bottom" in ''|*[!0-9]*) bottom=0;; esac

# Fall back to the insets the focused window was given, which differ on
# hosts that reserve the bars themselves.
if [ "$top" = 0 ] || [ "$bottom" = 0 ]; then
    hint=$(dumpsys window 2>/dev/null | awk '
        /mInsetsHint=Insets\{/ {
            line = $0
            kind = ""
            if (index($0, "mType=statusBars") > 0) kind = "status"
            else if (index($0, "mType=navigationBars") > 0) kind = "nav"
            if (kind == "") next
            sub(/.*mInsetsHint=Insets\{/, "", line)
            sub(/\}.*/, "", line)
            gsub(/[^0-9=,]/, " ", line)
            n = split(line, kv, ",")
            top = 0; bottom = 0
            for (i = 1; i <= n; i++) {
                split(kv[i], pair, "=")
                if (pair[1] == "top") top = pair[2] + 0
                if (pair[1] == "bottom") bottom = pair[2] + 0
            }
            if (kind == "status" && status == "") status = top
            else if (kind == "nav" && nav == "") nav = bottom
        }
        END { printf "%d %d\n", status + 0, nav + 0 }
    ')
    htop=$(printf '%s\n' "$hint" | cut -d' ' -f1)
    hbottom=$(printf '%s\n' "$hint" | cut -d' ' -f2)
    [ "$top" = 0 ] && [ "$htop" -gt 0 ] && top=$htop
    [ "$bottom" = 0 ] && [ "$hbottom" -gt 0 ] && bottom=$hbottom
fi

dpi=$(density)
[ -n "$dpi" ] || dpi=160

# Convert device pixels to CSS pixels.
top_css=$((top * 160 / dpi))
bottom_css=$((bottom * 160 / dpi))

printf 'top=%s bottom=%s dpi=%s\n' "$top_css" "$bottom_css" "$dpi"
