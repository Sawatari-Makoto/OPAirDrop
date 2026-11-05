#!/system/bin/sh
ui_print "- OPAirDrop v$(sed -n 's/^version=//p' "$MODPATH/module.prop")：为 Oplus sun 平台设备开启 AirDrop"
[ "$(id -u)" = 0 ] || abort 'Root required'
verify_output=$(sh "$MODPATH/bin/verify.sh" "$MODPATH" 2>&1) || abort "Payload integrity check failed: ${verify_output:-no output}"
. "$MODPATH/bin/root-runtime.sh"
# The runtime library carries the identity helpers and the kernel gate. The
# installer sources this script, so the library is loaded by its module path
# instead of by the path of this script.
[ -f "$MODPATH/bin/common.sh" ] || abort 'Runtime library missing: bin/common.sh'
. "$MODPATH/bin/common.sh" || abort 'Failed to load the runtime library'
ui_print "- Root 环境：$(root_manager)；支持管理器内置挂载或外部挂载模块"
ui_print '- 不按元模块名称或目录拦截；重启后检查系统文件的实际挂载结果'
. "$MODPATH/bin/compatibility.sh"
compat_result=$(compatibility_device 2>&1) || abort "Compatibility check failed: $compat_result"
ui_print "- 兼容性检测通过：$(getprop ro.product.model) / Oplus / sun / $(uname -r)"
ui_print "- 内核 ABI 家族：${KERNEL_ABI_FAMILY:-未知}（页面大小 ${KERNEL_ABI_PAGESIZE:-未知}）"
# Record the original driver build ids of this device. They are read back at
# runtime, so the package itself carries no device specific identity and the
# same release works on every supported device.
stock_captured=0
capture_stock_identity >/dev/null 2>&1 && stock_captured=1
if [ "$stock_captured" = 1 ]; then
    stock_wlan=$(stock_note qca_cld3_peach_v2)
    stock_mac=$(stock_note mac80211)
    if [ -n "$stock_wlan" ]; then
        ui_print "- 已记录本机原厂 WLAN 驱动构建标识：${stock_wlan}"
    fi
    if [ -n "$stock_mac" ]; then
        ui_print "- 已记录本机原厂 mac80211 构建标识：${stock_mac}"
    fi
fi
[ "$stock_captured" = 1 ] || ui_print '- 未能读取本机原厂驱动构建标识；恢复原厂驱动时由内核自身校验'
# Replacement must not merge obsolete split APKs from the earlier module.
REPLACE="/system/system_ext/priv-app/MoseyApp"
root_write_policy "$MODPATH" || abort 'Root SELinux policy configuration failed'
for item in "$MODPATH"/*;do
    if [ -d "$item" ];then set_perm_recursive "$item" 0 0 0755 0644
    else set_perm "$item" 0 0 0644;fi
done
for f in "$MODPATH"/bin/*.sh "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" "$MODPATH/uninstall.sh";do set_perm "$f" 0 0 0755;done
set_perm "$MODPATH/system/system_ext/bin/mosey_server" 0 0 0755
ui_print '- 配置内置 Unlock CN GMS v3.8'
gms_result=$(sh "$MODPATH/bin/setup-gms.sh" "$MODPATH" 2>&1) || abort "GMS 配置生成失败：$gms_result"
printf '%s\n' "$gms_result" | while IFS= read -r line; do ui_print "- $line"; done
if [ -s "$MODPATH/gms-unlock/mounts.list" ]; then
    for standalone in /data/adb/modules/com.fei_ke.unlockcngms /data/adb/modules_update/com.fei_ke.unlockcngms; do
        if [ -d "$standalone" ]; then
            touch "$standalone/disable"
            ui_print '- 已停用独立 Unlock CN GMS 模块，重启后由内置版本接管'
        fi
    done
fi
mkdir -p /data/adb/opairshare
rm -f /data/adb/opairshare/coex-off /data/adb/opairshare/coex-same149-only
if [ ! -e /data/adb/opairshare/autostart-off ];then touch /data/adb/opairshare/autostart;fi
# Preserve explicit compatibility profiles. Otherwise select the fast profile.
if [ ! -e /data/adb/opairshare/turbo-off ] && [ ! -e /data/adb/opairshare/turbo-vht ] && [ ! -e /data/adb/opairshare/turbo-on ]; then
    touch /data/adb/opairshare/turbo-fast
fi
rm -f /data/adb/opairshare/disabled /data/adb/opairshare/startup-incomplete
ui_print '- Reboot once; the driver/native service starts automatically'
ui_print '- Fast profile is the default; compatibility fallback: su -c "sh /data/adb/modules/opairshare/bin/turbo.sh off"'
ui_print '- AirDrop background receive follows the selected sharing mode and Wi-Fi coexistence policy'
ui_print '- Original signed APKs stay in system; callback APK mounts after boot; no firmware or partition writes'
ui_print '- RX re-arms after real vdev delete and normal teardown; uncertain states quarantine'

set_perm "$MODPATH/payload/native/opairshare-socket-buffer" 0 0 0755
set_perm "$MODPATH/bin/turbo.sh" 0 0 0755

# Earlier releases used a different ID; only one driver owner may start at boot.
for legacy_dir in /data/adb/modules/"op""13-quickshare-wondertap" /data/adb/modules_update/"op""13-quickshare-wondertap"; do
    if [ -d "$legacy_dir" ]; then
        touch "$legacy_dir/disable"
        ui_print '- 已停用旧版 AirDrop 模块，重启后由 OPAirDrop 接管'
    fi
done

ui_print '- 检测 LSPosed 并安装、激活 OShare Xposed 模块'
oshare_result=$(sh "$MODPATH/bin/install-oshare.sh" "$MODPATH" install 2>&1)
oshare_status=$?
printf '%s\n' "$oshare_result" | while IFS= read -r line; do ui_print "- $line"; done
[ "$oshare_status" = 0 ] || ui_print '- OShare 配置尚未完成，重启后将自动重试；请查看 /data/adb/opairshare/oshare-install.log'

ui_print '- Open the OPAirDrop app for settings, status and logs'
