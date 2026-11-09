#!/usr/bin/env python3
"""Assemble OPAirDrop using current userspace and the verified reference kernel ABI."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import stat
import subprocess
import sys
import zipfile
from release import emit_metadata, REPOSITORY

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools' / 'kernel'))
import vermagic  # noqa: E402  (path is prepared above)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files(path):
    return sorted(p for p in path.rglob('*') if p.is_file() and p.name != '.DS_Store')


def copy_tree(source, target):
    shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('.DS_Store'))


def write_lf(path, text):
    """Write UTF-8 text with LF endings.

    Path.write_text() opens the file in text mode, so Windows rewrites every
    '\\n' as '\\r\\n'. The manager runs the module scripts with a POSIX shell
    on the device, where a trailing CR breaks option parsing such as
    `set -eu` and aborts the install with a misleading payload error.
    """
    path.write_bytes(text.replace('\r\n', '\n').replace('\r', '\n').encode('utf-8'))


def normalize_payload(output):
    """Force LF on every text payload and refuse to ship a stray CR."""
    changed = []
    for path in files(output):
        data = path.read_bytes()
        if b'\x00' in data[:4096]:
            continue
        fixed = data.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
        if fixed != data:
            path.write_bytes(fixed)
            changed.append(path.relative_to(output).as_posix())
    for path in files(output):
        if path.suffix != '.sh':
            continue
        if b'\r' in path.read_bytes():
            raise SystemExit(f'Carriage return left in script: {path.relative_to(output).as_posix()}')
    if changed:
        print(f'Normalized line endings for {len(changed)} payload files')
    return changed


def verify_driver_abi(output):
    """Fail the build when a script uses a sysfs parameter the driver lacks.

    The module ships a captured kernel driver. A script that reads or writes a
    parameter the driver does not export only fails at runtime, when the
    stack is already half swapped, so the names are checked while packaging.
    """
    # Search every shipped driver, not just qca: a token such as
    # /opairshare_wonder_provider.ko inside an insmod path is the provider
    # module's own file name, and /sys/module/<provider>/... parameters live
    # in the provider blob, not in the qca blob.
    blobs = [p.read_bytes() for p in files(output / 'payload/kernel')]
    missing = set()
    for path in files(output):
        if path.suffix != '.sh':
            continue
        text = path.read_text(encoding='utf-8', errors='ignore')
        for name in re.findall(r'/(opairshare_[a-z0-9_]+)', text):
            if name.encode() not in b''.join(blobs):
                missing.add((path.relative_to(output).as_posix(), name))
    for path, name in sorted(missing):
        raise SystemExit(f'Script uses a driver parameter that does not exist: {path} -> {name}')


def check_script_syntax(output):
    """Reject scripts that the device shell cannot parse."""
    for path in sorted((output / 'bin').glob('*.sh')) + [output / 'customize.sh', output / 'service.sh',
                                                         output / 'post-fs-data.sh', output / 'uninstall.sh']:
        if not path.exists():
            continue
        result = subprocess.run(['sh', '-n', path.as_posix()], capture_output=True, text=True)
        if result.returncode != 0:
            raise SystemExit(f'Shell syntax error in {path.relative_to(output).as_posix()}: {result.stderr.strip()}')


def check_sourced_scripts(output):
    """Reject library scripts that would run their command line when sourced.

    Several helpers in bin/ are sourced by other scripts to share functions.
    A sourced file that executes its own command line interface would run
    that interface in the caller's context, which fails or has side effects.
    The guard looks for a script that is sourced by another one and that has
    no guard around its command line dispatch.
    """
    scripts = {path.name: path for path in (output / 'bin').glob('*.sh')}
    for name, path in scripts.items():
        text = path.read_text(encoding='utf-8', errors='ignore')
        # A file that dispatches on its arguments has a command line part.
        if 'case "${1:-' not in text:
            continue
        # The dispatch has to be guarded by a check that only passes when the
        # file is the entry point of the run.
        if re.search(r'case\s+"\$\{0##\*/\}"', text):
            continue
        sourced_by = [other for other, other_path in scripts.items()
                      if other != name and re.search(rf'^\.\s+.*\b{re.escape(name)}\b', other_path.read_text(encoding='utf-8', errors='ignore'), re.M)]
        if sourced_by:
            raise SystemExit(f'{name} has a command line interface but is sourced by {", ".join(sorted(sourced_by))}; '
                             'guard the dispatch so sourcing it does not run the CLI')


def check_state_writes(output):
    """Reject state writes that are silently skipped.

    A helper that creates its state directory with mkdir and then treats a
    failing mkdir as fatal never writes anything once the directory exists,
    because mkdir fails on an existing directory. The state is then never
    refreshed and the behaviour silently changes. Any mkdir that guards a
    state write has to tolerate the directory already being there.
    """
    pattern = re.compile(r'mkdir[^\n]*?\|\|\s*(?:return|exit)')
    for path in sorted((output / 'bin').glob('*.sh')):
        text = path.read_text(encoding='utf-8', errors='ignore')
        for match in pattern.finditer(text):
            line = text[text.rfind('\n', 0, match.start()) + 1:text.find('\n', match.end())]
            # A directory that is created for a state write must tolerate the
            # directory already being there.
            if '.preparing' in line or '.tmp' in line or '.new' in line:
                continue
            raise SystemExit(f'{path.relative_to(output).as_posix()}: state directory creation aborts the write when the '
                             f'directory already exists: {line.strip()}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=ROOT / 'local-inputs/reference-r27')
    parser.add_argument('--repository', default=REPOSITORY,
                        help='GitHub owner/repository used for the KernelSU update feed')
    parser.add_argument('--archive-name', default=None,
                        help='Release ZIP file name (default OPAirDrop-v<version>.zip)')
    args = parser.parse_args()
    reference = args.reference.resolve()
    props = dict(line.split('=', 1) for line in (ROOT / 'module/module.prop').read_text().splitlines() if '=' in line)
    apk_dir = ROOT / 'oshare/app/build/outputs/apk/release'
    apk_metadata = json.loads((apk_dir / 'output-metadata.json').read_text())
    elements = apk_metadata.get('elements', [])
    if (apk_metadata.get('variantName') != 'release'
            or apk_metadata.get('applicationId') != 'com.oshare.airdrop'
            or len(elements) != 1
            or elements[0].get('versionName') != props['version']
            or elements[0].get('versionCode') != int(props['versionCode'])
            or elements[0].get('outputFile') != 'app-release.apk'):
        raise SystemExit('Build the signed OShare release APK with the current module version before packaging')
    oshare_apk = apk_dir / 'app-release.apk'
    output = ROOT / 'build/package/module'
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    # Verify every reused binary against the archived release before assembly.
    manifest = {}
    for line in (reference / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(None, 1)
        manifest[name] = expected
    reused = files(reference / 'payload/kernel') + files(reference / 'system') + [reference / 'payload/app-callback/base.apk']
    for path in reused:
        name = path.relative_to(reference).as_posix()
        if manifest.get(name) != digest(path):
            raise SystemExit(f'Reference hash mismatch: {name}')
    copy_tree(ROOT / 'module', output)
    copy_tree(reference / 'system', output / 'system')
    copy_tree(reference / 'payload/kernel', output / 'payload/kernel')
    copy_tree(reference / 'payload/app-callback', output / 'payload/app-callback')
    # Ship the signed stock base.apk as the static system payload: it is
    # verified by the manager integrity check, keeps the priv-app signature
    # consistent, and matches the proven layout of the upstream releases.
    # The patched APK stays in payload/app-callback and is swapped in at
    # late_start by the runtime callback path only.
    for name in ('APP-SHA256SUMS', 'APP-CALLBACK-SHA256SUMS'):
        source = reference / name
        if digest(source) != manifest.get(name):
            raise SystemExit(f'Reference hash mismatch: {name}')
        shutil.copy2(source, output / name)
    native = output / 'payload/native'
    native.mkdir(parents=True, exist_ok=True)
    for name in ('libopairshare_capability.so', 'libopairshare_config.so', 'libopairshare_channel.so',
                 'opairshare-socket-buffer', 'session-wifi.jar'):
        shutil.copy2(ROOT / 'build/native' / name, native / name)
    shutil.copy2(ROOT / 'build/lsposed/lsposed-setup.jar', native / 'lsposed-setup.jar')
    companion = output / 'companion/OShareAirDrop.apk'
    companion.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(oshare_apk, companion)

    # Keep the provider module under its generic, device-independent name:
    # the shipped reference payload .ko itself is patched to
    # opairshare_wonder_provider, so scripts must not remap it.
    # The sysfs parameters and the discovery/standby properties share the
    # same generic debug.opairshare.* namespace on both sides: the injected
    # DiscoveryPolicy class and the companion app read those names directly.
    for path in (output / 'bin').glob('*.sh'):
        write_lf(path, path.read_text())
    identity = json.loads((reference / 'kernel-identity.json').read_text())
    kernel_identity = output / 'bin/kernel-identity.sh'
    text = kernel_identity.read_text()
    # The stock build ids of a device differ per device and per firmware.
    # They are read on the device itself at install time, so no device
    # specific identity may ship with the package.
    text = ''.join(line + '\n' for line in text.splitlines()
                   if not line.startswith('STOCK_NOTE_'))
    for name in ('mac80211', 'qca_cld3_peach_v2', 'wonder', 'opairshare_wonder_provider'):
        text = text.replace(f'NOTE_{name}=BUILD_REQUIRED', f'NOTE_{name}=' + identity[name + '.ko']['build_id_note'])
    family, page_size = vermagic.abi(output / 'payload/kernel/qca_cld3_peach_v2.ko')
    text = text.replace('KERNEL_ABI_FAMILY=BUILD_REQUIRED', f'KERNEL_ABI_FAMILY={family}')
    text = text.replace('KERNEL_ABI_PAGESIZE=BUILD_REQUIRED', f'KERNEL_ABI_PAGESIZE={page_size}')
    if 'BUILD_REQUIRED' in text:
        raise SystemExit('Unresolved kernel build identities')
    if 'STOCK_NOTE_' in text:
        raise SystemExit('A device specific stock build id reached the package')
    write_lf(kernel_identity, text)

    # Check both original and patched APK/split manifests against the assembled files.
    app = output / 'system/system_ext/priv-app/MoseyApp'
    static_base = app / 'base.apk'
    callback_base = output / 'payload/app-callback/base.apk'
    for name in ('APP-SHA256SUMS', 'APP-CALLBACK-SHA256SUMS'):
        for line in (output / name).read_text().splitlines():
            expected, apk = line.split()
            if apk == 'base.apk':
                path = static_base if name.startswith('APP-SHA256SUMS') else callback_base
            else:
                path = app / apk
            if digest(path) != expected:
                raise SystemExit(f'APK hash mismatch: {name}: {apk}')
    if digest(static_base) == digest(callback_base):
        raise SystemExit('Static payload base.apk must be the signed stock APK')
    for name in ('LICENSE', 'LICENSING.md', 'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md'):
        shutil.copy2(ROOT / name, output / name)
    copy_tree(ROOT / 'LICENSES', output / 'LICENSES')
    copy_tree(ROOT / 'third-party/unlock-cn-gms', output / 'third-party/unlock-cn-gms')
    write_lf(output / 'README.md',
        f'# OPAirDrop {props["version"]}\n\n{props["description"]}\n\n'
        '设备准入：Oplus 品牌 / sun 平台（骁龙 8 Elite 平台）/ 内核 ABI 家族 '
        f'{family}（{page_size} 页）。内核以 ABI 家族为准：同一家族的不同 build id 与固件均可安装，'
        '跨家族内核会被拒绝。'
        '不限制机型型号、SDK、SoC 属性及原厂驱动身份——原厂驱动的真实 build id 由每台设备在安装时'
        '自行读取并记录在 /data/adb/opairshare/kernel-identity.sh，发行包内不写死任何一台设备的'
        '原厂构建标识。已验证机型 PJZ110；其他机型仍需实际收发验证。\n\n'
        '支持 KernelSU 及兼容分支、Magisk、APatch 的模块安装环境；系统文件可由管理器内置挂载、'
        'Magic Mount、OverlayFS 或 Mountify 等兼容实现提供，不强制要求元模块目录。'
        '管理器和挂载组合仍需实机验证。安装时检测 LSPosed，自动安装并启用 OShare，'
        '配置 OShare 与 Mosey 的主用户作用域。安装后重启一次。恢复环境安装或 LSPosed 尚未初始化时，'
        '开机后自动重试；若开机后才完成激活，需要再次重启。无 LSPosed 时会跳过桥接安装。\n\n'
        '配置前的 LSPosed 数据库备份与安装日志保存在 /data/adb/opairshare/。\n\n'
        '支持 KernelSU 管理器的模块在线更新，更新源为 Sawatari-Makoto/OPAirDrop 的 GitHub 正式 Release。'
        '首次需要手动安装包含 updateJson 的本版本，之后可在管理器中检查与安装新版本。\n\n'
        '内置 Unlock CN GMS v3.8（fei-ke）：安装时复制本机匹配的权限配置，移除两项国行 GMS 限制，'
        '开机早期只读挂载配置副本。仍需在系统设置开启 Google 服务。安装器会停用旧的独立解锁模块。'
        '解锁日志：/data/adb/opairshare/gms-unlock.log。停用或卸载 OPAirDrop 并重启后恢复原配置。\n\n'
        '如果检测到早期不同 ID 的同项目模块，安装器会将其停用，重启后由本模块接管，避免重复加载驱动。\n\n'
        '本包使用仓库最新用户态源码和已校验的 r27 内核、Mosey 二进制，'
        '打包时映射其原有 ABI 名称；内核与 Mosey 补丁未重新编译。\n'
    )
    record = {'name': props['name'], 'version': props['version'], 'versionCode': int(props['versionCode']),
              'update_json': props['updateJson'],
              'oshare': {'build_type': 'release', 'version': elements[0]['versionName'],
                         'version_code': elements[0]['versionCode'], 'sha256': digest(oshare_apk)},
              'kernel_rebuilt': False, 'callback_rebuilt': False, 'kernel_abi': 'reference-r27',
              'unlock_cn_gms': json.loads((ROOT / 'third-party/unlock-cn-gms/provenance.json').read_text()),
              'reference_binaries': {p.relative_to(reference).as_posix(): digest(p) for p in reused},
              'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'source_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())}
    with open(output / 'build-info.json', 'w', newline='\n', encoding='utf-8') as info:
        info.write(json.dumps(record, indent=2) + '\n')
    for path in files(output):
        executable = path.suffix == '.sh' or path.name in ('mosey_server', 'opairshare-socket-buffer')
        path.chmod(0o755 if executable else 0o644)
    # The archive is extracted and executed by the manager on the device, so
    # every text payload has to stay LF. A CRLF script fails the payload
    # verification with `set: illegal option` and the install is rejected.
    normalize_payload(output)
    check_script_syntax(output)
    check_sourced_scripts(output)
    check_state_writes(output)
    verify_driver_abi(output)
    sums = ''.join(f'{digest(p)}  {p.relative_to(output).as_posix()}\n' for p in files(output) if p.name not in ('SHA256SUMS', 'ARCHIVE-SHA256SUMS'))
    # Newline translation would append CR to every path and break bin/verify.sh.
    with open(output / 'SHA256SUMS', 'w', newline='\n', encoding='utf-8') as manifest:
        manifest.write(sums)
    # Forward slashes: a backslash path is mangled when MSYS translates the
    # argument for a native helper, so the payload check would read the wrong
    # file and report a checksum mismatch.
    subprocess.run(['sh', (output / 'bin/verify.sh').as_posix(), output.as_posix()], check=True)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    archive = dist / (args.archive_name or f'OPAirDrop-v{props["version"]}.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in files(output):
            info = zipfile.ZipInfo.from_file(path, path.relative_to(output).as_posix())
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | path.stat().st_mode & 0o777) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, path.read_bytes())
    # Final gate: the shipped archive itself must not contain CR in text
    # payloads. This is what the manager unpacks and runs on the device.
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            data = z.read(entry)
            if b'\x00' in data[:4096]:
                continue
            if b'\r' in data:
                raise SystemExit(f'Carriage return in archive payload: {entry.filename}')
    emit_metadata(archive, dist, args.repository)
    print(f'{archive} ({archive.stat().st_size:,} bytes)')
    print(digest(archive))


if __name__ == '__main__':
    main()
