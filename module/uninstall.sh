#!/system/bin/sh
# KernelSU runs this when the user removes the module in the manager. Everything
# the module put outside its own directory must be undone here: the companion
# app, its reception marker, the module state directory, and the runtime file
# changes. The driver/GMS replacements are overlay mounts, so a reboot clears
# them; the SELinux rules live in the module's sepolicy.rule and are therefore
# not applied to the next boot either.
MODDIR=${0%/*}

if /system/bin/sh "$MODDIR/bin/control.sh" stop; then
    /system/bin/sh "$MODDIR/bin/callback.sh" restore || true
fi

# The companion bridge (current identity) and the pre-1.2.0 identity. The
# LSPosed scope configuration of an uninstalled package is dropped by LSPosed
# itself once the package is gone, so removing both apps is sufficient.
for pkg in com.oshare.airdrop com.tlsu.oshareairdrop; do
    if [ -n "$(/system/bin/pm path --user 0 "$pkg" 2>/dev/null)" ]; then
        if /system/bin/pm uninstall --user 0 "$pkg" >/dev/null 2>&1; then
            echo "- 已卸载配套应用 $pkg"
        else
            echo "- 配套应用 $pkg 卸载失败，请在系统中手动卸载"
        fi
    fi
done

# The iPhone reception marker the LSPosed module writes into Settings.System.
/system/bin/settings delete --user 0 system opairshare_ios_receive >/dev/null 2>&1 || true

# Module state: runtime scripts, logs, LSPosed activation snapshots.
rm -rf /data/adb/opairshare

echo '请重启以移除驱动/GMS 挂载与运行时 SELinux 规则；系统分区及固件未被刷写。'
