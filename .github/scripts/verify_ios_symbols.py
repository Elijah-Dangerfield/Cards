#!/usr/bin/env python3
"""Select cached dSYMs only when their UUIDs match the released IPA."""
import argparse
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


def uuids(path):
    result = subprocess.run(['dwarfdump', '--uuid', str(path)], check=True, capture_output=True, text=True)
    return {arch: value.lower() for value, arch in re.findall(r'UUID: ([0-9A-Fa-f-]+) \(([^)]+)\)', result.stdout)}


def verify(ipa, symbols_root, output, version, store_build):
    if output.exists():
        raise ValueError('Output already exists; inspect it before retrying')
    with zipfile.ZipFile(ipa) as archive:
        plists = [name for name in archive.namelist() if len(Path(name).parts) == 3
                  and name.startswith('Payload/') and name.endswith('.app/Info.plist')]
        if len(plists) != 1:
            raise ValueError('Expected one main application in the IPA')
        info = plistlib.loads(archive.read(plists[0]))
        if (info.get('CFBundleIdentifier'), info.get('CFBundleShortVersionString'), str(info.get('CFBundleVersion'))) != (
                'com.dangerfield.cards.Cards', version, store_build):
            raise ValueError('IPA identity/version/build differs from the requested release')
        executable = info['CFBundleExecutable']
        if Path(executable).name != executable or executable in ('.', '..'):
            raise ValueError('Invalid executable name')
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / executable
            binary.write_bytes(archive.read(str(Path(plists[0]).parent / executable)))
            expected = uuids(binary)
    if not expected:
        raise ValueError('IPA executable contains no Mach-O UUIDs')
    match = None
    for bundle in sorted(symbols_root.rglob('*.dSYM')):
        dwarf = bundle / 'Contents/Resources/DWARF'
        if not dwarf.is_dir():
            continue
        for candidate in dwarf.iterdir():
            if not candidate.is_file():
                continue
            actual = uuids(candidate)
            if all(actual.get(arch) == value for arch, value in expected.items()):
                match = bundle
                break
        if match:
            break
    if match is None:
        raise ValueError('Exact released-binary UUIDs not found in cached dSYMs; nothing may be uploaded')
    output.mkdir(parents=True)
    shutil.copytree(match, output / match.name)
    evidence = {'bundleId': info['CFBundleIdentifier'], 'version': version, 'storeBuild': store_build,
                'executable': executable, 'uuids': expected, 'matchedBundle': match.name}
    (output / 'verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ipa', type=Path, required=True)
    parser.add_argument('--symbols-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--store-build', required=True)
    args = parser.parse_args()
    verify(args.ipa, args.symbols_root, args.output, args.version, args.store_build)


if __name__ == '__main__':
    main()
