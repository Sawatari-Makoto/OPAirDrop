#!/system/bin/sh
# Install only the bundled bridge and configure its two user-0 scopes.
set -u
BASE=${1:?module directory required}
PHASE=${2:-install}
STATE=/data/adb/opairshare
DB=/data/adb/lspd/config/modules_config.db
PKG=com.oshare.airdrop
# Releases up to v1.1.17 shipped the bridge under this package name. Any
# leftover copy must go once the new identity is on the device: two LSPosed
# modules hooking the same hosts would double-inject every surface.
LEGACY_PKG=com.tlsu.oshareairdrop
APK="$BASE/companion/OShareAirDrop.apk"
mkdir -p "$STATE"
chmod 700 "$STATE"

remove_legacy_bridge() {
    if [ -n "$(/system/bin/pm path --user 0 "$LEGACY_PKG" 2>/dev/null)" ]; then
        if /system/bin/pm uninstall --user 0 "$LEGACY_PKG" >> "$STATE/oshare-install.log" 2>&1; then
            echo "已卸载旧包名桥接 $LEGACY_PKG（v1.1.17 及之前的身份）"
        else
            echo "旧包名桥接 $LEGACY_PKG 卸载失败，请手动卸载；否则 LSPosed 会双份注入。"
        fi
    fi
}

