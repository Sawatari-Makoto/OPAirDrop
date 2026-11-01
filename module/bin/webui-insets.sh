#!/system/bin/sh
# Measure the system bar insets in CSS pixels and report them to the WebUI.
#
# The WebUI host differs between manager versions and the page cannot rely on
# a single source for the insets. This script asks the window manager for the
# real sizes so the page can reserve exactly the space the system bars take.
#
# The values are reported in device pixels and converted with the display
# density, so the page can use them directly as CSS pixels.
MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
. "$MODDIR/bin/common.sh"
init_state

density() {
    # The display density in dpi. 160 is the framework baseline.
    value=$(dumpsys display 2>/dev/null |
        sed -n 's/.*density \([0-9][0-9]*\).*/\1/p' | head -1)
    [ -n "$value" ] || value=160
    printf '%s\n' "$value"
}

# The current focused window reports the frame it occupies and the frame it
# would occupy without the system decorations. The difference is the space
# the status and navigation bars take.
window_frames() {
    dumpsys window windows 2>/dev/null |
        sed -n '/mCurrentFocus/p;/mFocusedApp/p'
}

status_inset() {
    # Height of the status bar in device pixels.
    dumpsys window 2>/dev/null |
        sed -n 's/.*mStatusBar.*insets.*statusBars[^0-9]*\([0-9][0-9]*\).*/\1/p' | head -1
}

nav_inset() {
    dumpsys window 2>/dev/null |
        sed -n 's/.*mNavigationBar.*insets.*navigationBars[^0-9]*\([0-9][0-9]*\).*/\1/p' | head -1
}

# Fallback for hosts that do not report the insets: read the resource values.
resource_inset() {
    # $1 = the resource name, e.g. status_bar_height
    dumpsys window 2>/dev/null | grep -o "$1[^0-9]*\([0-9][0-9]*\)" | head -1 |
        sed -n 's/.*[^0-9]\([0-9][0-9]*\).*/\1/p'
}

top=0
bottom=0

# Prefer the insets the window manager reports for the focused window.
insets_line=$(dumpsys window 2>/dev/null |
    grep -o 'mCurrentFocus[^}]*' | head -1)
frames=$(dumpsys window windows 2>/dev/null | grep -A2 'mCurrentFocus' | head -20)

# The frame of the focused window includes the system bars; the content frame
# excludes them. Their difference is the inset.
full=$(printf '%s\n' "$frames" | sed -n 's/.*frame=\[0\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\].*/\1 \2 \3 \4/p' | head -1)
content=$(printf '%s\n' "$frames" | sed -n 's/.*contentFrame=\[0\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\]\[\([0-9][0-9]*\)\].*/\1 \2 \3 \4/p' | head -1)

if [ -n "$full" ] && [ -n "$content" ]; then
    set -- $full
    full_top=$2
    full_bottom=$4
    set -- $content
    content_top=$2
    content_bottom=$4
    # The window is placed below the status bar already, so the gap above the
    # content is not ours to reserve; only the space the window manager
    # reports as the system bar insets matters.
    top=$((content_top > full_top ? content_top - full_top : 0))
    bottom=$((full_bottom > content_bottom ? full_bottom - content_bottom : 0))
fi

if [ "$top" = 0 ]; then
    top=$(status_inset)
    [ -n "$top" ] || top=$(resource_inset status_bar_height)
fi
if [ "$bottom" = 0 ]; then
    bottom=$(nav_inset)
    [ -n "$bottom" ] || bottom=$(resource_inset navigation_bar_height)
fi

dpi=$(density)
[ -n "$dpi" ] || dpi=160

# Convert device pixels to CSS pixels.
top_css=$((top * 160 / dpi))
bottom_css=$((bottom * 160 / dpi))

printf 'top=%s bottom=%s dpi=%s\n' "$top_css" "$bottom_css" "$dpi"
