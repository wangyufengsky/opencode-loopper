"""Offline regression for archive assembly and supply-chain validation."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
import sys
sys.dont_write_bytecode = True
import zipfile

spec = importlib.util.spec_from_file_location('packaging', Path(__file__).with_name('package-distributions.py'))
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class DistributionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='loopper packaging ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, platform, wrong_arch=False):
        item = dict(json.loads(packaging.LOCK.read_text())[platform])
        windows = item['os'] == 'windows'
        prefix = 'jdk-original/Contents/Home/' if item['os'] == 'mac' else 'jdk-original/'
        arch = 'wrong' if wrong_arch else {'x64': 'x86_64', 'aarch64': 'aarch64'}[item['architecture']]
        os_name = {'linux': 'Linux', 'windows': 'Windows', 'mac': 'Darwin'}[item['os']]
        files = {
            prefix + ('bin/java.exe' if windows else 'bin/java'): b'fixture',
            prefix + ('bin/javac.exe' if windows else 'bin/javac'): b'fixture',
            prefix + 'release': f'JAVA_VERSION="21.0.12"\nOS_ARCH="{arch}"\nOS_NAME="{os_name}"\n'.encode(),
            prefix + 'legal/java.base/LICENSE': b'fixture license',
        }
        archive = self.root / ('jdk.zip' if windows else 'jdk.tar.gz')
        if windows:
            with zipfile.ZipFile(archive, 'w') as target:
                for name, data in files.items():
                    target.writestr(name, data)
        else:
            with tarfile.open(archive, 'w:gz') as target:
                for name, data in files.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = 0o755 if '/bin/' in name else 0o644
                    target.addfile(info, io.BytesIO(data))
        item.update(size=archive.stat().st_size, sha256=packaging.sha256(archive))
        return archive, item

    def test_all_six_archives_contain_pinned_jar_jdk_licenses_and_launchers(self):
        for platform in json.loads(packaging.LOCK.read_text()):
            with self.subTest(platform=platform):
                archive, item = self.archive(platform)
                work = self.root / platform
                work.mkdir()
                output = work / 'output'
                output.mkdir()
                jar = self.root / 'opencode-loopper-1.0.0.jar'
                jar.write_bytes(b'fixture jar')
                result = packaging.assemble(platform, item, archive, jar, '1.0.0', work, output)
                if result.suffix == '.zip':
                    with zipfile.ZipFile(result) as source:
                        self.assertIsNone(source.testzip())
                        names = source.namelist()
                        metadata = json.loads(source.read(next(n for n in names if n.endswith('/distribution.json'))))
                else:
                    with tarfile.open(result) as source:
                        names = source.getnames()
                        metadata = json.load(source.extractfile(next(n for n in names if n.endswith('/distribution.json'))))
                        launch = next(m for m in source.getmembers() if m.name.endswith('/' + metadata['launcher']))
                        self.assertTrue(launch.mode & 0o111)
                self.assertTrue(any(n.endswith('/' + jar.name) for n in names))
                self.assertTrue(any('/jdk21/' in n and n.endswith('/legal/java.base/LICENSE') for n in names))
                self.assertEqual(platform, metadata['platform'])
                self.assertEqual(packaging.sha256(jar), metadata['jarSha256'])
                self.assertTrue(any(n.endswith('/' + metadata['launcher']) for n in names))

    def test_corruption_is_rejected_before_extraction(self):
        archive, item = self.archive('linux-amd64')
        packaging.checked_archive(archive, item)
        archive.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            packaging.checked_archive(archive, item)

    def test_wrong_cpu_is_rejected(self):
        archive, item = self.archive('linux-amd64', wrong_arch=True)
        with self.assertRaisesRegex(ValueError, 'platform/layout'):
            packaging.extract_jdk(archive, self.root / 'extract', item)

    def test_archive_traversal_is_rejected(self):
        archive = self.root / 'unsafe.zip'
        with zipfile.ZipFile(archive, 'w') as target:
            target.writestr('../escaped', b'bad')
        with self.assertRaisesRegex(ValueError, 'Unsafe ZIP'):
            packaging.extract_jdk(archive, self.root / 'extract', {})
        self.assertFalse((self.root / 'escaped').exists())
        with tarfile.open(self.root / 'unsafe.tar.gz', 'w:gz') as target:
            member = tarfile.TarInfo('../escaped')
            member.size = 3
            target.addfile(member, io.BytesIO(b'bad'))
        with self.assertRaises(tarfile.FilterError):
            packaging.extract_jdk(self.root / 'unsafe.tar.gz', self.root / 'extract-tar', {})
        self.assertFalse((self.root / 'escaped').exists())


if __name__ == '__main__':
    unittest.main()