# Ensure mode: re-assert the LSPosed activation on every boot. Out-of-band
# writes to modules_config.db (done by the setup tool from a shell context)
# are not authoritative for lspd: an lspd restart (soft reboot or boot) can
# roll them back, leaving the module registered but disabled. Running the
# setup tool again after lspd is up re-asserts the row for the lifetime of
# that lspd instance, so a per-boot re-assert keeps the module reliably on.
# A no-op when the activation is already intact.
if [ "$PHASE" = ensure ]; then
    [ -f "$DB" ] || exit 0
    installed=$(/system/bin/pm path --user 0 "$PKG" 2>/dev/null | sed -n 's/^package://p')
    case "$installed" in /data/app/*/base.apk) ;; *) exit 0;; esac
    remove_legacy_bridge
    state=$(CLASSPATH="$BASE/payload/native/lsposed-setup.jar" /system/bin/app_process /system/bin OPAirShareLSPosed --check "$DB" "$installed" 2>/dev/null)
    case "$state" in *enabled=1*apkMatches=1*) exit 0;; esac
    if CLASSPATH="$BASE/payload/native/lsposed-setup.jar" /system/bin/app_process /system/bin OPAirShareLSPosed "$DB" "$installed" >> "$STATE/oshare-install.log" 2>&1; then
        log -t opairshare-quickshare 'LSPosed activation re-asserted for this boot'
        # A process started before the re-assert runs unhooked; drop it so the
        # next launch (share sheet, receive session, KeepForeService) is
        # injected. Explicit startservice still reaches a stopped package.
        [ -z "$(pidof com.coloros.oshare)" ] || /system/bin/am force-stop --user 0 com.coloros.oshare
        # Each activation snapshots the config DB; keep the newest three.
        for old in $(ls -t "$STATE"/lsposed-before-activation-*.db 2>/dev/null | tail -n +4); do
            rm -f "$old"
        done
    fi
    exit 0
fi

detected=false
for prop in /data/adb/modules/*/module.prop /data/adb/modules_update/*/module.prop; do
    [ -f "$prop" ] || continue
    if grep -Eqi '^(id|name)=.*lsposed' "$prop"; then
        detected=true
        dir=${prop%/*}
        if [ -f "$dir/disable" ] || [ -f "$dir/remove" ]; then
            echo '检测到 LSPosed 已禁用或待移除，请在模块管理器中启用。'
        fi
    fi
done
[ ! -f "$DB" ] || detected=true
if [ "$detected" != true ]; then
    echo '未检测到 LSPosed，跳过 OShare 自动安装；安装 LSPosed 后可重新安装本模块。'
    rm -f "$STATE/oshare-setup-pending" "$STATE/oshare-setup-attempts"
    exit 0
fi

touch "$STATE/oshare-setup-pending"
if [ "$PHASE" = boot ]; then
    attempts=$(cat "$STATE/oshare-setup-attempts" 2>/dev/null || echo 0)
    case "$attempts" in ''|*[!0-9]*) attempts=0;; esac
    if [ "$attempts" -ge 3 ]; then
        echo 'OShare 自动配置已重试三次，请在 LSPosed 中手动启用并勾选 OShare、Mosey。'
        rm -f "$STATE/oshare-setup-pending"
        exit 1
    fi
    echo $((attempts + 1)) > "$STATE/oshare-setup-attempts"
else
    rm -f "$STATE/oshare-setup-attempts"
fi
if [ "$(getprop sys.boot_completed)" != 1 ]; then
    echo '已检测到 LSPosed，等待开机完成后自动安装 OShare。'
    exit 10
fi
[ -f "$APK" ] || { echo '缺少 OShare APK'; exit 1; }
# Retrying LSPosed activation must not reinstall an identical module APK:
# replacement changes its /data/app path while existing host loaders use the old one.
installed=$(/system/bin/pm path --user 0 "$PKG" 2>/dev/null | sed -n 's/^package://p')
if [ -n "$installed" ] && cmp -s "$APK" "$installed"; then
    echo 'OShare 已安装相同版本，直接配置 LSPosed。'
else
    # Capture framework output: KernelSU standalone ash must not substitute BusyBox pm.
    result=$(/system/bin/pm install -r --user 0 -S "$(wc -c < "$APK" | tr -d ' ')" < "$APK" 2>&1)
    status=$?
    printf '%s\n' "$result" >> "$STATE/oshare-install.log"
    if [ "$status" != 0 ] || ! printf '%s\n' "$result" | grep -q '^Success'; then
        # A rebuild signed with a different key cannot replace the installed
        # bridge in place. The bridge is a headless LSPosed module without user
        # data, so remove the stale copy once and retry the install.
        if printf '%s\n' "$result" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
            echo 'OShare 签名与已安装版本不同，移除旧桥接后重试。'
            printf '%s\n' "$result" >> "$STATE/oshare-install.log"
            /system/bin/pm uninstall --user 0 "$PKG" >> "$STATE/oshare-install.log" 2>&1
            result=$(/system/bin/pm install -r --user 0 -S "$(wc -c < "$APK" | tr -d ' ')" < "$APK" 2>&1)
            status=$?
            printf '%s\n' "$result" >> "$STATE/oshare-install.log"
        fi
        if [ "$status" != 0 ] || ! printf '%s\n' "$result" | grep -q '^Success'; then
            echo "OShare 安装失败：$result"
            exit 1
        fi
    fi
fi
installed=$(/system/bin/pm path --user 0 "$PKG" 2>/dev/null | sed -n 's/^package://p')
case "$installed" in /data/app/*/base.apk) ;; *) echo '无法确定已安装的 OShare APK 路径'; exit 1;; esac
remove_legacy_bridge
[ -f "$DB" ] || { echo 'LSPosed 配置尚未创建，已安排下次开机重试。'; exit 10; }
if CLASSPATH="$BASE/payload/native/lsposed-setup.jar" /system/bin/app_process /system/bin OPAirShareLSPosed "$DB" "$installed" >> "$STATE/oshare-install.log" 2>&1; then
    rm -f "$STATE/oshare-setup-pending" "$STATE/oshare-setup-attempts"
    echo 'OShare 已安装并启用，作用域：OShare、Mosey（主用户）。'
    if [ "$PHASE" = boot ]; then
        echo 'LSPosed 配置已更新，再重启一次以加载本次自动配置。'
        touch "$STATE/oshare-reboot-required"
    else
        rm -f "$STATE/oshare-reboot-required"
        echo '安装完成后请重启，使 LSPosed 加载模块和作用域。'
    fi
else
    echo 'LSPosed 自动激活失败，已保留原配置并安排开机重试。'
    exit 1
fi
