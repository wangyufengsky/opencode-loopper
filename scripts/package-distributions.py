#!/usr/bin/env python3
"""Build relocatable distributions from a verified JAR and checksum-pinned JDKs.

Requires Python 3.12+ and curl on the build host; end users need neither.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / 'scripts/jdk21-lock.json'


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked_archive(path, spec):
    if path.stat().st_size != spec['size'] or sha256(path) != spec['sha256']:
        raise ValueError(f'JDK size/SHA-256 mismatch: {path}')
    return path


def download(spec, cache):
    cache.mkdir(parents=True, exist_ok=True)
    suffix = '.zip' if spec['url'].endswith('.zip') else '.tar.gz'
    destination = cache / (spec['sha256'] + suffix)
    if destination.exists():
        return checked_archive(destination, spec)
    with tempfile.TemporaryDirectory(prefix='download-', dir=cache) as temporary:
        partial = Path(temporary) / ('jdk' + suffix)
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                        '--retry', '3', '--connect-timeout', '30', '--max-time', '900',
                        '--proto', '=https', '--proto-redir', '=https',
                        '--output', str(partial), spec['url']], check=True, timeout=3700)
        checked_archive(partial, spec)
        partial.replace(destination)
    return destination


def extract_jdk(archive, destination, spec, unix_modes=None):
    destination.mkdir()
    unix_modes = {} if unix_modes is None else unix_modes
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                path = PurePosixPath(member.filename)
                if (path.is_absolute() or '..' in path.parts or '\\' in member.filename
                        or ':' in member.filename or (member.external_attr >> 16) & 0o170000 == 0o120000):
                    raise ValueError(f'Unsafe ZIP member: {member.filename}')
            source.extractall(destination)
    else:
        def preserve_safe_mode(member, target):
            safe = tarfile.data_filter(member, target)
            if safe is not None and safe.mode is not None:
                unix_modes[PurePosixPath(safe.name).as_posix()] = safe.mode
            return safe

        with tarfile.open(archive, 'r:gz') as source:
            source.extractall(destination, filter=preserve_safe_mode)
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir() or roots[0].is_symlink():
        raise ValueError('Expected exactly one JDK archive root')
    jdk = roots[0]
    home = jdk / 'Contents/Home' if spec['os'] == 'mac' else jdk
    java = home / ('bin/java.exe' if spec['os'] == 'windows' else 'bin/java')
    javac = home / ('bin/javac.exe' if spec['os'] == 'windows' else 'bin/javac')
    release = dict(re.findall(r'^([A-Z_]+)="([^"]*)"$', (home / 'release').read_text(), re.M))
    expected_arch = {'x64': {'x86_64', 'amd64'}, 'aarch64': {'aarch64', 'arm64'}}[spec['architecture']]
    expected_os = {'linux': 'Linux', 'windows': 'Windows', 'mac': 'Darwin'}[spec['os']]
    if (not java.is_file() or not javac.is_file()
            or not release.get('JAVA_VERSION', '').startswith('21.')
            or release.get('OS_ARCH') not in expected_arch or release.get('OS_NAME') != expected_os
            or not (home / 'legal').is_dir()):
        raise ValueError(f'JDK version/platform/layout/license mismatch: {home}')
    if spec['os'] != 'windows':
        for executable in (java, javac):
            if not unix_modes.get(executable.relative_to(destination).as_posix(), 0) & 0o111:
                raise ValueError(f'JDK executable permission missing: {executable}')
    return jdk


def assemble(platform, spec, archive, jar, version, work, output):
    name = f'opencode-loopper-{version}-{platform}'
    bundle = work / name
    bundle.mkdir()
    unix_modes = {}
    jdk = extract_jdk(archive, work / f'{platform}-jdk', spec, unix_modes)
    shutil.move(str(jdk), bundle / 'jdk21')
    shutil.copy2(jar, bundle / jar.name)
    scripts = ['start-windows.bat'] if spec['os'] == 'windows' else ['start-linux.sh']
    if spec['os'] == 'mac':
        scripts.append('start-macos.command')
    for script in scripts:
        shutil.copy2(ROOT / 'scripts' / script, bundle / script)
        if spec['os'] != 'windows':
            (bundle / script).chmod(0o755)
    launcher = scripts[-1]
    (bundle / 'README.txt').write_text(
        f'OpenCode Loopper {version} — {platform}\n\n'
        f'解压整个目录，然后运行 {launcher}。无需安装 Java 或设置 JDK/JAR 路径。\n'
        'macOS 可以双击 start-macos.command；Linux 运行 ./start-linux.sh；Windows 双击 start-windows.bat。\n'
        '可从任意工作目录调用启动脚本。默认数据保存在本目录的 data/，升级时请保留和迁移此目录。\n'
        '默认页面：http://127.0.0.1:8080；Ctrl+C 停止。\n'
        'Git、OpenCode CLI 及模型认证仍需自行准备；本包不包含这些工具或模型。\n'
        'Linux 使用 glibc 发行版，不适用于 Alpine/musl。请选择与系统 CPU 匹配的包。\n'
        '高级配置：LOOPPER_JAVA_HOME / LOOPPER_JAR_PATH 可显式覆盖；一般无需设置。\n'
        'JDK 来自 Eclipse Temurin，完整许可证保留于 jdk21 内；来源见 distribution.json。\n', encoding='utf-8')
    (bundle / 'distribution.json').write_text(json.dumps({
        'version': version, 'platform': platform, 'jar': jar.name, 'jarSha256': sha256(jar),
        'jdk': spec, 'launcher': launcher,
    }, indent=2) + '\n')
    if spec['os'] == 'windows':
        result = output / (name + '.zip')
        with zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as target:
            for item in sorted(bundle.rglob('*')):
                if item.is_file():
                    target.write(item, item.relative_to(work).as_posix())
    else:
        result = output / (name + '.tar.gz')
        def target_permissions(member):
            relative = PurePosixPath(member.name).relative_to(name)
            if relative.parts and relative.parts[0] == 'jdk21':
                original = PurePosixPath(jdk.name, *relative.parts[1:]).as_posix()
                if original in unix_modes:
                    member.mode = unix_modes[original]
                elif member.isdir():
                    member.mode = 0o755
            elif member.isdir() or relative.as_posix() in scripts:
                member.mode = 0o755
            elif member.isfile():
                member.mode = 0o644
            return member

        with tarfile.open(result, 'w:gz', compresslevel=6) as target:
            # Windows cannot retain POSIX execute bits in extracted files.
            # Preserve the validated JDK archive modes and set launcher modes.
            target.add(bundle, arcname=name, filter=target_permissions)
    return result


def main():
    if sys.version_info < (3, 12):
        raise ValueError("Packaging requires Python 3.12 or newer")
    parser = argparse.ArgumentParser(description=__doc__)
    specs = json.loads(LOCK.read_text())
    parser.add_argument('--platform', choices=list(specs), action='append', help='Default: all six')
    parser.add_argument('--cache', type=Path, default=Path.home() / '.cache/opencode-loopper/jdk21')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/release')
    parser.add_argument('--download-only', action='store_true')
    args = parser.parse_args()
    platforms = args.platform or list(specs)
    if len(platforms) != len(set(platforms)):
        parser.error('Duplicate platform')
    version = re.search(r'<artifactId>opencode-loopper</artifactId>\s*<version>([^<]+)</version>',
                        (ROOT / 'pom.xml').read_text()).group(1)
    jar = ROOT / f'target/opencode-loopper-{version}.jar'
    if not args.download_only:
        if args.output.exists():
            raise ValueError(f'Output already exists; choose a new --output directory: {args.output}')
        with zipfile.ZipFile(jar) as content:
            if ('BOOT-INF/classes/static/index.html' not in content.namelist()
                    or not any(n.startswith('BOOT-INF/classes/static/assets/') for n in content.namelist())):
                raise ValueError('JAR is missing packaged frontend resources')
            sqlite = [name for name in content.namelist()
                      if name.startswith('BOOT-INF/lib/sqlite-jdbc-') and name.endswith('.jar')]
            if len(sqlite) != 1:
                raise ValueError('Expected exactly one bundled SQLite JDBC library')
            with zipfile.ZipFile(io.BytesIO(content.read(sqlite[0]))) as native:
                for platform in platforms:
                    item = specs[platform]
                    os_name = {'linux': 'Linux', 'mac': 'Mac', 'windows': 'Windows'}[item['os']]
                    arch = {'x64': 'x86_64', 'aarch64': 'aarch64'}[item['architecture']]
                    library = {'linux': 'libsqlitejdbc.so', 'mac': 'libsqlitejdbc.dylib',
                               'windows': 'sqlitejdbc.dll'}[item['os']]
                    if f'org/sqlite/native/{os_name}/{arch}/{library}' not in native.namelist():
                        raise ValueError(f'JAR lacks native SQLite support for {platform}')
    def fetch_platform(platform):
        print(f'[package] Verify/download JDK 21: {platform}', flush=True)
        return platform, download(specs[platform], args.cache)
    with ThreadPoolExecutor(max_workers=6) as executor:
        archives = dict(executor.map(fetch_platform, platforms))
    if args.download_only:
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.distributions-', dir=args.output.parent) as temporary:
        staging = Path(temporary) / 'release'
        staging.mkdir()
        for platform in platforms:
            print(f'[package] Assemble: {platform}', flush=True)
            with tempfile.TemporaryDirectory(prefix='bundle-', dir=temporary) as working:
                assemble(platform, specs[platform], archives[platform], jar, version, Path(working), staging)
        shutil.copy2(jar, staging / jar.name)
        artifacts = sorted(staging.iterdir())
        (staging / 'SHA256SUMS').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in artifacts))
        staging.rename(args.output)
    print(f'[package] Complete: {args.output}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, zipfile.BadZipFile) as error:
        sys.exit(f'[package] ERROR: {error}')
