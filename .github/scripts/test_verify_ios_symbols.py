import importlib.util
import plistlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('verify_ios_symbols', Path(__file__).with_name('verify_ios_symbols.py'))
symbols = importlib.util.module_from_spec(spec)
spec.loader.exec_module(symbols)


class VerifySymbolsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.ipa = self.root / 'Cards.ipa'
        with zipfile.ZipFile(self.ipa, 'w') as archive:
            archive.writestr('Payload/Downcard.app/Info.plist', plistlib.dumps({
                'CFBundleIdentifier': 'com.dangerfield.cards.Cards', 'CFBundleShortVersionString': '0.9.0',
                'CFBundleVersion': '202611151656', 'CFBundleExecutable': 'Downcard'}))
            archive.writestr('Payload/Downcard.app/Downcard', b'released binary')
        self.cache = self.root / 'cache'
        self.bundle = self.cache / 'Downcard.app.dSYM'
        dwarf = self.bundle / 'Contents/Resources/DWARF'
        dwarf.mkdir(parents=True)
        (dwarf / 'Downcard').write_bytes(b'cached symbols')
        self.output = self.root / 'verified'

    def run_verify(self, candidate, version='0.9.0'):
        def read_uuids(path):
            return candidate if self.cache in path.parents else {'arm64': 'exact-release-uuid'}
        with patch.object(symbols, 'uuids', side_effect=read_uuids):
            symbols.verify(self.ipa, self.cache, self.output, version, '202611151656')

    def test_exact_uuid_and_architecture_selects_symbols(self):
        self.run_verify({'arm64': 'exact-release-uuid'})
        self.assertTrue((self.output / self.bundle.name / 'Contents/Resources/DWARF/Downcard').is_file())
        self.assertTrue((self.output / 'verification.json').is_file())

    def test_different_uuid_leaves_nothing_to_upload(self):
        with self.assertRaises(ValueError):
            self.run_verify({'arm64': 'different-build-uuid'})
        self.assertFalse(self.output.exists())

    def test_different_architecture_leaves_nothing_to_upload(self):
        with self.assertRaises(ValueError):
            self.run_verify({'arm64e': 'exact-release-uuid'})
        self.assertFalse(self.output.exists())

    def test_wrong_release_version_leaves_nothing_to_upload(self):
        with self.assertRaises(ValueError):
            self.run_verify({'arm64': 'exact-release-uuid'}, version='0.8.0')
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
