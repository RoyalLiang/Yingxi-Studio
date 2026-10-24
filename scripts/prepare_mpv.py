#!/usr/bin/env python3
"""Extract the immutable official MPV runtime; never package its application."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare(manifest, cache, output):
    spec = json.loads(manifest.read_text())
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (spec['sha256'] + '.apk')
    if not archive.exists() or sha(archive.read_bytes()) != spec['sha256']:
        request = urllib.request.Request(spec['binary'], headers={'User-Agent': 'Yingxi-build'})
        with urllib.request.urlopen(request, timeout=180) as response:
            data = response.read()
        if sha(data) != spec['sha256']:
            raise ValueError('MPV upstream SHA256 mismatch')
        archive.write_bytes(data)
    inventory = {}
    with zipfile.ZipFile(archive) as bundle:
        for abi in spec['abis']:
            inventory[abi] = {}
            for name in spec['libraries']:
                data = bundle.read(f'lib/{abi}/{name}')
                if data[:4] != b'\x7fELF':
                    raise ValueError('MPV runtime is not ELF')
                target = output / 'jniLibs' / abi / ('libyingxi_mpv_' + name[3:])
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                inventory[abi][name] = sha(data)
    assets = output / 'assets'
    assets.mkdir(parents=True, exist_ok=True)
    (assets / 'mpv-native.json').write_text(json.dumps(inventory, sort_keys=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.manifest, args.cache, args.output)
